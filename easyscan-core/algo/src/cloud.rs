//! The accumulated scan: XYZ (board frame, mm) + RGB, exportable as a binary
//! little-endian PLY (exports use a Z-up frame over the board, see
//! [`FaceOutFrame::cornered`]).

use std::io::{self, Write};

use crate::geom::{FaceOutFrame, V3};

pub struct PointCloud {
    pub max_points: usize,
    xyz: Vec<[f32; 3]>,
    rgb: Vec<[u8; 3]>,
}

impl PointCloud {
    pub const BYTES_PER_POINT: usize = 3 * 4 + 3;

    pub fn new(max_points: usize) -> Self {
        PointCloud { max_points, xyz: Vec::new(), rgb: Vec::new() }
    }

    /// Add a point; returns false once the cloud is full.
    #[inline]
    pub fn push(&mut self, p: V3, rgb: [u8; 3]) -> bool {
        if self.xyz.len() >= self.max_points {
            return false;
        }
        self.xyz.push([p[0] as f32, p[1] as f32, p[2] as f32]);
        self.rgb.push(rgb);
        true
    }

    pub fn clear(&mut self) {
        self.xyz.clear();
        self.rgb.clear();
    }

    pub fn len(&self) -> usize {
        self.xyz.len()
    }

    pub fn is_empty(&self) -> bool {
        self.xyz.is_empty()
    }

    pub fn nbytes(&self) -> usize {
        self.len() * Self::BYTES_PER_POINT
    }

    /// Up to `n` points, strided over the whole cloud, for the live preview.
    pub fn sample(&self, n: usize, out: &mut Vec<[f32; 3]>) {
        out.clear();
        if self.xyz.is_empty() || n == 0 {
            return;
        }
        let step = if self.xyz.len() > n { self.xyz.len() / n + 1 } else { 1 };
        out.extend(self.xyz.iter().step_by(step));
    }

    /// Write the cloud as binary PLY (same layout the desktop prototype exported).
    pub fn write_ply(&self, w: &mut impl Write) -> io::Result<usize> {
        self.write_ply_with(w, |p| *p)
    }

    /// The same PLY with the points expressed in `frame`.
    pub fn write_ply_in(&self, w: &mut impl Write, frame: &FaceOutFrame) -> io::Result<usize> {
        self.write_ply_with(w, |p| frame.point([p[0] as f64, p[1] as f64, p[2] as f64]).map(|v| v as f32))
    }

    fn write_ply_with(&self, w: &mut impl Write, map: impl Fn(&[f32; 3]) -> [f32; 3]) -> io::Result<usize> {
        write!(
            w,
            "ply\nformat binary_little_endian 1.0\nelement vertex {}\n\
             property float x\nproperty float y\nproperty float z\n\
             property uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n",
            self.len()
        )?;
        let mut buf = Vec::with_capacity(64 * 1024);
        for (p, c) in self.xyz.iter().zip(&self.rgb) {
            for v in map(p) {
                buf.extend_from_slice(&v.to_le_bytes());
            }
            buf.extend_from_slice(c);
            if buf.len() >= 60 * 1024 {
                w.write_all(&buf)?;
                buf.clear();
            }
        }
        w.write_all(&buf)?;
        Ok(self.len())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ply_has_header_and_packed_records() {
        let mut c = PointCloud::new(10);
        c.push([1.0, 2.0, 3.0], [10, 20, 30]);
        c.push([-1.5, 0.0, 9.25], [255, 0, 1]);
        let mut out = Vec::new();
        assert_eq!(c.write_ply(&mut out).unwrap(), 2);
        let text = String::from_utf8_lossy(&out);
        let header_end = text.find("end_header\n").unwrap() + "end_header\n".len();
        assert!(text.starts_with("ply\nformat binary_little_endian 1.0\nelement vertex 2\n"));
        let body = &out[header_end..];
        assert_eq!(body.len(), 2 * PointCloud::BYTES_PER_POINT);
        assert_eq!(f32::from_le_bytes(body[0..4].try_into().unwrap()), 1.0);
        assert_eq!(&body[12..15], &[10, 20, 30]);
        assert_eq!(f32::from_le_bytes(body[23..27].try_into().unwrap()), 9.25);
    }

    #[test]
    fn ply_in_a_face_out_frame() {
        let mut c = PointCloud::new(10);
        c.push([10.0, 20.0, -3.0], [1, 2, 3]); // 3 mm above the board (camera side is -Z)
        let mut out = Vec::new();
        c.write_ply_in(&mut out, &FaceOutFrame::centred(100.0, 60.0)).unwrap();
        let body = &out[out.len() - PointCloud::BYTES_PER_POINT..];
        let f = |i: usize| f32::from_le_bytes(body[i..i + 4].try_into().unwrap());
        assert_eq!((f(0), f(4), f(8)), (-40.0, 10.0, 3.0));
    }

    #[test]
    fn ply_in_the_cornered_frame_stands_on_the_board() {
        let mut c = PointCloud::new(10);
        c.push([1.0, 2.0, -3.0], [10, 20, 30]); // 3 mm above the board (camera side is -Z)
        c.push([-1.5, 0.0, 9.25], [255, 0, 1]);
        let mut out = Vec::new();
        c.write_ply_in(&mut out, &FaceOutFrame::cornered(100.0)).unwrap();
        let body = &out[out.len() - 2 * PointCloud::BYTES_PER_POINT..];
        let f = |i: usize| f32::from_le_bytes(body[i..i + 4].try_into().unwrap());
        assert_eq!((f(0), f(4), f(8)), (1.0, 98.0, 3.0)); // Z up, Y from the far edge
        assert_eq!((f(15), f(19), f(23)), (-1.5, 100.0, -9.25));
    }

    #[test]
    fn capacity_and_sampling() {
        let mut c = PointCloud::new(5);
        for i in 0..8 {
            c.push([i as f64, 0.0, 0.0], [0; 3]);
        }
        assert_eq!(c.len(), 5);
        let mut s = Vec::new();
        c.sample(2, &mut s);
        assert!(s.len() <= 2 && !s.is_empty());
    }
}
