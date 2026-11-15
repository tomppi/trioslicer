//! Pose-fitted overlay geometry in image pixels: the board lattice, outline,
//! axes, working-volume wireframe and the accumulated cloud. Every segment is
//! near-plane clipped so geometry behind the camera never streaks across the
//! view; straight board edges are drawn per square so lens distortion bends
//! them the way the real board bends.

use crate::geom::{Intrinsics, Pose, V3, add, scale, sub};
use crate::volume::Volume;

/// Near-plane distance (mm) segments are clipped at.
pub const NEAR_MM: f64 = 10.0;

/// A board-frame segment as pixel endpoints `[x0, y0, x1, y1]`, near-plane
/// clipped; `None` when fully behind the camera or unprojectable.
pub fn segment(k: &Intrinsics, pose: &Pose, a: V3, b: V3) -> Option<[f32; 4]> {
    let (mut ca, mut cb) = (pose.to_cam(a), pose.to_cam(b));
    match (ca[2] >= NEAR_MM, cb[2] >= NEAR_MM) {
        (false, false) => return None,
        (true, true) => {}
        (a_in, _) => {
            let s = (NEAR_MM - ca[2]) / (cb[2] - ca[2]);
            let p = add(ca, scale(sub(cb, ca), s));
            if a_in { cb = p } else { ca = p }
        }
    }
    let pa = k.project(ca)?;
    let pb = k.project(cb)?;
    Some([pa[0] as f32, pa[1] as f32, pb[0] as f32, pb[1] as f32])
}

/// `a → b` split into `n` pieces.
fn polyline(k: &Intrinsics, pose: &Pose, a: V3, b: V3, n: usize, out: &mut Vec<[f32; 4]>) {
    let n = n.max(1);
    let step = scale(sub(b, a), 1.0 / n as f64);
    for i in 0..n {
        let p = add(a, scale(step, i as f64));
        if let Some(s) = segment(k, pose, p, add(p, step)) {
            out.push(s);
        }
    }
}

/// Every square edge of the `nx`×`ny` lattice with `sq`-mm squares.
pub fn board_grid(k: &Intrinsics, pose: &Pose, nx: usize, ny: usize, sq: f64, out: &mut Vec<[f32; 4]>) {
    for j in 0..=ny {
        for i in 0..=nx {
            let p = [i as f64 * sq, j as f64 * sq, 0.0];
            if i < nx && let Some(s) = segment(k, pose, p, [p[0] + sq, p[1], 0.0]) {
                out.push(s);
            }
            if j < ny && let Some(s) = segment(k, pose, p, [p[0], p[1] + sq, 0.0]) {
                out.push(s);
            }
        }
    }
}

/// The board's outer rectangle, split per square.
pub fn board_outline(k: &Intrinsics, pose: &Pose, nx: usize, ny: usize, sq: f64, out: &mut Vec<[f32; 4]>) {
    let (w, h) = (nx as f64 * sq, ny as f64 * sq);
    let c = [[0.0, 0.0, 0.0], [w, 0.0, 0.0], [w, h, 0.0], [0.0, h, 0.0]];
    for i in 0..4 {
        let n = if i % 2 == 0 { nx } else { ny };
        polyline(k, pose, c[i], c[(i + 1) % 4], n, out);
    }
}

/// X, Y and Z axes of length `len` from the board origin; +Z is drawn toward
/// the camera (up out of the table) so it never points into the floor.
pub fn axes(k: &Intrinsics, pose: &Pose, len: f64) -> [Option<[f32; 4]>; 3] {
    let o = [0.0; 3];
    [
        segment(k, pose, o, [len, 0.0, 0.0]),
        segment(k, pose, o, [0.0, len, 0.0]),
        segment(k, pose, o, [0.0, 0.0, pose.up_sign() * len]),
    ]
}

/// The working-volume wireframe, each edge split in `pieces`.
pub fn volume_edges(k: &Intrinsics, pose: &Pose, vol: &Volume, up: f64, pieces: usize, out: &mut Vec<[f32; 4]>) {
    let c = vol.corners(pose, up);
    for (a, b) in Volume::EDGES {
        polyline(k, pose, c[a], c[b], pieces, out);
    }
}

/// Project board-frame points (the cloud preview) that are in front of the
/// camera.
pub fn project_points(k: &Intrinsics, pose: &Pose, pts: &[[f32; 3]], out: &mut Vec<[f32; 2]>) {
    for p in pts {
        let c = pose.to_cam([p[0] as f64, p[1] as f64, p[2] as f64]);
        if c[2] > 1.0 && let Some(q) = k.project(c) {
            out.push([q[0] as f32, q[1] as f32]);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::geom::rodrigues;

    fn k() -> Intrinsics {
        Intrinsics { fx: 800.0, fy: 800.0, cx: 320.0, cy: 240.0, dist: [0.0; 5], width: 640.0, height: 480.0 }
    }

    #[test]
    fn segment_clips_at_near_plane() {
        let pose = Pose { r: crate::geom::IDENTITY, t: [0.0, 0.0, 0.0] };
        assert!(segment(&k(), &pose, [0.0, 0.0, -50.0], [0.0, 0.0, -10.0]).is_none());
        let s = segment(&k(), &pose, [10.0, 0.0, -50.0], [10.0, 0.0, 100.0]).unwrap();
        // the clipped end sits on z = NEAR_MM: x = 320 + 800·10/10
        assert!((s[0] - 1120.0).abs() < 1e-3, "{s:?}");
    }

    #[test]
    fn grid_has_every_square_edge() {
        let pose = Pose { r: rodrigues([std::f64::consts::PI, 0.0, 0.0]), t: [-144.0, 144.0, 600.0] };
        let mut segs = Vec::new();
        board_grid(&k(), &pose, 8, 8, 36.0, &mut segs);
        assert_eq!(segs.len(), 2 * 8 * 9);
        let mut outline = Vec::new();
        board_outline(&k(), &pose, 8, 8, 36.0, &mut outline);
        assert_eq!(outline.len(), 32);
        let ax = axes(&k(), &pose, 144.0);
        assert!(ax.iter().all(|a| a.is_some()));
    }
}
