// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 TrioSlicer contributors.
//
//! JNI surface for the native filaSim session.
//!
//! Two handles keep the blocking work and the UI thread apart: the SESSION
//! handle owns the mesh/grid/solver state and is touched by one thread at a
//! time (Kotlin runs solve/optimize on a worker), while the CONTROL handle
//! carries only the cancellation flag and the progress snapshot, so the UI can
//! poll and cancel while a solve is running.

use std::collections::HashSet;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jbyteArray, jdouble, jfloatArray, jint, jintArray, jlong, jstring};
use jni::JNIEnv;

pub mod session;

use crate::session::{ProgressState, Session};

pub const NATIVE_VERSION: &str = env!("CARGO_PKG_VERSION");

/// Cancellation + progress, shareable with the UI thread while a solve runs.
pub struct Control {
    cancel: Arc<AtomicBool>,
    progress: Arc<Mutex<ProgressState>>,
}

impl Control {
    fn new(cancel: Arc<AtomicBool>, progress: Arc<Mutex<ProgressState>>) -> Self {
        Control { cancel, progress }
    }
}

fn throw(env: &mut JNIEnv, message: &str) {
    let _ = env.throw_new("java/lang/IllegalStateException", message);
}

/// Live native objects, keyed by the address the app holds.
///
/// JNI hands Kotlin a raw pointer, so a second `destroy*` would free the same
/// memory twice and a call made after a destroy would read it. Every entry point
/// asks the registry first: an address that was never created, or was already
/// destroyed, resolves to `None`, the shim throws, and the memory is untouched.
/// The free belongs only to the destroy that is still registered.
fn live() -> &'static Mutex<HashSet<usize>> {
    static LIVE: OnceLock<Mutex<HashSet<usize>>> = OnceLock::new();
    LIVE.get_or_init(|| Mutex::new(HashSet::new()))
}

fn register(handle: jlong) {
    if let Ok(mut set) = live().lock() {
        set.insert(handle as usize);
    }
}

/// True when the handle was live, which is what makes this destroy own the free.
fn unregister(handle: jlong) -> bool {
    if handle == 0 {
        return false;
    }
    live().lock().map(|mut set| set.remove(&(handle as usize))).unwrap_or(false)
}

fn is_live(handle: jlong) -> bool {
    handle != 0
        && live()
            .lock()
            .map(|set| set.contains(&(handle as usize)))
            .unwrap_or(false)
}

fn session_ref<'a>(handle: jlong) -> Option<&'a Session> {
    if is_live(handle) {
        Some(unsafe { &*(handle as *const Session) })
    } else {
        None
    }
}

fn session_mut<'a>(handle: jlong) -> Option<&'a mut Session> {
    if is_live(handle) {
        Some(unsafe { &mut *(handle as *mut Session) })
    } else {
        None
    }
}

fn control_ref<'a>(handle: jlong) -> Option<&'a Control> {
    if is_live(handle) {
        Some(unsafe { &*(handle as *const Control) })
    } else {
        None
    }
}

fn string(env: &mut JNIEnv, value: &str) -> jstring {
    match env.new_string(value) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn float_array(env: &mut JNIEnv, values: &[f32]) -> jfloatArray {
    match env.new_float_array(values.len() as i32) {
        Ok(array) => {
            if env.set_float_array_region(&array, 0, values).is_err() {
                return std::ptr::null_mut();
            }
            array.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

fn int_array(env: &mut JNIEnv, values: &[i32]) -> jintArray {
    match env.new_int_array(values.len() as i32) {
        Ok(array) => {
            if env.set_int_array_region(&array, 0, values).is_err() {
                return std::ptr::null_mut();
            }
            array.into_raw()
        }
        Err(_) => std::ptr::null_mut(),
    }
}

fn byte_array(env: &mut JNIEnv, values: &[u8]) -> jbyteArray {
    match env.byte_array_from_slice(values) {
        Ok(array) => array.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn json_arg(env: &mut JNIEnv, value: &JString) -> Result<String, String> {
    env.get_string(value)
        .map(|s| s.to_string_lossy().into_owned())
        .map_err(|e| e.to_string())
}

/// Library version, so the Kotlin side can assert it is talking to the build
/// it thinks it is.
#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_nativeVersion<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jstring {
    string(&mut env, NATIVE_VERSION)
}

#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_createSession<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    bytes: JByteArray<'local>,
    name: JString<'local>,
) -> jlong {
    let data = match env.convert_byte_array(&bytes) {
        Ok(d) => d,
        Err(e) => {
            throw(&mut env, &format!("unable to read the model bytes: {e}"));
            return 0;
        }
    };
    let name = match json_arg(&mut env, &name) {
        Ok(n) => n,
        Err(e) => {
            throw(&mut env, &format!("unable to read the model name: {e}"));
            return 0;
        }
    };
    match Session::new_from_bytes(&data, &name) {
        Ok(session) => {
            let handle = Box::into_raw(Box::new(session)) as jlong;
            register(handle);
            handle
        }
        Err(e) => {
            throw(&mut env, &e);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_createControl<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
) -> jlong {
    match session_ref(session) {
        Some(s) => {
            let control = Control::new(s.cancel_handle(), s.progress_handle());
            let handle = Box::into_raw(Box::new(control)) as jlong;
            register(handle);
            handle
        }
        None => {
            throw(&mut env, "the Smart Infill session is not open");
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_destroySession<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
) {
    if unregister(session) {
        unsafe { drop(Box::from_raw(session as *mut Session)) };
    }
}

#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_destroyControl<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    control: jlong,
) {
    if unregister(control) {
        unsafe { drop(Box::from_raw(control as *mut Control)) };
    }
}

#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_sessionInfo<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
) -> jstring {
    match session_mut(session) {
        Some(s) => match s.voxel_info() {
            Ok(json) => string(&mut env, &json),
            Err(e) => {
                throw(&mut env, &e);
                std::ptr::null_mut()
            }
        },
        None => {
            throw(&mut env, "the Smart Infill session is not open");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_originalPositions<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
) -> jfloatArray {
    match session_ref(session) {
        Some(s) => float_array(&mut env, &s.original_positions()),
        None => {
            throw(&mut env, "the Smart Infill session is not open");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_patchOfOriginalTriangle<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
) -> jintArray {
    match session_ref(session) {
        Some(s) => {
            let patches: Vec<i32> =
                s.patch_of_original_triangle().into_iter().map(|p| p as i32).collect();
            int_array(&mut env, &patches)
        }
        None => {
            throw(&mut env, "the Smart Infill session is not open");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_originalTrianglesOfPatch<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    patch: jint,
) -> jintArray {
    match session_ref(session) {
        Some(s) => {
            let tris: Vec<i32> = s
                .original_triangles_of_patch(patch as u32)
                .into_iter()
                .map(|t| t as i32)
                .collect();
            int_array(&mut env, &tris)
        }
        None => {
            throw(&mut env, "the Smart Infill session is not open");
            std::ptr::null_mut()
        }
    }
}

/// The connected part of the hit triangle's surface within `radius_mm` — what a
/// tap on the model selects. A patch would be the whole smooth hull on an
/// organic model, so the bounded region is what the UI picks with.
#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_regionAround<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    triangle: jint,
    radius_mm: jdouble,
) -> jintArray {
    match session_mut(session) {
        Some(s) => {
            let tris: Vec<i32> = s
                .region_around(triangle.max(0) as u32, radius_mm)
                .into_iter()
                .map(|t| t as i32)
                .collect();
            int_array(&mut env, &tris)
        }
        None => {
            throw(&mut env, "the Smart Infill session is not open");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_patchCount<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
) -> jint {
    match session_ref(session) {
        Some(s) => s.patch_count() as jint,
        None => {
            throw(&mut env, "the Smart Infill session is not open");
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_clearBoundaryConditions<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
) {
    match session_mut(session) {
        Some(s) => s.clear_bcs(),
        None => throw(&mut env, "the Smart Infill session is not open"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_addBoundaryCondition<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    json: JString<'local>,
) -> jint {
    let json = match json_arg(&mut env, &json) {
        Ok(j) => j,
        Err(e) => {
            throw(&mut env, &e);
            return -1;
        }
    };
    match session_mut(session) {
        Some(s) => match s.add_bc_json(&json) {
            Ok(()) => s.bc_count() as jint,
            Err(e) => {
                throw(&mut env, &e);
                -1
            }
        },
        None => {
            throw(&mut env, "the Smart Infill session is not open");
            -1
        }
    }
}


/// Material, resolution, acceleration and solver limits in one JSON payload —
/// fewer JNI signatures to keep in step with the Kotlin side.
#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_configure<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    json: JString<'local>,
) {
    let json = match json_arg(&mut env, &json) {
        Ok(j) => j,
        Err(e) => {
            throw(&mut env, &e);
            return;
        }
    };
    match session_mut(session) {
        Some(s) => {
            if let Err(e) = s.configure_json(&json) {
                throw(&mut env, &e);
            }
        }
        None => throw(&mut env, "the Smart Infill session is not open"),
    }
}

/// The settings the next run will use, so the Kotlin panel starts from the
/// engine's own defaults rather than a copy of them.
#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_configuration<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
) -> jstring {
    match session_ref(session) {
        Some(s) => string(&mut env, &s.effective_config()),
        None => {
            throw(&mut env, "the Smart Infill session is not open");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_checkSetup<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
) -> jstring {
    match session_mut(session) {
        Some(s) => match s.check() {
            Ok(json) => string(&mut env, &json),
            Err(e) => {
                throw(&mut env, &e);
                std::ptr::null_mut()
            }
        },
        None => {
            throw(&mut env, "the Smart Infill session is not open");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_solve<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
) -> jstring {
    match session_mut(session) {
        Some(s) => match s.solve() {
            Ok(json) => string(&mut env, &json),
            Err(e) => {
                throw(&mut env, &e);
                std::ptr::null_mut()
            }
        },
        None => {
            throw(&mut env, "the Smart Infill session is not open");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_optimize<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    options: JString<'local>,
) -> jstring {
    let options = match json_arg(&mut env, &options) {
        Ok(o) => o,
        Err(e) => {
            throw(&mut env, &e);
            return std::ptr::null_mut();
        }
    };
    match session_mut(session) {
        Some(s) => match s.optimize(&options) {
            Ok(json) => string(&mut env, &json),
            Err(e) => {
                throw(&mut env, &e);
                std::ptr::null_mut()
            }
        },
        None => {
            throw(&mut env, "the Smart Infill session is not open");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_progress<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    control: jlong,
) -> jstring {
    match control_ref(control) {
        Some(c) => {
            let json = c
                .progress
                .lock()
                .map(|s| s.to_json())
                .unwrap_or_else(|_| "{}".into());
            string(&mut env, &json)
        }
        None => string(&mut env, "{}"),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_cancel<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    control: jlong,
) {
    if let Some(c) = control_ref(control) {
        c.cancel.store(true, Ordering::Relaxed);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_regionCount<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
) -> jint {
    match session_ref(session) {
        Some(s) => s.region_count() as jint,
        None => {
            throw(&mut env, "the Smart Infill session is not open");
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_regionDensity<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    index: jint,
) -> jdouble {
    match session_ref(session) {
        Some(s) => s.region_density(index as u32),
        None => {
            throw(&mut env, "the Smart Infill session is not open");
            0.0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_regionPositions<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    index: jint,
) -> jfloatArray {
    match session_ref(session) {
        Some(s) => float_array(&mut env, &s.region_positions(index as u32)),
        None => {
            throw(&mut env, "the Smart Infill session is not open");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_regionIndices<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
    index: jint,
) -> jintArray {
    match session_ref(session) {
        Some(s) => {
            let indices: Vec<i32> =
                s.region_indices(index as u32).into_iter().map(|i| i as i32).collect();
            int_array(&mut env, &indices)
        }
        None => {
            throw(&mut env, "the Smart Infill session is not open");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_resultSummary<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
) -> jstring {
    match session_ref(session) {
        Some(s) => string(&mut env, &s.result_summary()),
        None => string(&mut env, "{}"),
    }
}

/// The density bin of the material under every ORIGINAL model triangle (-1 when
/// none), which is what the result view tints the part with. Empty before an
/// optimization.
#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_surfaceBins<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
) -> jintArray {
    match session_ref(session) {
        Some(s) => {
            let bins: Vec<i32> = s.surface_bins();
            int_array(&mut env, &bins)
        }
        None => {
            throw(&mut env, "the Smart Infill session is not open");
            std::ptr::null_mut()
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_isSolidMode<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
) -> jboolean {
    match session_ref(session) {
        Some(s) => u8::from(s.is_solid_mode()),
        None => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_exportModifierZip<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
) -> jbyteArray {
    match session_ref(session) {
        Some(s) => match s.export_modifier_zip() {
            Ok(bytes) => byte_array(&mut env, &bytes),
            Err(e) => {
                throw(&mut env, &e);
                std::ptr::null_mut()
            }
        },
        None => {
            throw(&mut env, "the Smart Infill session is not open");
            std::ptr::null_mut()
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_exportSolidStl<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    session: jlong,
) -> jbyteArray {
    match session_ref(session) {
        Some(s) => match s.export_solid_stl() {
            Ok(bytes) => byte_array(&mut env, &bytes),
            Err(e) => {
                throw(&mut env, &e);
                std::ptr::null_mut()
            }
        },
        None => {
            throw(&mut env, "the Smart Infill session is not open");
            std::ptr::null_mut()
        }
    }
}

/// Parity between the JNI exports here and the declarations in the app.
///
/// JNI resolves a native method by name alone: it never checks the signature, so
/// a parameter or return that drifted on one side only compiles on both and
/// crashes on a phone. This module reads the app's declaration file and compares
/// every entry point, argument by argument and return included.
#[cfg(test)]
mod jni_tests {
    use std::path::PathBuf;

    // The registry and the accessors this module tests live at the crate root.
    use super::*;

    /// Kotlin's spelling of each Rust JNI type.
    fn kotlin_type(rust: &str) -> Option<&'static str> {
        Some(match rust {
            "jlong" => "Long",
            "jint" => "Int",
            "jdouble" => "Double",
            "jfloat" => "Float",
            "jboolean" => "Boolean",
            "jstring" | "JString" => "String",
            "jintArray" => "IntArray",
            "jlongArray" => "LongArray",
            "jfloatArray" => "FloatArray",
            "jdoubleArray" => "DoubleArray",
            "jbyteArray" | "JByteArray" => "ByteArray",
            "()" => "Unit",
            _ => return None,
        })
    }

    fn without_generics(rust: &str) -> &str {
        let end = rust.find(['<', '\'']).unwrap_or(rust.len());
        rust[..end].trim()
    }

    /// Walks up from the crate to the app's declaration file: the same crate is
    /// built inside a prepared engine tree that has no app around it, and there
    /// the test skips instead of failing.
    fn kotlin_declarations() -> Option<String> {
        let tail = "app/src/main/java/com/tomppi/enderslicer/smartinfill/FilaSimNative.kt";
        let mut dir = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        loop {
            let candidate = dir.join(tail);
            if candidate.is_file() {
                return std::fs::read_to_string(candidate).ok();
            }
            match dir.parent() {
                Some(parent) => dir = parent.to_path_buf(),
                None => return None,
            }
        }
    }

    /// "external fun name(a: Type): Ret" -> (name, argument types, return type)
    fn parse_kotlin(text: &str) -> Vec<(String, Vec<String>, String)> {
        let mut functions = Vec::new();
        for line in text.lines() {
            let Some(rest) = line.trim().strip_prefix("external fun ") else {
                continue;
            };
            let (head, args) = rest.split_once('(').expect("a declaration has arguments");
            let (args, returns) = args.split_once(')').expect("a declaration closes them");
            let parameters = if args.trim().is_empty() {
                Vec::new()
            } else {
                args.split(',')
                    .map(|arg| {
                        arg.split_once(':')
                            .expect("a parameter has a type")
                            .1
                            .trim()
                            .to_string()
                    })
                    .collect()
            };
            // No return type is Kotlin's Unit.
            let returns = returns.trim().trim_start_matches(':').trim().to_string();
            let returns = if returns.is_empty() { "Unit".to_string() } else { returns };
            functions.push((head.trim().to_string(), parameters, returns));
        }
        functions
    }

    /// The JNI exports of this file, without the env/class parameters:
    /// (name, argument types, return type).
    fn parse_rust(text: &str) -> Vec<(String, Vec<String>, String)> {
        const PREFIX: &str = "fn Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_";
        // Everything below the test module is this parser itself, which contains
        // the prefix as a literal: stop before it.
        let text = text.split("#[cfg(test)]").next().unwrap_or(text);
        let mut functions = Vec::new();
        let mut rest = text;
        while let Some(at) = rest.find(PREFIX) {
            rest = &rest[at + PREFIX.len()..];
            let Some(open) = rest.find('(') else { break };
            // The name carries the function's own <'local> generic.
            let name = without_generics(&rest[..open]).to_string();
            let Some(close) = rest[open..].find(')') else { break };
            let params = &rest[open + 1..open + close];
            let after = &rest[open + close + 1..];
            rest = after;
            let declared: Vec<String> = params
                .split(',')
                .skip(2)
                .filter_map(|param| param.split_once(':'))
                .map(|(_, kind)| without_generics(kind).to_string())
                .collect();
            // The return type, when there is one, sits between the parameter list
            // and the body: searching further would read the next function's.
            let body = after.find('{').unwrap_or(after.len());
            let signature = &after[..body];
            let returns = match signature.find("->") {
                Some(arrow) => without_generics(signature[arrow + 2..].trim()).to_string(),
                None => "()".to_string(),
            };
            functions.push((name, declared, returns));
        }
        functions
    }


    /// A JNI handle is a raw address. The registry is what keeps a second
    /// destroy from freeing the same memory twice, and a call after a destroy
    /// from reading it.
    #[test]
    fn an_unknown_or_destroyed_handle_is_refused() {
        let handle = 0x1234 as jlong;
        assert!(session_mut(handle).is_none(), "never created");
        register(handle);
        assert!(unregister(handle), "the registered destroy owns the free");
        assert!(!unregister(handle), "a second destroy owns nothing");
        assert!(session_mut(handle).is_none(), "and is refused afterwards");
    }

    #[test]
    fn a_live_session_resolves_until_it_is_destroyed() {
        // One triangle, built the way the app's STL is: 80-byte header, count,
        // then 50 bytes per facet.
        let mut bytes = vec![0u8; 80];
        bytes.extend_from_slice(&1u32.to_le_bytes());
        // A facet is 12 floats (normal, then three vertices) plus 2 attribute bytes.
        for value in [0.0f32, 0.0, 0.0, 0.0, 0.0, 0.0, 10.0, 0.0, 0.0, 0.0, 10.0, 0.0] {
            bytes.extend_from_slice(&value.to_le_bytes());
        }
        bytes.extend_from_slice(&0u16.to_le_bytes());
        let session = Session::new_from_bytes(&bytes, "handle").expect("a session");
        let handle = Box::into_raw(Box::new(session)) as jlong;
        register(handle);
        assert!(session_ref(handle).is_some(), "a live handle resolves");
        assert!(session_mut(handle).is_some());
        assert!(unregister(handle));
        unsafe { drop(Box::from_raw(handle as *mut Session)) };
        assert!(!unregister(handle), "the pointer is only freed once");
        assert!(session_mut(handle).is_none(), "a destroyed handle is refused");
    }
    #[test]
    fn kotlin_and_rust_agree_on_every_jni_entry_point() {
        let Some(kotlin) = kotlin_declarations() else {
            eprintln!("no app next to the engine — JNI parity test skipped");
            return;
        };
        let rust = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/src/lib.rs"))
            .expect("the JNI shim is readable");
        let kotlin = parse_kotlin(&kotlin);
        let rust = parse_rust(&rust);
        assert!(kotlin.len() >= 25, "only {} declarations were read", kotlin.len());
        assert_eq!(
            rust.len(),
            kotlin.len(),
            "the app declares {} entry points and {} are exported",
            kotlin.len(),
            rust.len()
        );

        for (name, parameters, returns) in &kotlin {
            let Some((_, declared, exported)) = rust.iter().find(|(export, _, _)| export == name)
            else {
                panic!("{name} is declared in Kotlin but not exported");
            };
            assert_eq!(
                declared.len(),
                parameters.len(),
                "{name} takes {} arguments but is exported with {}",
                parameters.len(),
                declared.len()
            );
            for (index, (kotlin_name, rust_name)) in parameters.iter().zip(declared).enumerate() {
                let expected = kotlin_type(rust_name)
                    .unwrap_or_else(|| panic!("{name} argument {index}: unknown JNI type {rust_name}"));
                assert_eq!(
                    kotlin_name, expected,
                    "{name} argument {index} is {kotlin_name} in Kotlin and {rust_name} in Rust"
                );
            }
            let expected = kotlin_type(exported)
                .unwrap_or_else(|| panic!("{name} returns unknown JNI type {exported}"));
            assert_eq!(
                returns, expected,
                "{name} returns {returns} in Kotlin and {exported} in Rust"
            );
        }
    }
}