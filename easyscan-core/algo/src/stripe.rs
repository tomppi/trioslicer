//! Turning detected stripe pixels into 3-D points: onto the board plane (for
//! the viewfinder read-out and the laser-plane calibration) and onto the
//! calibrated laser plane (the scan).

use crate::geom::{Intrinsics, Pose, V3, dot, ray_plane};
use crate::volume::Volume;

/// The stripe reconstructed onto the board, with a robust straight-line fit —
/// the laser on a flat board IS a line, so the clean fit is drawn instead of
/// the noisy per-pixel polyline.
#[derive(Clone, Debug, Default)]
pub struct BoardStripe {
    /// Camera frame, mm.
    pub cam_pts: Vec<V3>,
    /// Board frame, mm (z ≈ 0).
    pub board_pts: Vec<V3>,
    pub fit_a: [f64; 2],
    pub fit_b: [f64; 2],
    pub mean: [f64; 2],
    pub angle_deg: f64,
    pub length_mm: f64,
    /// Off-plane residual (≈ 0 by construction).
    pub plane_rms_mm: f64,
}

/// Intersect each pixel's camera ray with the board plane, keep hits inside
/// the volume footprint (grazing rays meet the infinite plane far away), and
/// fit the line with a median-based outlier pass. `None` when fewer than two
/// points survive.
pub fn on_board(pixels: &[[f32; 2]], k: &Intrinsics, pose: &Pose, vol: &Volume) -> Option<BoardStripe> {
    let n = pose.board_normal();
    let d = dot(n, pose.t);
    let mut cam_pts = Vec::with_capacity(pixels.len());
    let mut board_pts = Vec::with_capacity(pixels.len());
    for p in pixels {
        if let Some(x) = ray_plane(k.ray(p[0] as f64, p[1] as f64), n, d) {
            let b = pose.to_board(x);
            if vol.in_footprint(b) {
                cam_pts.push(x);
                board_pts.push(b);
            }
        }
    }
    if board_pts.len() < 2 {
        return None;
    }

    let (mut mean, mut dir) = line_fit_2d(&board_pts);
    let resid: Vec<f64> = board_pts.iter().map(|b| perp(b, mean, dir)).collect();
    let mut sorted = resid.clone();
    sorted.sort_by(f64::total_cmp);
    let med = sorted[sorted.len() / 2];
    let keep: Vec<bool> = resid.iter().map(|&r| r <= 2.5 * med + 0.5).collect();
    if keep.iter().filter(|&&k| k).count() >= 2 {
        let mut k_iter = keep.iter();
        cam_pts.retain(|_| *k_iter.next().unwrap());
        let mut k_iter = keep.iter();
        board_pts.retain(|_| *k_iter.next().unwrap());
        (mean, dir) = line_fit_2d(&board_pts);
    }

    let (mut lo, mut hi) = (f64::INFINITY, f64::NEG_INFINITY);
    for b in &board_pts {
        let t = (b[0] - mean[0]) * dir[0] + (b[1] - mean[1]) * dir[1];
        lo = lo.min(t);
        hi = hi.max(t);
    }
    let plane_rms_mm = (board_pts.iter().map(|b| b[2] * b[2]).sum::<f64>() / board_pts.len() as f64).sqrt();
    Some(BoardStripe {
        fit_a: [mean[0] + dir[0] * lo, mean[1] + dir[1] * lo],
        fit_b: [mean[0] + dir[0] * hi, mean[1] + dir[1] * hi],
        mean,
        angle_deg: dir[1].atan2(dir[0]).to_degrees(),
        length_mm: hi - lo,
        plane_rms_mm,
        cam_pts,
        board_pts,
    })
}

/// Principal direction of the points' XY (closed-form 2x2 PCA).
fn line_fit_2d(pts: &[V3]) -> ([f64; 2], [f64; 2]) {
    let n = pts.len() as f64;
    let mx = pts.iter().map(|p| p[0]).sum::<f64>() / n;
    let my = pts.iter().map(|p| p[1]).sum::<f64>() / n;
    let (mut sxx, mut syy, mut sxy) = (0.0, 0.0, 0.0);
    for p in pts {
        let (dx, dy) = (p[0] - mx, p[1] - my);
        sxx += dx * dx;
        syy += dy * dy;
        sxy += dx * dy;
    }
    let theta = 0.5 * (2.0 * sxy).atan2(sxx - syy);
    ([mx, my], [theta.cos(), theta.sin()])
}

fn perp(b: &V3, mean: [f64; 2], dir: [f64; 2]) -> f64 {
    let (dx, dy) = (b[0] - mean[0], b[1] - mean[1]);
    (dx * dir[1] - dy * dir[0]).abs()
}

/// Intersect each pixel's ray with the laser plane `n·X = d` (camera frame).
/// Calls `hit(i, X_cam)` for every pixel whose ray meets the plane in front of
/// the camera.
pub fn to_laser_plane(pixels: &[[f32; 2]], k: &Intrinsics, n: V3, d: f64, mut hit: impl FnMut(usize, V3)) {
    for (i, p) in pixels.iter().enumerate() {
        if let Some(x) = ray_plane(k.ray(p[0] as f64, p[1] as f64), n, d) {
            hit(i, x);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::geom::rodrigues;

    fn setup() -> (Intrinsics, Pose, Volume) {
        let k = Intrinsics { fx: 900.0, fy: 900.0, cx: 480.0, cy: 360.0, dist: [0.02, -0.01, 0.0, 0.0, 0.0], width: 960.0, height: 720.0 };
        let pose = Pose { r: rodrigues([2.6, 0.3, -0.1]), t: [-120.0, 90.0, 520.0] };
        let vol = Volume { board_w: 288.0, board_h: 288.0, margin: 20.0, height: 288.0 };
        (k, pose, vol)
    }

    #[test]
    fn stripe_on_board_recovers_the_drawn_line() {
        let (k, pose, vol) = setup();
        let (a, b) = ([30.0, 50.0], [250.0, 200.0]);
        let pixels: Vec<[f32; 2]> = (0..=100)
            .filter_map(|i| {
                let t = i as f64 / 100.0;
                let p = [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, 0.0];
                k.project(pose.to_cam(p)).map(|q| [q[0] as f32, q[1] as f32])
            })
            .collect();
        let s = on_board(&pixels, &k, &pose, &vol).unwrap();
        let ang = (b[1] - a[1]).atan2(b[0] - a[0]).to_degrees();
        let da = (s.angle_deg - ang).rem_euclid(180.0);
        assert!(da < 0.05 || da > 179.95, "angle {} vs {}", s.angle_deg, ang);
        let len = ((b[0] - a[0]).powi(2) + (b[1] - a[1]).powi(2)).sqrt();
        assert!((s.length_mm - len).abs() < 0.2, "length {} vs {len}", s.length_mm);
        assert!(s.plane_rms_mm < 1e-6);
    }

    #[test]
    fn laser_plane_triangulation_hits_the_plane() {
        let (k, _, _) = setup();
        let n = crate::geom::normalize([0.86, -0.22, -0.45]).unwrap();
        let d = -165.0;
        let pixels = [[300.0f32, 200.0], [500.0, 400.0], [700.0, 100.0]];
        let mut hits = Vec::new();
        to_laser_plane(&pixels, &k, n, d, |i, x| hits.push((i, x)));
        assert!(!hits.is_empty());
        for (_, x) in hits {
            assert!((dot(n, x) - d).abs() < 1e-9);
            assert!(x[2] > 0.0);
        }
    }
}
