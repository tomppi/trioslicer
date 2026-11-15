//! EasyScanner native core and its JNI bridge to
//! `com.rawmechatronics.easyscanner.NativeLib`.
//!
//! The app owns one engine handle and calls every function on the same
//! processing thread. Camera planes arrive as direct `ByteBuffer`s and are
//! read in place; the result is written into a caller-owned `float[]`.

mod board;
mod calib;
mod engine;
mod frame_out;

#[cfg(test)]
mod e2e_tests;

use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::PathBuf;

use jni::JNIEnv;
use jni::objects::{ JByteBuffer, JClass, JFloatArray, JString, JDoubleArray };
use jni::sys::{jdouble, jint, jlong, jstring};
use scan_algo::laser::YuvFrame;

use engine::Engine;

/// Run `f`, turning an `Err` or a panic into a Java `RuntimeException`.
fn guard<'l, R>(env: &mut JNIEnv<'l>, fallback: R, f: impl FnOnce(&mut JNIEnv<'l>) -> Result<R, String>) -> R {
    let msg = match catch_unwind(AssertUnwindSafe(|| f(&mut *env))) {
        Ok(Ok(v)) => return v,
        Ok(Err(m)) => m,
        Err(p) => p
            .downcast_ref::<&str>()
            .map(|s| s.to_string())
            .or_else(|| p.downcast_ref::<String>().cloned())
            .unwrap_or_else(|| "native panic".into()),
    };
    log::error!("{msg}");
    let _ = env.throw_new("java/lang/RuntimeException", msg);
    fallback
}

/// # Safety
/// `handle` must come from `nativeCreate` and not yet be destroyed.
unsafe fn engine<'a>(handle: jlong) -> Result<&'a mut Engine, String> {
    unsafe { (handle as *mut Engine).as_mut() }.ok_or_else(|| "engine handle is null".to_string())
}

fn string_arg(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    if s.is_null() {
        return Ok(String::new());
    }
    env.get_string(s).map(Into::into).map_err(|e| e.to_string())
}

fn new_string(env: &mut JNIEnv, s: &str) -> Result<jstring, String> {
    env.new_string(s).map(|j| j.into_raw()).map_err(|e| e.to_string())
}

fn buffer<'b>(env: &mut JNIEnv, buf: &JByteBuffer) -> Result<&'b [u8], String> {
    let ptr = env.get_direct_buffer_address(buf).map_err(|e| e.to_string())?;
    let len = env.get_direct_buffer_capacity(buf).map_err(|e| e.to_string())?;
    // SAFETY: a direct buffer's memory stays valid while the Java caller holds
    // the Image it belongs to, i.e. for the duration of this call.
    Ok(unsafe { std::slice::from_raw_parts(ptr, len) })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_rawmechatronics_easyscanner_NativeLib_nativeCreate<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    files_dir: JString<'l>,
) -> jlong {
    #[cfg(target_os = "android")]
    android_logger::init_once(android_logger::Config::default().with_max_level(log::LevelFilter::Info).with_tag("EasyScanner"));
    guard(&mut env, 0, |env| {
        let dir = PathBuf::from(string_arg(env, &files_dir)?);
        let e = Engine::new(dir).map_err(|e| e.to_string())?;
        Ok(Box::into_raw(Box::new(e)) as jlong)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_rawmechatronics_easyscanner_NativeLib_nativeDestroy<'l>(
    _env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) {
    if handle != 0 {
        // SAFETY: the handle came from Box::into_raw in nativeCreate.
        drop(unsafe { Box::from_raw(handle as *mut Engine) });
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_rawmechatronics_easyscanner_NativeLib_nativeConfigure<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
    json: JString<'l>,
) {
    guard(&mut env, (), |env| {
        let json = string_arg(env, &json)?;
        unsafe { engine(handle) }?.configure(&json)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_rawmechatronics_easyscanner_NativeLib_nativeCommand<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
    code: jint,
    arg: JString<'l>,
) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let arg = string_arg(env, &arg)?;
        let msg = unsafe { engine(handle) }?.command(code, &arg);
        new_string(env, &msg)
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_rawmechatronics_easyscanner_NativeLib_nativeMessages<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
) -> jstring {
    guard(&mut env, std::ptr::null_mut(), |env| {
        let msg = unsafe { engine(handle) }?.messages();
        new_string(env, &msg)
    })
}

/// Process one YUV_420_888 frame with a camera pose and intrinsics supplied by an
/// external tracker (ARCore). `pose` is 16 row-major doubles, scan-frame -> camera
/// (R | t); `intrinsics` is [fx, fy, cx, cy, width, height, k1, k2, p1, p2, k3].
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_rawmechatronics_easyscanner_NativeLib_nativeProcessAr<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
    y: JByteBuffer<'l>,
    y_stride: jint,
    u: JByteBuffer<'l>,
    v: JByteBuffer<'l>,
    uv_stride: jint,
    uv_pixel_stride: jint,
    width: jint,
    height: jint,
    timestamp_ns: jlong,
    pose: JDoubleArray<'l>,
    intrinsics: JDoubleArray<'l>,
    up_sign: jdouble,
    out: JFloatArray<'l>,
) -> jint {
    guard(&mut env, 0, |env| {
        let (w, h) = (width as usize, height as usize);
        let (ys, uvs, ps) = (y_stride as usize, uv_stride as usize, uv_pixel_stride as usize);
        if w < 16 || h < 16 || ys < w || ps == 0 {
            return Err(format!("bad frame geometry {w}x{h} stride {ys} ps {ps}"));
        }
        if env.get_array_length(&pose).map_err(|e| e.to_string())? < 16 {
            return Err("pose needs 16 doubles".into());
        }
        if env.get_array_length(&intrinsics).map_err(|e| e.to_string())? < 11 {
            return Err("intrinsics needs 11 doubles".into());
        }
        let mut p = [0f64; 16];
        env.get_double_array_region(&pose, 0, &mut p).map_err(|e| e.to_string())?;
        let mut ik = [0f64; 11];
        env.get_double_array_region(&intrinsics, 0, &mut ik).map_err(|e| e.to_string())?;
        let (yb, ub, vb) = (buffer(env, &y)?, buffer(env, &u)?, buffer(env, &v)?);
        let uv_need = (h / 2 - 1) * uvs + (w / 2 - 1) * ps + 1;
        if yb.len() < (h - 1) * ys + w || ub.len() < uv_need || vb.len() < uv_need {
            return Err("image planes smaller than their geometry".into());
        }
        let camera_pose = scan_algo::geom::Pose {
            r: [[p[0], p[1], p[2]], [p[4], p[5], p[6]], [p[8], p[9], p[10]]],
            t: [p[3], p[7], p[11]],
        };
        let k = scan_algo::geom::Intrinsics {
            fx: ik[0],
            fy: ik[1],
            cx: ik[2],
            cy: ik[3],
            width: ik[4],
            height: ik[5],
            dist: [ik[6], ik[7], ik[8], ik[9], ik[10]],
        };
        let frame = YuvFrame {
            width: w,
            height: h,
            y: yb,
            y_stride: ys,
            u: ub,
            v: vb,
            uv_stride: uvs,
            uv_pixel_stride: ps,
        };
        let data = unsafe { engine(handle) }?
            .process_ar(&frame, timestamp_ns, camera_pose, k, up_sign)
            .map_err(|e| e.to_string())?;
        let cap = env.get_array_length(&out).map_err(|e| e.to_string())? as usize;
        if data.len() > cap {
            return Ok(-(data.len() as jint));
        }
        let n = data.len() as jint;
        env.set_float_array_region(&out, 0, data).map_err(|e| e.to_string())?;
        unsafe { engine(handle) }?.clear_events();
        Ok(n)
    })
}

/// Process one YUV_420_888 frame (`timestamp_ns`: its sensor timestamp).
/// Returns the number of floats written to `out`, or `-needed` when `out` is
/// too small (nothing written).
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_rawmechatronics_easyscanner_NativeLib_nativeProcess<'l>(
    mut env: JNIEnv<'l>,
    _cls: JClass<'l>,
    handle: jlong,
    y: JByteBuffer<'l>,
    y_stride: jint,
    u: JByteBuffer<'l>,
    v: JByteBuffer<'l>,
    uv_stride: jint,
    uv_pixel_stride: jint,
    width: jint,
    height: jint,
    timestamp_ns: jlong,
    out: JFloatArray<'l>,
) -> jint {
    guard(&mut env, 0, |env| {
        let (w, h) = (width as usize, height as usize);
        let (ys, uvs, ps) = (y_stride as usize, uv_stride as usize, uv_pixel_stride as usize);
        if w < 16 || h < 16 || ys < w || ps == 0 {
            return Err(format!("bad frame geometry {w}x{h} stride {ys} ps {ps}"));
        }
        let (yb, ub, vb) = (buffer(env, &y)?, buffer(env, &u)?, buffer(env, &v)?);
        let uv_need = (h / 2 - 1) * uvs + (w / 2 - 1) * ps + 1;
        if yb.len() < (h - 1) * ys + w || ub.len() < uv_need || vb.len() < uv_need {
            return Err("image planes smaller than their geometry".into());
        }
        let frame = YuvFrame { width: w, height: h, y: yb, y_stride: ys, u: ub, v: vb, uv_stride: uvs, uv_pixel_stride: ps };
        let data = unsafe { engine(handle) }?.process(&frame, timestamp_ns).map_err(|e| e.to_string())?;
        let cap = env.get_array_length(&out).map_err(|e| e.to_string())? as usize;
        if data.len() > cap {
            return Ok(-(data.len() as jint));
        }
        let n = data.len() as jint;
        env.set_float_array_region(&out, 0, data).map_err(|e| e.to_string())?;
        unsafe { engine(handle) }?.clear_events();
        Ok(n)
    })
}
