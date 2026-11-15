//! Runtime configuration (sent by the app as JSON) and the on-disk formats of
//! the two calibrations, kept compatible with the desktop prototype's
//! `live_intrinsics.json` / `laser_plane.json`.

use std::fs;
use std::path::Path;

use crate::geom::{Intrinsics, V3, normalize};
use crate::keyframe::KeyframeCfg;
use crate::laser::LaserColor;
use serde::{Deserialize, Serialize};

#[derive(Deserialize, Clone, Copy, Debug, PartialEq)]
#[serde(default)]
pub struct BoardSpec {
    pub squares_x: i32,
    pub squares_y: i32,
    pub square_mm: f32,
    pub marker_mm: f32,
    /// `cv::aruco::PredefinedDictionaryType` (5 = DICT_5X5_100).
    pub dict: i32,
}

impl Default for BoardSpec {
    /// The Orbiter board: 8x8, 36 mm squares, 26.64 mm markers, 5x5_100.
    fn default() -> Self {
        BoardSpec { squares_x: 8, squares_y: 8, square_mm: 36.0, marker_mm: 26.64, dict: 5 }
    }
}

impl BoardSpec {
    pub fn width_mm(&self) -> f64 {
        self.squares_x as f64 * self.square_mm as f64
    }

    pub fn height_mm(&self) -> f64 {
        self.squares_y as f64 * self.square_mm as f64
    }
}

#[derive(Deserialize, Clone, Debug)]
#[serde(default)]
pub struct LaserCfg {
    pub enabled: bool,
    /// "red" (also pink/magenta), "green" or "auto".
    pub color: String,
    pub thresh: u8,
    pub min_sat: u8,
    /// Search the whole frame for the stripe when no board pose is available
    /// (the working volume can't be placed). Shown, never triangulated.
    pub without_board: bool,
}

impl Default for LaserCfg {
    fn default() -> Self {
        LaserCfg { enabled: true, color: "red".into(), thresh: 12, min_sat: 40, without_board: true }
    }
}

impl LaserCfg {
    pub fn laser_color(&self) -> LaserColor {
        match self.color.as_str() {
            "green" => LaserColor::Green,
            "auto" => LaserColor::Auto,
            _ => LaserColor::Red,
        }
    }
}

#[derive(Deserialize, Clone, Copy, Debug)]
#[serde(default)]
pub struct VolumeCfg {
    /// Cube height above the board (mm); 0 = the board's longer side.
    pub height_mm: f64,
    pub margin_mm: f64,
}

impl Default for VolumeCfg {
    fn default() -> Self {
        VolumeCfg { height_mm: 0.0, margin_mm: 20.0 }
    }
}

#[derive(Deserialize, Clone, Copy, Debug, PartialEq)]
#[serde(default)]
pub struct DetectorCfg {
    /// OpenCV's ArUco3 fast candidate search. Off by default: it loses small
    /// boards outright (a 280-px board at 640x480: 0 corners vs 46), which is
    /// exactly what the resolution governor produces.
    pub aruco3: bool,
    /// Search near the last board position first, full frame on a miss.
    pub roi_tracking: bool,
}

impl Default for DetectorCfg {
    fn default() -> Self {
        DetectorCfg { aruco3: false, roi_tracking: true }
    }
}

#[derive(Deserialize, Clone, Copy, Debug)]
#[serde(default)]
pub struct DrawCfg {
    pub grid: bool,
    pub axes: bool,
    pub corners: bool,
    pub volume: bool,
    pub cloud: bool,
}

impl Default for DrawCfg {
    fn default() -> Self {
        DrawCfg { grid: true, axes: true, corners: true, volume: true, cloud: true }
    }
}

#[derive(Deserialize, Serialize, Clone, Copy, Debug)]
pub struct IntrinsicsJson {
    pub fx: f64,
    pub fy: f64,
    pub cx: f64,
    pub cy: f64,
    #[serde(default)]
    pub dist: [f64; 5],
    pub width: f64,
    pub height: f64,
}

impl From<IntrinsicsJson> for Intrinsics {
    fn from(j: IntrinsicsJson) -> Self {
        Intrinsics { fx: j.fx, fy: j.fy, cx: j.cx, cy: j.cy, dist: j.dist, width: j.width, height: j.height }
    }
}

impl From<Intrinsics> for IntrinsicsJson {
    fn from(k: Intrinsics) -> Self {
        IntrinsicsJson { fx: k.fx, fy: k.fy, cx: k.cx, cy: k.cy, dist: k.dist, width: k.width, height: k.height }
    }
}

#[derive(Deserialize, Clone, Debug, Default)]
#[serde(default)]
pub struct CameraCfg {
    pub id: String,
    /// Factory estimate from Camera2 characteristics, at the stream aspect.
    pub guess: Option<IntrinsicsJson>,
    /// Locked lens focus (diopters); `None` while autofocus runs.
    pub focus_diopters: Option<f64>,
}

#[derive(Deserialize, Clone, Debug)]
#[serde(default)]
pub struct Config {
    pub board: BoardSpec,
    pub laser: LaserCfg,
    pub volume: VolumeCfg,
    pub detector: DetectorCfg,
    pub draw: DrawCfg,
    pub camera: CameraCfg,
    pub keyframes: KeyframeCfg,
    pub calib_views: usize,
    pub plane_views: usize,
    pub max_cloud_points: usize,
}

impl Default for Config {
    fn default() -> Self {
        Config {
            board: BoardSpec::default(),
            laser: LaserCfg::default(),
            volume: VolumeCfg::default(),
            detector: DetectorCfg::default(),
            draw: DrawCfg::default(),
            camera: CameraCfg::default(),
            keyframes: KeyframeCfg::default(),
            calib_views: 64,
            plane_views: 10,
            max_cloud_points: 6_000_000,
        }
    }
}

pub const INTRINSICS_FILE: &str = "live_intrinsics.json";
pub const PLANE_FILE: &str = "laser_plane.json";

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct IntrinsicsFile {
    pub fx: f64,
    pub fy: f64,
    pub cx: f64,
    pub cy: f64,
    pub dist: Vec<f64>,
    pub calibrated: bool,
    pub width: u32,
    pub height: u32,
    #[serde(default)]
    pub camera_id: Option<String>,
    #[serde(default)]
    pub focus_diopters: Option<f64>,
    #[serde(default)]
    pub rms_px: Option<f64>,
}

impl IntrinsicsFile {
    pub fn intrinsics(&self) -> Intrinsics {
        let mut dist = [0.0; 5];
        for (d, s) in dist.iter_mut().zip(&self.dist) {
            *d = *s;
        }
        Intrinsics {
            fx: self.fx,
            fy: self.fy,
            cx: self.cx,
            cy: self.cy,
            dist,
            width: self.width as f64,
            height: self.height as f64,
        }
    }
}

/// A K that looks like a real phone camera rather than an overfit solve:
/// square-ish pixels, a near-centred principal point, small distortion. Low
/// RMS alone is not enough — an under-diverse solve fits the board plane but
/// skews everything off it (the cube top, the laser depth).
pub fn intrinsics_plausible(k: &Intrinsics) -> bool {
    let (w, h) = (k.width, k.height);
    k.fx > 0.0
        && k.fy > 0.0
        && w > 0.0
        && h > 0.0
        && (k.fx - k.fy).abs() / k.fx.max(k.fy) <= 0.06
        && (k.cx - w / 2.0).abs() <= 0.08 * w
        && (k.cy - h / 2.0).abs() <= 0.08 * h
        && k.dist[0].abs() <= 0.4
        && k.dist[1].abs() <= 1.2
}

/// The saved live calibration, if present, plausible and taken with this
/// camera.
pub fn load_intrinsics(dir: &Path, camera_id: &str) -> Option<IntrinsicsFile> {
    let text = fs::read_to_string(dir.join(INTRINSICS_FILE)).ok()?;
    let f: IntrinsicsFile = serde_json::from_str(&text).ok()?;
    if f.camera_id.as_deref().is_some_and(|id| id != camera_id) {
        log::warn!("{INTRINSICS_FILE} is for camera {:?}, not {camera_id}", f.camera_id);
        return None;
    }
    if !intrinsics_plausible(&f.intrinsics()) {
        log::warn!("{INTRINSICS_FILE} holds an implausible K — ignoring");
        return None;
    }
    Some(f)
}

pub fn save_intrinsics(dir: &Path, f: &IntrinsicsFile) -> std::io::Result<()> {
    fs::write(dir.join(INTRINSICS_FILE), serde_json::to_string_pretty(f)?)
}

#[derive(Serialize, Deserialize, Clone, Debug)]
pub struct PlaneFile {
    pub n: [f64; 3],
    pub d: f64,
    pub rms_mm: Option<f64>,
    pub n_points: Option<usize>,
    pub frame: String,
    /// The plane is in this camera's frame; absent in the desktop prototype's files.
    #[serde(default)]
    pub camera_id: Option<String>,
    /// The camera model the plane was fitted with: the stripe rays, so the
    /// plane, hold only for it. Absent in older files.
    #[serde(default)]
    pub k: Option<IntrinsicsJson>,
}

/// A saved laser plane `n·X = d` and the camera model it was fitted with.
#[derive(Clone, Copy, Debug)]
pub struct SavedPlane {
    pub n: V3,
    pub d: f64,
    pub k: Option<Intrinsics>,
}

impl SavedPlane {
    /// Usable with the camera model `k`: fitted with it, not just present.
    pub fn fits(&self, k: &Intrinsics) -> bool {
        self.k.is_some_and(|pk| k.same_model(&pk))
    }
}

/// The saved laser plane, if present and taken with this camera.
pub fn load_plane(dir: &Path, camera_id: &str) -> Option<SavedPlane> {
    let text = fs::read_to_string(dir.join(PLANE_FILE)).ok()?;
    let f: PlaneFile = serde_json::from_str(&text).ok()?;
    if f.camera_id.as_deref().is_some_and(|id| id != camera_id) {
        log::warn!("{PLANE_FILE} is for camera {:?}, not {camera_id}", f.camera_id);
        return None;
    }
    let n = normalize(f.n)?;
    let ln = crate::geom::norm(f.n);
    Some(SavedPlane { n, d: f.d / ln, k: f.k.map(Into::into) })
}

pub fn save_plane(dir: &Path, camera_id: &str, plane: &SavedPlane, rms_mm: f64, n_points: usize) -> std::io::Result<()> {
    let f = PlaneFile {
        n: plane.n,
        d: plane.d,
        rms_mm: Some(rms_mm),
        n_points: Some(n_points),
        frame: "opencv_camera_mm".into(),
        camera_id: Some(camera_id.into()),
        k: plane.k.map(Into::into),
    };
    fs::write(dir.join(PLANE_FILE), serde_json::to_string_pretty(&f)?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prototype_files_parse() {
        let k: IntrinsicsFile = serde_json::from_str(
            r#"{"fx":1335.08,"fy":1278.26,"cx":540.04,"cy":861.79,"dist":[-0.0152,0.0093,0.0,0.0,0.0],"calibrated":true,"width":1080,"height":1920}"#,
        )
        .unwrap();
        assert_eq!(k.width, 1080);
        // the desktop prototype's saved K passes the gate, though only just
        assert!(intrinsics_plausible(&k.intrinsics()));
        let p: PlaneFile = serde_json::from_str(
            r#"{"n":[0.8635,-0.2199,-0.4538],"d":-165.636,"rms_mm":0.758,"n_points":1856,"frame":"opencv_camera_mm"}"#,
        )
        .unwrap();
        assert_eq!(p.n_points, Some(1856));
        let cfg: Config = serde_json::from_str(r#"{"board":{"squares_x":10},"laser":{"color":"green"}}"#).unwrap();
        assert_eq!(cfg.board.squares_x, 10);
        assert_eq!(cfg.board.squares_y, 8);
        assert_eq!(cfg.laser.laser_color(), LaserColor::Green);
        assert_eq!(cfg.calib_views, 64);
    }

    #[test]
    fn plane_remembers_its_camera_model() {
        let dir = std::env::temp_dir().join(format!("es-plane-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let k = Intrinsics { fx: 490.0, fy: 490.0, cx: 320.0, cy: 240.0, dist: [0.0; 5], width: 640.0, height: 480.0 };
        let plane = SavedPlane { n: [0.0, 0.8, -0.6], d: -120.0, k: Some(k) };
        save_plane(&dir, "0", &plane, 0.4, 900).unwrap();
        let back = load_plane(&dir, "0").unwrap();
        assert!(back.fits(&k) && back.fits(&k.for_frame(1440, 1080)));
        assert!(!back.fits(&Intrinsics { cx: 0.0, ..k }), "a re-calibrated camera makes the plane stale");
        assert!(load_plane(&dir, "1").is_none());
        // the desktop prototype's file has no model: present, but not usable
        fs::write(dir.join(PLANE_FILE), r#"{"n":[0,0.8,-0.6],"d":-120,"rms_mm":0.7,"n_points":10,"frame":"opencv_camera_mm"}"#)
            .unwrap();
        assert!(!load_plane(&dir, "0").unwrap().fits(&k));
        fs::remove_dir_all(&dir).unwrap();
    }
}
