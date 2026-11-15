//! End-to-end tests of the engine with the real OpenCV: synthetic camera
//! frames are rendered from a known board pose (and a known laser plane), fed
//! through `Engine::process` as YUV 4:2:0, and the recovered pose,
//! intrinsics and laser plane are checked against the truth.
//!
//! They need `libopencv_java4.so`, so they run on an Android device or
//! emulator (see README → Toolchain), not on the host.

use std::path::PathBuf;
use std::sync::atomic::{AtomicI64, Ordering};
use std::time::{Duration, Instant};

use opencv::core::{self, Mat, Scalar, Size};
use opencv::prelude::*;
use opencv::imgproc;
use scan_algo::config::{self, BoardSpec};
use scan_algo::geom::{Intrinsics, M3, Pose, V3, dot, mat_mul, mat_vec, normalize, rodrigues, sub};
use scan_algo::laser::YuvFrame;

use crate::board::make_board;
use crate::engine::{Engine, cmd};
use crate::frame_out::{ev, h, photo_state};

const W: usize = 1280;
const H: usize = 960;
/// Board image resolution, px per mm.
const BOARD_PX: i32 = 1600;

fn truth_k() -> Intrinsics {
    Intrinsics { fx: 1000.0, fy: 1000.0, cx: 639.5, cy: 479.5, dist: [0.0; 5], width: W as f64, height: H as f64 }
}

/// The laser plane of the desktop prototype's saved calibration.
fn truth_plane() -> (V3, f64) {
    (normalize([0.8635, -0.2199, -0.4538]).unwrap(), -165.636)
}

fn spec() -> BoardSpec {
    BoardSpec::default()
}

/// Board→camera pose looking at the printed face of the board (its +Z points
/// away from the camera, as OpenCV defines it) at the board centre from `dist`
/// mm, tilted by (`ax`, `ay`) rad about the board axes and spun by `spin`
/// about the view axis.
fn pose(dist: f64, ax: f64, ay: f64, spin: f64, off: [f64; 2]) -> Pose {
    let c = [spec().width_mm() / 2.0, spec().height_mm() / 2.0, 0.0];
    let r: M3 = mat_mul(
        &rodrigues([0.0, 0.0, spin]),
        &mat_mul(&rodrigues([ax, 0.0, 0.0]), &rodrigues([0.0, ay, 0.0])),
    );
    let t = sub([off[0], off[1], dist], mat_vec(&r, c));
    Pose { r, t }
}

fn inv3(m: &M3) -> M3 {
    let [[a, b, c], [d, e, f], [g, h, i]] = *m;
    let det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
    [
        [(e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det],
        [(f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det],
        [(d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det],
    ]
}

struct Renderer {
    board_img: Mat,
}

impl Renderer {
    fn new() -> Renderer {
        let board = make_board(&spec()).unwrap();
        let mut board_img = Mat::default();
        board.generate_image(Size::new(BOARD_PX, BOARD_PX), &mut board_img, 0, 1).unwrap();
        Renderer { board_img }
    }

    /// RGB frame of the board at `pose`, with the laser stripe where the
    /// plane meets the board.
    fn render(&self, k: &Intrinsics, pose: &Pose, laser: Option<(V3, f64)>) -> Yuv {
        let (w, hh) = (k.width as usize, k.height as usize);
        let s = BOARD_PX as f64 / spec().width_mm();
        let kk: M3 = [[k.fx, 0.0, k.cx], [0.0, k.fy, k.cy], [0.0, 0.0, 1.0]];
        let rt: M3 = [
            [pose.r[0][0], pose.r[0][1], pose.t[0]],
            [pose.r[1][0], pose.r[1][1], pose.t[1]],
            [pose.r[2][0], pose.r[2][1], pose.t[2]],
        ];
        let hb = mat_mul(&kk, &rt); // board mm → frame px
        let m = mat_mul(&hb, &[[1.0 / s, 0.0, 0.0], [0.0, 1.0 / s, 0.0], [0.0, 0.0, 1.0]]);
        let m_mat = Mat::from_slice_2d(&m).unwrap();
        let mut warped = Mat::default();
        imgproc::warp_perspective(
            &self.board_img,
            &mut warped,
            &m_mat,
            Size::new(w as i32, hh as i32),
            imgproc::INTER_LINEAR,
            core::BORDER_CONSTANT,
            Scalar::all(110.0),
        )
        .unwrap();
        let gray = warped.data_typed::<u8>().unwrap();
        let hinv = inv3(&hb);
        // laser ∩ board: n·(R·[x y 0] + t) = d  →  a·x + b·y = c
        let line = laser.map(|(n, d)| {
            let a = dot(n, [pose.r[0][0], pose.r[1][0], pose.r[2][0]]);
            let b = dot(n, [pose.r[0][1], pose.r[1][1], pose.r[2][1]]);
            (a, b, d - dot(n, pose.t), (a * a + b * b).sqrt())
        });
        let mut rgb = vec![0u8; w * hh * 3];
        for y in 0..hh {
            for x in 0..w {
                let g = gray[y * w + x] as f64;
                let mut px = [g, g, g];
                if let Some((a, b, c, ab)) = line {
                    let q = mat_vec(&hinv, [x as f64, y as f64, 1.0]);
                    let (bx, by) = (q[0] / q[2], q[1] / q[2]);
                    let on_board = (0.0..=spec().width_mm()).contains(&bx) && (0.0..=spec().height_mm()).contains(&by);
                    if on_board {
                        let dmm = (a * bx + b * by - c).abs() / ab;
                        let kk = (-0.5 * (dmm / 0.45).powi(2)).exp();
                        let laser = [255.0, 40.0, 70.0];
                        for i in 0..3 {
                            px[i] = px[i] * (1.0 - 0.6 * kk) + laser[i] * kk;
                        }
                    }
                }
                for i in 0..3 {
                    rgb[(y * w + x) * 3 + i] = px[i].round().clamp(0.0, 255.0) as u8;
                }
            }
        }
        Yuv::from_rgb(&rgb, w, hh)
    }
}

/// Camera-ISP-like YUV 4:2:0 (JFIF matrix, 2x2-averaged chroma).
struct Yuv {
    w: usize,
    h: usize,
    y: Vec<u8>,
    u: Vec<u8>,
    v: Vec<u8>,
}

impl Yuv {
    fn from_rgb(rgb: &[u8], w: usize, h: usize) -> Yuv {
        let (cw, ch) = (w / 2, h / 2);
        let mut y = vec![0u8; w * h];
        let mut uf = vec![0f64; cw * ch];
        let mut vf = vec![0f64; cw * ch];
        for yy in 0..h {
            for xx in 0..w {
                let i = (yy * w + xx) * 3;
                let (r, g, b) = (rgb[i] as f64, rgb[i + 1] as f64, rgb[i + 2] as f64);
                y[yy * w + xx] = (0.299 * r + 0.587 * g + 0.114 * b).round().clamp(0.0, 255.0) as u8;
                let ci = (yy / 2) * cw + xx / 2;
                uf[ci] += 0.25 * (128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b);
                vf[ci] += 0.25 * (128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b);
            }
        }
        let q = |c: &f64| c.round().clamp(0.0, 255.0) as u8;
        Yuv { w, h, y, u: uf.iter().map(q).collect(), v: vf.iter().map(q).collect() }
    }

    fn frame(&self) -> YuvFrame<'_> {
        YuvFrame {
            width: self.w,
            height: self.h,
            y: &self.y,
            y_stride: self.w,
            u: &self.u,
            v: &self.v,
            uv_stride: self.w / 2,
            uv_pixel_stride: 1,
        }
    }
}

/// Sensor timestamps of the frames the tests feed: 30 fps, never repeating.
static CLOCK_NS: AtomicI64 = AtomicI64::new(1_000_000_000);
const FRAME_NS: i64 = 33_333_333;

/// One frame through the engine, as the JNI bridge does it.
fn step(e: &mut Engine, yuv: &Yuv) -> Vec<f32> {
    step_at(e, yuv, CLOCK_NS.fetch_add(FRAME_NS, Ordering::Relaxed))
}

fn step_at(e: &mut Engine, yuv: &Yuv, ts_ns: i64) -> Vec<f32> {
    let out = e.process(&yuv.frame(), ts_ns).unwrap().to_vec();
    e.clear_events();
    out
}

fn engine(name: &str, guess: Intrinsics) -> (Engine, PathBuf) {
    let dir = PathBuf::from(format!("./e2e_{name}"));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(&dir).unwrap();
    let mut e = Engine::new(dir.clone()).unwrap();
    let cfg = format!(
        r#"{{"camera":{{"id":"test","guess":{{"fx":{},"fy":{},"cx":{},"cy":{},"dist":[0,0,0,0,0],"width":{},"height":{}}}}}}}"#,
        guess.fx, guess.fy, guess.cx, guess.cy, guess.width, guess.height
    );
    e.configure(&cfg).unwrap();
    (e, dir)
}

/// A spread of viewpoints: distances, tilts about both board axes, spins.
fn viewpoints(n: usize) -> Vec<Pose> {
    (0..n)
        .map(|i| {
            let f = i as f64;
            pose(
                480.0 + 90.0 * (f * 0.7).sin(),
                0.45 * (f * 1.3).sin(),
                0.45 * (f * 0.9 + 1.0).cos(),
                0.6 * (f * 0.5).sin(),
                [25.0 * (f * 1.7).cos(), 20.0 * (f * 2.3).sin()],
            )
        })
        .collect()
}

#[test]
fn pose_matches_the_rendered_board() {
    let k = truth_k();
    let (mut e, _) = engine("pose", k);
    let r = Renderer::new();
    for (i, p) in viewpoints(6).iter().enumerate() {
        let yuv = r.render(&k, p, None);
        let out = step(&mut e, &yuv);
        if out[h::DETECTED] != 1.0 {
            let _ = std::fs::write(format!("./e2e_pose_view{i}.gray"), &yuv.y);
        }
        assert_eq!(out[h::DETECTED], 1.0, "view {i}: board not found ({} corners)", out[h::N_CORNERS]);
        let truth = p.camera_position();
        let got = [out[h::CAM_X] as f64, out[h::CAM_Y] as f64, out[h::CAM_Z] as f64];
        let err = scan_algo::geom::norm(sub(got, truth));
        eprintln!(
            "view {i}: {} corners, cam err {err:.2} mm, rms {:.3} px, margin {:.1}, {:.1} ms (board {:.1})",
            out[h::N_CORNERS], out[h::REPROJ_RMS], out[h::POSE_MARGIN], out[h::T_TOTAL_MS], out[h::T_BOARD_MS]
        );
        assert!(err < 1.5, "view {i}: camera position off by {err:.2} mm ({got:?} vs {truth:?})");
        assert!(out[h::REPROJ_RMS] < 0.5);
    }
}

#[test]
fn laser_plane_calibration_recovers_the_plane_and_scan_classifies() {
    let k = truth_k();
    let (n_true, d_true) = truth_plane();
    let (mut e, dir) = engine("plane", k);
    let r = Renderer::new();
    e.command(cmd::PLANE_START, "");
    let mut saved = false;
    for (i, p) in viewpoints(40).iter().enumerate() {
        let yuv = r.render(&k, p, Some((n_true, d_true)));
        let out = step(&mut e, &yuv);
        eprintln!(
            "view {i}: laser {} pts, lines {}, spread {:.2}",
            out[h::LASER_POINTS], out[h::PLANE_LINES], out[h::PLANE_SPREAD]
        );
        if out[h::EVENTS] as u32 & ev::PLANE_SAVED != 0 {
            saved = true;
            break;
        }
    }
    assert!(saved, "plane never saved: {}", e.messages());
    let config::SavedPlane { n, d, k: plane_k } = config::load_plane(&dir, "test").unwrap();
    assert!(plane_k.is_some(), "the plane is saved with the camera model it was fitted with");
    let ang = dot(n, n_true).abs().min(1.0).acos().to_degrees();
    eprintln!("plane: angle err {ang:.3} deg, d {d:.2} vs {d_true:.2}");
    assert!(ang < 0.5, "normal off by {ang} deg");
    assert!((d - d_true).abs() < 1.5, "d {d} vs {d_true}");

    // scanning the board itself: every laser point is ON the board, so none
    // may count as the object
    e.command(cmd::SCAN_START, "");
    let p = pose(500.0, 0.2, -0.3, 0.1, [0.0, 0.0]);
    let yuv = r.render(&k, &p, Some((n_true, d_true)));
    let out = step(&mut e, &yuv);
    assert!(out[h::LASER_POINTS] > 50.0);
    assert_eq!(out[h::IN_VOLUME], 0.0, "board points classified as object");
}

#[test]
fn live_calibration_converges_from_a_wrong_guess() {
    let k = truth_k();
    let guess = Intrinsics { fx: k.fx * 1.08, fy: k.fy * 1.08, ..k };
    let (mut e, dir) = engine("calib", guess);
    e.command(cmd::CALIB_START, "");
    let r = Renderer::new();
    let views = viewpoints(80);
    let start = Instant::now();
    let mut saved = false;
    'outer: for _ in 0..3 {
        for p in &views {
            let yuv = r.render(&k, p, None);
            let out = step(&mut e, &yuv);
            if out[h::EVENTS] as u32 & ev::INTRINSICS_SAVED != 0 {
                saved = true;
                break 'outer;
            }
            if e.messages().contains("solving") {
                std::thread::sleep(Duration::from_millis(50));
            }
        }
    }
    // the solve runs on its own thread: wait for it
    while !saved && start.elapsed() < Duration::from_secs(120) {
        let yuv = r.render(&k, &views[0], None);
        let out = step(&mut e, &yuv);
        saved = out[h::EVENTS] as u32 & ev::INTRINSICS_SAVED != 0;
        std::thread::sleep(Duration::from_millis(100));
    }
    assert!(saved, "calibration never saved: {}", e.messages());
    let f = config::load_intrinsics(&dir, "test").unwrap();
    eprintln!("calibrated fx {:.2} fy {:.2} cx {:.2} cy {:.2} rms {:?} in {:.1}s", f.fx, f.fy, f.cx, f.cy, f.rms_px, start.elapsed().as_secs_f64());
    assert!((f.fx / k.fx - 1.0).abs() < 0.005, "fx {}", f.fx);
    assert!((f.fy / k.fy - 1.0).abs() < 0.005, "fy {}", f.fy);
    assert!((f.cx - k.cx).abs() < 3.0 && (f.cy - k.cy).abs() < 3.0);
}

#[test]
fn tracking_survives_a_resolution_switch() {
    // board tracked on the right of a 1280x960 frame, then the governor drops
    // to 640x480: the old search window must not be applied to the new frame
    let k = truth_k();
    let (mut e, _) = engine("switch", k);
    let r = Renderer::new();
    let p = pose(520.0, 0.2, 0.25, 0.1, [170.0, 60.0]);
    for _ in 0..3 {
        let out = step(&mut e, &r.render(&k, &p, None));
        assert_eq!(out[h::DETECTED], 1.0);
    }
    let small = k.for_frame(640, 480);
    for i in 0..3 {
        let out = step(&mut e, &r.render(&small, &p, None));
        assert_eq!(out[h::FRAME_W], 640.0);
        assert_eq!(out[h::DETECTED], 1.0, "frame {i} after the switch");
    }
}

#[test]
fn photo_keyframes_carry_the_pose_of_their_exposure() {
    use scan_algo::geom::{FaceOutFrame, mat_from_quat, norm, rotation_distance};
    let k = truth_k();
    let (mut e, dir) = engine("photos", k);
    let r = Renderer::new();
    let session = dir.join("session");
    let arg = serde_json::json!({"dir": session, "session_id": "t", "started_utc": "2026-10-19T00:00:00Z"});
    let msg = e.command(cmd::PHOTOS_START, &arg.to_string());
    assert!(msg.starts_with("photos on"), "{msg}");

    // a steady camera: the first keyframe comes once its motion is measured
    let p = pose(480.0, 0.3, -0.25, 0.2, [10.0, -15.0]);
    let yuv = r.render(&k, &p, None);
    let mut t = 5_000_000_000;
    let due = (0..30)
        .find_map(|_| {
            t += FRAME_NS;
            let out = step_at(&mut e, &yuv, t);
            (out[h::EVENTS] as u32 & ev::PHOTO_DUE != 0).then_some(t)
        })
        .expect("no keyframe requested");
    assert!(due - 5_000_000_000 >= 3 * FRAME_NS, "fired before the motion was measured");

    // the app's still: requested now, its own analysis frame processed next
    let still = |tag: &str| {
        let f = session.join("photos").join(format!(".pending_{tag}.jpg"));
        std::fs::write(&f, b"\xff\xd8").unwrap();
        f
    };
    let a = still("a");
    let ts_a = due + FRAME_NS;
    e.command(cmd::PHOTO_ADD, &serde_json::json!({"file": a, "ts": ts_a, "w": 2560, "h": 1920}).to_string());
    assert!(a.exists(), "posed before its frame was processed");
    let out = step_at(&mut e, &yuv, ts_a);
    assert!(!a.exists() && session.join("photos/left_0001.jpg").exists(), "{}", e.messages());
    assert_eq!(out[h::PHOTO_COUNT], 1.0);

    // a still whose frame the processing thread skipped: posed between neighbours
    let b = still("b");
    let ts_b = ts_a + 2 * FRAME_NS;
    step_at(&mut e, &yuv, ts_b + FRAME_NS);
    e.command(cmd::PHOTO_ADD, &serde_json::json!({"file": b, "ts": ts_b - FRAME_NS / 2, "w": 2560, "h": 1920}).to_string());
    assert!(session.join("photos/left_0002.jpg").exists(), "{}", e.messages());

    let msg = e.command(cmd::PHOTOS_STOP, "");
    assert!(msg.contains("2 in") && msg.contains("no laser cloud"), "{msg}");

    let rows: Vec<serde_json::Value> = std::fs::read_to_string(session.join("photos.jsonl"))
        .unwrap()
        .lines()
        .map(|l| serde_json::from_str(l).unwrap())
        .collect();
    assert_eq!(rows.len(), 2);
    assert_eq!(rows[0]["pose_source"], "easyscanner:frame");
    assert_eq!(rows[1]["pose_source"], "easyscanner:interpolated");
    let frame = FaceOutFrame::centred(spec().width_mm(), spec().height_mm());
    let truth = frame.pose(&p);
    for row in &rows {
        let q: Vec<f64> = row["pose"]["q_wxyz"].as_array().unwrap().iter().map(|v| v.as_f64().unwrap()).collect();
        let t: Vec<f64> = row["pose"]["t_mm"].as_array().unwrap().iter().map(|v| v.as_f64().unwrap()).collect();
        let got = Pose { r: mat_from_quat([q[0], q[1], q[2], q[3]]), t: [t[0], t[1], t[2]] };
        let dc = norm(sub(got.camera_position(), truth.camera_position()));
        let da = rotation_distance(&got.r, &truth.r).to_degrees();
        eprintln!("{}: camera {dc:.2} mm, {da:.3} deg off", row["file"]);
        assert!(dc < 1.5 && da < 0.3, "{row}");
    }
    let s: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(session.join("session.json")).unwrap()).unwrap();
    assert_eq!(s["eyes"]["left"]["wh"], serde_json::json!([2560, 1920]));
    assert!((s["eyes"]["left"]["intrinsics"]["fx"].as_f64().unwrap() - 2000.0).abs() < 1e-6);
}

#[test]
fn failing_photos_stop_the_photos_not_the_session() {
    let k = truth_k();
    let (mut e, dir) = engine("photo_fail", k);
    // a directory that already holds a session is never written into
    let taken = dir.join("session");
    std::fs::create_dir_all(&taken).unwrap();
    std::fs::write(taken.join("session.json"), b"{}").unwrap();
    let arg = serde_json::json!({"dir": taken, "session_id": "f", "started_utc": "x", "serial": 7});
    assert!(e.command(cmd::PHOTOS_START, &arg.to_string()).starts_with("photo session failed"));
    assert_eq!(std::fs::read(taken.join("session.json")).unwrap(), b"{}");
    let arg2 = serde_json::json!({"dir": dir.join("session2"), "session_id": "f2", "started_utc": "x", "serial": 8});
    assert!(e.command(cmd::PHOTOS_START, &arg2.to_string()).starts_with("photos on"));
    let r = Renderer::new();
    let out = step(&mut e, &r.render(&k, &pose(480.0, 0.3, -0.25, 0.2, [0.0, 0.0]), None));
    assert_eq!(out[h::PHOTO_SERIAL], 8.0);
    assert_ne!(out[h::PHOTO_STATE] as f64, photo_state::OFF);
    // an in-flight still lands as a pending file, then the camera keeps failing
    std::fs::write(dir.join("session2/photos/.pending_1.jpg"), b"\xff\xd8").unwrap();
    for i in 0..5 {
        let msg = e.command(cmd::PHOTO_FAILED, "still capture failed (reason 0)");
        assert_eq!(msg.contains("photos off"), i == 4, "{i}: {msg}");
    }
    // the session carries on without photos, so a scan in it is still kept
    let out = step(&mut e, &r.render(&k, &pose(480.0, 0.3, -0.25, 0.2, [0.0, 0.0]), None));
    assert_eq!(out[h::PHOTO_STATE] as f64, photo_state::NO_PHOTOS);
    assert!(dir.join("session2").exists());
    // closing it with nothing captured removes it, the unposed pending still with it
    e.command(cmd::PHOTOS_STOP, "");
    let out = step(&mut e, &r.render(&k, &pose(480.0, 0.3, -0.25, 0.2, [0.0, 0.0]), None));
    assert_eq!(out[h::PHOTO_STATE] as f64, photo_state::OFF);
    assert_eq!(out[h::PHOTO_SERIAL], 8.0, "the app tells its own session's close by the serial");
    assert!(!dir.join("session2").exists(), "a session that caught nothing is removed");
}

#[test]
fn opencv_exceptions_become_errors() {
    // the C++ wrapper must share libopencv_java4's C++ runtime, or a
    // cv::Exception would terminate the process instead of returning Err
    let m = Mat::new_rows_cols_with_default(10, 10, core::CV_8UC1, Scalar::all(0.0)).unwrap();
    assert!(Mat::roi(&m, core::Rect::new(5, 5, 20, 20)).is_err());
}
