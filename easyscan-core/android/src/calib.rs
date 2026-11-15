//! Live intrinsics self-calibration from the stream — port of the desktop prototype's
//! `calib_live.py`: collect diverse ChArUco views, then one
//! `cv::calibrateCamera` (USE_INTRINSIC_GUESS | ZERO_TANGENT_DIST | FIX_K3)
//! behind the same RMS and plausibility gates. The solve runs on its own
//! thread so the viewfinder keeps going.

use std::thread::JoinHandle;

use opencv::core::{self, Mat, Point2f, Point3f, Size, TermCriteria, Vector};
use opencv::prelude::*;
use opencv::calib3d;
use scan_algo::config::{BoardSpec, intrinsics_plausible};
use scan_algo::geom::Intrinsics;

use crate::board::make_board;

/// Reprojection-RMS ceiling (px) at 1920 px, scaled up for larger frames.
const MAX_RMS_PX: f64 = 2.5;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CalibState {
    Idle = 0,
    Collecting = 1,
    Solving = 2,
    Saved = 3,
    Rejected = 4,
}

pub enum Outcome {
    Solved { k: Intrinsics, rms: f64 },
    Rejected { rms: f64, reason: String },
}

struct View {
    corners: Vec<Point2f>,
    ids: Vec<i32>,
}

pub struct LiveCalibrator {
    pub target: usize,
    min_corners: usize,
    views: Vec<View>,
    wh: Option<(u32, u32)>,
    solved_at: usize,
    job: Option<JoinHandle<Outcome>>,
    pub last_rms: Option<f64>,
}

impl LiveCalibrator {
    pub fn new(target: usize) -> Self {
        LiveCalibrator { target, min_corners: 8, views: Vec::new(), wh: None, solved_at: 0, job: None, last_rms: None }
    }

    pub fn count(&self) -> usize {
        self.views.len()
    }

    pub fn solving(&self) -> bool {
        self.job.is_some()
    }

    /// Store one detected view. Rejects too few corners and near-duplicates
    /// (same corner count, centroid moved less than ~8 px at 1920 px), so
    /// holding the board still does not flood the set. Views must share one
    /// resolution; a different one starts the set over.
    pub fn capture(&mut self, corners: &Vector<Point2f>, ids: &Vector<i32>, wh: (u32, u32)) -> bool {
        if self.job.is_some() || corners.len() < self.min_corners {
            return false;
        }
        if self.wh != Some(wh) {
            self.views.clear();
            self.solved_at = 0;
            self.wh = Some(wh);
        }
        let c: Vec<Point2f> = corners.to_vec();
        let centroid = mean(&c);
        let tol = 8.0 * (wh.0.max(wh.1) as f32 / 1920.0).max(0.25);
        let dup = self.views.iter().any(|v| {
            let m = mean(&v.corners);
            v.corners.len() == c.len() && ((m.0 - centroid.0).powi(2) + (m.1 - centroid.1).powi(2)).sqrt() < tol
        });
        if dup {
            return false;
        }
        self.views.push(View { corners: c, ids: ids.to_vec() });
        true
    }

    pub fn reset(&mut self) {
        self.views.clear();
        self.solved_at = 0;
        self.last_rms = None;
    }

    /// Start a background solve once the set reaches the target, and again
    /// every `target/12` new views after a rejected attempt.
    pub fn maybe_solve(&mut self, spec: BoardSpec, seed: Intrinsics) -> bool {
        let step = (self.target / 12).max(1);
        let Some(wh) = self.wh else { return false };
        if self.job.is_some() || self.views.len() < self.target || self.views.len() - self.solved_at < step {
            return false;
        }
        self.solved_at = self.views.len();
        let views: Vec<(Vec<Point2f>, Vec<i32>)> = self.views.iter().map(|v| (v.corners.clone(), v.ids.clone())).collect();
        let (min_corners, min_views) = (self.min_corners, self.target);
        self.job = Some(std::thread::spawn(move || match solve(spec, &views, wh, seed, min_corners, min_views) {
            Ok(o) => o,
            Err(e) => Outcome::Rejected { rms: f64::INFINITY, reason: format!("calibrateCamera failed: {e}") },
        }));
        true
    }

    /// The finished background solve, if any.
    pub fn poll(&mut self) -> Option<Outcome> {
        if !self.job.as_ref()?.is_finished() {
            return None;
        }
        let out = self.job.take()?.join().unwrap_or(Outcome::Rejected { rms: f64::INFINITY, reason: "solver panicked".into() });
        match &out {
            Outcome::Solved { rms, .. } | Outcome::Rejected { rms, .. } => self.last_rms = rms.is_finite().then_some(*rms),
        }
        Some(out)
    }
}

fn mean(c: &[Point2f]) -> (f32, f32) {
    let n = c.len().max(1) as f32;
    (c.iter().map(|p| p.x).sum::<f32>() / n, c.iter().map(|p| p.y).sum::<f32>() / n)
}

fn solve(
    spec: BoardSpec,
    views: &[(Vec<Point2f>, Vec<i32>)],
    (w, h): (u32, u32),
    seed: Intrinsics,
    min_corners: usize,
    min_views: usize,
) -> opencv::Result<Outcome> {
    let board = make_board(&spec)?;
    let mut obj_all: Vector<Vector<Point3f>> = Vector::new();
    let mut img_all: Vector<Vector<Point2f>> = Vector::new();
    for (corners, ids) in views {
        let (mut obj, mut img) = (Vector::<Point3f>::new(), Vector::<Point2f>::new());
        board.match_image_points(&Vector::from_slice(corners), &Vector::from_slice(ids), &mut obj, &mut img)?;
        if obj.len() >= min_corners {
            obj_all.push(obj);
            img_all.push(img);
        }
    }
    if obj_all.len() < min_views {
        return Ok(Outcome::Rejected {
            rms: f64::INFINITY,
            reason: format!("only {} usable views of {min_views}", obj_all.len()),
        });
    }

    // Seed with the camera's own estimate when it is sane, else the classic
    // centred guess with f ≈ the longer side.
    let seed = seed.for_frame(w, h);
    let seed = if intrinsics_plausible(&seed) {
        seed
    } else {
        let f = w.max(h) as f64;
        Intrinsics { fx: f, fy: f, cx: w as f64 / 2.0, cy: h as f64 / 2.0, dist: [0.0; 5], width: w as f64, height: h as f64 }
    };
    let mut k = Mat::from_slice_2d(&[[seed.fx, 0.0, seed.cx], [0.0, seed.fy, seed.cy], [0.0, 0.0, 1.0]])?;
    let mut d = Mat::from_slice(&[0.0f64; 5])?.try_clone()?;
    let (mut rvecs, mut tvecs) = (Vector::<Mat>::new(), Vector::<Mat>::new());
    // never FIX_ASPECT_RATIO / FIX_PRINCIPAL_POINT (same as the server)
    let flags = calib3d::CALIB_USE_INTRINSIC_GUESS | calib3d::CALIB_ZERO_TANGENT_DIST | calib3d::CALIB_FIX_K3;
    let criteria = TermCriteria::new(core::TermCriteria_COUNT + core::TermCriteria_EPS, 30, f64::EPSILON)?;
    let rms = calib3d::calibrate_camera(
        &obj_all,
        &img_all,
        Size::new(w as i32, h as i32),
        &mut k,
        &mut d,
        &mut rvecs,
        &mut tvecs,
        flags,
        criteria,
    )?;

    let gate = MAX_RMS_PX * (w as f64 / 1920.0).max(1.0);
    if !rms.is_finite() || rms > gate {
        return Ok(Outcome::Rejected { rms, reason: format!("rms {rms:.2} px > {gate:.2}") });
    }
    let mut dist = [0.0; 5];
    for (i, v) in dist.iter_mut().enumerate() {
        *v = *d.at::<f64>(i as i32)?;
    }
    let out = Intrinsics {
        fx: *k.at_2d::<f64>(0, 0)?,
        fy: *k.at_2d::<f64>(1, 1)?,
        cx: *k.at_2d::<f64>(0, 2)?,
        cy: *k.at_2d::<f64>(1, 2)?,
        dist,
        width: w as f64,
        height: h as f64,
    };
    if !intrinsics_plausible(&out) {
        return Ok(Outcome::Rejected {
            rms,
            reason: format!(
                "K implausible (fx {:.0} fy {:.0} cx {:.0} cy {:.0} k1 {:.3}) — more varied angles",
                out.fx, out.fy, out.cx, out.cy, out.dist[0]
            ),
        });
    }
    Ok(Outcome::Solved { k: out, rms })
}
