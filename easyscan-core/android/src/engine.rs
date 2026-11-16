//! The per-frame pipeline and the scanner's modes — the desktop prototype's `app.py`
//! main loop without the window: board pose → live intrinsics calibration →
//! laser stripe in the working volume → laser-plane calibration → plane
//! triangulation into the board-frame cloud → overlay layers.
//!
//! One `Engine` is driven from a single thread (the app's processing thread):
//! frames, configuration and commands all arrive there, so it needs no locks.
//! Only the intrinsics solve runs elsewhere (see `calib`).

use std::collections::VecDeque;
use std::fs;
use std::io::{BufWriter, Write};
use std::path::{Path, PathBuf};
use std::time::Instant;

use opencv::core::{CV_8UC1, Mat};
use scan_algo::cloud::PointCloud;
use scan_algo::config::{self, Config, IntrinsicsFile, SavedPlane};
use scan_algo::focus::laplacian_variance;
use scan_algo::geom::{FaceOutFrame, Intrinsics, Pose, V3, euler_xyz_deg};
use scan_algo::keyframe::{Due, Hold, Miss, PoseTrack, TrackedPose, Trigger};
use scan_algo::laser::{LaserDetector, LaserParams, Roi, YuvFrame};
use scan_algo::photo_session::{Cylinder, PhotoSession, SessionInfo};
use scan_algo::plane::{PlaneCalibrator, PlaneFit};
use scan_algo::stripe::{self, BoardStripe};
use scan_algo::volume::Volume;
use scan_algo::overlay;
use serde::Deserialize;

use crate::board::{BoardTracker, PoseResult, corner_bbox, distance};
use crate::calib::{CalibState, LiveCalibrator, Outcome};
use crate::frame_out::{FrameOut, ev, h, layer, photo_state};

/// Commands from the app (`NativeCore.Cmd`).
pub mod cmd {
    pub const CALIB_START: i32 = 1;
    pub const CALIB_STOP: i32 = 2;
    pub const CALIB_CAPTURE: i32 = 3;
    pub const CALIB_RESET: i32 = 4;
    pub const PLANE_START: i32 = 5;
    pub const PLANE_STOP: i32 = 6;
    pub const PLANE_FORCE: i32 = 7;
    pub const SCAN_START: i32 = 8;
    pub const SCAN_STOP: i32 = 9;
    pub const CLOUD_CLEAR: i32 = 10;
    pub const EXPORT_PLY: i32 = 11;
    pub const FORGET_INTRINSICS: i32 = 12;
    pub const FORGET_PLANE: i32 = 13;
    /// arg: `{"dir", "session_id", "started_utc", "serial"}` — open a photo session
    pub const PHOTOS_START: i32 = 14;
    /// close it: drop stills in flight, write `laser.ply`
    pub const PHOTOS_STOP: i32 = 15;
    /// take the next keyframe as soon as the camera is steady
    pub const PHOTO_SNAP: i32 = 16;
    /// arg: `{"file", "ts", "w", "h"}` — a still the app wrote for a requested keyframe
    pub const PHOTO_ADD: i32 = 17;
    /// arg: why — the requested still could not be taken or written
    pub const PHOTO_FAILED: i32 = 18;
    /// pause the scan and write the cloud into the open session (the app is leaving the screen)
    pub const CHECKPOINT: i32 = 19;
    /// no more photos in the open session, which keeps the scan (no still stream can be had)
    pub const PHOTOS_OFF: i32 = 20;
    /// arg: "1"/"0" - pose comes from ARCore, skip the plane-camera-model gate
    pub const AR_MODE: i32 = 21;
}

/// The Z-up cloud a session keeps next to orbiter-native's `laser.ply`.
const SESSION_SCAN_PLY: &str = "scan.ply";

/// Points drawn from the accumulated cloud each frame.
const PREVIEW_POINTS: usize = 4000;
/// Analysis frames kept for posing stills (~4 s at 30 fps, ~2 s at 60).
const TRACK_FRAMES: usize = 128;
/// A still whose own frame was skipped is posed between its neighbours only
/// when they are this close (≈ 4 frames at 30 fps).
const MAX_INTERP_GAP_NS: i64 = 140_000_000;
/// A still is given up on when no frame at or after its exposure arrives in
/// this many frames.
const STILL_PATIENCE_FRAMES: u32 = 60;
/// Laser points this close to the board are its surface, not the object
/// (orbiter-native's `floor_mm`).
const FLOOR_MM: f64 = 5.0;
/// A photo counts as laser-free only when no stripe was seen this close to
/// its exposure, either side: a stripe the detector missed in one frame would
/// otherwise go un-inpainted into the texture.
const LASER_NEAR_NS: i64 = 1_000_000_000;
/// Stills failing this many times in a row close the session: the cause
/// repeats (disk, camera) and retrying only burns captures.
const MAX_PHOTO_FAILURES: u32 = 5;

#[derive(Default)]
struct Messages {
    calib: String,
    plane: String,
    scan: String,
    photos: String,
}

/// A still written by the app, waiting for its exposure's pose.
struct Still {
    file: PathBuf,
    ts_ns: i64,
    wh: (u32, u32),
    frames_waited: u32,
}

#[derive(Deserialize)]
struct PhotosStartArg {
    dir: PathBuf,
    session_id: String,
    started_utc: String,
    #[serde(default)]
    serial: f64,
    /// take photo keyframes; without them the session only keeps the scan
    #[serde(default = "yes")]
    photos: bool,
    /// a new scan: start from an empty cloud, unless it holds points no
    /// session has saved yet (those go into this one rather than away)
    #[serde(default)]
    fresh: bool,
}

fn yes() -> bool {
    true
}

#[derive(Deserialize)]
struct PhotoAddArg {
    file: PathBuf,
    ts: i64,
    w: u32,
    h: u32,
}

pub struct Engine {
    dir: PathBuf,
    cfg: Config,
    tracker: BoardTracker,
    k_base: Intrinsics,
    k_file: Option<IntrinsicsFile>,
    calib: LiveCalibrator,
    calib_active: bool,
    calib_state: CalibState,
    plane: Option<SavedPlane>,
    plane_cal: PlaneCalibrator,
    plane_active: bool,
    plane_solved_at: usize,
    scan_on: bool,
    /// Pose comes from an external tracker (ARCore): no board, plane model check skipped.
    ar_mode: bool,
    cloud: PointCloud,
    /// Frame number that produced each cloud point (same order as the cloud).
    point_frames: Vec<u32>,
    /// The cloud has points the open session (or, with none open, any session) has not written.
    cloud_unsaved: bool,
    preview: Vec<[f32; 3]>,
    frame_no: u64,
    laser: LaserDetector,
    laser_px: Vec<[f32; 2]>,
    full_roi: Roi,
    last_wh: (u32, u32),
    focus_max: f64,
    msgs: Messages,
    events: u32,
    out: FrameOut,
    segs: Vec<[f32; 4]>,
    pts: Vec<[f32; 2]>,
    pts_in: Vec<[f32; 2]>,
    pts_out: Vec<[f32; 2]>,
    track: PoseTrack,
    trigger: Trigger,
    photos: Option<PhotoSession>,
    photos_enabled: bool,
    stills: Vec<Still>,
    photo_state: f64,
    photo_serial: f64,
    /// Timestamps of recent frames with a laser stripe in view.
    stripe_seen: VecDeque<i64>,
}

impl Engine {
    pub fn new(dir: PathBuf) -> opencv::Result<Engine> {
        let cfg = Config::default();
        let tracker = BoardTracker::new(cfg.board, cfg.detector.aruco3, cfg.detector.roi_tracking)?;
        let mut e = Engine {
            calib: LiveCalibrator::new(cfg.calib_views),
            plane_cal: PlaneCalibrator::new(cfg.plane_views),
            cloud: PointCloud::new(cfg.max_cloud_points),
            point_frames: Vec::new(),
            dir,
            tracker,
            k_base: default_guess(),
            k_file: None,
            calib_active: false,
            calib_state: CalibState::Idle,
            plane: None,
            plane_active: false,
            plane_solved_at: 0,
            scan_on: false,
            ar_mode: false,
            cloud_unsaved: false,
            preview: Vec::new(),
            frame_no: 0,
            laser: LaserDetector::default(),
            laser_px: Vec::new(),
            full_roi: Roi::default(),
            last_wh: (0, 0),
            focus_max: 1e-6,
            msgs: Messages::default(),
            events: ev::MESSAGES,
            out: FrameOut::default(),
            segs: Vec::new(),
            pts: Vec::new(),
            pts_in: Vec::new(),
            pts_out: Vec::new(),
            track: PoseTrack::new(TRACK_FRAMES),
            trigger: Trigger::default(),
            photos: None,
            photos_enabled: true,
            stills: Vec::new(),
            photo_state: photo_state::OFF,
            photo_serial: 0.0,
            stripe_seen: VecDeque::new(),
            cfg,
        };
        e.reload_calibrations();
        Ok(e)
    }

    /// Apply a new configuration (JSON from the app).
    pub fn configure(&mut self, json: &str) -> Result<(), String> {
        let cfg: Config = serde_json::from_str(json).map_err(|e| format!("bad config: {e}"))?;
        if cfg.board != self.cfg.board || cfg.detector != self.cfg.detector {
            self.tracker =
                BoardTracker::new(cfg.board, cfg.detector.aruco3, cfg.detector.roi_tracking).map_err(|e| e.to_string())?;
        }
        if cfg.board != self.cfg.board {
            // a different board invalidates collected views and lines, and
            // the frame an open photo session's poses are in
            self.calib.reset();
            self.plane_cal.reset();
            self.close_photos("board changed");
        }
        if cfg.camera.id != self.cfg.camera.id {
            self.close_photos("camera changed");
        }
        self.calib.target = cfg.calib_views.max(4);
        self.plane_cal.min_lines = cfg.plane_views.max(3);
        self.cloud.max_points = cfg.max_cloud_points;
        self.cfg = cfg;
        let k_before = self.k_base;
        self.reload_calibrations();
        // a model, not bits: a K just solved and the same K read back from its file differ in the last digits
        if !self.k_base.same_model(&k_before) {
            // the session's photos and poses are in the old camera model
            self.close_photos("camera model changed");
        }
        self.events |= ev::MESSAGES;
        Ok(())
    }

    /// The laser plane, when it was fitted with the current camera model.
    fn usable_plane(&self) -> Option<(V3, f64)> {
        self.plane.filter(|p| p.fits(&self.k_base)).map(|p| (p.n, p.d))
    }

    /// A saved camera calibration taken at the lens focus in use now.
    fn k_ok(&self) -> bool {
        self.k_file.as_ref().is_some_and(|f| match (f.focus_diopters, self.cfg.camera.focus_diopters) {
            (Some(a), Some(b)) => (a - b).abs() <= 0.05,
            (a, b) => a.is_none() && b.is_none(),
        })
    }

    /// Calibrations from disk that belong to this camera. K falls back to the
    /// camera's factory estimate, else the desktop prototype's default guess.
    fn reload_calibrations(&mut self) {
        self.plane = config::load_plane(&self.dir, &self.cfg.camera.id);
        self.k_file = config::load_intrinsics(&self.dir, &self.cfg.camera.id);
        self.k_base = match (&self.k_file, self.cfg.camera.guess) {
            (Some(f), _) => f.intrinsics(),
            (None, Some(g)) => g.into(),
            (None, None) => default_guess(),
        };
    }

    fn volume(&self) -> Volume {
        let b = &self.cfg.board;
        let height = if self.cfg.volume.height_mm > 0.0 { self.cfg.volume.height_mm } else { b.width_mm().max(b.height_mm()) };
        Volume { board_w: b.width_mm(), board_h: b.height_mm(), margin: self.cfg.volume.margin_mm, height }
    }

    fn laser_params(&self) -> LaserParams {
        LaserParams {
            color: self.cfg.laser.laser_color(),
            thresh: self.cfg.laser.thresh,
            min_sat: self.cfg.laser.min_sat,
            ..LaserParams::default()
        }
    }

    /// One analysis frame; `ts_ns` is its sensor timestamp, the clock the
    /// stills of a photo session are matched on.
    pub fn process(&mut self, f: &YuvFrame, ts_ns: i64) -> opencv::Result<&[f32]> {
        self.process_inner(f, ts_ns, None)
    }

    /// One analysis frame whose camera pose and intrinsics come from an external
    /// tracker (ARCore). `pose` is scan-frame -> camera (the same convention the
    /// ChArUco board pose uses); `k` is the camera model for this frame. No board
    /// detection and no live recalibration: the saved laser plane is used as-is.
    pub fn process_ar(
        &mut self,
        f: &YuvFrame,
        ts_ns: i64,
        pose: Pose,
        k: Intrinsics,
        up: f64,
    ) -> opencv::Result<&[f32]> {
        self.process_inner(f, ts_ns, Some((pose, k, up)))
    }

    fn process_inner(
        &mut self,
        f: &YuvFrame,
        ts_ns: i64,
        ar: Option<(Pose, Intrinsics, f64)>,
    ) -> opencv::Result<&[f32]> {
        let t0 = Instant::now();
        let (w, hgt) = (f.width as u32, f.height as u32);
        if self.last_wh != (w, hgt) {
            // sharpness is not comparable across resolutions
            self.focus_max = 1e-6;
            self.last_wh = (w, hgt);
        }
        self.frame_no += 1;
        if ar.is_none() {
            self.poll_calibration();
        }

        let k = match &ar {
            Some((_, k, _)) => *k,
            None => self.k_base.for_frame(w, hgt),
        };
        // Laser-plane calibration in AR mode: use the same board detection and
        // plane fit as the board app, with ARCore's per-frame K and the board
        // pose it solves in the ARCore camera frame.
        let ar_plane_cal = ar.is_some() && self.plane_active;
        let (n_corners, pose_res, t_board) = if ar_plane_cal {
            let gray = unsafe {
                Mat::new_rows_cols_with_data_unsafe(hgt as i32, w as i32, CV_8UC1, f.y.as_ptr() as *mut _, f.y_stride)
            }?;
            let n = self.tracker.detect(&gray)?;
            let pose_res = self.tracker.solve_pose(&k)?;
            (n, pose_res, t0.elapsed())
        } else if let Some((pose, _, _)) = ar {
            (0usize, Some(PoseResult { pose, reproj_rms: f64::NAN, margin: None }), t0.elapsed())
        } else {
            // the luma plane IS the grey image ArUco wants - wrapped, not copied
            let gray = unsafe {
                Mat::new_rows_cols_with_data_unsafe(hgt as i32, w as i32, CV_8UC1, f.y.as_ptr() as *mut _, f.y_stride)
            }?;
            let n = self.tracker.detect(&gray)?;
            let pose_res = self.tracker.solve_pose(&k)?;
            (n, pose_res, t0.elapsed())
        };

        let sharpness = if ar.is_some() {
            None
        } else {
            corner_bbox(&self.tracker.corners)
                .and_then(|bb| laplacian_variance(f.y, f.y_stride, (f.width, f.height), bb, 40_000))
        };
        let focus_pct = sharpness.map(|s| {
            self.focus_max = self.focus_max.max(s);
            100.0 * s / self.focus_max
        });

        // ── live intrinsics calibration: every genuinely new view ──
        if ar.is_none() && self.calib_active && pose_res.is_some() && !self.calib.solving() {
            if self.calib.capture(&self.tracker.corners, &self.tracker.ids, (w, hgt)) {
                let (n, t) = (self.calib.count(), self.calib.target);
                self.msgs.calib = if n < t {
                    format!("collecting {n}/{t} — move the board")
                } else {
                    format!("{n} views — last solve rejected, vary angles & retrying")
                };
                self.events |= ev::MESSAGES;
            }
            if self.calib.maybe_solve(self.cfg.board, self.k_base) {
                self.calib_state = CalibState::Solving;
                self.msgs.calib = format!("solving over {} views… keep the board steady", self.calib.count());
                self.events |= ev::MESSAGES;
            }
        }

        // ── laser stripe, confined to the projected working volume ──
        let t_laser0 = Instant::now();
        let vol = self.volume();
        let params = self.laser_params();
        let pose = pose_res.as_ref().map(|p| p.pose);
        let up = match &ar {
            Some((_, _, u)) => *u,
            None => pose.map(|p| p.up_sign()).unwrap_or(1.0),
        };
        let mut found = false;
        let mut roi_frac = f64::NAN;
        let mut board_stripe: Option<BoardStripe> = None;
        if self.cfg.laser.enabled {
            if self.full_roi.spans.len() != f.height || self.full_roi.spans.first().map(|s| s.1) != Some(w) {
                self.full_roi = Roi::full(f.width, f.height);
            }
            let volume_roi;
            let roi = match pose {
                Some(p) => {
                    volume_roi = vol.roi(&p, &k, f.width, f.height, up);
                    volume_roi.as_ref()
                }
                None if self.cfg.laser.without_board => Some(&self.full_roi),
                None => None,
            };
            if let Some(roi) = roi {
                roi_frac = roi.area() as f64 / (f.width * f.height) as f64;
                found = self.laser.detect(f, roi, &params, &mut self.laser_px);
            }
            if let (true, Some(p)) = (found, pose) {
                board_stripe = stripe::on_board(&self.laser_px, &k, &p, &vol);
            }
        }
        if !found {
            self.laser_px.clear();
        }

        // ── laser-plane calibration: on-board stripes across tilts ──
        if self.plane_active && let Some(s) = &board_stripe {
            let n_lines = self.plane_cal.add(&s.cam_pts);
            let last = self.plane_cal.last_rms.map(|r| format!(" — last fit {r:.1}mm")).unwrap_or_default();
            self.msgs.plane = format!("vary board tilt{last}");
            self.events |= ev::MESSAGES;
            if self.plane_cal.ready() && n_lines != self.plane_solved_at {
                self.plane_solved_at = n_lines;
                match self.plane_cal.solve(false) {
                    Some(fit) => self.accept_plane(fit, false),
                    None => {
                        self.msgs.plane =
                            format!("fit {:.1}mm > 6 — vary tilt or force", self.plane_cal.last_rms.unwrap_or(f64::NAN));
                    }
                }
            }
        }

        // ── classify by 3-D volume membership; accumulate the scan ──
        self.pts_in.clear();
        self.pts_out.clear();
        let plane = if ar.is_some() {
            // the saved plane is a physical sheet in camera coordinates; with
            // ARCore per-frame intrinsics the saved camera model need not match
            self.plane.as_ref().map(|p| (p.n, p.d))
        } else {
            self.usable_plane()
        };
        let classified = !self.plane_active && plane.is_some() && pose.is_some() && found;
        if let (true, Some((n, d)), Some(p)) = (classified, plane, pose) {
            let mut inside = vec![false; self.laser_px.len()];
            let mut pushed = false;
            let frame_id = self.frame_no.min(u32::MAX as u64) as u32;
            let (cloud, frames, px, scan_on) = (&mut self.cloud, &mut self.point_frames, &self.laser_px, self.scan_on);
            stripe::to_laser_plane(px, &k, n, d, |i, x| {
                let b = p.to_board(x);
                if vol.contains(&p, b, up) {
                    inside[i] = true;
                    if scan_on {
                        let (u, v) = (px[i][0].round() as usize, px[i][1].round() as usize);
                        if cloud.push(b, f.rgb_at(u.min(f.width - 1), v.min(f.height - 1))) {
                            frames.push(frame_id);
                        }
                        pushed = true;
                    }
                }
            });
            self.cloud_unsaved |= pushed;
            for (q, ins) in self.laser_px.iter().zip(&inside) {
                if *ins { self.pts_in.push(*q) } else { self.pts_out.push(*q) }
            }
        }
        if self.cloud.is_empty() {
            self.preview.clear();
        } else if self.preview.is_empty() || self.frame_no.is_multiple_of(10) {
            self.cloud.sample(PREVIEW_POINTS, &mut self.preview);
        }
        let t_laser = t_laser0.elapsed();

        // ── photo keyframes: this frame's pose for the stills, then the trigger ──
        let tracked = pose_res.as_ref().map(|p| TrackedPose {
            ts_ns,
            pose: p.pose,
            reproj_rms: p.reproj_rms,
            corners: n_corners,
            margin: p.margin.unwrap_or(f64::NAN),
            stripe_px: if found { self.laser_px.len() } else { 0 },
            kept: self.pts_in.len(),
            sharpness: sharpness.unwrap_or(f64::NAN),
        });
        self.track.push(ts_ns, tracked);
        if found {
            self.stripe_seen.push_back(ts_ns);
        }
        while self.stripe_seen.front().is_some_and(|&t| t < ts_ns - 4 * LASER_NEAR_NS || t > ts_ns) {
            // too old, or from before a camera clock restart
            self.stripe_seen.pop_front();
        }
        self.photos_step();

        // ── result: header + overlay layers ──
        self.out.begin();
        self.fill_header(&k, n_corners, pose_res.as_ref(), focus_pct, board_stripe.as_ref(), found, roi_frac);
        self.emit_layers(&k, pose, &vol, classified, board_stripe.as_ref(), up);
        let o = &mut self.out;
        o.set(h::T_TOTAL_MS, t0.elapsed().as_secs_f64() * 1e3);
        o.set(h::T_BOARD_MS, t_board.as_secs_f64() * 1e3);
        o.set(h::T_LASER_MS, t_laser.as_secs_f64() * 1e3);
        o.set(h::PHOTO_STATE, self.photo_state);
        o.set(h::PHOTO_COUNT, self.photos.as_ref().map_or(0, |s| s.count()) as f64);
        o.set(h::PHOTO_SERIAL, self.photo_serial);
        if let Some((v, w)) = self.track.speed(Trigger::SPEED_WINDOW_NS) {
            o.set(h::CAM_SPEED, v);
            o.set(h::CAM_TURN, w);
        }
        o.set(h::EVENTS, self.events as f64);
        o.set(h::FRAME_NO, self.frame_no as f64);
        Ok(&self.out.buf)
    }

    /// The last result reached the app: its one-shot events are delivered.
    pub fn clear_events(&mut self) {
        self.events = 0;
    }

    fn poll_calibration(&mut self) {
        match self.calib.poll() {
            Some(Outcome::Solved { k, rms }) => {
                let file = IntrinsicsFile {
                    fx: k.fx,
                    fy: k.fy,
                    cx: k.cx,
                    cy: k.cy,
                    dist: k.dist.to_vec(),
                    calibrated: true,
                    width: k.width as u32,
                    height: k.height as u32,
                    camera_id: Some(self.cfg.camera.id.clone()),
                    focus_diopters: self.cfg.camera.focus_diopters,
                    rms_px: Some(rms),
                };
                let saved = config::save_intrinsics(&self.dir, &file);
                self.close_photos("camera re-calibrated");
                self.k_base = k;
                self.k_file = Some(file);
                self.calib_active = false;
                self.calib.reset();
                self.calib_state = CalibState::Saved;
                self.msgs.calib = match saved {
                    Ok(()) => format!("SAVED | fx {:.0} cx {:.0} rms {rms:.2}px", k.fx, k.cx),
                    Err(e) => format!("solved (rms {rms:.2}px) but NOT saved: {e}"),
                };
                self.events |= ev::MESSAGES | ev::INTRINSICS_SAVED;
            }
            Some(Outcome::Rejected { reason, .. }) => {
                // keep collecting and retry — that is why the count climbs past the target
                self.calib_state = CalibState::Rejected;
                self.msgs.calib = format!("solve REJECTED ({reason}) — add more varied angles");
                self.events |= ev::MESSAGES;
            }
            None => {}
        }
    }

    fn accept_plane(&mut self, fit: PlaneFit, forced: bool) {
        if !self.cloud.is_empty() {
            // the points so far are triangulated with the old plane: they end that scan
            self.close_photos("laser plane re-fitted");
        }
        let plane = SavedPlane { n: fit.n, d: fit.d, k: Some(self.k_base) };
        let saved = config::save_plane(&self.dir, &self.cfg.camera.id, &plane, fit.rms_mm, self.plane_cal.count());
        self.plane = Some(plane);
        self.plane_active = false;
        self.plane_cal.reset();
        self.plane_solved_at = 0;
        let tag = if forced { " (forced)" } else { "" };
        self.msgs.plane = match saved {
            Ok(()) => format!("SAVED plane{tag} | rms {:.2} mm", fit.rms_mm),
            Err(e) => format!("plane fitted{tag} but NOT saved: {e}"),
        };
        self.events |= ev::MESSAGES | ev::PLANE_SAVED;
    }

    #[allow(clippy::too_many_arguments)]
    fn fill_header(
        &mut self,
        k: &Intrinsics,
        n_corners: usize,
        pose: Option<&PoseResult>,
        focus_pct: Option<f64>,
        stripe: Option<&BoardStripe>,
        found: bool,
        roi_frac: f64,
    ) {
        let (plane_ok, k_ok, n_markers) = (self.usable_plane().is_some(), self.k_ok(), self.tracker.n_markers());
        let o = &mut self.out;
        o.set(h::FRAME_W, self.last_wh.0 as f64);
        o.set(h::FRAME_H, self.last_wh.1 as f64);
        o.set(h::N_CORNERS, n_corners as f64);
        o.set(h::DETECTED, pose.is_some() as u8 as f64);
        if let Some(p) = pose {
            let c = p.pose.camera_position();
            let e = euler_xyz_deg(&scan_algo::geom::transpose(&p.pose.r));
            o.set(h::CAM_X, c[0]);
            o.set(h::CAM_Y, c[1]);
            o.set(h::CAM_Z, c[2]);
            o.set(h::ROLL, e[0]);
            o.set(h::PITCH, e[1]);
            o.set(h::YAW, e[2]);
            o.set(h::DISTANCE, distance(&p.pose));
            o.set(h::REPROJ_RMS, p.reproj_rms);
            o.set(h::POSE_MARGIN, p.margin.unwrap_or(f64::NAN));
        }
        o.set(h::FOCUS_PCT, focus_pct.unwrap_or(f64::NAN));
        o.set(h::LASER_STATE, if !self.cfg.laser.enabled { 0.0 } else if found { 2.0 } else { 1.0 });
        o.set(h::LASER_POINTS, self.laser_px.len() as f64);
        o.set(h::LASER_PEAK, self.laser.stats.peak as f64);
        o.set(h::LASER_ROI_FRAC, roi_frac);
        if let Some(s) = stripe {
            o.set(h::LASER_MEAN_X, s.mean[0]);
            o.set(h::LASER_MEAN_Y, s.mean[1]);
            o.set(h::LASER_ANGLE, s.angle_deg);
            o.set(h::LASER_LENGTH, s.length_mm);
            o.set(h::LASER_PLANE_RMS, s.plane_rms_mm);
        }
        let calib_state = if self.calib.solving() { CalibState::Solving } else { self.calib_state };
        o.set(h::CALIB_STATE, calib_state as i32 as f64);
        o.set(h::CALIB_ACTIVE, self.calib_active as u8 as f64);
        o.set(h::CALIB_COUNT, self.calib.count() as f64);
        o.set(h::CALIB_TARGET, self.calib.target as f64);
        o.set(h::CALIB_RMS, self.calib.last_rms.unwrap_or(f64::NAN));
        o.set(h::PLANE_ACTIVE, self.plane_active as u8 as f64);
        o.set(h::PLANE_LINES, self.plane_cal.n_lines() as f64);
        o.set(h::PLANE_TARGET, self.plane_cal.min_lines as f64);
        o.set(h::PLANE_SPREAD, self.plane_cal.spread());
        o.set(h::PLANE_RMS, self.plane_cal.last_rms.unwrap_or(f64::NAN));
        o.set(h::PLANE_OK, plane_ok as u8 as f64);
        o.set(h::PLANE_STALE, (self.plane.is_some() && !plane_ok) as u8 as f64);
        o.set(h::SCAN_ON, self.scan_on as u8 as f64);
        o.set(h::CLOUD_POINTS, self.cloud.len() as f64);
        o.set(h::CLOUD_MB, self.cloud.nbytes() as f64 / 1e6);
        o.set(h::IN_VOLUME, self.pts_in.len() as f64);
        o.set(h::K_CALIBRATED, self.k_file.is_some() as u8 as f64);
        o.set(h::K_OK, k_ok as u8 as f64);
        o.set(h::N_MARKERS, n_markers as f64);
        o.set(h::FX, k.fx);
        o.set(h::FY, k.fy);
        o.set(h::CX, k.cx);
        o.set(h::CY, k.cy);
    }

    fn emit_layers(&mut self, k: &Intrinsics, pose: Option<Pose>, vol: &Volume, classified: bool, stripe: Option<&BoardStripe>, up: f64) {
        let draw = self.cfg.draw;
        let b = self.cfg.board;
        if draw.corners {
            self.pts.clear();
            self.pts.extend(self.tracker.corners.iter().map(|p| [p.x, p.y]));
            self.out.points(layer::CORNERS, &self.pts);
        }
        if let Some(p) = pose {
            let (nx, ny, sq) = (b.squares_x as usize, b.squares_y as usize, b.square_mm as f64);
            if draw.grid {
                self.segs.clear();
                overlay::board_grid(k, &p, nx, ny, sq, &mut self.segs);
                self.out.segments(layer::GRID, &self.segs);
            }
            self.segs.clear();
            overlay::board_outline(k, &p, nx, ny, sq, &mut self.segs);
            self.out.segments(layer::OUTLINE, &self.segs);
            if draw.axes {
                let len = 0.5 * b.width_mm().min(b.height_mm());
                for (kind, seg) in [layer::AXIS_X, layer::AXIS_Y, layer::AXIS_Z].into_iter().zip(overlay::axes(k, &p, len)) {
                    if let Some(s) = seg {
                        self.out.segments(kind, &[s]);
                    }
                }
            }
            if draw.volume {
                self.segs.clear();
                overlay::volume_edges(k, &p, vol, up, 4, &mut self.segs);
                self.out.segments(layer::VOLUME, &self.segs);
            }
            if draw.cloud && !self.preview.is_empty() {
                self.pts.clear();
                overlay::project_points(k, &p, &self.preview, &mut self.pts);
                self.out.points(layer::CLOUD, &self.pts);
            }
        }
        if classified {
            self.out.points(layer::LASER_OUT, &self.pts_out);
            self.out.points(layer::LASER_IN, &self.pts_in);
        } else {
            self.out.points(layer::LASER_RAW, &self.laser_px);
            if let (Some(s), Some(p)) = (stripe, pose)
                && let Some(seg) = overlay::segment(k, &p, [s.fit_a[0], s.fit_a[1], 0.0], [s.fit_b[0], s.fit_b[1], 0.0])
            {
                self.out.segments(layer::LASER_FIT, &[seg]);
            }
        }
    }

    pub fn command(&mut self, code: i32, arg: &str) -> String {
        self.events |= ev::MESSAGES;
        let solving = self.calib.solving();
        let msg = match code {
            cmd::CALIB_START | cmd::CALIB_STOP | cmd::CALIB_RESET if solving => "solving… please wait".to_string(),
            cmd::CALIB_START => {
                self.calib_active = true;
                self.calib.reset();
                self.calib_state = CalibState::Collecting;
                self.plane_active = false;
                self.scan_on = false;
                self.msgs.calib = "collecting views — move the board to varied angles".into();
                return self.msgs.calib.clone();
            }
            cmd::CALIB_STOP => {
                self.calib_active = false;
                self.calib_state = CalibState::Idle;
                format!("calibration paused ({} views)", self.calib.count())
            }
            cmd::CALIB_CAPTURE => {
                if solving || self.tracker.corners.len() < 4 {
                    "no board to capture".to_string()
                } else if self.calib.capture(&self.tracker.corners, &self.tracker.ids, self.last_wh) {
                    format!("captured {}/{}", self.calib.count(), self.calib.target)
                } else {
                    "view rejected (too few corners or a near-duplicate)".to_string()
                }
            }
            cmd::CALIB_RESET => {
                self.calib.reset();
                self.calib_active = false;
                self.calib_state = CalibState::Idle;
                "calibration reset".to_string()
            }
            cmd::PLANE_START => {
                self.plane_active = true;
                self.plane_cal.reset();
                self.plane_solved_at = 0;
                self.calib_active = false;
                self.scan_on = false;
                self.msgs.plane = "sweep the laser across the board at varied tilts".into();
                return self.msgs.plane.clone();
            }
            cmd::PLANE_STOP => {
                self.plane_active = false;
                format!("plane calibration paused ({} lines)", self.plane_cal.n_lines())
            }
            cmd::PLANE_FORCE => {
                if !self.plane_active {
                    "start the laser-plane calibration first".to_string()
                } else if let Some(fit) = self.plane_cal.solve(true) {
                    self.accept_plane(fit, true);
                    return self.msgs.plane.clone();
                } else {
                    "can't fit yet — collect a few more varied tilts".to_string()
                }
            }
            cmd::SCAN_START => {
                if self.plane.is_none() {
                    "calibrate the laser plane first".to_string()
                } else if !self.ar_mode && self.usable_plane().is_none() {
                    "the laser plane was fitted with another camera calibration — calibrate it again".to_string()
                } else {
                    self.scan_on = true;
                    self.plane_active = false;
                    self.calib_active = false;
                    "scanning…".to_string()
                }
            }
            cmd::SCAN_STOP => {
                self.scan_on = false;
                let saved = self.checkpoint();
                format!("paused — {} pts{saved}", self.cloud.len())
            }
            cmd::CHECKPOINT => {
                self.scan_on = false;
                // a still asked for now never comes back: the camera is closing
                self.trigger.clear_pending();
                let saved = self.checkpoint();
                format!("paused — {} pts{saved}", self.cloud.len())
            }
            cmd::CLOUD_CLEAR => {
                self.cloud.clear();
                self.point_frames.clear();
                self.preview.clear();
                self.cloud_unsaved = false;
                self.scan_on = false;
                "cloud cleared".to_string()
            }
            cmd::PHOTOS_OFF => {
                if self.photos.is_none() {
                    "photos are off".to_string()
                } else {
                    self.stop_photos_keep_session(arg)
                }
            }
            cmd::AR_MODE => {
                self.ar_mode = arg != "0";
                format!("AR pose mode {}", if self.ar_mode { "on" } else { "off" })
            }
            cmd::EXPORT_PLY => self.export_ply(arg),
            cmd::PHOTOS_START => self.open_photos(arg),
            cmd::PHOTOS_STOP => self.close_photos("stopped"),
            cmd::PHOTO_SNAP => {
                if self.photos.is_none() {
                    "start photos first".to_string()
                } else {
                    self.trigger.force();
                    "photo: next steady frame".to_string()
                }
            }
            cmd::PHOTO_ADD => self.add_still(arg),
            cmd::PHOTO_FAILED => {
                if self.photos.is_some() {
                    self.photo_failed(&format!("photo failed: {arg}"));
                }
                self.msgs.photos.clone()
            }
            cmd::FORGET_INTRINSICS => {
                self.close_photos("intrinsics reset");
                let _ = fs::remove_file(self.dir.join(config::INTRINSICS_FILE));
                self.reload_calibrations();
                "intrinsics reset to the camera estimate".to_string()
            }
            cmd::FORGET_PLANE => {
                let _ = fs::remove_file(self.dir.join(config::PLANE_FILE));
                self.plane = None;
                self.scan_on = false;
                "laser plane cleared".to_string()
            }
            _ => format!("unknown command {code}"),
        };
        match code {
            cmd::CALIB_START..=cmd::CALIB_RESET | cmd::FORGET_INTRINSICS => self.msgs.calib = msg.clone(),
            cmd::PLANE_START..=cmd::PLANE_FORCE | cmd::FORGET_PLANE => self.msgs.plane = msg.clone(),
            cmd::PHOTOS_START..=cmd::PHOTO_FAILED | cmd::PHOTOS_OFF => self.msgs.photos = msg.clone(),
            _ => self.msgs.scan = msg.clone(),
        }
        msg
    }

    fn export_ply(&self, path: &str) -> String {
        if self.cloud.is_empty() {
            return "nothing to export yet".to_string();
        }
        match self.write_scan_ply(Path::new(path)) {
            Ok(n) => {
                if let Err(e) = self.write_frames_sidecar(Path::new(path)) {
                    return format!("exported {n} pts -> {path} (frame ids failed: {e})");
                }
                format!("exported {n} pts -> {path}")
            }
            Err(e) => format!("export failed: {e}"),
        }
    }

    /// The cloud as a Z-up PLY over the board, through a temporary file so a
    /// reader never finds half of one.
    fn write_scan_ply(&self, path: &Path) -> std::io::Result<usize> {
        let tmp = path.with_extension("ply.tmp");
        let mut w = BufWriter::new(fs::File::create(&tmp)?);
        // Z up over the board: OpenCV's board +Z points away from the camera, so
        // in the board frame objects sit at -Z and viewers show them upside down
        let n = self.cloud.write_ply_in(&mut w, &FaceOutFrame::cornered(self.cfg.board.height_mm()))?;
        w.flush()?;
        drop(w);
        fs::rename(&tmp, path)?;
        Ok(n)
    }

    /// Per-point frame ids beside the PLY, for the drift correction pass:
    /// u32 point count, then one u32 frame number per vertex in PLY order.
    fn write_frames_sidecar(&self, path: &Path) -> std::io::Result<()> {
        let n = self.cloud.len();
        let mut buf = Vec::with_capacity(4 + 4 * n);
        buf.extend_from_slice(&(n as u32).to_le_bytes());
        for i in 0..n {
            let f = self.point_frames.get(i).copied().unwrap_or(0);
            buf.extend_from_slice(&f.to_le_bytes());
        }
        let side = PathBuf::from(format!("{}.frames", path.display()));
        fs::write(side, buf)
    }

    /// Write the cloud into the open session (`laser.ply` for orbiter-recon,
    /// `scan.ply` Z up) when it has points the session lacks, so leaving the
    /// screen or a killed process loses nothing. Returns a note for the status line.
    fn checkpoint(&mut self) -> String {
        if self.photos.is_none() || self.cloud.is_empty() {
            return String::new();
        }
        if !self.cloud_unsaved {
            return ", saved".to_string();
        }
        match self.write_session_clouds() {
            Ok(_) => ", saved".to_string(),
            Err(e) => format!(", NOT saved: {e}"),
        }
    }

    fn write_session_clouds(&mut self) -> std::io::Result<usize> {
        let Some(s) = &self.photos else { return Ok(0) };
        let n = s.write_laser(&self.cloud)?.unwrap_or(0);
        self.write_scan_ply(&s.dir().join(SESSION_SCAN_PLY))?;
        self.cloud_unsaved = false;
        Ok(n)
    }

    /// No more photos in the open session; it carries on with the scan alone.
    fn stop_photos_keep_session(&mut self, why: &str) -> String {
        for st in self.stills.drain(..) {
            let _ = fs::remove_file(&st.file);
        }
        self.photos_enabled = false;
        self.trigger.reset();
        self.photo_state = photo_state::NO_PHOTOS;
        self.events |= ev::MESSAGES;
        self.msgs.photos = format!("photos off ({why}) — the scan carries on and is kept");
        self.msgs.photos.clone()
    }

    // ── photo keyframes ──────────────────────────────────────────────────────

    fn open_photos(&mut self, arg: &str) -> String {
        let a: PhotosStartArg = match serde_json::from_str(arg) {
            Ok(a) => a,
            Err(e) => return format!("bad photo session request: {e}"),
        };
        self.close_photos("replaced");
        let vol = self.volume();
        let info = SessionInfo {
            session_id: a.session_id,
            started_utc: a.started_utc,
            camera_id: self.cfg.camera.id.clone(),
            board: self.cfg.board,
            // the cylinder inside the box this app scans in
            volume: Cylinder {
                height_mm: vol.height,
                radius_mm: 0.5 * vol.board_w.min(vol.board_h) + vol.margin,
                floor_mm: FLOOR_MM,
            },
            k: self.k_base,
            calibrated: self.k_file.is_some(),
            k_rms_px: self.k_file.as_ref().and_then(|f| f.rms_px),
            params: serde_json::json!({
                "source": "easyscanner",
                "capture": self.cfg.keyframes,
                "max_interpolation_ms": MAX_INTERP_GAP_NS as f64 * 1e-6,
            }),
        };
        match PhotoSession::open(a.dir, info) {
            Ok(s) => {
                let msg = format!(
                    "photos on -> {}{}{}",
                    s.dir().display(),
                    if a.photos { "" } else { " (scan only, no photos)" },
                    if self.k_file.is_none() { " (camera NOT calibrated)" } else { "" }
                );
                if a.fresh && !self.cloud_unsaved {
                    self.cloud.clear();
                    self.point_frames.clear();
                    self.preview.clear();
                }
                // whatever the cloud holds now is not in this session yet
                self.cloud_unsaved = !self.cloud.is_empty();
                self.photos = Some(s);
                self.photos_enabled = a.photos;
                self.photo_serial = a.serial;
                self.trigger.reset();
                self.stills.clear();
                msg
            }
            Err(e) => format!("photo session failed: {e}"),
        }
    }

    /// Close the photo session: stills still in flight are dropped, the laser
    /// cloud goes into `laser.ply`.
    fn close_photos(&mut self, why: &str) -> String {
        if self.photos.is_none() {
            return "photos are off".to_string();
        }
        // the scan belongs to its session: no session, no scanning
        self.scan_on = false;
        for st in self.stills.drain(..) {
            let _ = fs::remove_file(&st.file);
        }
        self.photo_state = photo_state::OFF;
        self.events |= ev::MESSAGES;
        let laser = if self.cloud.is_empty() {
            "no laser cloud (orbiter-recon needs laser.ply)".to_string()
        } else if !self.cloud_unsaved {
            format!("laser.ply + {SESSION_SCAN_PLY} {} pts", self.cloud.len())
        } else {
            match self.write_session_clouds() {
                Ok(n) => format!("laser.ply + {SESSION_SCAN_PLY} {n} pts"),
                Err(e) => format!("the cloud was NOT written: {e}"),
            }
        };
        let s = self.photos.take().expect("checked above");
        if s.count() == 0 && self.cloud.is_empty() {
            // nothing taken: leave no session behind
            remove_empty_session(&s.dir().join("photos"));
            self.msgs.photos = format!("photos {why}: nothing taken");
            return self.msgs.photos.clone();
        }
        self.msgs.photos = format!("photos {why}: {} in {} | {laser}", s.count(), s.dir().display());
        self.msgs.photos.clone()
    }

    fn add_still(&mut self, arg: &str) -> String {
        let a: PhotoAddArg = match serde_json::from_str(arg) {
            Ok(a) => a,
            Err(e) => return format!("bad photo: {e}"),
        };
        if self.photos.as_ref().is_none_or(|s| !a.file.starts_with(s.dir())) {
            // came back after its session closed
            let _ = fs::remove_file(&a.file);
            if let Some(photos) = a.file.parent() {
                remove_empty_session(photos);
            }
            return "photo dropped: photos are off".to_string();
        }
        self.stills.push(Still { file: a.file, ts_ns: a.ts, wh: (a.w, a.h), frames_waited: 0 });
        self.resolve_stills();
        self.msgs.photos.clone()
    }

    /// A still failed: back off, and give up on the session when it keeps failing.
    fn photo_failed(&mut self, msg: &str) {
        self.trigger.failed(self.track.newest_ts().unwrap_or(0));
        self.msgs.photos = msg.to_string();
        self.events |= ev::MESSAGES;
        if self.trigger.failures() >= MAX_PHOTO_FAILURES {
            // the cause repeats (disk, camera): stop the photos, never the scan
            self.stop_photos_keep_session(&format!("{MAX_PHOTO_FAILURES} failed in a row: {msg}"));
        }
    }

    /// Whether a laser stripe was in view near `ts_ns`. Unknown — the
    /// detector is off — counts as yes: a stripe left in a "clean" photo is
    /// worse than an unneeded inpaint.
    fn laser_near(&self, ts_ns: i64) -> bool {
        !self.cfg.laser.enabled || self.stripe_seen.iter().any(|&t| (t - ts_ns).abs() <= LASER_NEAR_NS)
    }

    /// Pose every waiting still whose exposure the track now covers.
    fn resolve_stills(&mut self) {
        let mut i = 0;
        while i < self.stills.len() && self.photos.is_some() {
            let st = &mut self.stills[i];
            let res = match self.track.at(st.ts_ns, MAX_INTERP_GAP_NS) {
                Err(Miss::NotYet) if st.frames_waited < STILL_PATIENCE_FRAMES => {
                    st.frames_waited += 1;
                    i += 1;
                    continue;
                }
                Err(Miss::NotYet) => Err("its frame never arrived".to_string()),
                Err(Miss::NoPose) => Err("no board pose at its exposure".to_string()),
                Err(Miss::Evicted) => Err("it came back too late to be posed".to_string()),
                Ok(at) => Ok(at),
            };
            let st = self.stills.swap_remove(i);
            let laser_on = self.laser_near(st.ts_ns);
            let session = self.photos.as_mut().expect("checked by the loop");
            match res.and_then(|at| session.add(&st.file, st.ts_ns, st.wh, &at, laser_on).map(|name| (name, at))) {
                Ok((name, at)) => {
                    self.trigger.taken(st.ts_ns, at.pose);
                    let how = if at.interpolated_gap_ns == 0 {
                        String::new()
                    } else {
                        format!(", pose interpolated over {:.0} ms", at.interpolated_gap_ns as f64 * 1e-6)
                    };
                    self.msgs.photos = format!("photo {} {name} {}x{}{how}", session.count(), st.wh.0, st.wh.1);
                    self.events |= ev::MESSAGES;
                }
                Err(e) => {
                    let _ = fs::remove_file(&st.file);
                    self.photo_failed(&format!("photo dropped: {e}"));
                }
            }
        }
    }

    fn photos_step(&mut self) {
        if self.photos.is_none() {
            self.photo_state = photo_state::OFF;
            return;
        }
        if !self.photos_enabled {
            self.photo_state = photo_state::NO_PHOTOS;
            return;
        }
        self.resolve_stills();
        self.photo_state = match self.trigger.check(&self.cfg.keyframes, &self.track) {
            Due::Now => {
                self.events |= ev::PHOTO_DUE;
                photo_state::IN_FLIGHT
            }
            Due::No if self.trigger.pending() => photo_state::IN_FLIGHT,
            Due::No => photo_state::WAITING,
            Due::Hold(Hold::NoBoard) => photo_state::HOLD_NO_BOARD,
            Due::Hold(Hold::PoseRms) => photo_state::HOLD_POSE_RMS,
            Due::Hold(Hold::FewCorners) => photo_state::HOLD_FEW_CORNERS,
            Due::Hold(Hold::Settling) => photo_state::HOLD_SETTLING,
            Due::Hold(Hold::Moving) => photo_state::HOLD_MOVING,
        };
    }

    /// Status lines for the HUD, as JSON.
    pub fn messages(&self) -> String {
        let intr = match &self.k_file {
            Some(f) => format!(
                "calibrated {}x{}{}",
                f.width,
                f.height,
                f.rms_px.map(|r| format!(" rms {r:.2}px")).unwrap_or_default()
            ),
            None if self.cfg.camera.guess.is_some() => "camera estimate (uncalibrated)".to_string(),
            None => "DEFAULT guess (uncalibrated)".to_string(),
        };
        let focus_note = match (&self.k_file, self.cfg.camera.focus_diopters) {
            (Some(f), cur) if f.focus_diopters.is_some() && (f.focus_diopters.unwrap() - cur.unwrap_or(f64::NAN)).abs() > 0.05 => {
                " — focus differs from calibration".to_string()
            }
            (Some(_), None) => " — autofocus on, K may drift".to_string(),
            _ => String::new(),
        };
        serde_json::json!({
            "calib": self.msgs.calib,
            "plane": self.msgs.plane,
            "scan": self.msgs.scan,
            "photos": self.msgs.photos,
            "intrinsics": format!("{intr}{focus_note}"),
            "k_focus": self.k_file.as_ref().and_then(|f| f.focus_diopters),
            "plane_file": match (self.plane, self.usable_plane()) {
                (None, _) => "no laser plane",
                (Some(_), None) => "laser plane fitted with another camera calibration",
                (Some(_), Some(_)) => "laser plane loaded",
            },
        })
        .to_string()
    }
}

/// Remove a photo session's `photos/` and the session directory itself when
/// nothing but unposed stills (`.pending_*`) is left in them. Directories with
/// anything else in them stay.
fn remove_empty_session(photos: &Path) {
    if let Ok(entries) = fs::read_dir(photos) {
        for e in entries.flatten() {
            if e.file_name().to_string_lossy().starts_with(".pending_") {
                let _ = fs::remove_file(e.path());
            }
        }
    }
    if fs::remove_dir(photos).is_ok()
        && let Some(dir) = photos.parent()
    {
        let _ = fs::remove_dir(dir);
    }
}

/// the desktop prototype's fallback when nothing better is known: f = 1500 at 1920x1080.
fn default_guess() -> Intrinsics {
    Intrinsics { fx: 1500.0, fy: 1500.0, cx: 960.0, cy: 540.0, dist: [0.0; 5], width: 1920.0, height: 1080.0 }
}
