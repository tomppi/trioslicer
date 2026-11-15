//! EasyScanner's pure-Rust core: the laser stripe detector, camera geometry,
//! laser-plane fit, point cloud, overlay geometry, and the config and
//! calibration file formats.
//!
//! Everything here is independent of OpenCV and of Android so it can be unit
//! tested on the host. The OpenCV-backed parts (ChArUco detection, PnP,
//! intrinsics calibration) and the JNI bridge live in `easyscanner-core`.
//!
//! Units: millimetres for 3-D geometry, pixels for image coordinates. The
//! camera frame is OpenCV's (x right, y down, z forward).

pub mod cloud;
pub mod config;
pub mod focus;
pub mod geom;
pub mod keyframe;
pub mod laser;
pub mod overlay;
pub mod photo_session;
pub mod plane;
pub mod stripe;
pub mod volume;
