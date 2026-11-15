//! Small fixed-size linear algebra, the pinhole + Brown–Conrady camera model,
//! and rigid poses. Row-major 3x3 matrices, f64 throughout.

pub type V3 = [f64; 3];
pub type M3 = [[f64; 3]; 3];

pub const IDENTITY: M3 = [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]];

#[inline]
pub fn add(a: V3, b: V3) -> V3 {
    [a[0] + b[0], a[1] + b[1], a[2] + b[2]]
}

#[inline]
pub fn sub(a: V3, b: V3) -> V3 {
    [a[0] - b[0], a[1] - b[1], a[2] - b[2]]
}

#[inline]
pub fn scale(a: V3, s: f64) -> V3 {
    [a[0] * s, a[1] * s, a[2] * s]
}

#[inline]
pub fn dot(a: V3, b: V3) -> f64 {
    a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}

#[inline]
pub fn cross(a: V3, b: V3) -> V3 {
    [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]
}

#[inline]
pub fn norm(a: V3) -> f64 {
    dot(a, a).sqrt()
}

/// Unit vector, or `None` for a (near) zero vector.
pub fn normalize(a: V3) -> Option<V3> {
    let n = norm(a);
    (n > 1e-12).then(|| scale(a, 1.0 / n))
}

#[inline]
pub fn mat_vec(m: &M3, v: V3) -> V3 {
    [dot(m[0], v), dot(m[1], v), dot(m[2], v)]
}

/// `mᵀ · v`
#[inline]
pub fn mat_t_vec(m: &M3, v: V3) -> V3 {
    [
        m[0][0] * v[0] + m[1][0] * v[1] + m[2][0] * v[2],
        m[0][1] * v[0] + m[1][1] * v[1] + m[2][1] * v[2],
        m[0][2] * v[0] + m[1][2] * v[1] + m[2][2] * v[2],
    ]
}

pub fn transpose(m: &M3) -> M3 {
    [[m[0][0], m[1][0], m[2][0]], [m[0][1], m[1][1], m[2][1]], [m[0][2], m[1][2], m[2][2]]]
}

pub fn mat_mul(a: &M3, b: &M3) -> M3 {
    let mut r = [[0.0; 3]; 3];
    for (i, row) in r.iter_mut().enumerate() {
        for (j, cell) in row.iter_mut().enumerate() {
            *cell = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
        }
    }
    r
}

/// Rotation matrix from an axis-angle vector (OpenCV `Rodrigues`).
pub fn rodrigues(r: V3) -> M3 {
    let theta = norm(r);
    if theta < 1e-12 {
        return IDENTITY;
    }
    let [x, y, z] = scale(r, 1.0 / theta);
    let (s, c) = theta.sin_cos();
    let c1 = 1.0 - c;
    [
        [c + x * x * c1, x * y * c1 - z * s, x * z * c1 + y * s],
        [y * x * c1 + z * s, c + y * y * c1, y * z * c1 - x * s],
        [z * x * c1 - y * s, z * y * c1 + x * s, c + z * z * c1],
    ]
}

/// Angle (rad) of the relative rotation `aᵀ·b` — the geodesic distance.
pub fn rotation_distance(a: &M3, b: &M3) -> f64 {
    let rel = mat_mul(&transpose(a), b);
    let tr = rel[0][0] + rel[1][1] + rel[2][2];
    ((tr - 1.0) * 0.5).clamp(-1.0, 1.0).acos()
}

/// Unit quaternion `[w, x, y, z]` of a rotation matrix (Shepperd's method),
/// with `w ≥ 0` — COLMAP's `QW QX QY QZ` order.
pub fn quat_from_mat(m: &M3) -> [f64; 4] {
    let tr = m[0][0] + m[1][1] + m[2][2];
    let q = if tr > 0.0 {
        let s = 2.0 * (tr + 1.0).sqrt();
        [0.25 * s, (m[2][1] - m[1][2]) / s, (m[0][2] - m[2][0]) / s, (m[1][0] - m[0][1]) / s]
    } else if m[0][0] > m[1][1] && m[0][0] > m[2][2] {
        let s = 2.0 * (1.0 + m[0][0] - m[1][1] - m[2][2]).sqrt();
        [(m[2][1] - m[1][2]) / s, 0.25 * s, (m[0][1] + m[1][0]) / s, (m[0][2] + m[2][0]) / s]
    } else if m[1][1] > m[2][2] {
        let s = 2.0 * (1.0 + m[1][1] - m[0][0] - m[2][2]).sqrt();
        [(m[0][2] - m[2][0]) / s, (m[0][1] + m[1][0]) / s, 0.25 * s, (m[1][2] + m[2][1]) / s]
    } else {
        let s = 2.0 * (1.0 + m[2][2] - m[0][0] - m[1][1]).sqrt();
        [(m[1][0] - m[0][1]) / s, (m[0][2] + m[2][0]) / s, (m[1][2] + m[2][1]) / s, 0.25 * s]
    };
    let n = (q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]).sqrt();
    let sign = if q[0] < 0.0 { -1.0 } else { 1.0 };
    q.map(|c| sign * c / n)
}

/// Rotation matrix of a unit quaternion `[w, x, y, z]`.
pub fn mat_from_quat(q: [f64; 4]) -> M3 {
    let [w, x, y, z] = q;
    [
        [1.0 - 2.0 * (y * y + z * z), 2.0 * (x * y - w * z), 2.0 * (x * z + w * y)],
        [2.0 * (x * y + w * z), 1.0 - 2.0 * (x * x + z * z), 2.0 * (y * z - w * x)],
        [2.0 * (x * z - w * y), 2.0 * (y * z + w * x), 1.0 - 2.0 * (x * x + y * y)],
    ]
}

/// Rotation a fraction `s` of the way from `a` to `b` along the geodesic.
pub fn slerp(a: &M3, b: &M3, s: f64) -> M3 {
    let qa = quat_from_mat(a);
    let mut qb = quat_from_mat(b);
    let mut d: f64 = qa.iter().zip(&qb).map(|(x, y)| x * y).sum();
    if d < 0.0 {
        qb = qb.map(|c| -c);
        d = -d;
    }
    let (wa, wb) = if d > 0.9995 {
        (1.0 - s, s)
    } else {
        let th = d.acos();
        (((1.0 - s) * th).sin() / th.sin(), (s * th).sin() / th.sin())
    };
    let q: [f64; 4] = std::array::from_fn(|i| wa * qa[i] + wb * qb[i]);
    let n = q.iter().map(|c| c * c).sum::<f64>().sqrt();
    mat_from_quat(q.map(|c| c / n))
}

/// Roll(x)/pitch(y)/yaw(z) in degrees for `R = Rz·Ry·Rx`, gimbal-safe.
pub fn euler_xyz_deg(m: &M3) -> V3 {
    let sy = m[0][0].hypot(m[1][0]);
    let (roll, pitch, yaw) = if sy > 1e-6 {
        (m[2][1].atan2(m[2][2]), (-m[2][0]).atan2(sy), m[1][0].atan2(m[0][0]))
    } else {
        ((-m[1][2]).atan2(m[1][1]), (-m[2][0]).atan2(sy), 0.0)
    };
    [roll.to_degrees(), pitch.to_degrees(), yaw.to_degrees()]
}

/// Eigen-decomposition of a symmetric 3x3 matrix (cyclic Jacobi). Returns the
/// eigenvalues in DESCENDING order and the matching unit eigenvectors.
pub fn sym3_eigen(m: &M3) -> ([f64; 3], [V3; 3]) {
    let mut a = *m;
    let mut v = IDENTITY;
    for _ in 0..32 {
        let off = a[0][1].abs() + a[0][2].abs() + a[1][2].abs();
        if off < 1e-15 * (a[0][0].abs() + a[1][1].abs() + a[2][2].abs() + 1e-300) {
            break;
        }
        for (p, q) in [(0usize, 1usize), (0, 2), (1, 2)] {
            if a[p][q].abs() < 1e-300 {
                continue;
            }
            let theta = (a[q][q] - a[p][p]) / (2.0 * a[p][q]);
            let t = theta.signum() / (theta.abs() + (theta * theta + 1.0).sqrt());
            let c = 1.0 / (t * t + 1.0).sqrt();
            let s = t * c;
            // A' = Jᵀ A J for the rotation J in the (p, q) plane.
            for k in 0..3 {
                let akp = a[k][p];
                let akq = a[k][q];
                a[k][p] = c * akp - s * akq;
                a[k][q] = s * akp + c * akq;
            }
            for k in 0..3 {
                let apk = a[p][k];
                let aqk = a[q][k];
                a[p][k] = c * apk - s * aqk;
                a[q][k] = s * apk + c * aqk;
            }
            for row in v.iter_mut() {
                let vkp = row[p];
                let vkq = row[q];
                row[p] = c * vkp - s * vkq;
                row[q] = s * vkp + c * vkq;
            }
        }
    }
    let mut idx = [0usize, 1, 2];
    idx.sort_by(|&i, &j| a[j][j].total_cmp(&a[i][i]));
    let vals = [a[idx[0]][idx[0]], a[idx[1]][idx[1]], a[idx[2]][idx[2]]];
    let vecs = idx.map(|i| [v[0][i], v[1][i], v[2][i]]);
    (vals, vecs)
}

/// Mean and scatter (sum of outer products of the centred points) — the PCA
/// input. Returns `None` for an empty slice.
pub fn mean_scatter(pts: &[V3]) -> Option<(V3, M3)> {
    if pts.is_empty() {
        return None;
    }
    let n = pts.len() as f64;
    let mut mean = [0.0; 3];
    for p in pts {
        mean = add(mean, *p);
    }
    mean = scale(mean, 1.0 / n);
    let mut s = [[0.0; 3]; 3];
    for p in pts {
        let d = sub(*p, mean);
        for i in 0..3 {
            for j in 0..3 {
                s[i][j] += d[i] * d[j];
            }
        }
    }
    Some((mean, s))
}

/// Camera intrinsics in OpenCV's model: K = [fx 0 cx; 0 fy cy; 0 0 1] and
/// distortion (k1, k2, p1, p2, k3), expressed at `width`×`height`.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Intrinsics {
    pub fx: f64,
    pub fy: f64,
    pub cx: f64,
    pub cy: f64,
    pub dist: [f64; 5],
    pub width: f64,
    pub height: f64,
}

impl Intrinsics {
    /// A `w`×`h` frame has this calibration's aspect, so [`Self::for_frame`]
    /// scales it exactly.
    pub fn same_aspect(&self, w: u32, h: u32) -> bool {
        let a = w as f64 / h as f64;
        (a - self.width / self.height).abs() < 0.005 * a
    }

    /// These intrinsics re-expressed for a `w`×`h` frame.
    ///
    /// Camera2 scales every output stream of the same aspect ratio from the
    /// same sensor crop, so for a matching aspect the scaling is exact
    /// (pixel-centre convention). For a different aspect the crop is unknown,
    /// and we fall back to the square-pixel, centred guess the desktop prototype used.
    pub fn for_frame(&self, w: u32, h: u32) -> Intrinsics {
        let (w, h) = (w as f64, h as f64);
        if (w - self.width).abs() < 0.5 && (h - self.height).abs() < 0.5 {
            return *self;
        }
        if self.same_aspect(w as u32, h as u32) {
            let s = w / self.width;
            return Intrinsics {
                fx: self.fx * s,
                fy: self.fy * s,
                cx: (self.cx + 0.5) * s - 0.5,
                cy: (self.cy + 0.5) * s - 0.5,
                dist: self.dist,
                width: w,
                height: h,
            };
        }
        let s = w.max(h) / self.width.max(self.height);
        let f = 0.5 * (self.fx + self.fy) * s;
        Intrinsics { fx: f, fy: f, cx: w / 2.0, cy: h / 2.0, dist: self.dist, width: w, height: h }
    }

    /// The same camera model as `other` up to the rounding of a saved file:
    /// what a laser plane fitted with `other` needs to still hold.
    pub fn same_model(&self, other: &Intrinsics) -> bool {
        let o = other.for_frame(self.width as u32, self.height as u32);
        self.same_aspect(other.width as u32, other.height as u32)
            && (self.fx - o.fx).abs() <= 0.002 * self.fx
            && (self.fy - o.fy).abs() <= 0.002 * self.fy
            && (self.cx - o.cx).abs() <= 0.002 * self.width
            && (self.cy - o.cy).abs() <= 0.002 * self.height
            && self.dist.iter().zip(&o.dist).all(|(a, b)| (a - b).abs() <= 1e-3)
    }

    /// Project a camera-frame point (mm) to pixels. `None` behind the camera or
    /// so far off-axis that the polynomial distortion model folds back.
    pub fn project(&self, p: V3) -> Option<[f64; 2]> {
        if p[2] <= 1e-6 {
            return None;
        }
        let x = p[0] / p[2];
        let y = p[1] / p[2];
        let r2 = x * x + y * y;
        if r2 > 4.0 {
            return None;
        }
        let [k1, k2, p1, p2, k3] = self.dist;
        let radial = 1.0 + r2 * (k1 + r2 * (k2 + r2 * k3));
        let xd = x * radial + 2.0 * p1 * x * y + p2 * (r2 + 2.0 * x * x);
        let yd = y * radial + p1 * (r2 + 2.0 * y * y) + 2.0 * p2 * x * y;
        Some([self.fx * xd + self.cx, self.fy * yd + self.cy])
    }

    /// Undistorted normalised coordinates of a pixel — the same fixed-point
    /// iteration as `cv::undistortPoints` (more iterations than its default 5).
    pub fn undistort(&self, u: f64, v: f64) -> [f64; 2] {
        let x0 = (u - self.cx) / self.fx;
        let y0 = (v - self.cy) / self.fy;
        let [k1, k2, p1, p2, k3] = self.dist;
        if k1 == 0.0 && k2 == 0.0 && p1 == 0.0 && p2 == 0.0 && k3 == 0.0 {
            return [x0, y0];
        }
        let (mut x, mut y) = (x0, y0);
        for _ in 0..10 {
            let r2 = x * x + y * y;
            let icdist = 1.0 / (1.0 + r2 * (k1 + r2 * (k2 + r2 * k3)));
            let dx = 2.0 * p1 * x * y + p2 * (r2 + 2.0 * x * x);
            let dy = p1 * (r2 + 2.0 * y * y) + 2.0 * p2 * x * y;
            x = (x0 - dx) * icdist;
            y = (y0 - dy) * icdist;
        }
        [x, y]
    }

    /// Camera ray direction (z = 1) through a pixel.
    #[inline]
    pub fn ray(&self, u: f64, v: f64) -> V3 {
        let [x, y] = self.undistort(u, v);
        [x, y, 1.0]
    }
}

/// Board→camera rigid transform: `X_cam = R·X_board + t` (mm).
#[derive(Clone, Copy, Debug)]
pub struct Pose {
    pub r: M3,
    pub t: V3,
}

impl Pose {
    #[inline]
    pub fn to_cam(&self, p: V3) -> V3 {
        add(mat_vec(&self.r, p), self.t)
    }

    #[inline]
    pub fn to_board(&self, x: V3) -> V3 {
        mat_t_vec(&self.r, sub(x, self.t))
    }

    /// Camera centre in the board frame.
    pub fn camera_position(&self) -> V3 {
        scale(mat_t_vec(&self.r, self.t), -1.0)
    }

    /// The pose a fraction `s` of the way from `a` to `b`: the camera centre
    /// on the straight line, the orientation on the geodesic.
    pub fn lerp(a: &Pose, b: &Pose, s: f64) -> Pose {
        let r = slerp(&a.r, &b.r, s);
        let (ca, cb) = (a.camera_position(), b.camera_position());
        let c = add(ca, scale(sub(cb, ca), s));
        Pose { r, t: scale(mat_vec(&r, c), -1.0) }
    }

    /// The board's +Z axis in the camera frame (the board plane normal).
    pub fn board_normal(&self) -> V3 {
        [self.r[0][2], self.r[1][2], self.r[2][2]]
    }

    /// Sign of the "up" side of the board (toward the camera, where objects
    /// sit). OpenCV's board +Z points away from a viewer looking at the
    /// pattern, so up is −Z whenever `R[2][2] > 0`.
    pub fn up_sign(&self) -> f64 {
        if self.r[2][2] > 0.0 { -1.0 } else { 1.0 }
    }
}

/// The board frame turned 180° about its X axis and moved to `origin`:
/// +Z out of the printed face (toward the camera, where objects stand), +Y
/// up. OpenCV's board `p` is `FACE_OUT·p' + origin` with
/// `FACE_OUT = diag(1, −1, −1)`. Centred on the board it is orbiter-native's
/// world (`cvcore.estimate_pose`).
#[derive(Clone, Copy, Debug)]
pub struct FaceOutFrame {
    /// Where this frame's origin sits, in OpenCV board coordinates.
    pub origin: V3,
}

impl FaceOutFrame {
    pub fn centred(board_w_mm: f64, board_h_mm: f64) -> Self {
        FaceOutFrame { origin: [board_w_mm / 2.0, board_h_mm / 2.0, 0.0] }
    }

    /// Origin at the board's corner: the board covers [0, W] × [0, H] and the
    /// object stands on +Z over it (the exported scans' frame).
    pub fn cornered(board_h_mm: f64) -> Self {
        FaceOutFrame { origin: [0.0, board_h_mm, 0.0] }
    }

    /// An OpenCV board point in this frame.
    pub fn point(&self, p: V3) -> V3 {
        [p[0] - self.origin[0], self.origin[1] - p[1], self.origin[2] - p[2]]
    }

    /// The same board→camera pose against this frame: `R·FACE_OUT`,
    /// `t + R·origin`.
    pub fn pose(&self, pose: &Pose) -> Pose {
        let r = pose.r;
        Pose {
            r: [[r[0][0], -r[0][1], -r[0][2]], [r[1][0], -r[1][1], -r[1][2]], [r[2][0], -r[2][1], -r[2][2]]],
            t: add(pose.t, mat_vec(&r, self.origin)),
        }
    }
}

/// Intersect the ray `s·dir` (s > 0, from the camera centre) with the plane
/// `n·X = d`. Returns the hit point or `None` for a parallel / backward ray.
#[inline]
pub fn ray_plane(dir: V3, n: V3, d: f64) -> Option<V3> {
    let denom = dot(dir, n);
    if denom.abs() <= 1e-9 {
        return None;
    }
    let s = d / denom;
    (s > 1e-6).then(|| scale(dir, s))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn close(a: f64, b: f64, eps: f64) -> bool {
        (a - b).abs() <= eps
    }

    fn test_k() -> Intrinsics {
        Intrinsics {
            fx: 1400.0,
            fy: 1390.0,
            cx: 960.0,
            cy: 720.0,
            dist: [-0.08, 0.05, 0.001, -0.0005, 0.0],
            width: 1920.0,
            height: 1440.0,
        }
    }

    #[test]
    fn rodrigues_matches_axis_rotation() {
        let r = rodrigues([0.0, 0.0, std::f64::consts::FRAC_PI_2]);
        let x = mat_vec(&r, [1.0, 0.0, 0.0]);
        assert!(close(x[0], 0.0, 1e-12) && close(x[1], 1.0, 1e-12));
        assert!(close(rotation_distance(&IDENTITY, &r), std::f64::consts::FRAC_PI_2, 1e-12));
    }

    #[test]
    fn same_model_across_sizes_not_across_calibrations() {
        let k = test_k();
        assert!(k.same_model(&k.for_frame(640, 480)));
        assert!(k.for_frame(640, 480).same_model(&k));
        assert!(!k.same_model(&Intrinsics { fx: k.fx * 1.01, ..k }));
        assert!(!k.same_model(&Intrinsics { cx: 0.0, ..k }));
        assert!(!k.same_model(&Intrinsics { dist: [0.0; 5], ..k }));
        assert!(!k.same_model(&k.for_frame(1920, 1080)));
    }

    #[test]
    fn project_undistort_roundtrip() {
        let k = test_k();
        for &p in &[[10.0, -20.0, 300.0], [-120.0, 90.0, 250.0], [150.0, 140.0, 280.0]] {
            let px = k.project(p).unwrap();
            let ray = k.ray(px[0], px[1]);
            assert!(close(ray[0], p[0] / p[2], 1e-7), "{ray:?} vs {p:?}");
            assert!(close(ray[1], p[1] / p[2], 1e-7));
        }
    }

    #[test]
    fn scaling_same_aspect_is_exact() {
        let k = test_k();
        let half = k.for_frame(960, 720);
        let p = [33.0, -12.0, 400.0];
        let a = k.project(p).unwrap();
        let b = half.project(p).unwrap();
        // pixel-centre convention: (a + 0.5) / 2 - 0.5
        assert!(close((a[0] + 0.5) * 0.5 - 0.5, b[0], 1e-9));
        assert!(close((a[1] + 0.5) * 0.5 - 0.5, b[1], 1e-9));
    }

    #[test]
    fn eigen_recovers_principal_axes() {
        let pts: Vec<V3> = (0..200)
            .map(|i| {
                let t = i as f64 - 100.0;
                [t * 2.0, t * 0.5 + (i % 7) as f64 * 0.01, (i % 3) as f64 * 0.001]
            })
            .collect();
        let (_, s) = mean_scatter(&pts).unwrap();
        let (vals, vecs) = sym3_eigen(&s);
        assert!(vals[0] >= vals[1] && vals[1] >= vals[2]);
        let dir = normalize([2.0, 0.5, 0.0]).unwrap();
        assert!(dot(vecs[0], dir).abs() > 0.9999);
    }

    #[test]
    fn pose_roundtrip() {
        let pose = Pose { r: rodrigues([0.3, -0.2, 0.9]), t: [10.0, -5.0, 400.0] };
        let p = [12.0, 34.0, 0.0];
        let back = pose.to_board(pose.to_cam(p));
        assert!(norm(sub(back, p)) < 1e-9);
        let c = pose.camera_position();
        assert!(norm(pose.to_cam(c)) < 1e-9);
    }

    #[test]
    fn face_out_frame_keeps_the_camera_image() {
        let pose = Pose { r: rodrigues([2.9, 0.2, 0.4]), t: [-150.0, 120.0, 420.0] };
        let f = FaceOutFrame::centred(360.0, 270.0);
        let fp = f.pose(&pose);
        for p in [[0.0, 0.0, 0.0], [360.0, 270.0, 0.0], [100.0, 40.0, -55.0]] {
            // the same physical point lands on the same camera point
            assert!(norm(sub(pose.to_cam(p), fp.to_cam(f.point(p)))) < 1e-9);
        }
        // the board centre is the origin, the camera side of the board is +Z
        assert!(norm(f.point([180.0, 135.0, 0.0])) < 1e-12);
        assert!(f.point([0.0, 0.0, -10.0])[2] > 0.0);
        assert!(f.point([0.0, 0.0, 0.0])[1] > 0.0, "OpenCV's first row is the top edge: +Y");
        // a proper rotation, not a mirror
        let r = fp.r;
        let det = dot(r[0], cross(r[1], r[2]));
        assert!((det - 1.0).abs() < 1e-9);
    }

    #[test]
    fn quaternion_roundtrip_all_branches() {
        // trace > 0, and each of the three diagonal-dominant branches (≈180° turns)
        for r in [[0.3, -0.2, 0.9], [3.1, 0.1, 0.0], [0.05, 3.1, 0.1], [0.0, 0.1, 3.1], [0.0, 0.0, 0.0]] {
            let m = rodrigues(r);
            let q = quat_from_mat(&m);
            assert!(q[0] >= 0.0);
            assert!(close(q.iter().map(|c| c * c).sum::<f64>(), 1.0, 1e-12));
            assert!(rotation_distance(&m, &mat_from_quat(q)) < 1e-9, "{r:?}");
        }
        // COLMAP's convention: a turn by θ about +Z is (cos θ/2, 0, 0, sin θ/2)
        let q = quat_from_mat(&rodrigues([0.0, 0.0, 0.5]));
        assert!(close(q[0], 0.25f64.cos(), 1e-12) && close(q[3], 0.25f64.sin(), 1e-12));
    }

    #[test]
    fn pose_lerp_follows_centre_and_geodesic() {
        let a = Pose { r: rodrigues([0.1, 0.0, 0.0]), t: [0.0, 0.0, 300.0] };
        let b = Pose { r: rodrigues([0.1, 0.0, 0.6]), t: [40.0, -10.0, 320.0] };
        let m = Pose::lerp(&a, &b, 0.5);
        let mid = scale(add(a.camera_position(), b.camera_position()), 0.5);
        assert!(norm(sub(m.camera_position(), mid)) < 1e-9);
        let (da, db) = (rotation_distance(&a.r, &m.r), rotation_distance(&m.r, &b.r));
        assert!(close(da, db, 1e-9) && close(da + db, rotation_distance(&a.r, &b.r), 1e-9));
        assert!(norm(sub(Pose::lerp(&a, &b, 0.0).t, a.t)) < 1e-9);
        assert!(norm(sub(Pose::lerp(&a, &b, 1.0).t, b.t)) < 1e-9);
    }
}
