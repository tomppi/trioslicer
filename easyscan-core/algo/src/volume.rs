//! The working volume: the board footprint plus a margin, extruded toward the
//! camera. It bounds where the laser is searched for (its projected hull is
//! the detection ROI) and which triangulated points count as the object.

use crate::geom::{Intrinsics, Pose, V3};
use crate::laser::Roi;

/// Points closer to the board than this (mm) are the board itself, not the
/// object.
pub const BOARD_EPS_MM: f64 = 3.0;

#[derive(Clone, Copy, Debug)]
pub struct Volume {
    pub board_w: f64,
    pub board_h: f64,
    pub margin: f64,
    pub height: f64,
}

impl Volume {
    /// The 12 cube edges as pairs of [`Volume::corners`] indices.
    pub const EDGES: [(usize, usize); 12] = [
        (0, 1), (2, 3), (4, 5), (6, 7), // vertical (z)
        (0, 2), (1, 3), (4, 6), (5, 7), // along y
        (0, 4), (1, 5), (2, 6), (3, 7), // along x
    ];

    /// In-plane bounds `(x0, x1, y0, y1)`, board mm.
    pub fn bounds(&self) -> (f64, f64, f64, f64) {
        (-self.margin, self.board_w + self.margin, -self.margin, self.board_h + self.margin)
    }

    #[inline]
    pub fn in_footprint(&self, p: V3) -> bool {
        let (x0, x1, y0, y1) = self.bounds();
        p[0] >= x0 && p[0] <= x1 && p[1] >= y0 && p[1] <= y1
    }

    /// Board-frame corners, index = `xi·4 + yi·2 + zi`, with the top face on
    /// the camera side of the board.
    pub fn corners(&self, pose: &Pose, up: f64) -> [V3; 8] {
        let (x0, x1, y0, y1) = self.bounds();
        let ztop = up * self.height;
        let mut c = [[0.0; 3]; 8];
        for (i, x) in [x0, x1].into_iter().enumerate() {
            for (j, y) in [y0, y1].into_iter().enumerate() {
                for (k, z) in [0.0, ztop].into_iter().enumerate() {
                    c[i * 4 + j * 2 + k] = [x, y, z];
                }
            }
        }
        c
    }

    /// Whether a board-frame point lies inside the volume and above the board
    /// surface — the scan's "object" test.
    #[inline]
    pub fn contains(&self, _pose: &Pose, p: V3, up: f64) -> bool {
        let up_coord = up * p[2];
        up_coord > BOARD_EPS_MM && up_coord <= self.height && self.in_footprint(p)
    }

    /// The image region covered by the volume: the convex hull of its
    /// projected corners, rasterised to row spans. `None` when too little of
    /// the volume is in front of the camera.
    pub fn roi(&self, pose: &Pose, k: &Intrinsics, w: usize, h: usize, up: f64) -> Option<Roi> {
        let mut pts: Vec<[f64; 2]> = self
            .corners(pose, up)
            .iter()
            .map(|&c| pose.to_cam(c))
            .filter(|c| c[2] > 1.0)
            .map(|c| k.project(c).unwrap_or([k.fx * c[0] / c[2] + k.cx, k.fy * c[1] / c[2] + k.cy]))
            .filter(|p| p[0].is_finite() && p[1].is_finite())
            .collect();
        if pts.len() < 3 {
            return None;
        }
        let hull = convex_hull(&mut pts);
        if hull.len() < 3 {
            return None;
        }
        Some(rasterize_convex(&hull, w, h))
    }
}

/// Andrew's monotone chain; returns the hull counter-clockwise.
pub fn convex_hull(pts: &mut [[f64; 2]]) -> Vec<[f64; 2]> {
    pts.sort_by(|a, b| a[0].total_cmp(&b[0]).then(a[1].total_cmp(&b[1])));
    let cross = |o: [f64; 2], a: [f64; 2], b: [f64; 2]| (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0]);
    let mut hull: Vec<[f64; 2]> = Vec::with_capacity(pts.len() * 2);
    for &p in pts.iter() {
        while hull.len() >= 2 && cross(hull[hull.len() - 2], hull[hull.len() - 1], p) <= 0.0 {
            hull.pop();
        }
        hull.push(p);
    }
    let lower = hull.len() + 1;
    for &p in pts.iter().rev().skip(1) {
        while hull.len() >= lower && cross(hull[hull.len() - 2], hull[hull.len() - 1], p) <= 0.0 {
            hull.pop();
        }
        hull.push(p);
    }
    hull.pop();
    hull
}

/// Row spans of a convex polygon clipped to a `w`×`h` image (boundary pixels
/// included, like `cv::fillConvexPoly`).
pub fn rasterize_convex(poly: &[[f64; 2]], w: usize, h: usize) -> Roi {
    let ymin = poly.iter().map(|p| p[1]).fold(f64::INFINITY, f64::min);
    let ymax = poly.iter().map(|p| p[1]).fold(f64::NEG_INFINITY, f64::max);
    let y0 = ymin.floor().max(0.0) as usize;
    let y1 = (ymax.ceil() + 1.0).min(h as f64).max(0.0) as usize;
    if y1 <= y0 {
        return Roi::default();
    }
    let mut spans = Vec::with_capacity(y1 - y0);
    for y in y0..y1 {
        let yc = y as f64;
        let (mut xmin, mut xmax) = (f64::INFINITY, f64::NEG_INFINITY);
        for i in 0..poly.len() {
            let a = poly[i];
            let b = poly[(i + 1) % poly.len()];
            let (lo, hi) = if a[1] <= b[1] { (a, b) } else { (b, a) };
            // the row line through the pixel centre, widened by half a pixel
            if yc + 0.5 < lo[1] || yc - 0.5 > hi[1] {
                continue;
            }
            let yy = yc.clamp(lo[1], hi[1]);
            let x = if (hi[1] - lo[1]).abs() < 1e-9 {
                xmin = xmin.min(lo[0].min(hi[0]));
                xmax = xmax.max(lo[0].max(hi[0]));
                continue;
            } else {
                lo[0] + (yy - lo[1]) * (hi[0] - lo[0]) / (hi[1] - lo[1])
            };
            xmin = xmin.min(x);
            xmax = xmax.max(x);
        }
        if xmax < xmin {
            spans.push((0, 0));
            continue;
        }
        let a = xmin.floor().clamp(0.0, w as f64) as u32;
        let b = (xmax.floor() + 1.0).clamp(0.0, w as f64) as u32;
        spans.push((a, b.max(a)));
    }
    Roi { y0, spans }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::geom::rodrigues;

    #[test]
    fn hull_drops_interior_points() {
        let mut pts = vec![[0.0, 0.0], [10.0, 0.0], [10.0, 10.0], [0.0, 10.0], [5.0, 5.0], [2.0, 7.0]];
        let hull = convex_hull(&mut pts);
        assert_eq!(hull.len(), 4);
    }

    #[test]
    fn rasterized_square_covers_expected_pixels() {
        let roi = rasterize_convex(&[[10.0, 20.0], [30.0, 20.0], [30.0, 40.0], [10.0, 40.0]], 100, 100);
        assert_eq!(roi.y0, 20);
        assert_eq!(roi.spans.len(), 21);
        assert!(roi.spans.iter().all(|&s| s == (10, 31)));
    }

    #[test]
    fn rasterization_clips_to_image() {
        let roi = rasterize_convex(&[[-50.0, -50.0], [500.0, -40.0], [480.0, 900.0]], 320, 240);
        assert_eq!(roi.y0, 0);
        assert_eq!(roi.y1(), 240);
        assert!(roi.spans.iter().all(|&(a, b)| a <= b && b <= 320));
    }

    #[test]
    fn volume_roi_contains_board_centre_and_classifies() {
        let k = Intrinsics { fx: 800.0, fy: 800.0, cx: 320.0, cy: 240.0, dist: [0.0; 5], width: 640.0, height: 480.0 };
        // camera looking straight down at a 288 mm board from 600 mm
        let pose = Pose { r: rodrigues([std::f64::consts::PI, 0.0, 0.0]), t: [-144.0, 144.0, 600.0] };
        let vol = Volume { board_w: 288.0, board_h: 288.0, margin: 20.0, height: 100.0 };
        // up is toward the camera: board +Z points away from it here
        let up = pose.up_sign();
        let roi = vol.roi(&pose, &k, 640, 480, up).unwrap();
        let centre = k.project(pose.to_cam([144.0, 144.0, 0.0])).unwrap();
        let (cx, cy) = (centre[0] as usize, centre[1] as usize);
        let (a, b) = roi.spans[cy - roi.y0];
        assert!(a as usize <= cx && cx < b as usize);
        assert!(vol.contains(&pose, [100.0, 100.0, up * 50.0], up));
        assert!(!vol.contains(&pose, [100.0, 100.0, -up * 50.0], up));
        assert!(!vol.contains(&pose, [100.0, 100.0, up * 1.0], up));
        assert!(!vol.contains(&pose, [400.0, 100.0, up * 50.0], up));
    }
}
