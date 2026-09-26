> **Historical record.** The native Smart Infill workflow this describes is
> committed and was confirmed working end to end on the device (round 38). This
> file is kept for the debugging record, not as a plan of record.

# Native Smart Infill - handover

**Status: done.** The native engine, its build/CI plumbing and the Plate workflow
are implemented, verified on the host, and confirmed working end to end on the
device by the user (slice included) in round 38. All of the work below is
**uncommitted** in the working tree of /root/src/enderslicercura (HEAD is still
3dc8aebb).

This document is a working handover, not a product doc: delete it when the work
is merged.

## The objective

Integrate filaSim's structural Smart Infill workflow (model + boundary
conditions/forces + material/print properties + FEA solve + density
optimization + modifier/Part-Topo export) natively into the app so the filaSim
WebView is no longer needed **for that workflow**, keeping the existing
SmartInfillPackage contract and slice integration intact. Tracked as session goal
goal-850687d6-749d-4e2b-8031-a97254419f1f.

Decisions taken by the user at the start:

| Question | Choice |
| --- | --- |
| Solver | Compile the pinned Rust core (filasim-core) as an Android .so behind JNI - not a Kotlin rewrite |
| Scope | Structural smart infill only; the WebView stays for the other filaSim tabs (thermal integrity, annealing, build simulation, ...) |
| Results view | Regions, densities, mass, safety factor **and the picked supports/loads drawn on the model** |
| Entry point | Embedded in the **Plate** screen as a floating panel, not a full-screen flow |

## Objective coverage (what is done, and what proves it)

| Objective element | Where it lives | Evidence |
| --- | --- | --- |
| Model in | `prepareSmartInfillSource` writes the canonical STL; `FilaSimNative.createSession` → `Session::new_from_bytes` (import + crease segmentation) | Rust session tests; host smoke; the instrumented test asserts `sourceSha256` equals the file's SHA-256 |
| Boundary conditions / forces | `FilaSimBoundaryCondition` (ten kinds) → `addBcJson` → `Session::add_bc_json`; picking through `region_around` / `original_triangles_of_patch` | Kotlin key contract test; Rust test accepting all ten payloads and refusing unknown ones; a force shown to arrive as a load through the assembled system; device run adding fixed + force |
| Material / print properties | `FilaSimConfiguration` → `configure_json`; `FilaSimMaterialPresets` (PLA, PETG, ABS, ASA, S235, 6061-T6, SLA) | `configure_json_takes_the_kotlin_payload` (all fifteen keys round-trip); preset tests; device run reading defaults back and rebuilding a coarse grid |
| FEA solve | `Session::solve` (matrix-free MGCG) | Rust tests; host smoke converged at rel. residual 9.1e-6; device run matching iteration for iteration |
| Density optimization | `Session::optimize`: graded / binary / Part Topo, goals budget / match / safety factor, SIMP + binning + smoothing | host and device smokes in every mode and goal (strength: SF 2.077 against a 2.0 target, feasible); cancellation covered by a test |
| Modifier export | `export_modifier_zip` → `SmartInfillNativeExport` (recenters into the package contract's frame) → `SmartInfillPackageStore.importPackage` | smoke reads its own archive back (names, rising densities, STL headers); the instrumented test imports the real archive into the real store and checks densities, base, mode and fingerprint |
| Part Topo export | `export_solid_stl` → `SmartInfillNativeExport.writeOptimizedShape` → `importPartTopoResult` (the WebView-era import path) | smoke: one body of 231 604 triangles, header consistent; the same path ran on the device |
| SmartInfillPackage contract | `smartInfillMetadataJson` (v2) + the store's validation | `FilaSimEngineContractTest`; the instrumented store import; the Cura contract tests for the pattern mapping |
| Slice integration | `SmartInfillRuntime` → CuraEngine modifiers, untouched WebView-era plumbing | its existing tests, plus `NativeResultFrameTest`: a staged native volume passes the real command builder and the build-volume preflight (and the un-recentered one is refused, naming the volume) |
| WebView no longer needed for this workflow (entry points audited in round 37: the More menu's Smart Infill row opens the native panel, and the WebView is reachable only from the panel's own "filaSim workspace · thermal, annealing, build simulation" button) | the Plate panel replaces it; the workspace button still opens the WebView for thermal / annealing / build simulation | the panel renders and wires up under Robolectric in the normal unit run (`SmartInfillWorkbenchPanelTest`: start, quick-adds, busy gating, armed hint, check/solve readouts, density legend); **confirmed working end to end on the device by the user in round 38** |

Two fixes are verified and built but **not installed**: the stale-session guard (round
19) and the palette repaint (round 20). The phone runs the 13:47 build. Install with
`adb install -r app/build/outputs/apk/debug/app-debug.apk` when the device is free.

## What exists now

### Native engine (native/filasim/)

- jni/src/session.rs - the session layer (mesh import, 10 degree surface
  segmentation, voxel grid, assembly, check, solve, optimize with
  budget/match/strength goals and graded/binary/solid modes, region extraction,
  exports). Mirrors the upstream filasim-wasm Model semantics, including the
  printable-geometry clamps, so it produces the package the WebView produced.
- jni/src/lib.rs - 27 JNI entry points on class
  com.tomppi.enderslicer.smartinfill.FilaSimNative. **Two handles**: the session
  (one worker thread) and a control handle (cancel + progress polled from the UI
  thread). Read native/filasim/README.md first.
- jni/src/bin/smoke.rs - runs the whole pipeline on the host; this is the
  parity/verification tool.
- Cancellation/progress use the core's own hooks:
  filasim_core::cancel::set_checker and progress::set_sink.

### Kotlin

- smartinfill/FilaSimNative.kt - JNI declarations (lazy ensureLoaded() like
  BlenderBridge; arm64 only).
- smartinfill/FilaSimEngine.kt - session API plus every data class
  (FilaSimBoundaryCondition with withTriangles, FilaSimOptimizeOptions,
  FilaSimConfiguration, region/progress/check/solve report types) and
  smartInfillMetadataJson (the v2 store contract).
- smartinfill/SmartInfillEngine.kt - the interface the workflow drives, so the
  workflow is unit-testable without the native library.
- smartinfill/SmartInfillController.kt - the workflow state machine: conditions
  and surface picks, settings, check/solve/optimize with progress, cancel,
  result, metadata. Android-free.
- smartinfill/SmartInfillNativeExport.kt - handoff files with the WebView's
  naming (*_smart_infill_modifiers.zip, *_optimized.stl).
- smartinfill/SmartInfillOverlay.kt - what the model shows: the picked supports,
  loads and armed condition per triangle, plus the result tint (a density bin per
  triangle).
- smartinfill/SmartInfillSelection.kt - selection geometry (the area-weighted
  centroid a remote point mass snaps to).
- smartinfill/FilaSimMaterialPresets.kt - the upstream material library as
  presets (PLA/PETG/ABS/ASA + steel/aluminium/SLA, the isotropic three turning
  layer-shear scoring off).
- viewer/DensityRamp.kt - the one density colormap, shared by the model tint and
  the panel legend (upstream's blue->cyan->yellow->red ramp).
- ui/SmartInfillWorkbenchPanel.kt - the Plate panel (conditions with editable
  values, material + resolution + goal, print assumptions, mode buttons,
  run/progress/stop, results with the density legend, apply).
- app/src/test/.../ui/SmartInfillWorkbenchPanelTest.kt - the panel under
  Robolectric: start, quick-adds, busy gating, the armed hint, the check and solve
  readouts and the density legend, all in the normal unit-test run.
- Wiring: ui/IntegratedEnderSlicerApp.kt owns the session lifecycle, the pick
  handler and result application; ui/MainViewModel.kt
  (setSmartInfillPickHandler, pickSurfaceAt, setSmartInfillPicking) and
  ui/MainUiState.kt (smartInfillPicking) route taps;
  viewer/ModelSurfaceView.kt has the tap-pick path (surfacePickActive /
  onSurfacePick, slop-guarded so a drag still orbits); ui/EnderSlicerApp.kt
  threads plateOverlayContent into ViewerPanel.
- The old WebView-era SmartInfillSheet composable was deleted; the panel's
  footer button still opens the WebView for the other filaSim tabs.

### Slice integration (unchanged, and that is the point)

A native result is committed through applyModifierPackage(uri, metadata, sha) in
IntegratedEnderSlicerApp.kt - the same import/activation/cleanup the WebView
handoff used - so SmartInfillPackageStore remains the single validation gate.
prepareSmartInfillSource(mesh) writes the canonical STL (the same writer the
validator hashes) and the engine analyzes that exact file, so the package's
fingerprint route is identical to the browser's.

### Build, verification, CI

- scripts/build-filasim-engine-android.sh - builds libfilasim_jni.so from the
  pinned upstream source (prepared tree if present, otherwise fetches the pinned
  commit) and stages it into app/src/main/jniLibs/arm64-v8a/; --host builds the
  smoke binary instead. Env: FILASIM_SRC, FILASIM_PREPARED_ROOT,
  FILASIM_NATIVE_SRC_ROOT, OUTPUT_ROOT, APP_JNILIBS.
- scripts/fetch-filasim-engine-android.sh - stages from FILASIM_ENGINE_DIR, a
  release asset (FILASIM_ENGINE_TAG + optional FILASIM_ENGINE_SHA256), or the
  newest filasim-engine-android CI artifact (FILASIM_ENGINE_RUN_ID, needs
  GITHUB_TOKEN).
- app/build.gradle.kts - :app:verifyFilaSimEngineLibrary (AArch64 ELF **and** the
  ...FilaSimNative_createSession symbol) gates every assemble*;
  :app:verifyDebugApkFilaSimContents checks the packaged library; both are in
  verifyDebugApkEngines / verifyReleaseApkEngines.
- .github/workflows/filasim-engine-android.yml - builds the engine, checks the
  ELF/NEEDED list/JNI symbol, runs the Rust session tests, smoke-tests the whole
  pipeline on a beam it generates, uploads the artifact.
- .github/workflows/build.yml builds the engine in the APK job; scripts/setup.sh
  stages it (build when Rust is present, fetch otherwise).

## Verified evidence (host)

- Cross-compile: libfilasim_jni.so is ELF64 AArch64, NEEDED only libdl/libm/libc;
  all 29 Java_...FilaSimNative_* symbols exported (27 in round 4, plus
  `configuration` and `regionAround` in round 6).
- Full pipeline, real part (hook5 v3.stl, 17 658 triangles): grid 112x208x32,
  solve converged in 24 MGCG iterations, optimize produced 2 regions (22 %, 42 %)
  over a 10 % base, mean infill 24.9 % against a 25 % target, 6.65 g vs 15.84 g
  solid, 25.7 s. Exported ZIP entries are valid binary STLs whose header counts
  match their byte lengths.
- Second smoke on a generated 30x12x8 mm beam (what CI runs): 2 regions, 24.8 %
  mean infill, 1.72 g of 3.57 g solid, 17.8 s. CI builds this model because the
  pinned upstream ships a STEP, not an STL.
- **The tint is repainted when the *colours* change, not just the bin count**
  (round 20): the renderer reused its colour buffer whenever the palette had the
  same number of slots, so a second optimization that produced the same number of
  bins with different densities kept the previous run's ramp on the part while the
  legend showed the new one. `PaintColorBuffer.hasPalette` compares the values and
  the renderer rebuilds (and re-uploads) when any of them differ; covered by a test
  on the buffer.
- **A session opened on geometry that is no longer on the plate is dropped**
  (round 19): `startNativeSmartInfill` prepares the model for tens of seconds
  (round 17 measured the grid build), and the plate can change under it. The model
  path is captured before the wait and checked again before the session is
  published; a mismatch closes the fresh session and tells the user to start again.
  Without that guard the panel would tint the new part with the old part's picks and
  Apply would export a package whose fingerprint the store refuses.
- **The JNI handle boundary is guarded, not trusted** (round 14): the handles are
  `Box::into_raw` addresses and `destroy*` was an unconditional `Box::from_raw`, so
  a second destroy was a double free and any call after a close handed out
  `&mut` to freed memory. `lib.rs` now keeps a registry of live handles: creates
  register, a destroy frees only what is still registered (a repeat is a no-op),
  and `session_ref`/`session_mut`/`control_ref` refuse an unknown or destroyed
  address, so the shim throws instead of dereferencing it. `FilaSimEngine.close()`
  is idempotent and zeroes both handles *before* destroying them. Two Rust tests
  cover the registry (18/18). Note the smoke binary talks to `Session` directly and
  never exercises the shim, so this layer is covered by those tests, the symbol
  checks and the app itself — not by the host smoke.
- **Session lifetime and the closed path are tested** (round 13): a run held
  inside the engine (a `NonCancellable` gate, the way a blocking JNI call behaves)
  proves that `close()` destroys the native session only after the call returns,
  and that closing twice destroys once. The test that started as "a closed
  controller refuses work" failed against the real code: `close()` set its flag but
  `launchWork`, `pickAt` and `expandToSurface` never read it, so a closed panel
  could still start a run against a destroyed session — and the tap path called the
  engine unwrapped, so a native failure during a pick would have thrown into a
  Compose callback and crashed the app. All four now gate on `closed`, and the two
  tap-path calls report instead of throwing.
- **Stop is a tested path** (round 12): `a_cancelled_optimization_stops_early_and_
  `clears_its_state` raises the shared flag from a second thread mid-run and asserts
  the run gives up quickly, reports it, and leaves `running: false` in the snapshot
  the panel polls. Fixing that last part closed a real state bug: `solve` and
  `optimize` returned on their error paths (cancel, under-constrained model,
  assemble failure) without clearing `running`, so the engine kept claiming a run
  that was over. Reverting the fix makes the test fail, so it is not vacuous.
- **The JNI signatures themselves are pinned** (round 11): a `jni_tests` module in
  `lib.rs` reads `FilaSimNative.kt` (walking up from the crate, so it also runs in
  the staged CI tree), and checks every declaration against its export — name,
  argument count, each argument's type and the return type — because JNI resolves
  by name alone and a drifted signature would compile on both sides and crash on a
  phone. It found one: `clearResults` was exported but never declared or called
  (a byte-identical twin of `clearBoundaryConditions`), so it is gone.
- **The Kotlin/native JSON seam is pinned from both ends** (round 10): the Kotlin
  writer is pinned by `FilaSimEngineContractTest` and the Rust reader by three
  session tests — the exact `FilaSimConfiguration.toJson()` payload (material,
  resolution, acceleration, solver limits) round-trips through `configure_json`
  and `effective_config`, all ten `FilaSimBoundaryCondition` payloads are
  accepted (an unknown kind, no triangles and an empty selection are refused),
  and a force payload is shown to arrive *as a load* by the assembled system's
  own check report (`hasLoads`). Parsing now lives in `Session::configure_json`,
  mirroring `add_bc_json`, so the payload the app writes is testable without JNI.
- **All three output modes verified on the host with their real artifacts**
  (round 9; the smoke takes a mode and reads its own export back with
  `filasim_core::zip::read_zip`, the reader the 3MF import uses):
  *graded* - 2 entries `modifier_18pct.stl` / `modifier_38pct.stl` over a 10 %
  base; *binary* - 2 entries, base 10 % up to 66 %; *Part Topo* - one body of
  231 604 triangles, an 11 580 284-byte binary STL. Every entry is checked for
  its name, its rising density and a binary STL header that matches its byte
  length. CI greps `verify: modifiers ok (binary` and `verify: solid ok (`.
- **Result tint** (round 8): `surface_bins` resolves the density bin under every
  surface triangle by marching the normal both ways to the first design cell (the
  design field excludes the printed skin, and a mesh's winding is not
  guaranteed). Smoke readouts: beam **12/12 resolved, {0: 6, 1: 2, 2: 4}**;
  hook5 v3 **17 658/17 658, {0: 10 686, 1: 5 161, 2: 1 811}**. CI greps the beam
  line, so a regression in the tint data fails the engine build.
- **Strength goal** (round 7, the smoke takes a goal argument now), same beam:
  goal "strength", sf_target 2.0, 4 passes / 35 iterations, converged,
  **sfAchieved 2.077, sfFeasible true, sfMeasure both**, mean infill 10.26 %
  against the 10.26 % it walked down to, 1.3626 g of 3.5712 g solid, 41.0 s.
  That is the pipeline behind the panel's "Safety factor" goal, and CI asserts
  goal/converged/sfFeasible/sfAchieved >= sfTarget on every engine build.
- **Parity finding**: the Enderslicer patch chain touches only
  crates/filasim-core/src/thermal.rs and the crate-root declaration - no
  structural file. A build from the pristine pinned commit produced an
  **identical summary JSON and an identical 21 202 814-byte modifier archive** as
  the patched tree. That is why the build script may fetch the pristine commit.
- Gradle: :app:verifyDebugApkEngines green with the new checks; the library check
  has teeth (swapping in another engine's .so fails with "came from the wrong
  crate").
- Tests: 8 Rust tests (4 option/clamp contract, 2 effective-config, 2 bounded-pick:
  a flat 20x20 grid is ONE patch and a 3 mm pick must not take it; a huge radius
  on a cube stops at the face crease). The whole Kotlin suite is green, including
  the new SmartInfillOverlayTest and the overlay cases in PaintColorBufferTest.
- Round 6 smoke re-run on hook5 v3.stl after the Session changed: identical
  numbers to the round-4 baseline (24 MGCG iterations, mean infill 24.91 %,
  6.6514 g of 15.8398 g solid, 2 regions) and the same 21 202 814-byte modifier
  archive - the picking/overlay/config work did not touch the solver.
- Installed on the phone: app-debug.apk (318 MB, contains
  lib/arm64-v8a/libfilasim_jni.so, 1 271 384 bytes stripped) installed
  2026-09-22 12:22, app starts clean (no FATAL/AndroidRuntime lines).
  **The functional on-device pass is with the user** (they said they would do it;
  the agent installs only). The round-5 pass reported the two bugs fixed in round
  6, so a fresh pass on this build is what confirms them.

Reproduce the host evidence:

    cd /root/src/enderslicercura
    scripts/build-filasim-engine-android.sh --host
    .build/filasim-engine-android/target/release/filasim-smoke /tmp/filasim-beam.stl /tmp/filasim-smoke 25
    # ... and again with the strength goal (4th argument: budget|match|strength):
    .build/filasim-engine-android/target/release/filasim-smoke /tmp/filasim-beam.stl /tmp/filasim-smoke-strength 25 strength
    # ... and the other two output modes (5th argument: graded|binary|solid):
    .build/filasim-engine-android/target/release/filasim-smoke /tmp/filasim-beam.stl /tmp/filasim-smoke-binary 25 budget binary
    .build/filasim-engine-android/target/release/filasim-smoke /tmp/filasim-beam.stl /tmp/filasim-smoke-solid 25 budget solid
    ./gradlew :app:testDebugUnitTest :app:verifyDebugApkEngines
    # engine build from a clean clone (fetches the pinned commit itself):
    FILASIM_PREPARED_ROOT=/tmp/empty FILASIM_NATIVE_SRC_ROOT=/tmp/filasim-native-test \
      scripts/build-filasim-engine-android.sh --host

### What a real model costs (host, 3DBenchy, 225 143 triangles)

A whole graded run at the defaults (25 % budget, 300k target cells) took **152 s**
on the host and peaked at **692 MB**: segmentation 3 969 patches, grid 176×96×144
at h = 0.373 mm (2.43 M cells, 319 474 solid), solve converged in 230 iterations,
2 modifier regions (26 % and 70 %), 51 % stiffer than uniform infill, and the tint
resolved **86 %** of the surface (the rest has no design cell within 8 mm — the
march reach; at 3 mm it was 83 %). Expect *minutes*, not seconds, on the phone,
and note that a much denser model (a 1.3 M-triangle platter) scales the same way:
the grid is capped by the target-cell setting, but the imported mesh, its pick
index and the region meshes are not, so memory grows with the triangle count.

Phase timings, measured on the same model with a throwaway `tests/timing.rs` in the staged tree
(never committed — it prints and asserts nothing about the engine):

| phase | time |
| --- | --- |
| import + crease segmentation | 0.23 s |
| **grid build (`sessionInfo`)** | **36.96 s** (2.43 M cells, 319 474 solid) |
| patch map over 225 143 triangles | < 1 ms |
| first tap (welds the pick index) | 56 ms |
| tap, average of 200 | **0.5 ms** (worst 1.1 ms) |
| "Face" expansion (whole patch) | 0.4 ms |
| setup check | 0.04 s |

One observation worth knowing (not acted on): at session start the panel calls
`sessionInfo()`, which is what builds that grid — purely to show the "grid n×n×n"
line. Picking, the spot radius and the patch map need the mesh, not the grid, so the
opening wait could be deferred to the first Check/Solve/Optimize (the cost is the
same, but the panel would open instantly and the wait would arrive when the user has
asked for computation). That is a workflow-timing decision for the user, not a bug:
today the trade is "wait once up front, then everything is instant".

So the *interactive* cost is negligible — a tap is half a millisecond — and the
session start is one long grid build, which the panel already runs on a worker
with "Preparing the model for the analysis…". The lever is the panel's *Grid target
cells* field: the default 300 000 is upstream's and is what that 37 s buys, while a
coarse grid for a first look is seconds. Nothing here needs fixing; it is what the
user should expect on the phone.

## Verified evidence (device)

The engine binary built for `aarch64-linux-android` runs on the phone itself.
`/data/local/tmp` is *not* writable by the adb shell on this firmware (SELinux
denies even a listing), so the binary runs inside the app's own sandbox — the app
is debuggable, and `run-as` may execute its own files:

    adb shell "run-as com.tomppi.enderslicercura mkdir -p files/smoke"
    adb shell "run-as com.tomppi.enderslicercura sh -c 'cat > files/smoke/filasim-smoke'" \
        < .build/filasim-engine-android/target/aarch64-linux-android/release/filasim-smoke
    adb shell "run-as com.tomppi.enderslicercura sh -c 'cat > files/smoke/beam.stl'" < /tmp/filasim-beam.stl
    adb shell "run-as com.tomppi.enderslicercura sh -c 'chmod 755 files/smoke/filasim-smoke && cd files/smoke && ./filasim-smoke beam.stl out 25 budget graded'"

Rebuild that binary (`scripts/build-filasim-engine-android.sh` does, for every
Android build) before trusting it, and delete `files/smoke` afterwards so the app
keeps no stray files. `cat >` writes the file without an exec bit, so the
`chmod 755` in the last line is what makes it runnable — without it the shell says
`can't execute: Permission denied`.

Every number matches the host builds exactly:

- graded budget on the beam: 22.0 s, mass 1.7216188298607997 g, mean infill
  24.85 %, 2 regions, bins {0.10: 23 235, 0.18: 15 818, 0.38: 34 455} cells;
- strength goal on the beam: 47.6 s, 4 passes, converged, SF 2.077 against a 2.0
  target and feasible, mass 1.3626328778609513 g;
- `hook5 v3.stl` (17 658 triangles): 35.0 s, 2 regions, mass 6.6513982927827 g,
  surface bins 17 658/17 658, {0: 10 686, 1: 5 161, 2: 1 811};
- Part Topo: 61.1 s, one body of 231 604 triangles, an 11 580 284-byte STL.

So the arm64 build is numerically identical to the host build — solver iteration
counts included — and every goal and output mode runs on the device. The UI
wiring (panel → controller → JNI) is what the manual pass still covers.

## Device policy (read before touching the phone)

**The user does the on-device testing.** Installing the APK is the agent's job;
running tests on the phone is not. Do not start instrumented test runs, do not
drive the UI with `adb shell input`, do not open panels to "just check" something,
and do not wake or unlock the device. If a device check is wanted, hand the user
the exact command and let them run it.

That boundary was crossed once (round 15) and it cost real state:
`./gradlew :app:connectedDebugAndroidTest` **uninstalls the app under test and its
test APK when the run finishes**, and uninstalling wipes the app's data — the plate
model, the workspace snapshot and the app-side settings. Their STL files in
`/sdcard/Download/dsh-agent/` are safe (shared storage, not app data), but anything
that lived inside the app is gone.

If a connected test is ever run *by the user*, the APKs can be kept in place with:

    ./gradlew :app:connectedDebugAndroidTest \
        -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
        -Pandroid.testInstrumentationRunnerArguments.class=com.tomppi.enderslicer.smartinfill.FilaSimShimTest

(`android.injected.androidTest.leaveApksInstalledAfterRun` is an AGP property;
verified against the AGP 9.2.0 jar in this environment and empirically.) The
instrumented test itself is worth keeping: it is the only coverage of the JNI shim
(handle registry, JSON marshalling, `surfaceBins`, the archive and the store
import), and it passed on the device.

## Environment facts

- Repo/working dir: /root/src/enderslicercura (cwd is /root).
- Pinned upstream: CNCKitchen/smartInfillGenerator commit
  e7485ec22d4ebe8baca04190404fbb877c90e031.
- Prepared (patched) tree already on disk:
  .build/filasim-android/e7485ec...-format8/ - used by the WebAssembly asset
  build; the native build prefers it when present.
- NDK 28.2.13676358, SDK /opt/android-sdk, JDK 21 active (build targets 17),
  Gradle wrapper 9.4.1. Rust 1.98 with aarch64-linux-android installed.
- Phone: SM-F946B over adb (`<phone-tailnet-ip>`:5555).
- scripts/env-setup.sh reports the toolchain, phone, harness and GPU box.
- The WebView-era filaSim assets are still packaged
  (app/src/main/assets/filasim/) because the other filaSim tabs still use them.

### Re-run on the user's own model (round 36)

The host smoke was run on the exact STL pulled from the phone (the s-hook,
6348 triangles) at the engine defaults, to confirm the native pipeline on the
geometry the user is testing rather than on a benchmark:

    imported 6348 triangles -> working 99136, bodies 1, patches 1
    grid 288 x 208 x 24 (h = 0.501 mm, 1 437 696 cells, 308 247 solid)
    check: ok (one component, constrained, has loads)
    solve: converged, 21 iterations, rel. residual 7.2e-6, max disp 0.000753 mm
    optimize: budget 25 %, 2 regions, bins 10/19/33 %, mean infill 24.9 %,
              mass 17.88 g against 46.79 g solid, 14 iterations
    surface bins: 6348/6348 triangles resolved
    wrote smoke-modifiers.zip (11 MB), verify: modifiers ok

## Final verification (round 33, host)

| check | result |
| --- | --- |
| Rust: `cargo test -p filasim-jni --lib` | **18/18** |
| Kotlin: `:app:testDebugUnitTest` | green, and every file this feature touches compiles without a warning (the panel tests moved to the non-deprecated Compose `v2` rule) |
| `:app:assembleDebug :app:verifyDebugApkEngines` | green — all five engines packaged |
| `:app:verifyReleaseApkEngines` | green (round 22) |
| Engine binary | aarch64, NEEDED only libc/libm/libdl, 58 JNI symbol entries = 29 functions x 2 tables, matching the 29 Kotlin declarations |
| Host vs device numerics | identical in every smoke run (rounds 13-16) |
| Frame contract | `NativeResultFrameTest`: the store keeps a staged volume where the engine put it, the real command builder accepts it, and an un-recentered volume is refused with its own name |
| Panel behaviour | `SmartInfillWorkbenchPanelTest` under Robolectric: start, quick-adds, busy gating, armed hint, check and solve readouts, density legend, vector editing, live NaN refusal, material presets, goal buttons |
| Slice command | built on the host from a staged native package (round 32) |

What is left is the user's own Slice with an applied package, plus their
judgement on the region-shell look. Nothing on the host can stand in for those.
## The frame contract (the bug that bit twice)

SmartInfillPackageStore.stageModifiers documents it, PartTopoResultPreparer
documents it, and the native producer has to honour it: **a filaSim result is
expressed in filaSim's local frame — centred on X and Y, grounded at Z**. The
WebView producer worked that way, so the store adds the analyzed model's centre
and base back when it stages the volumes, and the Part Topo import places its
body the same way.

The native engine instead works in the analyzed STL's own, already placed
coordinates, so its volumes were shifted a second time: on the user's s-hook a
30 % region reached X = 277 mm on a 210 mm bed and the slice refused the model
as outside the build volume ("Model vertex 1 ... X=267.71844").
One refinement found by writing the host test (round 31): `ModelPlacement.transformed`
**recenters before it places**, so the Part Topo import was already robust to either
frame — the pure translation in `stageModifiers` is what made the modifier path
fragile. Writing the Part Topo body in the local frame anyway keeps both producers
identical and is pinned by `NativeResultFrameTest`, which also reproduces the
device failure: export a volume, stage it as the runner does, and assert it is
still where the engine put it and inside the bed.

`SmartInfillNativeExport.recenter` / `writeOptimizedShape` now move both the
modifier archive and the Part Topo body into that frame (subtracting exactly the
`binaryStlBounds` the store measures, so the two cancel to the float), and the
store and the import path stay untouched.

The device evidence, from `files/logs/curaengine-last.log` on the phone: the
Apply itself succeeded (the package was imported at 15:46); the red box came from
the **slice** that followed, at `CuraEngineCommand.kt:105` —
`requireBinaryStlFits(modifier.file)` — refusing a *staged modifier* as outside
the build volume. Replaying both of the phone's real volumes with the analyzed
file:

    model X[36.50,173.50] Y[61.00,159.00]  -> restore shift (105.00, 110.00, 0.00)
    30 %: engine X[39.86,172.77] Y[87.46,128.63]  before-fix staged X[144.86,277.77] Y[197.46,238.63]  fits: no
    68 %: engine X[40.20,172.13] Y[91.41,128.16]  before-fix staged X[145.20,277.13] Y[201.41,238.16]  fits: no
    both: after-fix staged back to the engine coordinates -> fits: yes

The reported vertex 1 (267.71844, 197.96725, 0.57301) sits inside that shifted
box: vertex 1 is not a bounding corner.

Still open, and pre-existing (it affects WebView packages the same way): when a
Cura profile is imported, `CuraEngineRunner` stages the *unplaced* slice source as
the model while the modifiers are staged from the *displayed* file, so the two
are in different frames for that path. The user's slice log says "Imported Cura
values: 0/0", i.e. no profile, so their path is the consistent one.

A measured example from the device (s-hook, 6348 triangles): analyzed source
X[36.5, 173.5] Y[61, 159], centre (105, 110), minZ 0; the engine's 30 % volume
X[39.856, 172.767] is already placed; written recentered it is X[-65.1, 67.8],
and the store stages it back to X[39.856, 172.767] — inside the bed.

## The region view on the model

**A preview tool exists** (round 34): `.build/shell-preview/render-shell-variants.mjs`
(node, no dependencies) reads the analyzed STL and the engine's region STLs and
renders the result view offscreen with the app's own density ramp, lighting and a
z-buffer — used to compare the wireframe against other displays without a device.
It reproduces what the app draws (depth-tested edges), an x-ray variant and
translucent region surfaces. What the render showed on the s-hook:

- the depth-tested edges the app draws today only show where a region is coplanar
  with the outer surface, so they read as **patches of paint**, not as a wireframe;
  the regions of this model are internal volumes, so most of their edges are
  hidden. That is the "weird" look the user reported.
- no-depth-test (x-ray) edges look much the same at phone scale: the internal
  edges project into dense bands rather than a legible mesh.
- **translucent region surfaces** (upstream filaSim's own density view) read best:
  the 68 % core is visible inside the part, the 30 % region around it, and the
  model's own shading stays visible through both.

A three-panel comparison was delivered to the phone at
`/sdcard/Download/dsh-agent/smart-infill-shell-preview.png` for the user to pick.

The user asked for the optimized regions to be shown on the model, and chose the
region volumes (not the model's own triangles) as the subject. What the viewer
does with them, after the preview above:

- `SmartInfillOverlay.volumes` carries the engine's `FilaSimRegion` meshes from
  the controller through to the viewer; they are in the model's coordinates, so
  the viewer reuses the same model matrix.
- `viewer/WireframeBuffer` turns a mesh into a flat position/colour pair, in the
  shape the draw needs: `triangles` (three vertices per triangle, the surfaces the
  viewer draws now) or `soup`/`indexed` (six, the same geometry's edges, kept for a
  wireframe), joined across regions by `concat`. Unit tested, including the
  dropped-triangle, empty-input and mixed-shape cases.
- **What the renderer draws, after the preview (round 35)**: translucent region
  surfaces — per-vertex colour from `DensityRamp` at each region's own density,
  `REGION_ALPHA = 0.40`, blending on, `GL_DEPTH_TEST` off and `glDepthMask(false)`
  so a nested core shows through the part. Depth-tested edges were tried first and
  failed: they only paint the patches of a region that lie on the outer surface,
  which is the "weird" look the user reported. `WireframeBuffer.triangles` builds
  the surface shape; `soup`/`indexed`/`concat` still build the edge shape, so
  switching back to a wireframe is a change in `rebuildRegions` plus the draw call.
- The surface tint is off while volumes are drawn (the regions carry the colour);
  the picked supports, loads and armed surface stay tinted either way.
- **Unverified on a device**: how the 40 % blend reads at phone resolution and in
  daylight, and whether overlapping volumes need a sorted draw. They are not
  sorted: blending without depth ordering is inexact but readable, and sorting a
  quarter-million triangles per frame is not worth it. `REGION_ALPHA` is the dial.

## Gotchas hit while building this (save yourself the time)

- **A rejected volume must name itself.** `CuraEngineCommand` validated the Smart
  Infill and adaptive-wall modifiers with no `label`, so the envelope exception
  said "Model vertex N" for a *modifier* and the whole round-29 diagnosis started
  on the wrong file. Both call sites now pass a label ("Smart Infill 30%
  modifier"), pinned by a test in `PrinterEnvelopeTest`.
- **Every cache file handed out through a content URI must live in a directory
  `res/xml/file_paths.xml` lists.** The Apply path wrote the modifier archive to
  `cache/smart-infill-native/`, which the one-shot provider does not serve, so
  `FileProvider.getUriForFile` threw `IllegalArgumentException: Failed to find
  configured root` and crashed the app on the Apply button (round 26). The export
  now uses `OneShotExportFileProvider.SMART_INFILL_DIRECTORY` — the served
  `smart-infill-exports/`, the same directory the WebView handoff uses — and
  `ExportPathsTest` builds a real URI for a file in each served directory through
  the packaged provider, plus checks the Apply source uses the constant.
  The provider opens read descriptors once and deletes the file when closed, so
  the store must keep reading a URI in a single `openInputStream` (it does).
- **Robolectric on this module is pinned to SDK 35.** `:app:testDebugUnitTest` runs on
  a Java 17 JVM and Robolectric only drives SDK 36/37 on Java 21, so
  `SmartInfillWorkbenchPanelTest` carries `@Config(sdk = [35])`; the build needs
  `testOptions.unitTests.isIncludeAndroidResources = true` for the packaged
  resources. Raise both only together.
- **Release builds do not minify today** (`isMinifyEnabled = false`), so
  `FilaSimNative`'s class and method names stay as the JNI symbols expect. If
  minification is ever enabled, that class (and `nativebridge/BlenderBridge`) needs
  a `-keep` rule: JNI resolves by name, and obfuscation would turn every call into
  an `UnsatisfiedLinkError` in release only.
- **The AGPL notice has to name the native binary.** The APK ships a compiled
  filaSim core, not just the workspace, so the packaged `SOURCE.md` (generated by
  `scripts/prepare-filasim-assets.py`) and `THIRD_PARTY_NOTICES.md` both name
  `lib/arm64-v8a/libfilasim_jni.so`, the pinned commit, `native/filasim/` and
  `scripts/build-filasim-engine-android.sh` as its corresponding source, and
  `checkFilaSimPackaged` fails the build if the licence or the notice is missing
  from the APK (debug and release).

- Writing files through run_code template literals: a dollar-brace in
  shell/Kotlin/YAML is interpolated by JS, and **backticks end the template**.
  Use String.raw with an at-brace -> dollar-brace placeholder replace, or use the
  edit/write tools with single-quoted JS strings (backticks and dollar-brace are
  then harmless), or simply avoid backticks in file contents.
- edit/write require a prior read of the file, and the edit result echoes the
  whole file - do not console.log it.
- Kotlin **local functions must be declared before use** (that is why
  applyModifierPackage / prepareSmartInfillSource sit above
  processSmartInfillResult).
- ModelSurfaceView only emits pick hits while something is armed; the Smart
  Infill path uses its own surfacePickActive flag rather than the paint mode.
- The store's rules to respect: modifier densities unique, increasing, all
  greater than base and at most 100; at most 16 modifiers; graded must NOT carry
  binarySolidPattern; upstreamCommit must equal the pinned commit; sourceSha256
  must match the canonical STL.

## Close-out, and the next round (UI polish)

**Done, and confirmed on the device (round 38).** Nothing here blocks a merge: 49
paths (51 after round 39) uncommitted at 3dc8aebb, every check green (see "Final verification" above),
and the user confirmed the whole workflow — panel, picks, solve, optimize, apply
and slice — working end to end. Before merging: commit the change set (the user
decides how it is split) and delete this handover; the CHANGELOG already carries
the user-facing bullets.

### UI polish candidates, roughly in the order a user feels them

- **Opening the analysis is a long, silent wait.** The grid build was ~37 s on the
  host for a 225k-triangle part (round 17) and is minutes on the phone, while the
  panel says only "Preparing the model for the analysis…". An animated (or better,
  determinate) indicator plus a hint that *Grid target cells* is the lever would
  make it honest. The engine already has a progress sink the panel uses for
  solve/optimize (`FilaSimProgress`); the session start does not report yet.
- **The panel is capped at `heightIn(max = 440.dp)` with a vertical scroll.** On the
  fold that is either dead space or a cramped scroll depending on the section open.
  A real bottom sheet (drag handle, half/expanded snaps) fits content this long:
  conditions, material, print assumptions, goal, results.
- **Section density.** Fifteen material and resolution fields sit in one flat list.
  Collapsible "Material" and "Print assumptions" groups (auto-expanded when a value
  is off its preset) would cut most of the scrolling.
- **Results presentation.** The legend has swatches per bin; it could also carry the
  region view's blend strength (`REGION_ALPHA`), each region's share of the volume,
  and the gain-versus-uniform figure the summary already returns.
- **Condition rows** read "N triangles / tap the model to choose its surface". The
  armed state relies on the model tint alone and could be louder in the row.
- **Part Topo** replaces the model and reports with a toast; the panel could state
  what changed (triangle count, dimensions) before the user slices it.
- **Touch targets and contrast** have never been audited on the device.

### The UI polish pass (rounds 39+)

**Round 39 - a folded card is a chevron tab.** A folded Plate card used to keep its
header line. It is now nothing but its chevron: `CollapsibleCard` moved out of
`EnderSlicerApp.kt` into `ui/CollapsibleCard.kt` (internal, so the behaviour is
unit-tested directly), and folded it composes one 48dp square - the smallest
Material still calls a touch target - with the chevron centred and the whole
square tappable. There is no title left to name the card, so the chevron carries
it for TalkBack ("Expand Print session").

What the next round should know:

- All five call sites fold this way: the printer card and the status banner stay
  against the left edge; Print session, Quick settings and Actions now tuck
  against the panel's end edge, so the three stack into one rail down the right.
- Print session's "Ready" chip and "Hide" button ride in the header, so they are
  reachable only while the card is open, and the banner's status line is only
  visible while the banner is open. If the plate wants a status line that is
  always there, that is the item to revisit.
- The session panel's `widthIn(min = 230.dp)` drops to 0 while all three cards
  are folded, so the transparent box the cards float in stops covering the model
  once there is nothing in it.

Verified: `:app:testDebugUnitTest` green including the new `ui.CollapsibleCardTest`
(folded shows no title and no content and the chevron opens it; open shows header
and content and folds from the header), `:app:assembleDebug` and
`:app:verifyDebugApkEngines` green, and the debug APK installed for the user's
device pass.

**Round 40 - the foldable gets two cards, and the banner moves up.** Both came out
of the same device pass.

- The status banner is no longer the plate's bottom-left corner: it is the second
  card of the printer column, under the printer card. It used to land on top of
  the Smart Infill panel, and the bottom now belongs to that panel and the Slice
  block. `ViewerPanel` takes the banner's state (`noticeExpanded`,
  `onNoticeToggle`, `hintsDismissed`, `onDismissHints`) because the left column
  is its child; the bottom stack holds only the Model tools overlay now.
- `SmartInfillWorkbenchPanel` is two cards when it is wide.
  `BoxWithConstraints` reads the panel's own width - it is as wide as the plate -
  and splits at 600dp, the same threshold the app calls an expanded layout, into
  "Smart Infill · setup" against the left edge and "Run and results" against the
  right, with the model between them. One section list renders both:
  `AnalysisSection` takes an `AnalysisPane` and dispatches to `SetupSections`
  (model, boundary conditions, tap radius, material) and `RunSections` (goal,
  print assumptions, output mode, check/solve/optimize, results, apply), so the
  phone card and the two fold cards cannot drift apart. Each card is capped at
  `min(0.6 x plate height, 440dp)`.
- Opening the workflow on a fold folds the three session cards to their chevrons:
  the workflow needs that right-hand column. `EnderSlicerApp` gained
  `plateOverlayActive` and the host passes whether the overlay is on screen.
  Nothing restores them automatically - each card is one tap - which is the thing
  to settle if it turns out to be annoying.

Verified: `:app:testDebugUnitTest` green, including three new panel tests -
`aFoldableSplitsTheWorkflowInTwoCards` (at w1000dp-h800dp: both headers, and each
section rendered exactly once), `theTwoCardsSitAgainstOppositeEdges` (the tagged
cards measured into the left and right thirds with more than 50dp of model
between them - the test that caught the pane Row needing to be full width) and
`aPhoneKeepsOneCard` - plus `:app:assembleDebug` and
`:app:verifyDebugApkEngines`. APK installed.

**Round 41 - Material's adaptive libraries, and the rail.** Three of the four
Material 3 Adaptive candidates were picked: the window's own size class, the
fold-aware spacer, and the navigation suite.

- `app/build.gradle.kts` takes `androidx.compose.material3.adaptive:adaptive`,
  `:adaptive-layout` and `androidx.compose.material3:material3-adaptive-navigation-suite`,
  all versioned by the Compose BOM (2026.06.00 -> adaptive 1.2.0, navigation suite
  1.4.0), so there are no versions to keep in step by hand.
- The tab bar is `NavigationSuiteScaffold`: the same four destinations, a bottom bar
  on a phone and a rail down the side on a fold. `AppTabBar` became
  `NavigationSuiteScope.AppTabItems` (internal, so the JVM test drives it), the
  Scaffold at the root of `EnderSlicerApp` moved inside the suite, and the bottom bar
  slot now holds only the phone's action bar.
- The Smart Infill split follows the adaptive rules: two columns need the *expanded*
  width class (`isWidthAtLeastBreakpoint(WIDTH_DP_EXPANDED_LOWER_BOUND)`, 840dp, so a
  medium 600..840dp window keeps the single card), the band between the columns is
  the directive's `horizontalPartitionSpacerSize` (24dp), and when the directive
  reports excluded bounds - the device's fold - the band is placed on the fold inside
  the panel (`splitBand()`, from `positionInWindow()`), so it stays on the crease even
  with the rail taking the window's left edge.

Verified: `:app:testDebugUnitTest` green, including `SplitBandTest` (a fold inside
the panel becomes the band; a fold outside it, or one wide enough to leave slivers,
falls back to the spacer) and `AppNavigationTest` (a 460dp window puts the first
destination's label along the bottom, a 1184dp one puts it at the top of the rail),
plus `:app:assembleDebug`, `:app:verifyDebugApkEngines` and an install.

Worth knowing: `calculatePaneScaffoldDirective` runs against window-core 1.5.0, which
no longer has the old `WidthSizeClasses` API its sources jar still shows. The panel
tests call it through the real composition and pass, so the published adaptive
binaries are built against the new API - but if adaptive is ever upgraded on its own,
that is the pairing to re-check.

**Round 42 - the plate's XYZ marker moved onto the bed.** The 84dp Compose widget in
the plate's top-left corner is gone from the Model view: `viewer/AxisTriad.kt` builds
three coloured arrows on the bed's front-left corner and `ModelSurfaceView` draws
them with the line program after the mesh, so depth testing hides them behind the
part. The arm is resized every frame from the corner's own view depth
(`AxisTriad.armMm`), which keeps the marker the same size on screen at any zoom,
so nothing about it is screen-anchored except its size. The head of each arrow is
built in the plane facing the eye, borrowed from another axis when the axis points
straight at the camera, and each arrow names itself with a letter (X, Y, Z as line
glyphs in that same plane, three lines each with X's third one degenerate) - the
letters the floating widget used to render as text, so a fold never separates a
letter from the arrow it belongs to. The letter plane is squared to the *camera's*
up, not the plate's: the renderer hands over row 1 of the plate-to-view matrix
(`cameraUp`), because with the eye nearly straight above the bed corner - which is
the default view - the plate's own up is parallel to the view direction, and the
first cut of this had the letters lying on their side. Two tests pin it:
`aLetterIsSquaredToTheCameraNotToThePlate` and
`aLetterPlaneIsAlwaysPerpendicularToTheView`.

Two more defects came out of that same round, both from looking at the phone's
screenshots magnified rather than at the code:

- The Y *glyph itself* was written upside down (its arms started at the bottom of
  the letter box), so it read as an inverted Y however good the basis was. The
  letters are now checked as shapes: `everyLetterReadsTheRightWayUp`.
- The letters sat on top of their arrowheads. The head reaches 0.30 of the arm
  back from the tip, and the letter box reaches 0.20 out from its own centre
  either way, so the gap between tip and letter is 0.55 of the arm:
  `everyLetterSitsClearOfItsArrowHead` measures the closest point of each letter
  against the head's length.

How this was found is worth keeping: `AxisTriadProjectionTest` builds the
renderer's real look-at, scene and projection matrices and asserts that each
letter's up goes up the screen and its across goes left to right - and a throwaway
test drew the same vertices into a PNG with Java2D so the marker could actually be
looked at offline, without the phone. That render is what showed the upside-down Y. The plate's left column is now the printer card and the
status banner only. The two path views (Cura and Prusa) keep the Compose gizmo:
they are 2D previews with no bed to sit on.

Verified: `AxisTriadTest` 3/3 (the arm doubles with depth and halves with viewport
height, every axis is a shaft plus a two-line head, an axis pointing at the camera
still gets one) and the whole unit suite, `:app:assembleDebug`,
`:app:verifyDebugApkEngines` and an install, all green.

**Round 43 - the session in the sidebar, and settings you can change in place.**
The user asked for two things off a marked-up screenshot: move the print session's
headline values and slice-state chip into the left sidebar, and let the Quick
settings card change its values instead of navigating to the Settings tab.

- `ui/SessionValues.kt` is the new shared piece: `sessionValues(engine, summary,
  state, onSettings, onPrusaSettings, onOrcaSettings)` returns the four values with
  the *editors* that change them. Each editor carries a value, a range or a list,
  and a `set` lambda that writes the active engine's own key - the same callbacks
  the Settings tab uses, so the two screens cannot drift apart. A value with two
  settings behind it (infill density *and* its pattern) carries one editor each.
- `SessionRail` is that session *in the rail*: a 150dp column holding the four
  destinations, a divider, then the state chip with layer height, infill and
  supports under them, each opening its editor. The first cut put a second column
  beside the rail and the user rejected it on sight - "I don't want a new sidebar
  on the left side I want it in the existing sidebar" - so the shell changed
  shape: the M3 `NavigationSuiteScaffold` has no room to host content in its own
  rail, so the Scaffold now lives in a local `chrome` lambda and the wide branch is
  `Row { SessionRail(..); chrome(weight 1f) }` while a phone keeps
  `NavigationSuiteScaffold { chrome(fillMaxSize) }`. `AppTab` grew a `shortLabel`
  (the rail and bottom bar say "Settings", the page title stays "Print settings")
  and an `icon` mapping, which `AppTabItems` now uses too. The Print session card
  hides its three tiles and its chip when the rail carries them
  (`sessionInSidebar`).
- Follow-up on the same screenshot: "remove the print session card and make the
  sidebar the same width it was before". The Print session card is deleted
  outright - its slice state, layer count, print time and warnings are chips in
  the rail now, and its Hide action moved to the Quick settings card, which is
  the first of the two that remain - and `SessionRailWidth` is 80dp again,
  Material's own rail width. At that width a label and its value cannot share a
  line, so the rail uses `SessionValueTile` (label, value, detail stacked) while
  the Quick settings card keeps `SessionValueRow`. `SessionRailTest` pins the
  width and asserts the label sits above its value (in the *unmerged* tree, or
  the merged tap target answers with its own top instead).
- Third pass on the same rail: "add adhesion to the sidebar, and also the more
  tools, export and slice button and remove the cards on the right". `SessionPanel`
  is deleted outright, with the Quick settings and Actions cards in it, so nothing
  floats over the plate on a fold any more: the rail carries adhesion as a fourth
  value and the three actions under the slice chips (above the values, so the
  primary action is never the part that scrolls away). Its callbacks are the same
  ones the phone's `ActionBar` runs - `viewModel::sliceModel` reads
  `activeEngine` and checks *that* engine's availability and status, so Slice,
  Export and Model tools are engine-agnostic by construction, and the rail's values
  are built per engine like the card's were. `modelUiCollapsed`, the two
  card-expanded flags, the "Menu" button and the effect that folded the cards for
  the Smart Infill overlay all went with the panel; `SessionValueRow` went too,
  leaving `SessionValueTile`. The status banner opens folded by default now, under
  a new save key because the old one remembered the old default.

At 80dp the rail's labels wrap, and a wrapped label left to itself hugs the
leading edge of a button it no longer fills - "Model tools" read as a broken
button on the phone. `RailButtonLabel` centres each line and fills the button's
width, and `aWrappedButtonLabelStaysCentred` compares the label's centre with its
button's in the semantics tree.

The rail's red "why Slice is disabled" line flashed for a fraction of a second at
launch and the user caught it: a restored workspace has Smart Infill validating its
package (the `smartInfillValidating` flag in `IntegratedEnderSlicerApp`, which
holds Slice while it hashes the mesh on IO), so the reason arrived and cleared
inside the first frame or two. `SessionRail` now holds the text back for
`SLICE_REASON_REVEAL_MILLIS` (600ms) and clears it immediately, so a fleeting
reason never draws. Two tests drive the clock themselves:
`aFleetingReasonIsNeverShown` and `aReasonThatStandsIsShown`.

The user asked for the space beside the top bar's title to become a slice status, so
the state survives every collapsed card. `SliceStatus` + `sliceStatusLabel` sit in
the top bar's actions on the Plate tab, `detailed = expandedLayout` giving a wide
window the layers and print time and a phone just the state. Wiring it turned up
that PrusaSlicer and OrcaSlicer already report a real percentage through
`onProgress` (it went into `statusMessage` and nowhere else), so
`MainUiState.sliceProgressPercent` now carries it, `beginOperation` clears it, and
the ring is determinate when the engine reports and indeterminate when it cannot -
CuraEngine's JNI path reports nothing. `SliceStatusTest` covers the four states,
the percent, and the warnings line.

The user asked whether Cura could have the progress bar the desktop shows. It can,
and the local checkout already said why it did not: `Progress::messageProgress`
computes an overall percentage from per-stage weights and hands it to the active
`Communication`; `ArcusCommunication::sendProgress` sends a `Progress` protobuf
message over the socket Cura's frontend listens on, and `CommandLine::sendProgress`
- the channel this app's child process uses - measures the value, compares it to
`last_shown_progress_` and returns. The `-p` handler in `CommandLine.cpp` is
`// enableProgressLogging(); FIXME: how to handle progress logging? Is this still
relevant?`, while `include/progress/Progress.h` documents the flag as "Message
progress over the CommandSocket and to the terminal (if the command line arg '-p'
is provided)". Upstream main and 5.7.0 both have it that way, so this was never
implemented rather than lost. The Android build therefore implements the header's
promise in `scripts/build-curaengine-android.sh`: `-p` sets a `progress_logging_`
member and `sendProgress` prints `Slice progress: {}%` per whole percent. Every
command the app builds now carries `-p`; `CuraEngineRunner` reads the child's
output through a thread that appends it to the same log as before and parses those
lines into `onProgress`, the view model's Cura branch writes
`sliceProgressPercent`, and the top bar shows a real percentage and a determinate
ring for all three engines. `last_shown_progress_` had to become `mutable`
because `sendProgress` is const.

Two build gotchas, both met while doing this. `scripts/build-curaengine-android.sh`
is **not idempotent**: two of its patches touch `include/GCodePathConfig.h` and the
first one's "already applied" check looks for a two-flag version of the text that
the second patch has since replaced, so a second run on a patched checkout dies
with "Expected Android patch context was not found". Reset before re-running:

    cd .build/CuraEngine && git checkout -- . && git clean -fdq

Second, the script's `python3 -m pip install --user --upgrade conan` trips PEP 668
on this box; `PIP_BREAK_SYSTEM_PACKAGES=1` gets past it, and the NDK has to be
pointed at explicitly:

    PIP_BREAK_SYSTEM_PACKAGES=1 \
      ANDROID_NDK_HOME=/opt/android-sdk/ndk/28.2.13676358 \
      ANDROID_NDK_ROOT=/opt/android-sdk/ndk/28.2.13676358 \
      APP_JNILIBS_DIR="$PWD/app/src/main/jniLibs" \
      bash scripts/build-curaengine-android.sh

`APP_JNILIBS_DIR` is optional and easy to forget: without it the script builds and
strips the engine into `.build/curaengine-android/artifacts/` and the APK keeps
shipping the previous one. Copy `libcuraengine_exec.so` and
`libcura-formulae-engine.so` from there into `app/src/main/jniLibs/arm64-v8a/`
when it is unset. `strings app/src/main/jniLibs/arm64-v8a/libcuraengine_exec.so |
grep 'Slice progress'` is the cheap way to confirm the engine in the APK is the
one this patch produced.

A testing note worth keeping: the first version of `theActionsLiveInTheRail…` asked
for a button labelled "Slice" on a state where `gcodeAvailable` is true - the
button says "Slice again" then - and Compose reports a missing node and an
off-screen node with two different messages that both read like a layout problem.
It was not: the label was wrong.
- The editors are an `AlertDialog`: a slider with -/+ steppers for numbers
  (committed on release, so dragging does not write a setting per frame), a list
  for choices (applied at once, dialog stays open to compare). The list scrolls:
  Cura offers fourteen infill patterns, and the last of them was unreachable
  without it. The Quick settings card gained an "All settings" button where the
  navigation used to be.

Verified: `SessionValuesTest` 9/9 (every engine writes its own keys for layer
height, density, pattern, adhesion and supports; Prusa's and Orca's adhesion maps
between a named choice and brim width/skirt loops; a layer height outside the usual
band stays inside the slider's range) and `SessionRailTest` 7/7 (the four
destinations and the session are all in the rail, the session starts *below* the
destinations and in the same column rather than beside them, tapping a destination
selects it, the state chip reads "Not sliced" or "Ready", tapping a value opens the
editor and writes the setting through a stateful fake, the infill dialog carries
both the density and the pattern, and the rail follows the active engine), plus the
whole unit suite, `:app:assembleDebug`, `:app:verifyDebugApkEngines` and an
install. `AppNavigationTest` now covers the phone's bottom bar only: the suite's
rail is no longer what a wide window draws.

### Foldable candidates for the next pass

Only `EnderSlicerApp`, `ModelToolsSheet` and now `SmartInfillWorkbenchPanel` look
at how wide the window is. The surfaces that would gain the most from the same
treatment, in order:

1. **Settings** (`CategorizedSettingsSheet` plus the Orca, Prusa and machine
   sheets): a long scrolling catalogue with a search field. Categories and search
   results in the left column, the settings of the selected one on the right.
   Biggest single win - hundreds of settings currently in one list.
2. **Print / OctoPrint** (`HardenedOctoPrintSheet`): it pages between status,
   webcam and files. On a fold the file list belongs on the left with the job
   status and controls beside it, instead of one page at a time.
3. **Model tools** (`ModelToolsSheet`): already takes `expandedLayout` for
   widths, but its groups open one panel at a time above the bar; a fold could put
   the group list on the left and the selected group's controls on the right.
4. **Layers** (`LayerPreviewView` with `LayerEventsSheet`) and **Blender files**
   (`BlenderFilesScreen`): list beside detail, smaller wins.

The navigation rail is done (round 41). The pane scaffolds
(`ListDetailPaneScaffold`, `SupportingPaneScaffold`) are the right tool for the four
above; `PaneExpansionState` - a draggable divider for the Smart Infill split - is the
one adaptive feature still unused.

## Round-by-round notes (historical)

### 1. React to the user's device test (first thing)

The device test of round 5 produced two findings, both fixed in round 6 (below).
The APK with those fixes has to be rebuilt and installed before the next test:
More (three dots) -> Smart Infill -> Open the analysis -> add a **Fixed** support
and a **Force** (set its X/Y/Z fields), tap the model for each surface, watch the
green/orange tint, **Optimize**, then **Use these density modifiers** (and
separately Part Topo -> Replace the model). Ask for the toast text or:

    adb logcat -c && adb logcat -s FilaSimNative AndroidRuntime libc DEBUG

Likely failure modes: "The native Smart Infill engine is not available for this
device ABI" (library not loaded), a native exception at createSession (model
import), a tap assigning nothing, "model is under-constrained" from Optimize (a
condition landed where the solver sees no material), or a panel layout problem on
the fold's two screens.

### 2. What round 6 changed (device-reported fixes + wiring)

- **A tap selected the whole model.** The crease segmentation is a face finder,
  not a selection: a smooth or flat surface is ONE patch (a 3DBenchy hull is 70%
  of its 225k triangles in a single patch), and the old pick assigned that patch.
  Now `Session::region_around` (native/filasim/jni/src/session.rs) grows a
  connected region from the hit triangle, inside its patch, bounded by a radius;
  the panel exposes that radius ("Tap radius mm", default 4% of the model's
  bbox diagonal via SmartInfillController.spotSizeForDiagonalMm), taps
  accumulate, and a per-condition **Face** button takes the whole patch on
  request (`expandToSurface`). The weld/adjacency for it is built once per
  session (`ensure_pick_index`) and warmed at session start (`warmPicking`).
- **The selection is drawn on the model.** `SmartInfillOverlay` (support / load /
  armed) is pushed through MainUiState like `smartInfillPicking` and rendered by
  extending PaintColorBuffer with three overlay slots: the overlay wins over
  support paint per triangle, and only changed triangles are re-uploaded.
- **Loads and supports are editable.** `FilaSimBoundaryCondition.withVector` /
  `withScalar` plus per-condition fields in the panel (force/moment/bearing in
  X-Y-Z, pressure, bedding modulus, displacement, mass); the row title is derived
  from the values (`summary()`), so it never goes stale.
- **The engine reports its own defaults.** `Session::effective_config` ->
  `nativeConfiguration` -> `FilaSimConfiguration.fromJson` ->
  `SmartInfillController.refreshConfiguration`. The panel does not yet render
  these fields - that is step 3.
- **Session safety.** Every controller entry point that touches the session is
  refused while a native call runs (the panel disables those controls too),
  `close()` destroys the session only after the running call returns, and session
  start (voxelize + weld + read defaults) runs on Dispatchers.Default instead of
  freezing the UI thread.

### 3. Material, resolution and goal UI (round 7 - done)

The panel now carries the material fields (E, Poisson, density, tensile/layer/
shear strength, layer-shear scoring, self-weight), the grid resolution
(target cells), the goal (stiffest / match uniform / safety factor, with the SF
target and the material-vs-layer measure) and self-supporting infill with its
overhang limit. The material row is filled from the engine (`refreshConfiguration`)
and the presets are the upstream library's own values
(FilaSimMaterialPresets: PLA, PETG, ABS, ASA, Steel S235, Aluminium 6061-T6,
Resin SLA - the isotropic three score against material rather than layer).
A resolution edit rebuilds the grid under the busy phase on a worker thread, so
it neither freezes the UI nor races a tap.

Still missing for full parity with the WebView workflow: load cases (the native
session optimizes a single load set) and re-solving an optimized design under a
new load step; the load-step UI upstream has is much larger than this panel.

### 4. The result view (round 8: the surface density tint)

The part is now tinted by the density of the material under each surface triangle:
`Session::surface_bins` (an inward march to the first design cell) ->
`FilaSimEngine.surfaceBins()` -> `SmartInfillOverlay.regions` -> the region slots
of `PaintColorBuffer`, with the panel legend drawn from the same ramp
(`viewer/DensityRamp.kt`, the upstream colormap). The tint is dropped with the
result it came from, so a stale run cannot keep describing the part.

Upstream's *other* result view — nested translucent region meshes over a ghosted
part, with a density-threshold cutaway (its "density" view mode,
`SceneManager.setRegions`) — is deliberately not ported: the GLES2 renderer has no
blend state and the part is always opaque there, so that view needs a viewer mode
of its own rather than one more layer. The regions are already in hand
(`FilaSimRegion` positions/indices/density) if it is wanted later.

### 5. Device pass, then close-out

- What to check on the device: the panel scrolls (it is long now), a tap takes
  a bounded spot (not the whole part), the picked conditions tint green/orange
  with the armed one yellow, a Force row edits in X/Y/Z, the material preset
  fills the fields, an Optimize tints the part blue→red by density with the
  legend matching, Check prints its report (a setup with no supports or no load
  says so instead of doing nothing), and Apply produces modifiers the slice
  accepts.
- Re-run: ./gradlew :app:testDebugUnitTest :app:verifyDebugApkEngines, the host
  smoke (with and without the strength goal), and the engine build from a clean
  clone.
- Update CHANGELOG.md / docs/smart-infill.md / README.md (they already describe
  the native engine; keep them in step with the UI).
- The goal is tracked as goal-850687d6-749d-4e2b-8031-a97254419f1f; call get_goal
  before update_goal, and only mark it complete with evidence covering model +
  conditions + properties + solve + optimize + export on the device.

- Re-run: ./gradlew :app:testDebugUnitTest :app:verifyDebugApkEngines, the host
  smoke, and the engine build from a clean clone.
- Update CHANGELOG.md / docs/smart-infill.md / README.md (they already describe
  the native engine; keep them in step with the UI).
- The goal is tracked as goal-850687d6-749d-4e2b-8031-a97254419f1f; call get_goal
  before update_goal, and only mark it complete with evidence covering model +
  conditions + properties + solve + optimize + export on the device.
