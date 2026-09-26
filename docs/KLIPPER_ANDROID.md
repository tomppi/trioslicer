# Klipper on Android - what this app already gives us

Investigation notes for the goal "get the app to run Klipper". Facts here were read
out of the tree, not assumed.

## How the native payload is produced

- app/src/main/jniLibs is gitignored and produced, never committed.
- Four of the five engines arrive as pinned release assets fetched by scripts:
  fetch-orca-engine-android.sh (orca-engine-arm64-v1.3.0.zip),
  fetch-prusa-engine-android.sh, fetch-blender-engine-android.sh
  (blender-engine-arm64-v1.2.0.zip, about 185 MB: engine, runtime libs, assets),
  fetch-cura-resources.sh.
- CuraEngine is cross-compiled in-repo by build-curaengine-android.sh with the NDK
  pinned in the docs: ANDROID_NDK_HOME=ndk/28.2.13676358.
- filaSim is built in-repo too (Rust plus a JNI crate at native/filasim/jni) by
  build-filasim-engine-android.sh, with a patch chain applied to pinned upstream
  source. So both a C++ and a Rust-plus-JNI native pipeline already exist.
- scripts/setup.sh stages everything from a clean checkout, and
  ./gradlew :app:verifyDebugApkEngines checks the staged tree really holds every
  engine. build.yml asserts a library count (at least 120) rather than trusting it.

## What Klipper needs that is already there

- **CPython 3.11 built for bionic**: libcpython.so comes in the Blender asset,
  with a full stdlib and site-packages under
  app/src/main/assets/blender/python/lib/python3.11 (pip, setuptools visible).
  No glibc anywhere: every library checked is bionic-linked.
- **Running a console program without root**: the slicers ship as executables
  named libprusa_slicer_exec.so and liborca_console_exec.so in jniLibs, which is
  the established way to get a runnable binary into an app on modern Android.
- **Keeping a long job alive**: BlenderEngineService already runs a foreground
  service with a PARTIAL_WAKE_LOCK.
- **JNI wiring**: native/filasim/jni shows the app's pattern for starting and
  stopping native work from Kotlin.
- **The payload scale**: 445 MB across 126 libraries, so a few more megabytes of
  Python extensions and one C helper is not a packaging problem.

## The gaps, honestly

1. **Klippy's compiled dependencies.** cffi needs libffi, greenlet has a small
   arch shim; jinja2, markupsafe and pyserial are pure Python. None of this is
   research, but it is cross-compilation that has not been done yet.
2. **Python headers.** Extensions need Python.h for the bundled 3.11. Unknown
   whether the Blender asset ships headers; if not, either obtain them from the
   Blender Android build or build a matching CPython for the headers alone.
3. **chelper and its transport.** chelper is plain C and will compile; the
   transport is the real change, because an app has no /dev/tty for the printer
   board. Hand it a pty, or patch its connect path to take a file descriptor.
4. **USB bulk to fd bridge.** Nothing in the app does bulk USB today; only
   vendored SDL HID code is present. This is the one genuinely new component.
5. **GPL separation.** Klipper is GPLv3. Vendored under a clear boundary until the
   user decides how they want to ship it.

## Next actions, in order

1. Inspect the Blender asset's CPython: exact version, whether headers are
   included, whether the stdlib is complete enough to run a program that imports
   what klippy imports.
2. Build one trivial C extension against that Python, load it in-app on the phone,
   and call it. Proves the extension path end to end before klippy is involved.
3. Then klippy itself, batch mode, no hardware: this is the moment the app runs
   Klipper.
4. Then the transport, then the timing measurements.

## The bundled CPython, inspected

- Version **3.11.4**, read from libcpython.so.
- It is a **static-extension build**: there is no lib-dynload directory at all,
  and the C modules live inside the library - PyInit__ctypes, PyInit__socket and
  PyInit__array are all present as symbols.
- **libffi is compiled in** (ffi_prep_cif, ffi_call), so ctypes works. This
  matters more than anything else here: Klipper loads its C helper with ctypes,
  so the one dependency that could have stopped the idea at this stage is
  already satisfied.
- The library exports Py_Initialize, Py_InitializeFromConfig and Py_Main, so it
  can host an embedded interpreter or act as a python executable.
- libblender_exec.so references libcpython.so and calls Py_Initialize: the app
  already ships a program that hosts this interpreter, which is exactly the shape
  klippy needs.
- The launch pattern is in OrcaEngineRunner, CuraEngineRunner and PrusaEngineRunner:
  nativeDirectory is applicationInfo.nativeLibraryDir, the executable is a
  lib*_exec.so inside it, availability is a file check, and resources are
  extracted into filesDir behind a .resources-version marker. A vendored klippy
  tree would stage exactly that way.

## Round 3: the builtins, and what upstream actually requires

Every module klippy imports is compiled into libcpython.so, verified as symbols:
_struct, _collections, _queue, zlib, _hashlib, select, _thread, math, fcntl,
_socket, termios, posix. Note termios and fcntl in particular - both are what
chelper's serial layer uses.

**Upstream's real dependency list**, read from Klipper's own
scripts/klippy-requirements.txt rather than from memory:

- greenlet, pinned 2.0.2 for Python below 3.12 - used by the reactor. A C
  extension, so it must be cross-compiled.
- cffi, pinned 1.14.6 below 3.12 - used by chelper and by greenlet. Also a C
  extension. libffi is already inside libcpython.so, but cffi needs its own
  backend extension built against it.
- jinja2 (pure Python), and MarkupSafe, which falls back to pure Python when its
  speedups are absent.
- pyserial is pure Python, and may not be needed at all if the transport is ours.

So cffi and greenlet cannot be avoided; both are ordinary cross-compilation work,
but they are the real content of "build klippy's dependencies for bionic".

The installer script also shows what stays a desktop step: the AVR and ARM
toolchains, dfu-util and stm32flash are for building and flashing MCU firmware,
which never needs to happen on the phone.

## The one thing that could bite: pyconfig.h

Greenlet and cffi need Python.h, and a Python.h is only safe with the pyconfig.h
the interpreter was actually built with. The Blender asset ships no headers, so
there are two honest routes: reconstruct a matching pyconfig.h for that build, or
ship our own CPython 3.11.4 for klippy and control the whole configuration. The
second costs a few megabytes and removes the guesswork, and the app already
proves the packaging works.

## Round 4: how the app really runs Python, and what klippy should copy

- libblender_exec.so is **not a standalone program**. Executing it from an adb shell
  dies with "Illegal instruction", and the app never execs it.
- It is a **library with a JNI shim, built in this repo**: native/blender/blender_exec.cpp,
  native/blender/CMakeLists.txt, a patches directory and a creator copy. BlenderBridge.kt
  calls System.loadLibrary("blender_exec") and then nativeBlenderStart,
  nativeBlenderIsRunning and nativeBlenderStop.
- SDL is vendored (org.libsdl.app, nativeRunMain), which is how Blender's main is
  entered in-process.
- The app already runs a Python script inside that embedded interpreter:
  configDir/scripts/startup/start_blender_mcp.py.

So the app has **two** native patterns, and the choice matters:

1. The slicers use the executable trick - a lib*_exec.so run from
   applicationInfo.nativeLibraryDir.
2. Blender uses JNI into an embedded interpreter running a script.

**Klippy should follow the second one**: a small JNI shim that starts the bundled
CPython, puts a vendored klippy tree on sys.path, and runs it on a background
thread, with the foreground service that already exists keeping it alive. That is
the pattern this codebase has already proven for a long-running embedded engine.

**And chelper gets simpler because of it.** Klippy loads its C helper through
ctypes, and ctypes plus libffi are compiled into the interpreter (verified in round
2). So chelper only has to be compiled as a shared library and be findable by
ctypes - no Python.h and no pyconfig.h are involved in the helper at all. The
pyconfig.h question from round 3 therefore only remains for cffi and greenlet.

## What this leaves

1. Confirm the handful of builtins klippy imports - _struct, _collections,
   _queue, zlib, hashlib. Cheap, and by this pattern they will be present.
2. chelper compiled for bionic. Plain C, and the NDK is pinned at 28.2.13676358.
3. Check whether the Klipper version chosen still needs cffi and greenlet, or
   whether ctypes plus the stdlib is enough. Fewer moving parts if it is.
4. Then the trivial extension proof, then klippy in batch mode.

## What this changes

Step 2 of the goal is smaller than it looked from outside: the Python runtime,
the keep-alive machinery, the executable-from-jniLibs trick and the native build
pattern all exist and are proven in this codebase. The work is extensions,
chelper, a transport, and then the timing question - which remains the only
unknown that can end the project.
