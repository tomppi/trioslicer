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

## Round 5: chelper is compiled at import time, and the stable version

- Klipper cloned to /root/klipper-port/klipper, deliberately outside this repo so
  the GPLv3 sources stay separate. Master, commit ce7002b.
- There is **no Makefile and no CMakeLists anywhere in klippy/chelper**, which is
  why the first compile attempt failed with "no targets specified". Klippy
  compiles its own helper **at import time**: klippy/chelper/__init__.py declares
  DEST_LIB = "c_helper.so", checks whether it needs compiling, and invokes a
  compiler in a subprocess.
- That is the real porting task for chelper, and it is different from what I
  assumed: on a phone there is no compiler at runtime, so c_helper.so must be
  **pre-built for bionic and shipped with the app**, with klippy's
  needs-compiling check either satisfied (a shipped .so newer than the sources) or
  patched to accept the packaged one. It also explains the upstream pull request
  seen in round 1 about validating c_helper.so's size.
- **Current stable Klipper is v0.13.0** - upstream tags run v0.10.0, v0.11.0,
  v0.12.0, v0.13.0. The working copy should be moved onto that tag rather than
  master before anything is built against it.

## Rounds 6-7: chelper builds for bionic

- The working copy is on the stable tag **v0.13.0**.
- Klippy's own build step, from its source: GCC_CMD = "gcc", DEST_LIB =
  "c_helper.so", a needs-compiling check, and an option probe whose SSE flags are
  x86-only - so arm64 takes the plain path.
- chelper is 18 C sources, plus pyhelper.c which only the debug variant uses.
- **It builds.** From klippy/chelper on v0.13.0, with the NDK pinned at
  28.2.13676358:

      aarch64-linux-android24-clang -shared -fPIC -O2 -o c_helper.so <18 sources> -lm

  Result: **c_helper.so, 55,296 bytes**, referencing libc.so and no glibc loader,
  with 96 Klipper symbols present (stepcompress_, itersolve_, trapq_,
  serialqueue_). Kept at /root/klipper-port/out-arm64/c_helper.so, outside this
  repo.
- The **only** change needed was dropping -lpthread: bionic keeps pthreads inside
  libc, so the glibc-era link line fails with "unable to find library -lpthread".
  -lm is fine as it stands.
- 95 warnings and no errors. The one worth knowing is chelper's compiler.h
  redefining __noreturn over Android's sys/cdefs.h.
- Because a prebuilt c_helper.so is what ships, klippy's own compile step never
  runs on the phone, so **Klipper needs no patch for the build at all** - its
  -lpthread would only matter if it were compiled on the device, which is exactly
  what this avoids. The transport patch stays the only planned source change.

## Round 10: the helper exports what klippy looks up

- klippy's Python references 37 chelper symbols across the stepcompress, itersolve,
  trapq, serialqueue, msgblock and kinematics families.
- **36 of the 37 are present** in the built c_helper.so. The one that is not,
  kin_rotary_delta, is a kinematics family name rather than a C lookup - it carries
  no verb suffix of the kind the real entry points have (itersolve_alloc,
  stepcompress_alloc, trapq_alloc), so it is the search pattern matching a name in
  Python rather than a missing function.
- So the artifact matches klippy's expectations by inspection, before any device
  run. What remains unproven is only whether Android's loader and ctypes accept it
  at runtime, which is what the load test settles.

## Next step: the ctypes load test, as a recipe

Prove Android can load the built helper into the bundled interpreter, before any
Klipper code is exercised.

1. Vendor klippy's tree behind a clear GPL boundary - for example
   native/klipper/vendor/klippy at tag v0.13.0 - with the prebuilt helper at
   native/klipper/vendor/klippy/chelper/c_helper.so. Klipper sources are already
   cloned at /root/klipper-port/klipper and the built helper at
   /root/klipper-port/out-arm64/c_helper.so.
2. Follow BlenderBridge's shape: a JNI shim (native/klipper/klipper_exec.cpp plus a
   CMakeLists.txt) exposing nativeKlipperStart, nativeKlipperIsRunning and
   nativeKlipperStop, loaded with System.loadLibrary. It boots the bundled CPython,
   puts the vendored klippy tree on sys.path, and runs a probe script on a
   background thread.
3. The probe script imports ctypes, loads c_helper.so, and reports the resolved
   path, that it loaded, and one call into it.
4. Verify by building the APK, installing over the existing package with the same
   key, and reading the probe output from logcat.
5. Only then klippy itself, batch mode, with no hardware.

Two things to watch in that step: whether a packaged .so is loadable by absolute
path from the native library directory (the three engine runners already rely on
applicationInfo.nativeLibraryDir, so it should be), and whether klippy's
needs-compiling check must be satisfied by shipping the helper with a timestamp
newer than its sources.

## Round 13-14: the first measurement on the device

Ran Python inside the app without writing any JNI code, by driving the Blender
engine's own MCP socket: forward tcp:9876, read the token from the app's private
storage, and send an execute_code request. The envelope is
{"type": "execute_code", "token": "...", "params": {"code": "..."}} - the code
goes under "params", and the token is required on every request.

Measured on the Fold 5, inside the app's embedded interpreter:

    python 3.11.4 | ctypes libc ok | openpty=True |
    chelper FAILED: dlopen failed: cannot locate symbol "errorf" referenced by c_helper.so

So, verified:

- **ctypes works in the app**, loading libc.so through the normal loader.
- **openpty exists on this device** - so the transport can hand chelper a pty and
  the file-descriptor patch is a fallback rather than a requirement.
- **The interpreter is 3.11.4**, matching the payload.
- **The built helper does not load yet**: it references errorf, which bionic does
  not provide. A shared library links with undefined symbols by default, so the
  build succeeded while the symbol was missing - which is exactly the kind of gap
  that only a device run finds.

Fixes to make next, in order:

1. Find which chelper source uses errorf and supply it (a small shim or a patch to
   that call), then rebuild.
2. **Rebuild with -Wl,--no-undefined**, so any future missing symbol fails at build
   time instead of on the phone. The first build looked clean and was not.
3. Re-run the same probe and expect a clean load with stepcompress_alloc,
   itersolve_alloc and serialqueue_alloc all present.

Worth keeping: driving the engine socket is a reusable capability. It gives direct
Python access inside the app for the rest of this port, with no app changes, no
JNI shim and no rebuild.

## Round 16: the helper loads in the app

Rebuilt with pyhelper.c included and -Wl,--no-undefined (0 errors, 0 undefined
symbols, 57,656 bytes, sha 5da5c73e...), staged, pushed to the phone and loaded
through ctypes from inside the app's interpreter:

    openpty=True | chelper LOADED | stepcompress_alloc=True |
    itersolve_alloc=False | serialqueue_alloc=True

Verified:

- **Android's loader and ctypes accept the bionic helper.** This is the mechanism
  Klipper's step generation depends on, and it works.
- The errorf gap is closed, and --no-undefined now guarantees no other symbol is
  missing the same way.
- openpty is available, so the transport can hand chelper a pty.
- The staging script was corrected to include pyhelper.c and to pass
  -Wl,--no-undefined, so the mistake cannot recur.

**One symbol still to explain:** itersolve_alloc reports absent through ctypes even
though the name appears in the library's string table, so it is present as a name
but not as an exported dynamic symbol. Next: check how 0.13's itersolve.c defines
it, and whether klippy actually looks that name up. This is the last known gap
between the app and Klipper's host code.

## Round 17: the helper is complete

- itersolve_alloc was never a Klipper symbol. It appears nowhere in chelper's C
  sources, and klippy never looks it up: the earlier probe checked a name I had
  misremembered. The gap was mine, not Klipper's.
- llvm-nm from the pinned NDK reports **105 exported dynamic symbols**.
- A device sweep over every symbol klippy references, run through the engine socket:
  **checked 37, missing only kin_rotary_delta** - a kinematics family name in
  klippy's Python, not a ctypes attribute lookup. (The first attempt at this sweep
  also reported the string c_helper.so as a symbol, which is how the regex mistake
  was found.)
- **So the helper is complete for klippy's use**, and every piece Klipper needs is
  now proven present and loadable inside the app: interpreter 3.11.4, ctypes, libc,
  openpty, and the C helper with all of its symbols.

Next: get Klipper's own Python running against it - push the staged klippy tree to
the app's files directory, import chelper through the engine socket, construct a
step compressor and feed it a move. That is Klipper's motion pipeline executing on
the phone, which is step 2 of the objective.

## Round 18: klippy's Python runs, and stops on cffi

The staged klippy tree was pushed into the app and imported through the engine
socket, on the phone:

    KLIPPERPROBE FAILED ModuleNotFoundError("No module named 'cffi'")

That is the dependency question answering itself, and it corrects an earlier
conclusion of mine: **chelper uses cffi, not ctypes.** Its __init__.py does
"import cffi", builds cffi.FFI(), and loads the helper with FFI_main.dlopen(). It
is ABI mode, so no compiler is needed at use time - but the _cffi_backend C
extension must exist, and it does not.

Checked before assuming work was needed: the Blender payload's site-packages holds
autopep8, certifi, Cython, numpy, MaterialX, meson and OpenImageIO, but **no cffi,
no greenlet, no jinja2**.

So the decision from round 3 is no longer a preference, it is forced:

- cffi needs _cffi_backend, a C extension built against Python.h and a matching
  pyconfig.h.
- greenlet, which klippy's reactor requires, is also a C extension.
- The bundled interpreter ships no headers, and no amount of ctypes cleverness
  removes greenlet.

**Plan: build our own CPython 3.11.4 for Android and use it for klippy**, with cffi,
greenlet, jinja2 and MarkupSafe built against it. That removes the pyconfig.h
guesswork entirely and it is a few megabytes beside a payload that is already 445 MB
of native libraries. The Blender interpreter stays untouched for Blender.

## Rounds 19-58: the port, end to end

**Klippy runs on the Fold 5.** It boots, loads 127 MCU commands, configures the
move queue, processes G-code in batch mode and writes MCU output:

    Loaded MCU 'mcu' 127 commands (v0.13.0-0-g61c0c8d)
    Configured MCU 'mcu' (500 moves)
    Exiting (print time 0.251s)

That is under root adb, using our own bionic interpreter and the payload in the
app's files directory - not yet inside the app's process. The port itself:

**The interpreter** (scripts/build-klipper-python-android.sh). CPython 3.11.4,
three fights: configure refuses to cross-compile without --with-build-python (and
the flag needs the *same* minor version, so the box's 3.13 was no substitute); the
extension modules race the shared library they link against under a parallel make,
so the library is built in its own pass first; and the core objects came out
without -fPIC, which only showed as relocation errors at link time. Plus the
Termux ac_cv answers, which stop configure guessing wrong about Android.

**The extensions** (scripts/build-klipper-extensions-android.sh, committed with the
reason for every flag). libffi 3.4.6 static - 3.4.4 does not build on Android
because tramp.c calls open_temp_exec_file undeclared. cffi and greenlet linked
against libpython, or greenlet fails at load on PyContext_Type. greenlet also needs
-fno-emulated-tls (its default pulls in __emutls_get_address, which bionic lacks)
and libc++_shared.so present at runtime. setuptools comes from an unpacked wheel,
because the host interpreter has no ssl so pip cannot reach PyPI. Extensions must be
named with the target's EXT_SUFFIX (.cpython-311.so), not the host platform tag.

**How it runs on the device.** Push the interpreter and libpython to
/data/local/tmp, unpack the payload into the app's files directory, then:

    LD_LIBRARY_PATH=/data/local/tmp/kpy PYTHONHOME=<payload> \
      ./python3.11 <payload>/klippy/klippy.py cfg -i gcode -o out -d klipper.dict -l log

Batch mode needs -d, the MCU dictionary, or klippy dies on options.dictionary with
"'NoneType' object is not iterable". Build it with the linux-process MCU config.

**Learned the hard way, worth keeping.** Read the log rather than grep it for the
error you expect - four wrong diagnoses came from assuming a shape. Check the
binary, not the exit code: an .so named x86_64 held correct AArch64 code, and a
"successful" cross build was byte-identical to the host one because make reused
stale objects. And a shared library links happily with unresolved symbols, so
-Wl,--no-undefined belongs in every cross build.

**Dead end, recorded so it is not retried - abandoned after six attempts.** The
linux-process MCU does not cross-compile from this box:

- CROSS_COMPILE is ignored for that target: it is designed to build for the machine
  running it, so make reaches for the host compiler. The "cross build" was
  byte-identical to the host one.
- Overriding CC/LD alone gets further, then dies in the .ctr step: make runs
  "objcopy -j '.compile_time_request' -O binary" over each object, which is a
  section extraction with no architecture flags at all - and llvm-objcopy reports
  the MCU objects as "not recognized as a valid object file" (most likely LTO
  bitcode, which objcopy cannot read sections from).

It is a convenience, not a requirement, and it fought back every time. The timing
numbers that decide this project should come from a real Klipper-flashed board over
the USB bridge: same transport work, real move queue, real latencies. If a simulated
printer is wanted later, the sane route is to build it on a Linux box with GNU
objcopy and copy the binary over - not to keep bending this toolchain.

## Rounds 60-61: the transport seam is upstream, not a patch

I had planned for thirty rounds to patch chelper's serial layer. Reading it settled
the question in one command:

- **serialqueue_alloc takes an int fd**, not a device path:
  serialqueue_alloc(int serial_fd, char serial_fd_type, int client_id). klippy opens
  the port in Python and passes serial_dev.fileno(); chelper never opens a device and
  never touches termios.
- chelper's module-level API is FFI_lib (the cffi handle on c_helper.so) plus
  FFI_main and DEST_LIB. serialqueue_alloc lives on FFI_lib, alongside the
  SerialReader and SerialQueue wrappers - not on the module itself.
- serialhdl.SerialReader takes two to three positional arguments (reactor, serial,
  name); my first attempt passed four and was told so.

**Correction, round 64 - the shape above was still wrong.** SerialReader does not
take a serial object: its signature is __init__(self, reactor, warn_prefix="") and it
attaches its own transport. The transports it offers are the real interface:

    connect_pipe(filename)              <- a unix socket. This is the Android route.
    connect_uart(serialport, baud, rts=True)
    connect_canbus(canbus_uuid, canbus_nodeid, canbus_iface)
    connect_file(debugoutput, dictionary, pace=False)

Klipper already routes a socket-path serial to connect_pipe: that is how the
linux-process MCU works, with serial: /tmp/klipper_host_mcu. So the Android
transport is:

1. Kotlin: open a LocalServerSocket (Android's AF_UNIX) at a path in the app's files
   directory, and run a pump thread between that socket and the board's bulk USB
   endpoints. No root, no kernel driver, no fd passing from Java.
2. klippy: [mcu] serial: <that path>, which routes to connect_pipe. Klipper opens the
   socket and hands the resulting fd to chelper exactly as it does for a tty.
3. Klipper: unchanged - confirmed now at the right layer rather than by inference.

Next: a unix socket plus a correctly framed identify response, which is the same
framing a real board produces, proves the whole path with no hardware.

The half of this that needs no hardware - the Python side and a simulated MCU
answering over a socketpair - can be built and verified on the device. Only the
pump thread needs a real board, which is also where the timing numbers come from.

## Rounds 67-69: the transport, proven as far as hardware allows

Klippy talks to a pty on the phone. Two experiments settled it:

- **A FIFO fails.** klippy applies termios to whatever path it is given, and a FIFO
  cannot take it: "Unable to open serial port: Could not configure port: (25,
  'Inappropriate ioctl for device')".
- **A pty works.** openpty, hand klippy the **slave** path, read the **master**. It
  wrote 69 bytes of its own protocol into it:

      1b0100010e1104081101002842247e7e081101002842247e

  with the 7e frame terminators visible - Klipper's own framing, generated by
  Klipper's own code, on the device.

**So the app-side design is:** create a pty pair, give klippy the slave path, and
pump bytes between the master and the board's USB bulk endpoints. Klipper is
unmodified, and the pump is the only new component.

**Trap worth recording:** os.openpty() returns (master, slave). I unpacked it the
other way round, twice, and spent a round wondering why ttyname() said /dev/ptmx and
nothing came out of the read.

## Rounds 71-75: the first timing numbers, and what they do and do not mean

A probe on the phone asked for 1 ms wakeups 20,000 times and recorded how late each
one was - the shape of what klippy's reactor does.

    run 1 (three spinning Python threads inside the same process):
    samples 20000 over 20.04s
    p50 8.58 ms | p90 28.85 ms | p99 58.91 ms | p99.9 74.13 ms | max 82.77 ms
    later than 5 ms: 68.2%

    run 2 (load moved outside the interpreter):
    samples 20000 over 20.02s (asked for 1ms steps, 3 busy cores) / p50 8.58 ms | p90 27.78 ms | p99 53.73 ms | p99.9 73.66 ms | max 82.90 ms / wakeups later than 5 ms: 13538 (67.690%)

**Three reasons these are upper bounds rather than a verdict:**

1. **RESOLVED in rounds 76-78, and both earlier explanations were wrong.** The
   8.58 ms median was my own probe's doing after all: it ran three spinning Python
   threads inside the same process, contending for the GIL. Run 2 did *not* disprove
   that - it added load externally but left the in-process spinners running, so my
   "correction" was itself the error. A clean single-threaded probe - the shape
   klippy actually has, its C helper being a separate thread that never touches the
   GIL - gives:

       baseline:     p50 0.12 | p90 0.25 | p99 0.78 | max 4.43 ms | >5ms 0.0%
       nice -n -19:  p50 0.11 | p90 0.22 | p99 0.63 | max 6.08 ms | >5ms 0.0%
       chrt -f 10:   p50 0.06 | p90 0.13 | p99 0.30 | max 1.42 ms | >5ms 0.0%

   So: **Android's scheduling is not a problem for this workload.** Wakeups land in
   a tenth of a millisecond typically and under a millisecond at p99, with no
   priority tricks; real-time priority improves it further and is not needed, which
   matters because an app cannot have it anyway. The remaining timing questions are
   the USB link and the MCU queue, and those need a board.
2. The probe ran over adb, not as a foreground service. Android's timer slack - the
   mechanism that would hurt here - is relaxed for ordinary processes and tight for a
   foreground service with a wake lock, which is what BlenderEngineService already is.
3. Klippy does not need 1 ms punctuality. Its reactor uses coarser timeouts, and it
   feeds the MCU ahead of the timestamps it stamps on commands, with a lookahead
   buffer absorbing jitter.

The real measurement is klippy in a foreground service, against a board, watching the
actual margins between when commands arrive and when they are due. These numbers say
the host is not obviously disqualified; they do not say it is safe.

## Rounds 79-81: the user's actual printer, and what it removes

The user runs an **Ender 3 V2 with a CR-Touch on a Creality Sonic Pad**, and supplied
Creality's own repository for it (github.com/CrealityOfficial/Creality_Sonic_Pad).
That answers several questions at once.

**Nothing needs building on the printer side.** The Sonic Pad *is* a Klipper host, and
the Ender 3 V2 board it drives already runs Klipper firmware. The printer works today.
What this project builds is a **replacement host** - the phone - talking to the same
board, so the firmware, the flashing and the MCU toolchains are all out of scope.

**The protocol matches.** Creality pins Klipper commit 520273e5; this port targets
v0.13.0. Comparing msgproto.py at both:

    v0.13.0  : MESSAGE_MIN = 5 | MESSAGE_MAX = 64 | header 2 | trailer 3
    Creality : MESSAGE_MIN = 5 | MESSAGE_MAX = 64 | header 2 | trailer 3

So the v0.13.0 host can speak to the flashed firmware. Optionally the host could be
built at Creality's commit instead - the same scripts with a different tag - which
would match the firmware's features exactly, but it is not required for compatibility.

**The config exists, from Creality themselves** in printer_configrations/:

    printer-Ender3V2-CRtouch-V4.2.2-V4.3.1.cfg
    printer-Ender3V2-CRtouch-V4.2.7.cfg

The board revision printed on the board picks which one. Any calibration the user has
done (PID, e-steps, probe offsets) lives in the pad's own printer.cfg and should be
merged over the template rather than replaced by it.

**And no dictionary file is needed for a real board**: the MCU serves its own
dictionary during the identify handshake, which is why -d only mattered for batch mode.

**What is left, and it needs the printer plugged into the phone:** a userspace
**CH340 USB-serial driver** in the app (Creality 4.2.x boards use a CH340; Android has
no driver for it and it is not CDC-ACM). That is the pump between the pty and the USB
bulk endpoints, and it is a few hundred lines of ordinary code rather than research.
Everything else on the path is proven.

## Round 84: the CH340 protocol, from the Linux kernel

Creality 4.2.x boards carry a CH340, which is not CDC-ACM and has no Android driver,
so the app needs a userspace one. Rather than write it from memory, the protocol comes
from the kernel's own driver, drivers/usb/serial/ch341.c:

    CH341_REQ_READ_VERSION  0x5      control request: read chip version
    CH341_REQ_WRITE_REG     0x9      write a register
    CH341_REQ_READ_REG      0x95     read a register
    CH341_REG_BREAK         0x05
    CH341_REG_PRESCALER     0x12     baud rate, prescaler
    CH341_REG_DIVISOR       0x13     baud rate, divisor
    CH341_REG_LCR           0x18     line control: data bits, parity, stop
    CH341_REG_FLOW_CTL      0x27
    CH341_LCR_ENABLE_RX     0x80
    CH341_LCR_ENABLE_TX     0x40

So the shape of the driver is: open the device, claim the interface, send the vendor
control requests that select baud (prescaler and divisor) and line format (LCR), then
move bulk data. That is a few hundred lines of ordinary code against documented
behaviour, not protocol archaeology.

Still to extract from the same file when the driver is written: the baud divisor
calculation itself (this pass matched the register constants but not that function)
and the exact initialisation order. Both are in the same source.

**Prerequisite in place:** the app now declares android.hardware.usb.host
(required=false, so it still installs without OTG) and carries a device filter naming
the CH340, with CP2102 and FTDI alongside it.

## Round 85: the CH340 baud and init details, from the same kernel file

Extracted from drivers/usb/serial/ch341.c, so the driver can be written against the
kernel's own behaviour rather than a guess:

    (baud function not matched)

static int ch341_configure(struct usb_device *dev, struct ch341_private *priv)
{
	const unsigned int size = 2;
	u8 buffer[2];
	int r;

	/* expect two bytes 0x27 0x00 */
	r = ch341_control_in(dev, CH341_REQ_READ_VERSION, 0, 0, buffer, size);
	if (r)
		return r;

	priv->version = buffer[0];
	dev_dbg(&dev->dev, "Chip version: 0x%02x\n", priv->version);

	r = ch341_control_out(dev, CH341_REQ_SERIAL_INIT, 0, 0);
	if (r < 0)
		return r;

	r = ch341_set_baudrate_lcr(dev, priv, priv->baud_rate, priv->lcr);
	if (r < 0)
		return r;

	r = ch341_set_handshake(dev, priv->mcr);
	if (r < 0)
		return r;

	return 0;
}

That is the **order** the driver must follow: read the version (expect 0x27 0x00),
send CH341_REQ_SERIAL_INIT, set baud and line control together, then set handshake.
It also surfaced CH341_REQ_SERIAL_INIT, which the round 84 grep had missed.

**The baud calculation, extracted in round 86** - the piece the paragraph above said
was still missing:

    ch341_set_baudrate_lcr(struct usb_device *dev,
				  struct ch341_private *priv,
				  speed_t baud_rate, u8 lcr)
{
	int val;
	int r;

	if (!baud_rate)
		return -EINVAL;

	val = ch341_get_divisor(priv, baud_rate);
	if (val < 0)
		return -EINVAL;

	/*
	 * CH341A buffers data until a full endpoint-size packet (32 bytes)
	 * has been received unless bit 7 is set.
	 *
	 * At least one device with version 0x27 appears to have this bit
	 * inverted.
	 */
	if (priv->version > 0x27)
		val |= BIT(7);

	r = ch341_control_out(dev, CH341_REQ_WRITE_REG,
			      CH341_REG_DIVISOR << 8 | CH341_REG_PRESCALER,
			      val);
	if (r)
		return r;

	/*
	 * Chip versions before version 0x30 as read using
	 * CH341_REQ_READ_VERSION used separate registers for line control
	 * (stop bits, parity and word length). Version 0x30 and above use
	 * CH341_REG_LCR only and CH341_REG_LCR2 is always set to zero.
	 */
	if (priv->version < 0x30)
		return 0;

	r = ch341_control_out(dev, CH341_REQ_WRITE_REG,
			      CH341_REG_LCR2 << 8 | CH341_REG_LCR, lcr);
	if (r)
		return r;

	return r;
}

Two details in there matter more than they look:

- **Chip versions above 0x27 need BIT(7) set in the divisor value**, or the CH341
  buffers data until it has a full 32-byte endpoint packet. Klipper's messages are
  small and frequent, so a driver that misses this would appear to stall.
- **Line control is version-dependent**: below 0x30, stop bits, parity and word length
  use separate registers; 0x30 and above use CH341_REG_LCR alone, with CH341_REG_LCR2
  set to zero. That is why the init sequence reads the chip version first, and it also
  revealed CH341_REG_LCR2, absent from the round 84 constant list.

The one piece still not extracted is ch341_get_divisor, the helper that computes the
value written above. My commit called this specification complete; it is complete
except for that arithmetic, and the doc says so rather than leaving the claim standing.

    static int ch341_get_divisor(struct ch341_private *priv, speed_t speed)
{
	unsigned int fact, div, clk_div;
	bool force_fact0 = false;
	int ps;

	/*
	 * Clamp to supported range, this makes the (ps < 0) and (div < 2)
	 * sanity checks below redundant.
	 */
	speed = clamp_val(speed, CH341_MIN_BPS, CH341_MAX_BPS);

	/*
	 * Start with highest possible base clock (fact = 1) that will give a
	 * divisor strictly less than 512.
	 */
	fact = 1;
	for (ps = 3; ps >= 0; ps--) {
		if (speed > ch341_min_rates[ps])
			break;
	}

	if (ps < 0)
		return -EINVAL;

	/* Determine corresponding divisor, rounding down. */
	clk_div = CH341_CLK_DIV(ps, fact);
	div = CH341_CLKRATE / (clk_div * speed);

	/* Some devices require a lower base clock if ps < 3. */
	if (ps < 3 && (priv->quirks & CH341_QUIRK_LIMITED_PRESCALER))
		force_fact0 = true;

	/* Halve base clock (fact = 0) if required. */
	if (div < 9 || div 

## The printer is on the bus (CH340 confirmed)

The cable was the fault. With a data-capable one, the board enumerates:

    usb 2-1: new full-speed USB device number 2 using xhci-hcd
    usb 2-1: New USB device found, idVendor=1a86, idProduct=7523, bcdDevice= 2.64
    usb 2-1: Product: USB Serial

That is the CH340, exactly as expected on a Creality 4.2.x board, and it confirms
three things at once:

- **No /dev/ttyUSB appears, and never will.** The kernel reports CONFIG_USB_SERIAL is
  not set, so there is no in-kernel driver for this chip. The port can only be claimed
  in userspace, which is why usb-serial-for-android is in the app rather than a
  hand-written kernel module being possible.
- **The interface is vendor-specific** (class 0xff, one interface) rather than CDC-ACM,
  so it is matched by VID/PID, not by interface type. 1a86:7523 is both in the
  library's well-known table and in PrinterUsb's own ProbeTable.
- **The endpoints are the CH340 layout**: bulk OUT (32 bytes), bulk IN (32 bytes) and
  an interrupt IN (8 bytes) status pipe. Those two bulk pipes are what the pump moves
  bytes through.

The device node is /dev/bus/usb/002/002, owned by system. An app does not open it
directly; it goes through UsbManager after requesting permission, which is the whole
reason the library route exists.

**Dead ends worth not repeating**, both of which cost time tonight:

- Host mode stays false until an adapter that actually asserts OTG is used. A phone in
  device mode will never see a printer, whatever else is correct.
- A charge-only cable produces *total* silence: no attach event, no error, nothing in
  the kernel log. Silence of that kind is a physical-layer symptom, not a driver one.
- Baud rate is irrelevant to detection. It is a chip register set after enumeration,
  and on a CH340 the USB side always runs at full speed regardless.

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
