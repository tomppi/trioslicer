# native/blender/patches — GPU rendering in the embedded engine

The files here are **whole patched copies** of the corresponding files in the
epai `blender-android_arm64` source tree (same relative paths). They are the
complete set of changes that make Workbench and EEVEE render inside the engine
on the device, which the stock port cannot do. Copy them over a clean checkout
and rebuild.

Build environment is unchanged from `native/blender/README.md`: the harness tree
must be reachable at `D:\FreeProjects\...` (a **junction** to
`Test\epai-root\FreeProjects`, because `subst` cannot take `D:` when that
letter is a real volume), NDK 21.4.7075529, CMake 3.10.2, ninja.

## Why it did nothing before

The engine runs as `blender -b`. Anything that asks for a GPU context went:

```
DRW_render_context_enable -> WM_init_opengl -> wm_ghost_init_background
  -> GHOST_CreateSystemBackground -> createSystem(false, true, nullptr)
  -> new GHOST_SystemAndroid(nullptr)          <-- SIGSEGV, writing NULL+0x10
```

No EGL was ever reached. That single null dereference is what killed the app
"for no reason" on every GPU render, and because Blender installs its own
SIGSEGV handler and exits, Android never wrote a tombstone either.

With that fixed, the next four layers were each broken in turn. All five are
fixed below.

## The changes

### 1. `intern/ghost/intern/GHOST_SystemAndroid.cc` — null `android_app`

The constructor does `((struct android_app *)m_nativeWindow)->onInputEvent = ...`
unconditionally. Background mode passes `nullptr` as the native window, so this
writes to `NULL + 0x10` (the offset of `onInputEvent`). Guarded: with no
`android_app` there is no input queue to attach to.

### 2. `intern/ghost/intern/GHOST_ContextEGL.cc` — the config attribute list

- The ES branch had a comment saying `EGL_RENDERABLE_TYPE` is required and then
  never pushed it. Without it `eglChooseConfig` defaults to
  `EGL_OPENGL_ES_BIT` and returns no usable config. Now pushes
  `EGL_OPENGL_ES3_BIT_KHR`.
- `EGL_SURFACE_TYPE` was pushed twice when there was no native window (once as
  `EGL_WINDOW_BIT` from the branch, once as `EGL_PBUFFER_BIT` below), so
  `eglCreatePbufferSurface` ran against a window-only config. It is now chosen
  exactly once from `m_nativeWindow`.
- `[egl]` stage tracing on stderr, which is how the rest of this was found.

### 3. `GHOST_SystemAndroid.hh` / `GHOST_WindowAndroid.hh` / `GHOST_SystemHeadless.hh` — desktop GL requested on Android

All three asked for `EGL_OPENGL_API` and desktop GL 4.6→4.3, which no Android
EGL can bind. The rest of the port is already retargeted to GLSL ES 3.2
(`gl_shader.cc` forces `#version 320 es`), so all three now request
`EGL_OPENGL_ES_API` and an ES 3.2 context.

### 4. `source/blender/windowmanager/intern/wm_init_exit.cc` — the backend was never selected

`WM_init_opengl()` bails at `if (!GPU_backend_supported()) return;`, and nothing
on this path ever calls `GPU_backend_type_selection_detect()`, so the selection
is still `GPU_BACKEND_NONE` and it reports "no backend" while a perfectly good
one exists. Background rendering is OpenGL only, so it is now selected
explicitly.

### 5. `intern/locale/boost_locale_wrapper.cpp` — ICU data that does not exist

With GL working, the render died as
`terminating with uncaught exception ... Creation of collate failed:U_FILE_ACCESS_ERROR`.
`bl_locale_init()` deliberately selects the **posix** backend to avoid ICU, but
`bl_locale_set()` builds a default `boost::locale::generator`, which takes
`localization_backend_manager::global()` - and if `bl_locale_init()` has not run
first, that is ICU. This engine ships no ICU data: `libicuc.so` is 6 MB
(data-less) and Android's own `icudt76l.dat` belongs to a different ICU version.
`bl_locale_set()` now pins the generator to posix itself.

### 6. `source/blender/render/intern/pipeline.cc` and `gpu/intern/gpu_context.cc`

`RE_gpu_context_get()` creates the offscreen context when the render has none,
mirroring what `RE_engine_gpu_context_create()` does for the viewport path.
`gpu_context.cc` carries a `[gpu]` trace. `GHOST_ISystem.cc` is included for
reference (unchanged by these fixes).

## Verified

```
BLENDER_WORKBENCH, 128x128, default cube, warm:
  [34, 11, 10, 9, 9] ms      (34 ms is the first-render shader compile)
BLENDER_WORKBENCH, 256x256:  22 ms
CYCLES CPU,        128x128:  52 ms
```

About 10 ms per frame is fast enough to stream the engine's own view to the app
for a live shared camera.

## Debugging note

Blender's own crash handler writes `blender.crash.txt` with an **empty**
backtrace on this port - its unwinder does not work under Android - and then
exits, so Android's debuggerd never sees the signal and no tombstone is written
either. `native/blender/blender_exec.cpp` therefore passes
`--disable-crash-handler`, which hands the fault to debuggerd. That is what
produced the symbolized backtrace that found the null `android_app`.

Keep the build unstripped for symbolised tombstones: `llvm-strip --strip-debug`
gives 125 MB with all 238,773 symbols intact, while `--strip-unneeded` gives
96 MB and no symbols at all.
