//! ChArUco board detection and the camera-relative-to-board pose — the same
//! math as the desktop prototype's `detect.py` (CharucoDetector → matchImagePoints →
//! solvePnPGeneric/IPPE with temporal flip disambiguation), plus a tracking
//! ROI so a found board is searched for near where it was.

use opencv::core::{self, Mat, Point2f, Point3f, Rect, Vector};
use opencv::prelude::*;
use opencv::{calib3d, objdetect};
use scan_algo::config::BoardSpec;
use scan_algo::geom::{Intrinsics, M3, Pose, V3, norm, rodrigues, rotation_distance};

pub fn make_board(spec: &BoardSpec) -> opencv::Result<objdetect::CharucoBoard> {
    let dict = objdetect::get_predefined_dictionary_i32(spec.dict)?;
    objdetect::CharucoBoard::new_def(core::Size::new(spec.squares_x, spec.squares_y), spec.square_mm, spec.marker_mm, &dict)
}

pub struct PoseResult {
    pub pose: Pose,
    pub reproj_rms: f64,
    /// Runner-up IPPE branch's error / the chosen one's: ≫1 unambiguous,
    /// ≈1 near-frontal and genuinely flippable.
    pub margin: Option<f64>,
}

pub struct BoardTracker {
    pub spec: BoardSpec,
    board: objdetect::CharucoBoard,
    detector: objdetect::CharucoDetector,
    roi_tracking: bool,
    prev_r: Option<M3>,
    /// Next search window and the frame size it was computed for: a window
    /// from another resolution (the governor switched) is meaningless.
    search: Option<(Rect, (i32, i32))>,
    frames_since_full: u32,
    // detections of the current frame, full-frame pixel coordinates
    pub corners: Vector<Point2f>,
    pub ids: Vector<i32>,
    marker_corners: Vector<Vector<Point2f>>,
    marker_ids: Vector<i32>,
    obj: Vector<Point3f>,
    img: Vector<Point2f>,
}

impl BoardTracker {
    pub fn new(spec: BoardSpec, aruco3: bool, roi_tracking: bool) -> opencv::Result<Self> {
        let board = make_board(&spec)?;
        let mut params = objdetect::DetectorParameters::default()?;
        // sub-pixel marker corners feed the ChArUco interpolation and the pose
        params.set_corner_refinement_method(objdetect::CornerRefineMethod::CORNER_REFINE_SUBPIX as i32);
        params.set_use_aruco3_detection(aruco3);
        let detector = objdetect::CharucoDetector::new(
            &board,
            &objdetect::CharucoParameters::default()?,
            &params,
            objdetect::RefineParameters::new_def()?,
        )?;
        Ok(BoardTracker {
            spec,
            board,
            detector,
            roi_tracking,
            prev_r: None,
            search: None,
            frames_since_full: 0,
            corners: Vector::new(),
            ids: Vector::new(),
            marker_corners: Vector::new(),
            marker_ids: Vector::new(),
            obj: Vector::new(),
            img: Vector::new(),
        })
    }

    /// Detect the board's ChArUco corners in a luma image. Tries the tracking
    /// window first and falls back to the full frame on a miss (and every
    /// 15th frame, to pick up parts of the board that just came into view).
    pub fn detect(&mut self, gray: &Mat) -> opencv::Result<usize> {
        let (w, h) = (gray.cols(), gray.rows());
        let windowed = self.roi_tracking && self.frames_since_full < 15;
        let window = self.search.take().filter(|&(_, size)| size == (w, h)).map(|(r, _)| r);
        if let (true, Some(r)) = (windowed, window) {
            let sub = Mat::roi(gray, r)?;
            self.detect_in(&sub)?;
            if self.corners.len() >= 4 {
                for i in 0..self.corners.len() {
                    let mut p = self.corners.get(i)?;
                    p.x += r.x as f32;
                    p.y += r.y as f32;
                    self.corners.set(i, p)?;
                }
                self.frames_since_full += 1;
                self.update_search(w, h)?;
                return Ok(self.corners.len());
            }
        }
        self.detect_in(gray)?;
        self.frames_since_full = 0;
        self.update_search(w, h)?;
        Ok(self.corners.len())
    }

    fn detect_in(&mut self, img: &impl core::ToInputArray) -> opencv::Result<()> {
        self.corners.clear();
        self.ids.clear();
        self.marker_corners.clear();
        self.marker_ids.clear();
        self.detector.detect_board(img, &mut self.corners, &mut self.ids, &mut self.marker_corners, &mut self.marker_ids)
    }

    /// Next frame's search window: the corners' bounding box grown by a
    /// border-square's worth (markers extend past the outer corners) plus
    /// room for motion.
    fn update_search(&mut self, w: i32, h: i32) -> opencv::Result<()> {
        if self.corners.len() < 4 {
            self.search = None;
            return Ok(());
        }
        let (mut x0, mut y0, mut x1, mut y1) = (f32::MAX, f32::MAX, f32::MIN, f32::MIN);
        for p in self.corners.iter() {
            x0 = x0.min(p.x);
            y0 = y0.min(p.y);
            x1 = x1.max(p.x);
            y1 = y1.max(p.y);
        }
        let (bw, bh) = (x1 - x0, y1 - y0);
        let inner = ((self.spec.squares_x - 2).max(1)).min((self.spec.squares_y - 2).max(1)) as f32;
        let square = bw.max(bh) / inner;
        let pad = 1.5 * square + 0.25 * bw.max(bh) + 16.0;
        let rx0 = ((x0 - pad).floor() as i32).clamp(0, w - 1);
        let ry0 = ((y0 - pad).floor() as i32).clamp(0, h - 1);
        let rx1 = ((x1 + pad).ceil() as i32).clamp(rx0 + 1, w);
        let ry1 = ((y1 + pad).ceil() as i32).clamp(ry0 + 1, h);
        // a window covering most of the frame buys nothing
        self.search = ((rx1 - rx0) * (ry1 - ry0) < w * h * 3 / 4).then(|| (Rect::new(rx0, ry0, rx1 - rx0, ry1 - ry0), (w, h)));
        Ok(())
    }

    /// ArUco markers of the current frame: many markers but no corners means
    /// the board settings do not match the printed board.
    pub fn n_markers(&self) -> usize {
        self.marker_ids.len()
    }

    /// Board→camera pose from the current detection.
    pub fn solve_pose(&mut self, k: &Intrinsics) -> opencv::Result<Option<PoseResult>> {
        if self.corners.len() < 4 {
            return Ok(None);
        }
        self.obj.clear();
        self.img.clear();
        self.board.match_image_points(&self.corners, &self.ids, &mut self.obj, &mut self.img)?;
        let n = self.obj.len();
        if n < 4 {
            return Ok(None);
        }
        let k_mat = Mat::from_slice_2d(&[[k.fx, 0.0, k.cx], [0.0, k.fy, k.cy], [0.0, 0.0, 1.0]])?;
        let d_mat = Mat::from_slice(&k.dist)?;
        let obj: Vec<V3> = self.obj.iter().map(|p| [p.x as f64, p.y as f64, p.z as f64]).collect();
        let img: Vec<[f64; 2]> = self.img.iter().map(|p| [p.x as f64, p.y as f64]).collect();

        let mut rvecs: Vector<Mat> = Vector::new();
        let mut tvecs: Vector<Mat> = Vector::new();
        let solutions = calib3d::solve_pnp_generic(
            &self.obj,
            &self.img,
            &k_mat,
            &d_mat,
            &mut rvecs,
            &mut tvecs,
            false,
            calib3d::SolvePnPMethod::SOLVEPNP_IPPE,
            &core::no_array(),
            &core::no_array(),
            &mut core::no_array(),
        )
        .unwrap_or(0);

        let mut cands: Vec<(Pose, f64)> = Vec::with_capacity(2);
        for i in 0..(solutions.max(0) as usize).min(rvecs.len()) {
            let pose = pose_from(&rvecs.get(i)?, &tvecs.get(i)?)?;
            cands.push((pose, reproj_rms(k, &pose, &obj, &img)));
        }
        if cands.is_empty() {
            // IPPE refused the view — plain iterative PnP, no margin
            let (mut rvec, mut tvec) = (Mat::default(), Mat::default());
            let ok = calib3d::solve_pnp(&self.obj, &self.img, &k_mat, &d_mat, &mut rvec, &mut tvec, false, calib3d::SOLVEPNP_ITERATIVE)?;
            if !ok {
                return Ok(None);
            }
            let pose = pose_from(&rvec, &tvec)?;
            self.prev_r = Some(pose.r);
            let rms = reproj_rms(k, &pose, &obj, &img);
            return Ok(Some(PoseResult { pose, reproj_rms: rms, margin: None }));
        }

        let mut order: Vec<usize> = (0..cands.len()).collect();
        order.sort_by(|&a, &b| cands[a].1.total_cmp(&cands[b].1));
        let mut best = order[0];
        let margin = if order.len() > 1 { cands[order[1]].1 / cands[order[0]].1.max(1e-6) } else { 99.0 };
        // Near-frontal views tie the two planar branches and the winner would
        // flip frame to frame; while ambiguous, stay with the branch nearest
        // the previous pose — unless it fits far worse.
        if let (Some(prev), true) = (self.prev_r, cands.len() > 1 && margin < 3.0) {
            let nearest = (0..cands.len())
                .min_by(|&a, &b| rotation_distance(&prev, &cands[a].0.r).total_cmp(&rotation_distance(&prev, &cands[b].0.r)))
                .unwrap();
            if cands[nearest].1 <= cands[best].1 * 2.0 {
                best = nearest;
            }
        }
        let (pose, rms) = cands[best];
        self.prev_r = Some(pose.r);
        Ok(Some(PoseResult { pose, reproj_rms: rms, margin: Some(margin) }))
    }
}

fn pose_from(rvec: &Mat, tvec: &Mat) -> opencv::Result<Pose> {
    let r = [*rvec.at::<f64>(0)?, *rvec.at::<f64>(1)?, *rvec.at::<f64>(2)?];
    let t = [*tvec.at::<f64>(0)?, *tvec.at::<f64>(1)?, *tvec.at::<f64>(2)?];
    Ok(Pose { r: rodrigues(r), t })
}

/// Per-point reprojection RMS (px).
fn reproj_rms(k: &Intrinsics, pose: &Pose, obj: &[V3], img: &[[f64; 2]]) -> f64 {
    let mut ss = 0.0;
    let mut n = 0usize;
    for (o, i) in obj.iter().zip(img) {
        if let Some(p) = k.project(pose.to_cam(*o)) {
            ss += (p[0] - i[0]).powi(2) + (p[1] - i[1]).powi(2);
            n += 1;
        }
    }
    if n == 0 { f64::INFINITY } else { (ss / n as f64).sqrt() }
}

/// Bounding box of the detected corners `(x0, y0, x1, y1)` in pixels.
pub fn corner_bbox(corners: &Vector<Point2f>) -> Option<(usize, usize, usize, usize)> {
    if corners.is_empty() {
        return None;
    }
    let (mut x0, mut y0, mut x1, mut y1) = (f32::MAX, f32::MAX, f32::MIN, f32::MIN);
    for p in corners.iter() {
        x0 = x0.min(p.x);
        y0 = y0.min(p.y);
        x1 = x1.max(p.x);
        y1 = y1.max(p.y);
    }
    Some((x0.max(0.0) as usize, y0.max(0.0) as usize, x1.ceil().max(0.0) as usize, y1.ceil().max(0.0) as usize))
}

/// Distance from the camera to the board origin (mm).
pub fn distance(pose: &Pose) -> f64 {
    norm(pose.t)
}
