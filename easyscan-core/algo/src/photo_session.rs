//! The photo keyframes on disk, as an orbiter-native photo session
//! (`orbiter.native.photo_session.v1`, `native/orbiter_native/photos.py`), so
//! `orbiter-recon --session <dir> --mode dense|texture-only` builds the dense
//! cloud, the mesh and its texture from the phone's session unchanged:
//!
//! ```text
//! <session>/
//!   session.json          board, frame, volume, the one eye and its K at the photo size
//!   photos.jsonl          one row per photograph, appended as each one lands
//!   photos/left_0001.jpg  the camera's own JPEG, raw sensor orientation
//!   laser.ply             the laser cloud in the same frame, mm (written on finish)
//! ```
//!
//! Everything is in orbiter-native's board frame ([`FaceOutFrame::centred`]):
//! origin at the board centre, +Z out of the printed face, +Y up.
//!
//! The phone is one eye, so it is `left`, there are no extrinsics and the
//! two-eye pose gaps are `null`, as native writes them for a one-eyed pose.
//! No `stripe/*.npz` sidecars are written: the stripe is found at the
//! analysis size, not the photograph's, and orbiter-recon builds a missing
//! sidecar's mask from the photograph's own pixels (its documented fallback).

use std::fs::{self, File, OpenOptions};
use std::io::{self, BufWriter, Write};
use std::path::{Path, PathBuf};

use serde_json::{Value, json};

use crate::cloud::PointCloud;
use crate::config::BoardSpec;
use crate::geom::{FaceOutFrame, Intrinsics, quat_from_mat};
use crate::keyframe::PoseAt;

pub const SCHEMA: &str = "orbiter.native.photo_session.v1";
pub const POSE_CONVENTION: &str = "board->camera, world=board";
const FRAME_NOTE: &str = "board — origin at the board centre, +z out of the printed face, y up; millimetres";
const EYE_NOTE: &str = "raw sensor frame; quarter_turns_cw/flip are display-only and NOT applied here";
const SIDE: &str = "left";
pub const LASER_PLY: &str = "laser.ply";

/// orbiter-native's scan volume: a cylinder standing on the board centre.
#[derive(Clone, Copy, Debug)]
pub struct Cylinder {
    pub height_mm: f64,
    pub radius_mm: f64,
    pub floor_mm: f64,
}

/// What `session.json` says, fixed when the session starts.
pub struct SessionInfo {
    pub session_id: String,
    pub started_utc: String,
    pub camera_id: String,
    pub board: BoardSpec,
    pub volume: Cylinder,
    /// The calibration, at any size with the photographs' aspect: it is
    /// rescaled to the first photograph's size.
    pub k: Intrinsics,
    /// `k` is a calibration rather than the camera's own estimate: the
    /// photographs must then have exactly its aspect (Camera2 crops each
    /// stream to its own aspect, so "within 0.5 %" is a different crop).
    pub calibrated: bool,
    pub k_rms_px: Option<f64>,
    /// The thresholds that decided what was photographed, verbatim.
    pub params: Value,
}

pub struct PhotoSession {
    dir: PathBuf,
    info: SessionInfo,
    frame: FaceOutFrame,
    /// The photograph size `session.json` was written for.
    wh: Option<(u32, u32)>,
    n: u32,
    pass_id: u32,
    last_laser: Option<bool>,
}

impl PhotoSession {
    /// Create the session directory; one that already exists is refused, so
    /// two sessions can never write into one.
    pub fn open(dir: PathBuf, info: SessionInfo) -> io::Result<Self> {
        if let Some(parent) = dir.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::create_dir(&dir)?;
        fs::create_dir(dir.join("photos"))?;
        let frame = FaceOutFrame::centred(info.board.width_mm(), info.board.height_mm());
        Ok(PhotoSession { dir, info, frame, wh: None, n: 0, pass_id: 0, last_laser: None })
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }

    /// Photographs on disk.
    pub fn count(&self) -> u32 {
        self.n
    }

    /// Move the still at `pending` into the session under its running name
    /// and append its row. `at` is the OpenCV board pose at the still's
    /// exposure; `laser_on` whether the laser stripe was in view.
    pub fn add(&mut self, pending: &Path, ts_ns: i64, wh: (u32, u32), at: &PoseAt, laser_on: bool) -> Result<String, String> {
        match self.wh {
            None => {
                let k = &self.info.k;
                let exact = wh.0 as f64 * k.height == wh.1 as f64 * k.width;
                if !(exact || !self.info.calibrated && k.same_aspect(wh.0, wh.1)) {
                    return Err(format!(
                        "photo {}x{} has another aspect than the calibration ({:.0}x{:.0})",
                        wh.0, wh.1, self.info.k.width, self.info.k.height
                    ));
                }
                self.write_session_json(wh).map_err(|e| format!("session.json: {e}"))?;
                self.wh = Some(wh);
            }
            Some(s) if s != wh => {
                // orbiter-recon refuses an eye whose photographs differ in size
                return Err(format!("photo {}x{} is not this session's {}x{}", wh.0, wh.1, s.0, s.1));
            }
            Some(_) => {}
        }
        // native's passes are laser-on / laser-off runs of photographs
        let pass_id = self.pass_id + self.last_laser.is_some_and(|l| l != laser_on) as u32;
        let n = self.n + 1;
        let name = format!("{SIDE}_{n:04}.jpg");
        fs::rename(pending, self.dir.join("photos").join(&name)).map_err(|e| format!("{}: {e}", pending.display()))?;
        let row = self.row(n, &name, ts_ns, wh, at, laser_on, pass_id);
        let line = serde_json::to_string(&row).map_err(|e| e.to_string())? + "\n";
        OpenOptions::new()
            .create(true)
            .append(true)
            .open(self.dir.join("photos.jsonl"))
            .and_then(|mut f| f.write_all(line.as_bytes()))
            .map_err(|e| format!("photos.jsonl: {e}"))?;
        self.n = n;
        self.pass_id = pass_id;
        self.last_laser = Some(laser_on);
        Ok(name)
    }

    /// Write `laser.ply` from the cloud (through a temporary file: a reader
    /// must never find half of one). `None` when the cloud is empty.
    pub fn write_laser(&self, cloud: &PointCloud) -> io::Result<Option<usize>> {
        if cloud.is_empty() {
            return Ok(None);
        }
        let path = self.dir.join(LASER_PLY);
        let tmp = self.dir.join(format!("{LASER_PLY}.tmp"));
        let mut w = BufWriter::new(File::create(&tmp)?);
        let n = cloud.write_ply_in(&mut w, &self.frame)?;
        w.flush()?;
        drop(w);
        fs::rename(&tmp, &path)?;
        Ok(Some(n))
    }

    fn write_session_json(&self, wh: (u32, u32)) -> io::Result<()> {
        let (i, b, v) = (&self.info, &self.info.board, &self.info.volume);
        let k = i.k.for_frame(wh.0, wh.1);
        let mut intrinsics = json!({ "fx": k.fx, "fy": k.fy, "cx": k.cx, "cy": k.cy, "dist": k.dist });
        if let Some(rms) = i.k_rms_px {
            // absent rather than null: native reads it with float(), which
            // takes its NaN default for a missing key and fails on None
            intrinsics["rms_px"] = json!(rms);
        }
        let doc = json!({
            "schema": SCHEMA,
            "session_id": i.session_id,
            "started_utc": i.started_utc,
            "board": {
                "squares_x": b.squares_x,
                "squares_y": b.squares_y,
                "square_mm": decimal(b.square_mm),
                "marker_mm": decimal(b.marker_mm),
                "dictionary": dictionary_name(b.dict),
            },
            "frame": FRAME_NOTE,
            "volume": { "height_mm": v.height_mm, "radius_mm": v.radius_mm, "floor_mm": v.floor_mm },
            "eyes": {
                SIDE: {
                    "camera_id": i.camera_id,
                    "wh": [wh.0, wh.1],
                    "intrinsics": intrinsics,
                    "note": EYE_NOTE,
                },
            },
            "extrinsics": null,
            "params": i.params,
        });
        fs::write(self.dir.join("session.json"), serde_json::to_string_pretty(&doc)?)
    }

    #[allow(clippy::too_many_arguments)]
    fn row(&self, n: u32, name: &str, ts_ns: i64, wh: (u32, u32), at: &PoseAt, laser_on: bool, pass_id: u32) -> Value {
        let pose = self.frame.pose(&at.pose);
        let f = &at.frame;
        let mono = ts_ns as f64 * 1e-9;
        json!({
            "n": n,
            "side": SIDE,
            "camera_id": self.info.camera_id,
            "file": format!("photos/{name}"),
            "stripe": format!("stripe/{}.npz", name.trim_end_matches(".jpg")),
            "wh": [wh.0, wh.1],
            "capture_mono": mono,
            "pair_capture_mono": mono,
            "pose": { "q_wxyz": quat_from_mat(&pose.r), "t_mm": pose.t, "convention": POSE_CONVENTION },
            "pose_frame": SIDE,
            "pose_source": if at.interpolated_gap_ns == 0 { "easyscanner:frame" } else { "easyscanner:interpolated" },
            "pose_composed": false,
            "pose_rms_px": f.reproj_rms,
            "pose_gap_deg": null,
            "pose_gap_mm": null,
            "pose_corners": f.corners,
            "pose_smooth_mm": 0.0,
            "sharpness": f.sharpness,
            "pass_id": pass_id,
            "laser_on": laser_on,
            "stripe_pixels": f.stripe_px,
            "stripe_shifted": false,
            "kept_points": f.kept,
            // EasyScanner's own, ignored by native: the exposure's clock and,
            // for a skipped frame, the span the pose was interpolated across
            "sensor_timestamp_ns": ts_ns,
            "pose_interpolated_ms": at.interpolated_gap_ns as f64 * 1e-6,
            "pose_margin": f.margin,
        })
    }
}

/// An `f32` setting as the decimal it was typed as (33.3, not 33.29999923706055).
fn decimal(v: f32) -> f64 {
    (v as f64 * 1e4).round() / 1e4
}

/// `cv::aruco::PredefinedDictionaryType` by name, as native's manifest
/// carries it.
pub fn dictionary_name(id: i32) -> String {
    const SIZES: [&str; 4] = ["4X4", "5X5", "6X6", "7X7"];
    const COUNTS: [&str; 4] = ["50", "100", "250", "1000"];
    match id {
        0..=15 => format!("DICT_{}_{}", SIZES[id as usize / 4], COUNTS[id as usize % 4]),
        16 => "DICT_ARUCO_ORIGINAL".into(),
        17 => "DICT_APRILTAG_16h5".into(),
        18 => "DICT_APRILTAG_25h9".into(),
        19 => "DICT_APRILTAG_36h10".into(),
        20 => "DICT_APRILTAG_36h11".into(),
        21 => "DICT_ARUCO_MIP_36h12".into(),
        _ => format!("DICT_{id}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::geom::{Pose, mat_from_quat, norm, rodrigues, rotation_distance, sub};
    use crate::keyframe::TrackedPose;

    fn info() -> SessionInfo {
        SessionInfo {
            session_id: "20261019-083000".into(),
            started_utc: "2026-10-19T06:30:00Z".into(),
            camera_id: "0".into(),
            board: BoardSpec { squares_x: 8, squares_y: 8, square_mm: 45.0, marker_mm: 33.3, dict: 5 },
            volume: Cylinder { height_mm: 360.0, radius_mm: 200.0, floor_mm: 5.0 },
            k: Intrinsics { fx: 1000.0, fy: 1000.0, cx: 639.5, cy: 479.5, dist: [0.01, -0.02, 0.0, 0.0, 0.003], width: 1280.0, height: 960.0 },
            calibrated: true,
            k_rms_px: Some(0.4),
            params: json!({ "capture": { "interval_s": 5.0 } }),
        }
    }

    fn at(pose: Pose, stripe_px: usize) -> PoseAt {
        let frame =
            TrackedPose { ts_ns: 0, pose, reproj_rms: 0.25, corners: 49, margin: f64::NAN, stripe_px, kept: 7, sharpness: f64::NAN };
        PoseAt { pose, frame, interpolated_gap_ns: 0 }
    }

    fn scratch(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("es-photo-session-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        d
    }

    fn still(dir: &Path, tag: &str) -> PathBuf {
        let p = dir.join("photos").join(format!(".pending_{tag}.jpg"));
        fs::write(&p, b"\xff\xd8jpeg").unwrap();
        p
    }

    #[test]
    fn writes_what_orbiter_native_reads() {
        let dir = scratch("native");
        let mut s = PhotoSession::open(dir.clone(), info()).unwrap();
        let pose = Pose { r: rodrigues([2.8, 0.1, 0.3]), t: [-160.0, 150.0, 430.0] };

        let name = s.add(&still(&dir, "a"), 1_500_000_000, (4000, 3000), &at(pose, 0), false).unwrap();
        assert_eq!(name, "left_0001.jpg");
        assert!(dir.join("photos/left_0001.jpg").exists() && !dir.join("photos/.pending_a.jpg").exists());
        s.add(&still(&dir, "b"), 1_600_000_000, (4000, 3000), &at(pose, 120), true).unwrap();
        // another size on the same eye is refused, and the still stays where it was
        let odd = still(&dir, "c");
        assert!(s.add(&odd, 1_700_000_000, (2000, 1500), &at(pose, 0), true).is_err());
        assert!(odd.exists() && s.count() == 2);

        let session: Value = serde_json::from_str(&fs::read_to_string(dir.join("session.json")).unwrap()).unwrap();
        assert_eq!(session["schema"], SCHEMA);
        assert_eq!(session["board"]["dictionary"], "DICT_5X5_100");
        let eye = &session["eyes"]["left"];
        assert_eq!(eye["wh"], json!([4000, 3000]));
        // K rescaled to the photograph (pixel-centre convention)
        assert!((eye["intrinsics"]["fx"].as_f64().unwrap() - 3125.0).abs() < 1e-9);
        assert!((eye["intrinsics"]["cx"].as_f64().unwrap() - 1999.5).abs() < 1e-9);
        assert_eq!(eye["intrinsics"]["dist"].as_array().unwrap().len(), 5);
        assert!(session["extrinsics"].is_null());

        let rows: Vec<Value> =
            fs::read_to_string(dir.join("photos.jsonl")).unwrap().lines().map(|l| serde_json::from_str(l).unwrap()).collect();
        assert_eq!(rows.len(), 2);
        let r = &rows[1];
        assert_eq!((r["n"].as_u64(), r["side"].as_str(), r["file"].as_str()), (Some(2), Some("left"), Some("photos/left_0002.jpg")));
        assert_eq!(r["pose"]["convention"], POSE_CONVENTION);
        assert!(r["pose_gap_mm"].is_null() && r["sharpness"].is_null(), "NaN is written as null, like native");
        // the laser came into view: a new pass
        assert_eq!((rows[0]["pass_id"].as_u64(), rows[1]["pass_id"].as_u64()), (Some(0), Some(1)));
        assert_eq!(r["laser_on"], true);

        // the row's pose is the same camera, expressed in native's centred face-out board
        let q: Vec<f64> = r["pose"]["q_wxyz"].as_array().unwrap().iter().map(|v| v.as_f64().unwrap()).collect();
        let t: Vec<f64> = r["pose"]["t_mm"].as_array().unwrap().iter().map(|v| v.as_f64().unwrap()).collect();
        let native = Pose { r: mat_from_quat([q[0], q[1], q[2], q[3]]), t: [t[0], t[1], t[2]] };
        let frame = FaceOutFrame::centred(360.0, 360.0);
        let expect = frame.pose(&pose);
        // acos near 1 resolves ~1e-8 rad at best
        assert!(rotation_distance(&native.r, &expect.r) < 1e-7 && norm(sub(native.t, expect.t)) < 1e-9);
        let corner = [360.0, 0.0, 0.0];
        assert!(norm(sub(pose.to_cam(corner), native.to_cam(frame.point(corner)))) < 1e-6);
        fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn uncalibrated_k_existing_dir_and_inexact_aspect() {
        let dir = scratch("uncal");
        let pose = Pose { r: rodrigues([2.8, 0.1, 0.3]), t: [-160.0, 150.0, 430.0] };
        // the camera's own estimate at the active array (not exactly 4:3)
        let guess = SessionInfo {
            calibrated: false,
            k_rms_px: None,
            k: Intrinsics { fx: 3200.0, fy: 3200.0, cx: 2040.0, cy: 1536.0, dist: [0.0; 5], width: 4080.0, height: 3072.0 },
            ..info()
        };
        let mut s = PhotoSession::open(dir.clone(), guess).unwrap();
        s.add(&still(&dir, "a"), 1, (4000, 3000), &at(pose, 0), false).unwrap();
        let session: Value = serde_json::from_str(&fs::read_to_string(dir.join("session.json")).unwrap()).unwrap();
        let k = session["eyes"]["left"]["intrinsics"].as_object().unwrap();
        assert!(!k.contains_key("rms_px"), "native reads rms_px with float(): null would crash it");
        // a second session can never open into the same directory
        assert!(PhotoSession::open(dir.clone(), info()).is_err());
        fs::remove_dir_all(&dir).unwrap();

        // calibrated at 1280x960: a 4080x3072 still is another crop, refused
        let dir = scratch("crop");
        let mut s = PhotoSession::open(dir.clone(), info()).unwrap();
        let p = still(&dir, "b");
        let err = s.add(&p, 1, (4080, 3072), &at(pose, 0), false).unwrap_err();
        assert!(err.contains("aspect") && p.exists(), "{err}");
        s.add(&p, 2, (4000, 3000), &at(pose, 0), false).unwrap();
        fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn laser_cloud_in_the_session_frame() {
        let dir = scratch("laser");
        let s = PhotoSession::open(dir.clone(), info()).unwrap();
        let mut cloud = PointCloud::new(10);
        assert_eq!(s.write_laser(&cloud).unwrap(), None);
        cloud.push([180.0, 180.0, -12.0], [9, 9, 9]);
        assert_eq!(s.write_laser(&cloud).unwrap(), Some(1));
        let bytes = fs::read(dir.join(LASER_PLY)).unwrap();
        let body = &bytes[bytes.len() - PointCloud::BYTES_PER_POINT..];
        let f = |i: usize| f32::from_le_bytes(body[i..i + 4].try_into().unwrap());
        assert_eq!((f(0), f(4), f(8)), (0.0, 0.0, 12.0));
        assert!(!dir.join("laser.ply.tmp").exists());
        fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn dictionary_names() {
        assert_eq!(dictionary_name(0), "DICT_4X4_50");
        assert_eq!(dictionary_name(5), "DICT_5X5_100");
        assert_eq!(dictionary_name(11), "DICT_6X6_1000");
        assert_eq!(dictionary_name(20), "DICT_APRILTAG_36h11");
    }
}
