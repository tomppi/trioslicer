//! Laser-plane calibration: accumulate laser-on-board stripe lines (camera
//! frame, mm) across board orientations and fit the plane `n·X = d` the
//! rigidly mounted line laser sweeps. Port of the desktop prototype's `laser_plane.py`.

use crate::geom::{M3, V3, add, cross, dot, mean_scatter, norm, scale, sub, sym3_eigen};

/// Small, fast, seedable PRNG (xorshift64*) for RANSAC sampling.
#[derive(Clone, Debug)]
pub struct Rng(u64);

impl Rng {
    pub fn new(seed: u64) -> Self {
        Rng(seed.max(1))
    }

    pub fn next_u64(&mut self) -> u64 {
        let mut x = self.0;
        x ^= x >> 12;
        x ^= x << 25;
        x ^= x >> 27;
        self.0 = x;
        x.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }

    /// Uniform in `0..n` (n > 0).
    pub fn below(&mut self, n: usize) -> usize {
        (((self.next_u64() >> 11) as u128 * n as u128) >> 53) as usize
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PlaneFit {
    /// Unit normal, oriented toward the camera (`n.z ≤ 0`).
    pub n: V3,
    pub d: f64,
    pub rms_mm: f64,
    pub inliers: usize,
}

/// RANSAC (3-point planes, most inliers within `tol_mm`), then a least-squares
/// refit on the inliers.
pub fn fit_plane_ransac(pts: &[V3], tol_mm: f64, iters: usize, rng: &mut Rng) -> Option<PlaneFit> {
    if pts.len() < 3 {
        return None;
    }
    let mut best_cnt = 0usize;
    let mut best: Option<(V3, V3)> = None;
    for _ in 0..iters {
        let a = pts[rng.below(pts.len())];
        let b = pts[rng.below(pts.len())];
        let c = pts[rng.below(pts.len())];
        let nrm = cross(sub(b, a), sub(c, a));
        let ln = norm(nrm);
        if ln < 1e-9 {
            continue;
        }
        let nrm = scale(nrm, 1.0 / ln);
        let cnt = pts.iter().filter(|&&p| dot(sub(p, a), nrm).abs() < tol_mm).count();
        if cnt > best_cnt {
            best_cnt = cnt;
            best = Some((a, nrm));
        }
    }
    let (a, nrm) = best?;
    if best_cnt < 3 {
        return None;
    }
    let inl: Vec<V3> = pts.iter().copied().filter(|&p| dot(sub(p, a), nrm).abs() < tol_mm).collect();
    let (centroid, s) = mean_scatter(&inl)?;
    let (_, vecs) = sym3_eigen(&s);
    let mut n = vecs[2];
    if n[2] > 0.0 {
        n = scale(n, -1.0);
    }
    let d = dot(n, centroid);
    let ss: f64 = inl.iter().map(|&p| dot(sub(p, centroid), n).powi(2)).sum();
    Some(PlaneFit { n, d, rms_mm: (ss / inl.len() as f64).sqrt(), inliers: best_cnt })
}

/// Collects stripe lines until there are enough DISTINCT orientations to pin
/// the plane down. One board pose dumps hundreds of collinear points yet
/// constrains the plane no better than two, so holding still must not make it
/// "ready": a frame only counts when its line differs (angle or offset) from
/// every line already held.
#[derive(Clone, Debug)]
pub struct PlaneCalibrator {
    pub min_lines: usize,
    pub max_points: usize,
    pub line_min_pts: usize,
    pub dup_angle_deg: f64,
    pub dup_offset_mm: f64,
    lines: Vec<(V3, V3)>,
    pts: Vec<V3>,
    // running sums for the O(1) spread
    sum: V3,
    sum_sq: M3,
    pub last_rms: Option<f64>,
    rng: Rng,
}

impl PlaneCalibrator {
    pub fn new(min_lines: usize) -> Self {
        PlaneCalibrator {
            min_lines,
            max_points: 20_000,
            line_min_pts: 8,
            dup_angle_deg: 4.0,
            dup_offset_mm: 8.0,
            lines: Vec::new(),
            pts: Vec::new(),
            sum: [0.0; 3],
            sum_sq: [[0.0; 3]; 3],
            last_rms: None,
            rng: Rng::new(0x9E37_79B9_7F4A_7C15),
        }
    }

    /// Add one frame's laser-on-board points. Returns the number of distinct
    /// lines held.
    pub fn add(&mut self, cam_pts: &[V3]) -> usize {
        if cam_pts.len() < self.line_min_pts {
            return self.lines.len();
        }
        let Some((cen, s)) = mean_scatter(cam_pts) else { return self.lines.len() };
        let (vals, vecs) = sym3_eigen(&s);
        if vals[0] < 1e-12 {
            return self.lines.len();
        }
        let dir = vecs[0];
        let cos_dup = self.dup_angle_deg.to_radians().cos();
        for &(d0, c0) in &self.lines {
            let cosang = dot(dir, d0).abs().min(1.0);
            let dc = sub(cen, c0);
            let off = norm(sub(dc, scale(d0, dot(dc, d0))));
            if cosang > cos_dup && off < self.dup_offset_mm {
                return self.lines.len();
            }
        }
        self.lines.push((dir, cen));
        for &p in cam_pts {
            self.sum = add(self.sum, p);
            for i in 0..3 {
                for j in 0..3 {
                    self.sum_sq[i][j] += p[i] * p[j];
                }
            }
        }
        self.pts.extend_from_slice(cam_pts);
        self.lines.len()
    }

    pub fn reset(&mut self) {
        self.lines.clear();
        self.pts.clear();
        self.sum = [0.0; 3];
        self.sum_sq = [[0.0; 3]; 3];
        self.last_rms = None;
    }

    pub fn n_lines(&self) -> usize {
        self.lines.len()
    }

    pub fn count(&self) -> usize {
        self.pts.len()
    }

    /// Ratio of the 2nd to 1st singular value of the collected cloud: ~0 when
    /// every line is parallel (degenerate), higher = real 2-D coverage.
    pub fn spread(&self) -> f64 {
        let n = self.pts.len() as f64;
        if n < 3.0 {
            return 0.0;
        }
        let mean = scale(self.sum, 1.0 / n);
        let mut s = self.sum_sq;
        for i in 0..3 {
            for j in 0..3 {
                s[i][j] -= n * mean[i] * mean[j];
            }
        }
        let (vals, _) = sym3_eigen(&s);
        if vals[0] > 1e-9 { (vals[1].max(0.0) / vals[0]).sqrt() } else { 0.0 }
    }

    /// Enough lines with real 2-D coverage; fit quality is gated in `solve`.
    pub fn ready(&self) -> bool {
        self.n_lines() >= self.min_lines && self.spread() >= 0.2
    }

    /// Fit + validate. `force` accepts the best fit even when it fails the
    /// quality gate (residual ≤ 6 mm, ≥ 40 % inliers).
    pub fn solve(&mut self, force: bool) -> Option<PlaneFit> {
        if self.n_lines() < 3 {
            return None;
        }
        let sample: Vec<V3> = if self.pts.len() > self.max_points {
            // partial Fisher–Yates: a uniform sample without replacement
            let mut idx: Vec<usize> = (0..self.pts.len()).collect();
            for i in 0..self.max_points {
                let j = i + self.rng.below(idx.len() - i);
                idx.swap(i, j);
            }
            idx[..self.max_points].iter().map(|&i| self.pts[i]).collect()
        } else {
            self.pts.clone()
        };
        let fit = fit_plane_ransac(&sample, 3.0, 250, &mut self.rng)?;
        self.last_rms = Some(fit.rms_mm);
        if !force && (fit.rms_mm > 6.0 || (fit.inliers as f64) < 0.4 * sample.len() as f64) {
            return None;
        }
        Some(fit)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::geom::normalize;

    fn plane_line(n: V3, d: f64, along: V3, origin_off: f64, rng: &mut Rng) -> Vec<V3> {
        // points on n·X = d, spread along `along` (projected into the plane)
        let along = normalize(sub(along, scale(n, dot(along, n)))).unwrap();
        let base = add(scale(n, d), scale(normalize(cross(n, along)).unwrap(), origin_off));
        (0..60)
            .map(|i| {
                let jitter = (rng.below(1000) as f64 / 1000.0 - 0.5) * 0.4;
                add(add(base, scale(along, i as f64 * 3.0 - 90.0)), scale(n, jitter))
            })
            .collect()
    }

    #[test]
    fn ransac_recovers_plane_with_outliers() {
        let n = normalize([0.86, -0.22, -0.45]).unwrap();
        let d = -165.0;
        let mut rng = Rng::new(7);
        let mut pts = Vec::new();
        for k in 0..6 {
            pts.extend(plane_line(n, d, [1.0, k as f64 * 0.4, 0.3], k as f64 * 15.0, &mut rng));
        }
        for i in 0..40 {
            pts.push([i as f64 * 7.0, 300.0 - i as f64, 500.0]);
        }
        let fit = fit_plane_ransac(&pts, 3.0, 250, &mut rng).unwrap();
        assert!(dot(fit.n, n).abs() > 0.9999, "{:?}", fit.n);
        assert!((fit.d.abs() - d.abs()).abs() < 0.5);
        assert!(fit.rms_mm < 0.3);
        assert!(fit.n[2] <= 0.0);
    }

    #[test]
    fn calibrator_counts_distinct_lines_only() {
        let n = normalize([0.8, -0.2, -0.5]).unwrap();
        let mut rng = Rng::new(3);
        let mut cal = PlaneCalibrator::new(5);
        let line = plane_line(n, -160.0, [1.0, 0.0, 0.2], 0.0, &mut rng);
        assert_eq!(cal.add(&line), 1);
        assert_eq!(cal.add(&line), 1, "duplicate orientation must not count");
        for k in 1..6 {
            cal.add(&plane_line(n, -160.0, [1.0, k as f64 * 0.5, 0.2], k as f64 * 20.0, &mut rng));
        }
        assert!(cal.n_lines() >= 5);
        assert!(cal.spread() > 0.2, "spread {}", cal.spread());
        assert!(cal.ready());
        let fit = cal.solve(false).unwrap();
        assert!(dot(fit.n, n).abs() > 0.999);
    }

    #[test]
    fn rng_below_stays_in_range() {
        let mut r = Rng::new(42);
        assert!((0..10_000).all(|_| r.below(7) < 7));
    }
}
