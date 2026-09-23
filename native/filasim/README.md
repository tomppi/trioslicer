# filaSim native engine (Android)

TrioSlicer runs filaSim's structural analysis **inside the app** instead of in a
WebView: this crate wraps the pinned upstream engine semantics in a JNI surface
the Kotlin layer drives directly.

## What is here and what is not

- `jni/` — **our** crate (`filasim-jni`, AGPL-3.0-only). It owns the session
  state (mesh, segmentation, voxel grid, boundary conditions, solution,
  optimization result) and exposes it over JNI, plus a host smoke binary
  (`src/bin/smoke.rs`) that runs the whole pipeline outside Android so the
  numerics can be checked without a phone.
- The **engine itself is not in this repository**. `filasim-core` is upstream
  `CNCKitchen/smartInfillGenerator` at the commit the app pins
  (`e7485ec22d4ebe8baca04190404fbb877c90e031`). The WebAssembly build fetches that
  commit and applies the Enderslicer patch chain to it
  (`scripts/prepare-filasim-assets.py`); this native build
  (`scripts/build-filasim-engine-android.sh`) uses the prepared tree when one is
  present and otherwise fetches the pinned commit itself. Either is correct: the
  patch chain only adds the thermal module (`thermal.rs` and its crate-root
  declaration), which the structural session never calls. The two builds were
  run on the same part and agreed exactly — same solve, same regions, identical
  modifier archive — so native and WebAssembly results come from the same
  structural code.

The session layer deliberately mirrors the upstream `filasim-wasm` `Model`
semantics — option resolution, the printable-geometry clamps, mass/gram
conversion, the goal/budget/strength handling — so a natively produced Smart
Infill package is the same package the WebView produced. Where the upstream
WebAssembly layer serializes to `JsValue`, this crate serializes to JSON and
plain arrays.

## Building and checking it

`scripts/build-filasim-engine-android.sh` builds `libfilasim_jni.so` into
`app/src/main/jniLibs/arm64-v8a/` (`--host` builds the smoke binary instead),
`scripts/fetch-filasim-engine-android.sh` stages a release asset or a
`filasim-engine-android` CI artifact on a machine without Rust, and
`:app:verifyFilaSimEngineLibrary` / `:app:verifyDebugApkFilaSimContents` check
that the packaged library is an AArch64 ELF whose JNI entry points survived
stripping.

## Cancellation and progress

`filasim-core` ships the embedder hooks this needs: `cancel::set_checker`
(polled by the MGCG and SIMP loops) and `progress::set_sink` (residual trace).
The session installs an atomic checker and a progress snapshot, so a solve can
be cancelled from the UI thread and polled for live telemetry.

## License

AGPL-3.0-only, like the upstream engine and the rest of the app's slicer
engines. See `THIRD_PARTY_NOTICES.md` in the repository root.
