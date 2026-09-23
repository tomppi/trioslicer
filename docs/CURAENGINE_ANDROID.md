# CuraEngine Android integration

The APK ships the CuraEngine 5.14 executable built for Android and runs it as a
child process: CuraEngineRunner stages the request in an isolated workspace,
CuraEngineCommand builds the CLI invocation, and OwnedProcessRunner executes
the packaged binary with a bounded timeout. There is no JNI boundary.

## Pin

- CuraEngine: `5.14.0-alpha.0`
- Cura project compatibility target: Cura `5.14.0-alpha.0`, setting version `27`
- Android ABI: `arm64-v8a`
- NDK: `28.2.13676358`
- C++ standard: C++20

## Machine catalogue

The APK ships Cura's own definition tree rather than one hand-picked chain: 1250 files from
`resources/definitions` and `resources/extruders` of Cura `5.14.0-alpha.0`, staged flat in
`assets/cura/definitions/` because a machine names its extruder train by bare file name.
`scripts/fetch-cura-resources.sh` fetches it from the pinned tag and validates it: every
`inherits` target and every `metadata.machine_extruder_trains` entry has to resolve, and the
default chain has to keep resolving to exactly the five files the app shipped before the
catalogue existed. The fetch normalizes three extruder trains that upstream ships with an
uppercase `.def.JSON` extension (`tizyx_evy_extruder_0`, `tizyx_evy_dual_extruder_0/1`),
which machines reference by bare lowercase name.

`CuraMachineCatalog` reads those rules back off the data:

- a definition is a **machine** when its chain reaches `fdmprinter`, it is not an extruder
  train (`*_extruder_0`, `*_extruder_left`, ...), `metadata.visible` is not false and its id
  does not name shared scaffolding (`*_base`, `*_common`) other definitions inherit - 636
  machines across 200-odd vendors. Being inherited is deliberately not a disqualifier: Cura
  lets a printer's variants inherit it (`ultimaker_s5` by the S7/S8), so a leaf test silently
  dropped working machines;
- a machine's **closure** is the machine, its parents up to `fdmprinter`, the extruder trains
  those definitions name and their parents up to `fdmextruder`; a closure that cannot be read
  in full is not offered, so the picker cannot hand the engine a stack it would reject.

The Cura settings sheet offers the catalogue behind a filter, and the choice is stored
(`cura-machine-id`). `CuraEngineRunner` stages that closure and slices with its machine and
extruder train, while the app's printer envelope, start/end G-code and settings layer over it
exactly as they do for an imported project. Nothing is chosen until the user picks: the default
is `creality_ender3`, whose closure is the same five files as before, so an untouched install
slices what it always did. `checkCuraEnginePackaged` fails an APK whose assets carry only the
historic chain.

Variants and quality resources (7 MB + 26 MB) are deliberately not shipped: they are frontend
concepts - nozzle-size choices and per-material quality levels - that this app models with its
own printer envelope and settings.

## Native configuration

The Android cross-build disables components that are not required for local
one-printer slicing:

- `ENABLE_ARCUS=OFF`
- `ENABLE_PLUGINS=OFF`
- `ENABLE_REMOTE_PLUGINS=OFF`
- `ENABLE_SENTRY=OFF`
- `ENABLE_TESTING=OFF`
- `ENABLE_BENCHMARKS=OFF`

`scripts/build-curaengine-android.sh` clones and pins CuraEngine into
`.build/CuraEngine` and produces the AArch64 executable staged in
`arm64-v8a` jniLibs; `app`'s `verifyCuraEngineExecutable` task fails any
assemble that lacks it.

## Packaged shared libraries

CuraEngine 5.14 links dynamically against cura-formulae-engine: the engine
binary records a `NEEDED` entry on `libcura-formulae-engine.so`, so the APK
ships it next to `libcuraengine_exec.so` in `arm64-v8a` jniLibs.

The Conan cache of a development machine can hold several builds of that
library at once — the AArch64 copy from the Android cross-build and an x86-64
copy from the host test build (`scripts/build-curaengine-host-tests.sh`).
`scripts/build-curaengine-android.sh` selects the candidate whose ELF machine
is AArch64 via the NDK `llvm-readelf` and fails the build when none exists.
Do not replace this with a first-match `find`: packaging the x86-64 library
makes the Android linker refuse to load the engine on-device
(`EM_X86_64 (62) instead of EM_AARCH64 (183)`).
