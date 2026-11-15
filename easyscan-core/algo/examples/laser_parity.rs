//! Parity harness against the desktop prototype's Python `detect_laser`.
//!
//! Reads a raw interleaved RGB image, converts it to YUV 4:2:0 the way a
//! camera ISP does (JFIF matrix, 2x2-averaged chroma), runs the detector on
//! the full frame and prints one `x y` line per stripe point.
//!
//! `cargo run --release -p scan-algo --example laser_parity -- in.rgb 960 720 > rust.txt`

use std::env;
use std::fs;

use scan_algo::laser::{LaserDetector, LaserParams, Roi, YuvFrame};

fn main() {
    let args: Vec<String> = env::args().collect();
    let (path, w, h) = (&args[1], args[2].parse::<usize>().unwrap(), args[3].parse::<usize>().unwrap());
    let rgb = fs::read(path).expect("read rgb");
    assert_eq!(rgb.len(), w * h * 3, "size mismatch");

    let mut y = vec![0u8; w * h];
    let (cw, ch) = (w / 2, h / 2);
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
    let u: Vec<u8> = uf.iter().map(q).collect();
    let v: Vec<u8> = vf.iter().map(q).collect();
    let frame = YuvFrame { width: w, height: h, y: &y, y_stride: w, u: &u, v: &v, uv_stride: cw, uv_pixel_stride: 1 };

    let mut det = LaserDetector::default();
    let mut pts = Vec::new();
    let found = det.detect(&frame, &Roi::full(w, h), &LaserParams::default(), &mut pts);
    eprintln!("found={found} points={} peak={}", pts.len(), det.stats.peak);
    for p in pts {
        println!("{} {}", p[0], p[1]);
    }
}
