//! Focus meter: variance of the 4-neighbour Laplacian (OpenCV's
//! `Laplacian(ksize=1)`) over the board region of the luma plane. A soft image
//! throws corner localisation and the pose; focus breathing also changes the
//! focal length. Sampled on a sparse grid so it costs ~0.1 ms at any size.

/// Laplacian variance over `[x0, x1) × [y0, y1)` of a luma plane, sampled so
/// that at most ~`max_samples` pixels are visited. `None` for a region too
/// small to measure.
pub fn laplacian_variance(
    y: &[u8],
    stride: usize,
    (w, h): (usize, usize),
    (x0, y0, x1, y1): (usize, usize, usize, usize),
    max_samples: usize,
) -> Option<f64> {
    let x0 = x0.max(1);
    let y0 = y0.max(1);
    let x1 = x1.min(w.saturating_sub(1));
    let y1 = y1.min(h.saturating_sub(1));
    if x1 < x0 + 8 || y1 < y0 + 8 {
        return None;
    }
    let area = (x1 - x0) * (y1 - y0);
    let step = ((area as f64 / max_samples.max(1) as f64).sqrt().floor() as usize).max(1);
    let (mut n, mut s, mut s2) = (0u64, 0i64, 0i64);
    for yy in (y0..y1).step_by(step) {
        let row = yy * stride;
        for xx in (x0..x1).step_by(step) {
            let c = y[row + xx] as i64;
            let l = y[row + xx - 1] as i64 + y[row + xx + 1] as i64 + y[row - stride + xx] as i64
                + y[row + stride + xx] as i64
                - 4 * c;
            n += 1;
            s += l;
            s2 += l * l;
        }
    }
    let n = n as f64;
    let mean = s as f64 / n;
    Some(s2 as f64 / n - mean * mean)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sharp_pattern_beats_blurred() {
        let (w, h) = (200, 200);
        let sharp: Vec<u8> = (0..w * h).map(|i| if ((i % w) / 4 + (i / w) / 4) % 2 == 0 { 230 } else { 20 }).collect();
        let mut soft = sharp.clone();
        for _ in 0..3 {
            let src = soft.clone();
            for y in 1..h - 1 {
                for x in 1..w - 1 {
                    let i = y * w + x;
                    let sum: u32 = [i - w - 1, i - w, i - w + 1, i - 1, i, i + 1, i + w - 1, i + w, i + w + 1]
                        .iter()
                        .map(|&j| src[j] as u32)
                        .sum();
                    soft[i] = (sum / 9) as u8;
                }
            }
        }
        let a = laplacian_variance(&sharp, w, (w, h), (10, 10, 190, 190), 10_000).unwrap();
        let b = laplacian_variance(&soft, w, (w, h), (10, 10, 190, 190), 10_000).unwrap();
        assert!(a > 4.0 * b, "sharp {a} soft {b}");
        assert!(laplacian_variance(&sharp, w, (w, h), (0, 0, 5, 5), 100).is_none());
    }
}
