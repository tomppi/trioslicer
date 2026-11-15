//! The per-frame result handed to the app as one flat `float[]`: a fixed
//! header of numbers, then drawable layers `[kind, stride, count, data…]` in
//! frame pixel coordinates. `NativeCore.kt` mirrors these indices.

/// Header slots.
pub mod h {
    pub const FRAME_W: usize = 0;
    pub const FRAME_H: usize = 1;
    pub const DETECTED: usize = 2;
    pub const N_CORNERS: usize = 3;
    pub const CAM_X: usize = 4;
    pub const CAM_Y: usize = 5;
    pub const CAM_Z: usize = 6;
    pub const ROLL: usize = 7;
    pub const PITCH: usize = 8;
    pub const YAW: usize = 9;
    pub const DISTANCE: usize = 10;
    pub const REPROJ_RMS: usize = 11;
    pub const POSE_MARGIN: usize = 12;
    pub const FOCUS_PCT: usize = 13;
    pub const T_TOTAL_MS: usize = 14;
    pub const T_BOARD_MS: usize = 15;
    pub const T_LASER_MS: usize = 16;
    /// 0 off, 1 searching (nothing), 2 stripe found
    pub const LASER_STATE: usize = 17;
    pub const LASER_POINTS: usize = 18;
    pub const LASER_PEAK: usize = 19;
    pub const LASER_MEAN_X: usize = 20;
    pub const LASER_MEAN_Y: usize = 21;
    pub const LASER_ANGLE: usize = 22;
    pub const LASER_LENGTH: usize = 23;
    pub const LASER_PLANE_RMS: usize = 24;
    pub const CALIB_STATE: usize = 25;
    pub const CALIB_COUNT: usize = 26;
    pub const CALIB_TARGET: usize = 27;
    pub const CALIB_RMS: usize = 28;
    pub const PLANE_ACTIVE: usize = 29;
    pub const PLANE_LINES: usize = 30;
    pub const PLANE_TARGET: usize = 31;
    pub const PLANE_SPREAD: usize = 32;
    pub const PLANE_RMS: usize = 33;
    pub const PLANE_OK: usize = 34;
    pub const SCAN_ON: usize = 35;
    pub const CLOUD_POINTS: usize = 36;
    pub const CLOUD_MB: usize = 37;
    pub const IN_VOLUME: usize = 38;
    pub const K_CALIBRATED: usize = 39;
    pub const FX: usize = 40;
    pub const FY: usize = 41;
    pub const CX: usize = 42;
    pub const CY: usize = 43;
    /// bit flags, see `ev`
    pub const EVENTS: usize = 44;
    pub const LASER_ROI_FRAC: usize = 45;
    /// the camera calibration is collecting views (resolution must hold)
    pub const CALIB_ACTIVE: usize = 46;
    /// photo keyframes: see `photo_state`
    pub const PHOTO_STATE: usize = 47;
    pub const PHOTO_COUNT: usize = 48;
    /// camera speed over the last 200 ms: mm/s and deg/s
    pub const CAM_SPEED: usize = 49;
    pub const CAM_TURN: usize = 50;
    /// the serial the app gave the last photo session opened (open or closed)
    pub const PHOTO_SERIAL: usize = 51;
    /// a saved camera calibration taken at the current lens focus
    pub const K_OK: usize = 52;
    /// a laser plane is saved but was fitted with another camera model
    pub const PLANE_STALE: usize = 53;
    pub const N_MARKERS: usize = 54;
    pub const LEN: usize = 55;
}

/// One-shot event bits in `h::EVENTS`.
pub mod ev {
    pub const MESSAGES: u32 = 1;
    pub const INTRINSICS_SAVED: u32 = 2;
    pub const PLANE_SAVED: u32 = 4;
    /// take a photo keyframe now (the app requests a still)
    pub const PHOTO_DUE: u32 = 8;
}

/// Values of `h::PHOTO_STATE`.
pub mod photo_state {
    pub const OFF: f64 = 0.0;
    /// on, the next keyframe is not due yet
    pub const WAITING: f64 = 1.0;
    /// a still is requested and not back yet
    pub const IN_FLIGHT: f64 = 2.0;
    // due, but held:
    pub const HOLD_NO_BOARD: f64 = 3.0;
    pub const HOLD_POSE_RMS: f64 = 4.0;
    pub const HOLD_SETTLING: f64 = 5.0;
    pub const HOLD_MOVING: f64 = 6.0;
    pub const HOLD_FEW_CORNERS: f64 = 7.0;
    /// a session is open for the scan, without photos
    pub const NO_PHOTOS: f64 = 8.0;
}

/// Layer kinds.
pub mod layer {
    pub const CORNERS: f32 = 1.0;
    pub const GRID: f32 = 2.0;
    pub const OUTLINE: f32 = 3.0;
    pub const AXIS_X: f32 = 4.0;
    pub const AXIS_Y: f32 = 5.0;
    pub const AXIS_Z: f32 = 6.0;
    pub const VOLUME: f32 = 7.0;
    pub const CLOUD: f32 = 8.0;
    pub const LASER_RAW: f32 = 9.0;
    pub const LASER_IN: f32 = 10.0;
    pub const LASER_OUT: f32 = 11.0;
    pub const LASER_FIT: f32 = 12.0;
}

#[derive(Default)]
pub struct FrameOut {
    pub buf: Vec<f32>,
}

impl FrameOut {
    pub fn begin(&mut self) {
        self.buf.clear();
        self.buf.resize(h::LEN, f32::NAN);
    }

    #[inline]
    pub fn set(&mut self, idx: usize, v: f64) {
        self.buf[idx] = v as f32;
    }

    pub fn points(&mut self, kind: f32, pts: &[[f32; 2]]) {
        if pts.is_empty() {
            return;
        }
        self.buf.extend_from_slice(&[kind, 2.0, pts.len() as f32]);
        self.buf.extend(pts.iter().flatten());
    }

    pub fn segments(&mut self, kind: f32, segs: &[[f32; 4]]) {
        if segs.is_empty() {
            return;
        }
        self.buf.extend_from_slice(&[kind, 4.0, segs.len() as f32]);
        self.buf.extend(segs.iter().flatten());
    }
}
