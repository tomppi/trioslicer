// JNI entry point for training with Brush inside the Splat Proto app.
//
// Build with: cargo ndk -t arm64-v8a -P 29 build --release -p brush-jni
#![cfg(target_os = "android")]

use brush_process::config::TrainStreamConfig;
use brush_process::message::{ProcessMessage, TrainMessage};
use brush_process::{burn_init_setup, create_process_with_device, DataSource, ProcessDevice};
use jni::objects::{JObject, JString, JValue};
use jni::sys::{jdouble, jfloat, jint};
use jni::JNIEnv;
use tokio::sync::OnceCell;
use tokio_stream::StreamExt;

static DEVICE: OnceCell<ProcessDevice> = OnceCell::const_new();

fn read_string(env: &mut JNIEnv, value: &JString) -> String {
    env.get_string(value)
        .map(|s| s.into())
        .unwrap_or_default()
}

/// Sends one progress update to Kotlin. Returns false when the UI asks to cancel.
fn report(env: &mut JNIEnv, progress: &JObject, message: &ProcessMessage) -> bool {
    let (stage, iter) = match message {
        ProcessMessage::NewProcess => (0i32, 0i32),
        ProcessMessage::TrainMessage(TrainMessage::TrainStep { iter, .. }) => (1, *iter as i32),
        ProcessMessage::TrainMessage(TrainMessage::DoneTraining) => (2, 0),
        _ => return true,
    };
    match env.call_method(
        progress,
        "onProgress",
        "(II)Z",
        &[JValue::Int(stage), JValue::Int(iter)],
    ) {
        Ok(value) => value.z().unwrap_or(true),
        Err(_) => true,
    }
}


#[unsafe(no_mangle)]
pub extern "system" fn Java_com_tomppi_splatproto_BrushTrainer_nativeTrain(
    mut env: JNIEnv,
    _this: JObject,
    dataset_path: JString,
    total_steps: jint,
    refine_every: jint,
    max_resolution: jint,
    export_every: jint,
    max_splats: jint,
    sh_degree: jint,
    lr_mean: jdouble,
    lr_coeffs_dc: jdouble,
    lr_opac: jdouble,
    lr_scale: jdouble,
    growth_grad_threshold: jfloat,
    growth_select_fraction: jfloat,
    min_scale_factor: jfloat,
    ssim_weight: jfloat,
    growth_stop_iter: jint,
    output_path: JString,
    progress: JObject,
) -> jint {
    let dataset = read_string(&mut env, &dataset_path);
    let output = read_string(&mut env, &output_path);

    let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let mut config = TrainStreamConfig::default();
        config.train_config.total_train_iters = total_steps.max(1) as u32;
        config.train_config.refine_every = refine_every.max(1) as u32;
        config.load_config.max_resolution = max_resolution.max(64) as u32;
        config.process_config.export_every = export_every.max(1) as u32;
        config.process_config.export_path = output.clone();
        config.train_config.max_splats = max_splats.max(1000) as u32;
        config.model_config.sh_degree = sh_degree.clamp(0, 4) as u32;
        config.train_config.lr_mean = lr_mean.max(0.0) as f64;
        config.train_config.lr_coeffs_dc = lr_coeffs_dc.max(0.0) as f64;
        config.train_config.lr_opac = lr_opac.max(0.0) as f64;
        config.train_config.lr_scale = lr_scale.max(0.0) as f64;
        if growth_grad_threshold > 0.0 {
            config.train_config.growth_grad_threshold = growth_grad_threshold;
        }
        if growth_select_fraction > 0.0 {
            config.train_config.growth_select_fraction = growth_select_fraction.min(1.0);
        }
        if min_scale_factor >= 0.0 {
            config.train_config.min_scale_factor = min_scale_factor;
        }
        if ssim_weight >= 0.0 {
            config.train_config.ssim_weight = ssim_weight;
        }
        if growth_stop_iter > 0 {
            config.train_config.growth_stop_iter = growth_stop_iter as u32;
        }

        let runtime = tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
            .expect("failed to create tokio runtime");

        runtime.block_on(async {
            let device = DEVICE.get_or_init(burn_init_setup).await.clone();
            let mut process = create_process_with_device(
                DataSource::Path(dataset),
                device,
                async move |_| Some(config),
            );

            while let Some(message) = process.stream.next().await {
                match message {
                    Ok(message) => {
                        if !report(&mut env, &progress, &message) {
                            return 2i32; // cancelled by UI
                        }
                    }
                    Err(error) => {
                        eprintln!("brush training error: {error}");
                        return 1i32;
                    }
                }
            }
            0i32
        })
    }));

    outcome.unwrap_or(1)
}

// ---- Headless splat viewer: copied Brush core (render_splats + Camera), no UI ----

use std::sync::Mutex;

use brush_render::camera::Camera;
use brush_render::gaussian_splats::{SplatRenderMode, Splats, TextureMode, render_splats};
use brush_render::kernels::camera_model::CameraModel;
use brush_train::train::{BOUND_PERCENTILE, get_splat_bounds};
use glam::{Quat, UVec2, Vec2, Vec3};

struct ViewerState {
    splats: Splats,
    center: Vec3,
    radius: f32,
    distance: f32,
    position: Vec3,
    rotation: Quat,
    initial_distance: f32,
    initial_position: Vec3,
    initial_rotation: Quat,
    splat_scale: Option<f32>,
    background: Vec3,
}

static VIEWER: Mutex<Option<ViewerState>> = Mutex::new(None);

static VIEWER_RUNTIME: std::sync::OnceLock<tokio::runtime::Runtime> = std::sync::OnceLock::new();

fn block_on<F: std::future::Future>(future: F) -> F::Output {
    VIEWER_RUNTIME
        .get_or_init(|| {
            tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .expect("viewer runtime")
        })
        .block_on(future)
}

fn to_jstring(env: &mut JNIEnv, text: String) -> jni::sys::jstring {
    env.new_string(text)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_tomppi_splatproto_BrushRenderer_nativeLoad<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    path: JString<'local>,
) -> jni::sys::jboolean {
    let path: String = env.get_string(&path).map(|s| s.into()).unwrap_or_default();
    let loaded = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        block_on(async {
            let file = tokio::fs::File::open(&path).await.ok()?;
            let message = brush_serde::load_splat_from_ply(file, None).await.ok()?;
            let device = DEVICE.get_or_init(burn_init_setup).await.clone();
            let mode = message.meta.render_mode.unwrap_or(SplatRenderMode::Default);
            let splats = message.data.into_splats(&device, mode);
            let bounds = get_splat_bounds(splats.clone(), BOUND_PERCENTILE).await;
            let radius = bounds.median_size().max(1e-3);
            let distance = radius * 2.6;
            let position = bounds.center - Vec3::Z * distance;
            let mut slot = VIEWER.lock().unwrap();
            *slot = Some(ViewerState {
                splats,
                center: bounds.center,
                radius,
                distance,
                position,
                rotation: Quat::IDENTITY,
                initial_distance: distance,
                initial_position: position,
                initial_rotation: Quat::IDENTITY,
                splat_scale: None,
                background: Vec3::ZERO,
            });
            Some(())
        })
    }));
    matches!(loaded, Ok(Some(()))) as jni::sys::jboolean
}

/// Orbit/pan/zoom one step and render a frame. Drag deltas in radians, pan in screen fractions,
/// zoom as a distance multiplier, splat_scale <= 0 means renderer default. Returns packed RGBA8.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_tomppi_splatproto_BrushRenderer_nativeRender<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    width: jni::sys::jint,
    height: jni::sys::jint,
    delta_yaw: jni::sys::jfloat,
    delta_pitch: jni::sys::jfloat,
    pan_x: jni::sys::jfloat,
    pan_y: jni::sys::jfloat,
    zoom: jni::sys::jfloat,
    splat_scale: jni::sys::jfloat,
    bg_r: jni::sys::jfloat,
    bg_g: jni::sys::jfloat,
    bg_b: jni::sys::jfloat,
    buffer: jni::objects::JByteBuffer<'local>,
) -> jni::sys::jint {
    let w = width.max(16) as u32;
    let h = height.max(16) as u32;
    let required = (w * h * 4) as usize;
    let Ok(capacity) = env.get_direct_buffer_capacity(&buffer) else {
        return 0;
    };
    if capacity < required {
        return 0;
    }

    let pixels = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Option<Vec<u8>> {
        let prepared = {
            let mut slot = VIEWER.lock().unwrap();
            let state = slot.as_mut()?;
            if splat_scale > 0.0 {
                state.splat_scale = Some(splat_scale);
            } else {
                state.splat_scale = None;
            }
            state.background = Vec3::new(bg_r as f32, bg_g as f32, bg_b as f32);

            let focal_point = state.position + state.rotation * Vec3::Z * state.distance;
            let yaw = Quat::from_axis_angle(Vec3::NEG_Y, -delta_yaw as f32);
            let pitch = Quat::from_axis_angle(state.rotation * Vec3::X, -delta_pitch as f32);
            let rotation = (yaw * pitch * state.rotation).normalize();
            let mut focal = focal_point;
            if pan_x != 0.0 || pan_y != 0.0 {
                let right = rotation * Vec3::X;
                let up = rotation * Vec3::NEG_Y;
                let scale = state.radius * 2.0;
                focal += right * (pan_x as f32 * scale) + up * (pan_y as f32 * scale);
            }
            state.distance = (state.distance * zoom.max(0.2))
                .clamp(state.radius * 0.4, state.radius * 40.0);
            let position = focal - rotation * Vec3::Z * state.distance;
            state.position = position;
            state.rotation = rotation;
            (
                state.splats.clone(),
                position,
                rotation,
                state.splat_scale,
                state.background,
            )
        };
        let (splats, position, rotation, splat_scale, background) = prepared;
        block_on(async move {
            let camera = Camera::new(
                position,
                rotation,
                0.8,
                0.8,
                Vec2::new(0.5, 0.5),
                CameraModel::Pinhole,
            );
            let (image, _) = render_splats(
                splats,
                &camera,
                UVec2::new(w, h),
                background,
                splat_scale,
                TextureMode::Packed,
            )
            .await;
            let data = image.into_data_async().await.ok()?;
            Some(data.into_bytes().to_vec())
        })
    }));
    let Ok(Some(bytes)) = pixels else {
        return 0;
    };
    if bytes.len() != required {
        return 0;
    }
    let Ok(destination) = env.get_direct_buffer_address(&buffer) else {
        return 0;
    };
    unsafe {
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), destination, required);
    }
    required as jni::sys::jint
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_tomppi_splatproto_BrushRenderer_nativeResetView<'local>(
    _env: JNIEnv<'local>,
    _this: JObject<'local>,
) {
    let mut slot = VIEWER.lock().unwrap();
    if let Some(state) = slot.as_mut() {
        state.position = state.initial_position;
        state.rotation = state.initial_rotation;
        state.distance = state.initial_distance;
        state.splat_scale = None;
        state.background = Vec3::ZERO;
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_tomppi_splatproto_BrushRenderer_nativeViewerCenter<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jni::sys::jstring {
    let slot = VIEWER.lock().unwrap();
    let text = match slot.as_ref() {
        Some(state) => format!(
            "center={},{},{} radius={} distance={} splats={}",
            state.center.x, state.center.y, state.center.z, state.radius, state.distance,
            state.splats.num_splats()
        ),
        None => String::from("none"),
    };
    to_jstring(&mut env, text)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_tomppi_splatproto_BrushRenderer_nativeSetUpFlip<'local>(
    _env: JNIEnv<'local>,
    _this: JObject<'local>,
    flip: jni::sys::jboolean,
) {
    let mut slot = VIEWER.lock().unwrap();
    if let Some(state) = slot.as_mut() {
        state.initial_rotation = if flip != 0 {
            Quat::from_rotation_x(std::f32::consts::PI)
        } else {
            Quat::IDENTITY
        };
        state.rotation = state.initial_rotation;
        state.distance = state.initial_distance;
        state.position = state.center - state.initial_rotation * Vec3::Z * state.initial_distance;
        state.initial_position = state.position;
    }
}
