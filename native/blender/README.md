# native/blender — Blender 3.6 MCP engine wrapper (libblender_exec.so)

Drop-in engine wrapper for enderslicercura. Pattern: identical to the
CuraEngine/Prusa exec wrappers (one shared lib + JNI + assets).

## What's here
- `blender_exec.cpp` — JNI wrapper: starts Blender **embedded** in background
  mode (`-b --python start_blender_mcp.py`), which boots the MCP addon (socket
  server, default port 9876) inside the engine. No GL/viewport needed for
  generation — the AI MCP server (external) drives bpy via the socket.
- `creator/` — epai-patched creator sources (from APP-android_arm64) that
  provide `mainBlenderInitial(argv)` (embedded entry).
- `blender-gensrc/` — generated DNA/RNA headers from the harness build
  (dna.c, dna_type_offsets.h, rna_*_gen.c, RNA_*.h).
- `blender-jniLibs/` — the engine's runtime shared libs (120 `.so`,
  Alembic/OIIO/cpython/boost/ffmpeg…) — staged into the app's jniLibs by
  `scripts/fetch-blender-engine-android.sh`, which prefers the copy inside the
  engine package. The engine cannot be loaded without them.
- `blender-libs/` *(optional vendored copy of the 148 static libs)*.
- `assets/startup/*.py` — the tracked MCP addon (`start_blender_mcp.py`,
  `blender_mcp_slim.py`), and `assets/licenses/` — the tracked GPL and
  third-party texts. Both are laid over the engine package by
  `scripts/fetch-blender-engine-android.sh`. See "The addon's source of truth".

## Building (verified: 1.34 GB arm64 .so, exports OK)
Env: BLENDER_ENGINE_LIBS (engine package libs dir), BLENDER_SO_DIR,
BLENDER_SRC_DIR (Blender source root), BLENDER_LIBDIR (lib-android_arm64),
BLENDER_PYTHON_INCLUDE (cpython/include).

CMake: `-G Ninja -DCMAKE_TOOLCHAIN_FILE=<NDK 28.2>/build/cmake/android.toolchain.cmake
 -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_shared`
(NDK 28.2 — the app's pinned NDK; NDK 21 fails on the patched creator's C++17
math usage).

Result: `libblender_exec.so` — copy to `app/src/main/jniLibs/arm64-v8a/`
(done). Kotlin: `app/.../nativebridge/BlenderBridge.kt`
(System.loadLibrary("blender_exec"); start(home, config, port)).

## Runtime assets (app must package)
- `assets/blender/python/` — CPython 3.11 stdlib (engine package python/) —
  MUST match the engine's cpython 3.11.4.
- `assets/blender/scripts/` — Blender scripts + `startup/{start_blender_mcp.py,
  blender_mcp_slim.py}` — auto-boots the MCP addon. The two startup scripts come
  from `native/blender/assets/startup/`, which is the tracked source of truth:
  the fetch script copies them over the package's copies.
- `assets/blender/3.6/config/datafiles/` — OCIO + locale datafiles.
- `assets/blender/licenses/` — GPL and third-party license texts. The engine
  package carries them; the tracked copy lives in this directory and
  `scripts/fetch-blender-engine-android.sh` falls back to it.

## The release asset the app fetches
`scripts/fetch-blender-engine-android.sh` downloads
`blender-engine-arm64-<tag>.zip` from this repository's releases and reads it
as a directory named `blender-engine-arm64` holding exactly the six things the
app packages:

    blender-engine-arm64/libblender_exec.so   the engine, linked and stripped
    blender-engine-arm64/jniLibs/*.so         the 120 libraries it loads
    blender-engine-arm64/python/              CPython 3.11.4 stdlib
    blender-engine-arm64/scripts/             Blender scripts + the MCP addon
    blender-engine-arm64/3.6/                 config/datafiles (OCIO, locale)
    blender-engine-arm64/licenses/            GPL + third-party texts

Zip that directory with any tool and attach it to the release. Gradle prunes
the unusable parts (CUDA kernels, ensurepip, venv, `__pycache__`) at build
time, so the package is the unpruned tree.

## The addon's source of truth

`native/blender/assets/startup/{start_blender_mcp.py,blender_mcp_slim.py}` is
tracked in this repository, and `scripts/fetch-blender-engine-android.sh` copies
those `.py` files over whatever the engine package shipped, after it unpacks the
package. A clone — and CI — therefore runs **this repository's addon**, not the
package's older copy: the serve loop that parks instead of returning, the socket
token check and the request framing are all changed by editing the tracked files,
with no engine rebuild and no repackaged release asset.

The delivery copy in `app/src/main/assets/blender/scripts/startup/` is gitignored
and is overwritten by every fetch. Edit it to try something, then copy the change
back to `native/blender/assets/startup/` or it is one clean clone from not existing.

**`RESOURCES_VERSION` in `app/src/main/java/com/tomppi/enderslicer/nativebridge/BlenderEngine.kt`
must be bumped whenever the staged assets change.** The extracted tree carries a
marker holding that string and the app re-extracts only when it differs, so without
a bump an install that already has the tree keeps it — and keeps running the old
addon, no matter how new the APK is. This has been missed twice; a change to
anything under `assets/blender/` is not shipped until the string moves.

## MCP server (external, per user decision 2026-09-09)
`blender-mcp-slim/server.py` (FastMCP, 5 tools) runs wherever the AI session
runs; connects via `BLENDER_HOST`/`BLENDER_PORT` (default localhost:9876).
`device_bridge.py` does `adb forward tcp:9876 tcp:9876` for dev.

## Why NDK 28.2 and not the harness's 21.4
The epai harness pins NDK 21.4 (their 3.6 line), but its libc++ breaks on the
patched C++ creator sources (`std::is_trivial_v` etc.). The wrapper compiles
only the creator glue; everything else came prebuilt from the harness. The
app's pinned NDK 28.2 works (verified: full link + exports).
