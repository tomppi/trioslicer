//! Laser-stripe detection straight from the camera's YUV_420_888 planes.
//!
//! Port of the desktop prototype's `detect_laser`: score saturated, bright pixels of the
//! laser HUE (not channel dominance, which collapses when the laser
//! oversaturates to magenta or lands on a coloured object), take the response
//! PEAK per scan line with a windowed sub-pixel centroid, and keep only long
//! continuous CHAINS of peaks, tried along both scan axes.
//!
//! The response is computed per pixel from YUV without a colour-space
//! conversion pass: with `R,G,B = Y + a(Cb, Cr)`, the HSV product `S·V/255`
//! the desktop prototype used is exactly the chroma `max − min` of the clamped RGB, and
//! the hue test reduces to integer comparisons. Only rows/columns inside the
//! region of interest are touched, and row bands run in parallel.
//!
//! That response is (up to clamping) a function of chroma alone, and chroma is
//! subsampled 2x2. Sampling it nearest-neighbour would quantise the stripe
//! centroid to 2-px blocks, so chroma is upsampled with libjpeg's "fancy"
//! triangle filter (centred siting, weights 9/3/3/1) — the same smooth profile
//! the desktop prototype got from its JPEG decoder.

use rayon::prelude::*;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LaserColor {
    /// Red, also oversaturated pink / magenta.
    Red,
    Green,
    /// Run both and keep whichever finds the longer stripe.
    Auto,
}

/// Detection tuning, in the desktop prototype's units. The pixel tolerances (peak
/// window, chain gaps) were tuned on a 1920-px-long frame and are scaled to
/// the actual frame by [`LaserDetector::detect`].
#[derive(Clone, Copy, Debug)]
pub struct LaserParams {
    pub color: LaserColor,
    /// Response floor (lower = more sensitive, for a dim laser).
    pub thresh: u8,
    /// Minimum HSV saturation of a laser pixel.
    pub min_sat: u8,
    /// Minimum summed response in a scan line's peak window.
    pub min_mass: f32,
    pub min_points: usize,
    pub max_points: usize,
    /// Adaptive threshold as a fraction of the frame's response peak.
    pub frac: f32,
}

impl Default for LaserParams {
    fn default() -> Self {
        Self {
            color: LaserColor::Red,
            thresh: 12,
            min_sat: 40,
            min_mass: 50.0,
            min_points: 10,
            max_points: 400,
            frac: 0.4,
        }
    }
}

/// A borrowed YUV_420_888 image as Camera2's `Image` exposes it: a full-size
/// luma plane and two half-size chroma planes with a shared row stride and a
/// pixel stride of 1 (planar) or 2 (semi-planar NV12/NV21).
#[derive(Clone, Copy)]
pub struct YuvFrame<'a> {
    pub width: usize,
    pub height: usize,
    pub y: &'a [u8],
    pub y_stride: usize,
    pub u: &'a [u8],
    pub v: &'a [u8],
    pub uv_stride: usize,
    pub uv_pixel_stride: usize,
}

impl YuvFrame<'_> {
    /// Full-range BT.601 (JFIF) RGB of one pixel.
    pub fn rgb_at(&self, x: usize, y: usize) -> [u8; 3] {
        let yv = self.y[y * self.y_stride + x] as i32;
        let ci = (y >> 1) * self.uv_stride + (x >> 1) * self.uv_pixel_stride;
        let (ar, ag, ab) = chroma_offsets(self.u[ci], self.v[ci]);
        [
            (yv + ar).clamp(0, 255) as u8,
            (yv + ag).clamp(0, 255) as u8,
            (yv + ab).clamp(0, 255) as u8,
        ]
    }
}

/// `(R−Y, G−Y, B−Y)` for one chroma sample, 8.8 fixed point, JFIF matrix.
#[inline(always)]
fn chroma_offsets(u: u8, v: u8) -> (i32, i32, i32) {
    chroma_offsets_16(u as i32 * 16, v as i32 * 16)
}

/// Same, from chroma in 1/16 units (the output of the 9/3/3/1 upsampler).
#[inline(always)]
fn chroma_offsets_16(u16: i32, v16: i32) -> (i32, i32, i32) {
    let cb = u16 - 128 * 16;
    let cr = v16 - 128 * 16;
    ((359 * cr + 2048) >> 12, -((88 * cb + 183 * cr + 2048) >> 12), (454 * cb + 2048) >> 12)
}

/// Laser response of one RGB pixel: the HSV chroma `max − min` when the pixel
/// has the laser hue and enough saturation, else 0. Hue/saturation bounds are
/// OpenCV's 8-bit HSV ones the desktop prototype used (red H ≤ 14 or ≥ 150, green
/// 40..=90, S ≥ `min_sat`), rewritten as divisions-free comparisons.
#[inline(always)]
fn response(r: i32, g: i32, b: i32, color: LaserColor, min_sat: i32) -> u8 {
    let mx = r.max(g).max(b);
    let mn = r.min(g).min(b);
    let c = mx - mn;
    // round(255·c/mx) < min_sat  ⇔  510·c < (2·min_sat − 1)·mx
    if c == 0 || (min_sat > 0 && 510 * c < (2 * min_sat - 1) * mx) {
        return 0;
    }
    // OpenCV's sector order: R wins ties, then G, then B.
    let ok = match color {
        LaserColor::Red | LaserColor::Auto => {
            if r == mx {
                // hue = 60·(g−b)/c, or that + 360 when g < b (always ≥ 300°)
                g < b || 60 * (g - b) < 29 * c
            } else if g == mx {
                false
            } else {
                // hue = 240 + 60·(r−g)/c ≥ 299°
                60 * (r - g) >= 59 * c
            }
        }
        LaserColor::Green => {
            if r == mx {
                false
            } else if g == mx {
                // hue = 120 + 60·(b−r)/c ≥ 79°
                60 * (b - r) >= -41 * c
            } else {
                // hue = 240 + 60·(r−g)/c < 181°
                60 * (r - g) < -59 * c
            }
        }
    };
    if ok { c as u8 } else { 0 }
}

/// The image region to search: one `[x0, x1)` span per row from `y0`.
/// Rows outside `y0..y0 + spans.len()` and pixels outside a row's span are
/// never touched (treated as zero response).
#[derive(Clone, Debug, Default)]
pub struct Roi {
    pub y0: usize,
    pub spans: Vec<(u32, u32)>,
}

impl Roi {
    pub fn full(width: usize, height: usize) -> Roi {
        Roi { y0: 0, spans: vec![(0, width as u32); height] }
    }

    pub fn y1(&self) -> usize {
        self.y0 + self.spans.len()
    }

    /// Number of pixels inside the region.
    pub fn area(&self) -> usize {
        self.spans.iter().map(|&(a, b)| b.saturating_sub(a) as usize).sum()
    }
}

/// Pixel tolerances scaled from the desktop prototype's 1920-px reference.
#[derive(Clone, Copy, Debug)]
struct Tolerances {
    peak_win: usize,
    max_gap: f32,
    dy_step: f32,
    min_chain: usize,
    min_mass: f32,
}

impl Tolerances {
    fn for_frame(w: usize, h: usize, p: &LaserParams) -> Self {
        let s = (w.max(h) as f32 / 1920.0).max(0.1);
        Self {
            peak_win: ((10.0 * s).round() as usize).max(2),
            max_gap: 28.0 * s,
            dy_step: 10.0 * s,
            min_chain: ((15.0 * s).round() as usize).max(p.min_points),
            min_mass: p.min_mass * s,
        }
    }
}

/// What the last detection saw, for the HUD.
#[derive(Clone, Copy, Debug, Default)]
pub struct LaserStats {
    /// Highest response in the region (0..255).
    pub peak: u8,
    /// Pixels above the adaptive level.
    pub above_level: u32,
    /// Pixels scored.
    pub area: u32,
}

#[derive(Default)]
struct Band {
    col_val: Vec<u8>,
    col_row: Vec<u32>,
    hist: Vec<u32>,
    /// One luma row's vertically interpolated chroma, ×4.
    u4: Vec<u16>,
    v4: Vec<u16>,
}

/// Reusable detector: owns the response map and per-band scratch so a frame
/// allocates nothing once warmed up.
#[derive(Default)]
pub struct LaserDetector {
    resp: Vec<u8>,
    row_cen: Vec<f32>,
    bands: Vec<Band>,
    col_val: Vec<u8>,
    col_row: Vec<u32>,
    col_pts: Vec<[f32; 2]>,
    row_pts: Vec<[f32; 2]>,
    pub stats: LaserStats,
}

impl LaserDetector {
    /// Find the stripe centre-line. Fills `out` with sub-pixel points ordered
    /// along the stripe and returns true, or clears it and returns false when
    /// no stripe is present.
    pub fn detect(&mut self, f: &YuvFrame, roi: &Roi, p: &LaserParams, out: &mut Vec<[f32; 2]>) -> bool {
        out.clear();
        if p.color == LaserColor::Auto {
            let mut red = Vec::new();
            let mut green = Vec::new();
            let r = self.detect_color(f, roi, p, LaserColor::Red, &mut red);
            let red_stats = self.stats;
            let g = self.detect_color(f, roi, p, LaserColor::Green, &mut green);
            if r && (!g || red.len() >= green.len()) {
                self.stats = red_stats;
                *out = red;
                return true;
            }
            if g {
                *out = green;
                return true;
            }
            return false;
        }
        self.detect_color(f, roi, p, p.color, out)
    }

    fn detect_color(
        &mut self,
        f: &YuvFrame,
        roi: &Roi,
        p: &LaserParams,
        color: LaserColor,
        out: &mut Vec<[f32; 2]>,
    ) -> bool {
        let (w, h) = (f.width, f.height);
        let tol = Tolerances::for_frame(w, h, p);
        let y0 = roi.y0.min(h);
        let y1 = roi.y1().min(h);
        self.stats = LaserStats { area: roi.area() as u32, ..Default::default() };
        if y1 <= y0 {
            return false;
        }

        self.resp.resize(w * h, 0);
        self.row_cen.resize(h, f32::NAN);
        let rows = y1 - y0;
        let n_bands = (rayon::current_num_threads() * 2).min(rows.div_ceil(16)).max(1);
        let band_rows = rows.div_ceil(n_bands);
        if self.bands.len() < n_bands {
            self.bands.resize_with(n_bands, Band::default);
        }
        let min_sat = p.min_sat as i32;
        let win = tol.peak_win;
        let min_mass = tol.min_mass;
        let cw = (w / 2).max(1);
        let ch = (h / 2).max(1);

        // ── pass 1 (parallel by row bands): response map, per-band column
        //    peaks, histogram, and the row-axis centroids. ──
        self.resp[y0 * w..y1 * w]
            .par_chunks_mut(band_rows * w)
            .zip(self.row_cen[y0..y1].par_chunks_mut(band_rows))
            .zip(self.bands[..n_bands].par_iter_mut())
            .enumerate()
            .for_each(|(bi, ((resp_band, cen_band), band))| {
                band.col_val.clear();
                band.col_val.resize(w, 0);
                band.col_row.clear();
                band.col_row.resize(w, 0);
                band.hist.clear();
                band.hist.resize(256, 0);
                band.u4.resize(cw, 0);
                band.v4.resize(cw, 0);
                let by0 = y0 + bi * band_rows;
                let ps = f.uv_pixel_stride;
                for (ri, (row_out, cen)) in resp_band.chunks_mut(w).zip(cen_band.iter_mut()).enumerate() {
                    let y = by0 + ri;
                    let (sx0, sx1) = roi.spans[y - roi.y0];
                    let (sx0, sx1) = ((sx0 as usize).min(w), (sx1 as usize).min(w));
                    row_out.fill(0);
                    *cen = f32::NAN;
                    if sx1 <= sx0 {
                        continue;
                    }
                    let y_row = &f.y[y * f.y_stride..y * f.y_stride + w];
                    // vertical half of the triangle filter: 3/4 own chroma row,
                    // 1/4 the neighbour on this luma row's side
                    let cy = (y >> 1).min(ch - 1);
                    let cyn = if y & 1 == 0 { cy.saturating_sub(1) } else { (cy + 1).min(ch - 1) };
                    let (r0, r1) = (cy * f.uv_stride, cyn * f.uv_stride);
                    let c0 = (sx0 >> 1).saturating_sub(1);
                    let c1 = (((sx1 - 1) >> 1) + 1).min(cw - 1);
                    for cx in c0..=c1 {
                        let o = cx * ps;
                        band.u4[cx] = 3 * f.u[r0 + o] as u16 + f.u[r1 + o] as u16;
                        band.v4[cx] = 3 * f.v[r0 + o] as u16 + f.v[r1 + o] as u16;
                    }
                    let (mut best, mut best_x) = (0u8, 0usize);
                    for x in sx0..sx1 {
                        let cx = (x >> 1).min(cw - 1);
                        let nx = if x & 1 == 0 { cx.saturating_sub(1) } else { (cx + 1).min(cw - 1) };
                        let u16 = 3 * band.u4[cx] as i32 + band.u4[nx] as i32;
                        let v16 = 3 * band.v4[cx] as i32 + band.v4[nx] as i32;
                        let (ar, ag, ab) = chroma_offsets_16(u16, v16);
                        let yv = y_row[x] as i32;
                        let v = response(
                            (yv + ar).clamp(0, 255),
                            (yv + ag).clamp(0, 255),
                            (yv + ab).clamp(0, 255),
                            color,
                            min_sat,
                        );
                        if v != 0 {
                            row_out[x] = v;
                            band.hist[v as usize] += 1;
                            if v > best {
                                best = v;
                                best_x = x;
                            }
                            if v > band.col_val[x] {
                                band.col_val[x] = v;
                                band.col_row[x] = y as u32;
                            }
                        }
                    }
                    if best == 0 {
                        continue;
                    }
                    // windowed centroid around the row's peak
                    let a = best_x.saturating_sub(win).max(sx0);
                    let b = (best_x + win + 1).min(sx1);
                    let (mut mass, mut moment) = (0u32, 0u64);
                    for (xi, &rv) in row_out[a..b].iter().enumerate() {
                        mass += rv as u32;
                        moment += rv as u64 * (a + xi) as u64;
                    }
                    if mass as f32 > min_mass {
                        *cen = (moment as f64 / mass as f64) as f32;
                    }
                }
            });

        // ── merge bands: histogram + column peaks (topmost wins ties) ──
        let mut hist = [0u32; 256];
        self.col_val.clear();
        self.col_val.resize(w, 0);
        self.col_row.clear();
        self.col_row.resize(w, 0);
        for band in &self.bands[..n_bands] {
            for (hv, bv) in hist.iter_mut().zip(&band.hist) {
                *hv += *bv;
            }
            for x in 0..w {
                if band.col_val[x] > self.col_val[x] {
                    self.col_val[x] = band.col_val[x];
                    self.col_row[x] = band.col_row[x];
                }
            }
        }
        let peak = (1..256).rev().find(|&i| hist[i] > 0).unwrap_or(0);
        self.stats.peak = peak as u8;
        if (peak as f32) < (p.thresh as f32).max(1.0) {
            return false;
        }
        let level = (p.thresh as f32).max(p.frac * peak as f32);
        let above: u32 = hist[(level.floor() as usize + 1).min(256)..].iter().sum();
        self.stats.above_level = above;
        if (above as usize) < p.min_points {
            return false;
        }

        // ── pass 2: column-axis centroids (the stripe runs left-right) ──
        self.col_pts.clear();
        for x in 0..w {
            if self.col_val[x] == 0 {
                continue;
            }
            let r = self.col_row[x] as usize;
            let a = r.saturating_sub(win).max(y0);
            let b = (r + win + 1).min(y1);
            let (mut mass, mut moment) = (0u32, 0u64);
            for yy in a..b {
                let rv = self.resp[yy * w + x] as u32;
                mass += rv;
                moment += rv as u64 * yy as u64;
            }
            if mass as f32 > min_mass {
                self.col_pts.push([x as f32, (moment as f64 / mass as f64) as f32]);
            }
        }
        self.row_pts.clear();
        for y in y0..y1 {
            let c = self.row_cen[y];
            if !c.is_nan() {
                self.row_pts.push([c, y as f32]);
            }
        }

        // ── keep long chains on each axis; the laser's own axis wins ──
        let a = keep_chains(&mut self.col_pts, false, &tol, p.min_points);
        let b = keep_chains(&mut self.row_pts, true, &tol, p.min_points);
        let pts = match (a, b) {
            (false, false) => return false,
            (true, false) => &self.col_pts,
            (false, true) => &self.row_pts,
            (true, true) if self.col_pts.len() >= self.row_pts.len() => &self.col_pts,
            _ => &self.row_pts,
        };
        if pts.len() < p.min_points {
            return false;
        }
        let step = if pts.len() > p.max_points { pts.len() / p.max_points + 1 } else { 1 };
        out.extend(pts.iter().step_by(step));
        true
    }
}

/// Keep only points on LONG continuous chains along the scan axis, in place.
/// Consecutive peaks join a chain when their scan-axis gap ≤ `max_gap`
/// (bridges the board's white squares) and their centre moves by at most
/// `dy_step + 0.6·gap` (follows a curve, rejects a jump to a stray speck).
/// `rows` selects which coordinate is the scan axis. Returns whether at least
/// `min_points` survive.
fn keep_chains(pts: &mut Vec<[f32; 2]>, rows: bool, tol: &Tolerances, min_points: usize) -> bool {
    if pts.len() < min_points {
        pts.clear();
        return false;
    }
    let (ax, co) = if rows { (1, 0) } else { (0, 1) };
    let n = pts.len();
    let mut keep = vec![false; n];
    let mut start = 0;
    for i in 1..=n {
        let split = i == n || {
            let gap = pts[i][ax] - pts[i - 1][ax];
            let dy = (pts[i][co] - pts[i - 1][co]).abs();
            gap > tol.max_gap || dy > tol.dy_step + 0.6 * gap
        };
        if split {
            if i - start >= tol.min_chain {
                keep[start..i].fill(true);
            }
            start = i;
        }
    }
    let mut k = keep.iter();
    pts.retain(|_| *k.next().unwrap());
    pts.len() >= min_points
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Planar I420 test image built from an RGB painter. Chroma is the mean
    /// of each 2x2 block (centred siting), like a camera ISP produces.
    struct TestImage {
        w: usize,
        h: usize,
        y: Vec<u8>,
        u: Vec<u8>,
        v: Vec<u8>,
    }

    impl TestImage {
        fn new(w: usize, h: usize, paint: impl Fn(usize, usize) -> [u8; 3]) -> Self {
            let mut y = vec![0; w * h];
            let mut uf = vec![0.0f64; (w / 2) * (h / 2)];
            let mut vf = vec![0.0f64; (w / 2) * (h / 2)];
            for yy in 0..h {
                for xx in 0..w {
                    let [r, g, b] = paint(xx, yy).map(|c| c as f64);
                    y[yy * w + xx] = (0.299 * r + 0.587 * g + 0.114 * b).round().clamp(0.0, 255.0) as u8;
                    let ci = (yy / 2) * (w / 2) + xx / 2;
                    uf[ci] += 0.25 * (128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b);
                    vf[ci] += 0.25 * (128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b);
                }
            }
            let q = |c: &f64| c.round().clamp(0.0, 255.0) as u8;
            TestImage { w, h, y, u: uf.iter().map(q).collect(), v: vf.iter().map(q).collect() }
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

    /// Grey-ish background with a soft red stripe centred on `line(x)`.
    fn stripe_image(w: usize, h: usize, line: impl Fn(f64) -> f64) -> TestImage {
        TestImage::new(w, h, |x, y| {
            let d = (y as f64 - line(x as f64)).abs();
            let k = (-d * d / 2.0).exp();
            let bg = [90.0, 95.0, 92.0];
            let laser = [255.0, 30.0, 40.0];
            [0, 1, 2].map(|i| (bg[i] * (1.0 - k) + laser[i] * k).round() as u8)
        })
    }

    #[test]
    fn red_response_matches_hsv_definition() {
        // pure red: S = 255, V = 255 → S·V/255 = 255
        assert_eq!(response(255, 0, 0, LaserColor::Red, 40), 255);
        // magenta-ish oversaturated laser still counts as red
        assert!(response(255, 120, 230, LaserColor::Red, 40) > 0);
        // white / grey: no saturation
        assert_eq!(response(200, 200, 200, LaserColor::Red, 40), 0);
        // green object is not red, but is green
        assert_eq!(response(30, 200, 40, LaserColor::Red, 40), 0);
        assert!(response(30, 200, 40, LaserColor::Green, 40) > 0);
        // yellow (hue 60°) is outside the red band
        assert_eq!(response(220, 220, 20, LaserColor::Red, 40), 0);
    }

    #[test]
    fn finds_horizontal_stripe_with_subpixel_centre() {
        let img = stripe_image(640, 480, |x| 200.3 + 0.05 * x);
        let mut det = LaserDetector::default();
        let mut out = Vec::new();
        assert!(det.detect(&img.frame(), &Roi::full(640, 480), &LaserParams::default(), &mut out));
        assert!(out.len() >= 300, "only {} points", out.len());
        let worst = out
            .iter()
            .map(|p| (p[1] as f64 - (200.3 + 0.05 * p[0] as f64)).abs())
            .fold(0.0, f64::max);
        assert!(worst < 0.6, "worst centre error {worst} px");
    }

    #[test]
    fn finds_vertical_stripe_on_row_axis() {
        let img = TestImage::new(320, 480, |x, _y| {
            let d = (x as f64 - 150.0).abs();
            if d < 2.0 { [250, 20, 30] } else { [60, 60, 60] }
        });
        let mut det = LaserDetector::default();
        let mut out = Vec::new();
        assert!(det.detect(&img.frame(), &Roi::full(320, 480), &LaserParams::default(), &mut out));
        assert!(out.iter().all(|p| (p[0] - 150.0).abs() < 1.0));
        // 480 rows thinned to ≤ max_points (400) by stride 2
        assert!(out.len() >= 200 && out.len() <= 400, "{} points", out.len());
    }

    #[test]
    fn scattered_specks_are_rejected() {
        // isolated red dots on a regular lattice never form a chain
        let img = TestImage::new(400, 300, |x, y| {
            if x % 37 == 0 && y % 41 == 0 { [255, 0, 0] } else { [80, 80, 80] }
        });
        let mut det = LaserDetector::default();
        let mut out = Vec::new();
        assert!(!det.detect(&img.frame(), &Roi::full(400, 300), &LaserParams::default(), &mut out));
        assert!(out.is_empty());
    }

    #[test]
    fn roi_excludes_stripe_outside_region() {
        let img = stripe_image(320, 240, |_| 60.0);
        let roi = Roi { y0: 120, spans: vec![(0, 320); 120] };
        let mut det = LaserDetector::default();
        let mut out = Vec::new();
        assert!(!det.detect(&img.frame(), &roi, &LaserParams::default(), &mut out));
        let inside = Roi { y0: 30, spans: vec![(0, 320); 60] };
        assert!(det.detect(&img.frame(), &inside, &LaserParams::default(), &mut out));
    }

    #[test]
    fn semi_planar_chroma_layout_matches_planar() {
        let img = stripe_image(256, 128, |x| 50.0 + 0.1 * x);
        // interleave U/V like NV12 (pixel stride 2)
        let mut uv = Vec::with_capacity(img.u.len() * 2);
        for (u, v) in img.u.iter().zip(&img.v) {
            uv.push(*u);
            uv.push(*v);
        }
        let nv12 = YuvFrame {
            width: 256,
            height: 128,
            y: &img.y,
            y_stride: 256,
            u: &uv,
            v: &uv[1..],
            uv_stride: 256,
            uv_pixel_stride: 2,
        };
        let mut det = LaserDetector::default();
        let (mut a, mut b) = (Vec::new(), Vec::new());
        assert!(det.detect(&img.frame(), &Roi::full(256, 128), &LaserParams::default(), &mut a));
        assert!(det.detect(&nv12, &Roi::full(256, 128), &LaserParams::default(), &mut b));
        assert_eq!(a, b);
        assert_eq!(img.frame().rgb_at(10, 10), nv12.rgb_at(10, 10));
    }
}
