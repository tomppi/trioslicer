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

## What this changes

Step 2 of the goal is smaller than it looked from outside: the Python runtime,
the keep-alive machinery, the executable-from-jniLibs trick and the native build
pattern all exist and are proven in this codebase. The work is extensions,
chelper, a transport, and then the timing question - which remains the only
unknown that can end the project.
