# Technical notes

Building TrioSlicer, where its five engines come from, the tasks that verify a packaged build, CI, and
how releases are signed. The [README](../README.md) is the user-facing side of the project; this is
everything that needs a terminal.

## Requirements

JDK 17, Android SDK 36 + NDK `28.2.13676358`, CMake 3.22 or newer with Ninja (the CuraEngine and
OrcaSlicer builds configure with `-G Ninja`), Gradle `9.4.1` - the committed wrapper is that version,
so `./gradlew` is enough - Python 3, Node.js `22.18.0+`, stable Rust (`wasm32-unknown-unknown`) and
`wasm-pack 0.15.0`.

Four of the five engines are fetched ready-built. Building OrcaSlicer from source instead needs the
usual autotools chain (`autoconf`, `automake`, `libtool`, `m4`, `perl`) and takes hours;
`scripts/build-orca-deps-android.sh` then `scripts/build-orca-engine-android.sh` do it.

## From a clean checkout

The scripts are committed executable and `.gitattributes` keeps them LF, so there is nothing to
`chmod`:

```bash
scripts/fetch-cura-resources.sh
scripts/fetch-orca-engine-android.sh      # ~34 MB release asset; ORCA_ENGINE_DIR for a local build
scripts/fetch-prusa-engine-android.sh
scripts/fetch-blender-engine-android.sh   # ~185 MB release asset: engine, runtime libs, assets

export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/28.2.13676358"
export APP_JNILIBS_DIR="$PWD/app/src/main/jniLibs"
scripts/build-curaengine-android.sh

./gradlew :app:verifyDebugApkEngines
```

**Or run `./scripts/setup.sh`**, which does all of it: checks the toolchain, fetches the pinned Cura
definitions, stages all five engines from their own scripts, checks the staged tree really holds every
engine, and assembles the debug APK — failing early with the name of the script to run rather than
letting Gradle find the problem minutes in.

## Where the engines come from

**CuraEngine** is cross-compiled from pinned source by `scripts/build-curaengine-android.sh`, with the
definition tree fetched by `scripts/fetch-cura-resources.sh`. Notes: [CURAENGINE_ANDROID.md](CURAENGINE_ANDROID.md).

**OrcaSlicer** needs no token: `scripts/fetch-orca-engine-android.sh` stages the console from the
`orca-engine-arm64-v1.3.0.zip` release asset. `ORCA_ENGINE_DIR` packages a local build instead, and
`ORCA_ENGINE_RUN_ID` (with `GITHUB_TOKEN`) pulls one specific
[orca-engine-android](../.github/workflows/orca-engine-android.yml) run. That workflow uploads a build
artifact, not the release asset: the zip on the release page is attached by hand from a run's output.
Notes: [ORCAENGINE_ANDROID.md](ORCAENGINE_ANDROID.md).

**PrusaSlicer** is the one engine that needs a token. `scripts/fetch-prusa-engine-android.sh`
downloads the newest successful `PrusaSlicer-3.0.0-alpha11-android-arm64-v8a` artifact of the
[`prusa-engine-3`](../.github/workflows/prusa-engine-3.yml) workflow on any branch, which
cross-compiles the alpha11 console from source, and prints the branch and head SHA the engine came
from so the provenance is in the log. No release asset carries this engine, so the workflow artifact is
the only source: the script wants a `GITHUB_TOKEN` with `actions:read`. Pin one specific run with
`PRUSA_ENGINE_RUN_ID`, or set `PRUSA_ENGINE_DIR` to a directory holding `prusa-slicer` and
`resources` to package a local build. Notes: [PRUSASLICER_ANDROID.md](PRUSASLICER_ANDROID.md).

**Blender** (the `libblender_exec.so` wrapper around Blender 3.6) is not in this repository.
`app/src/main/jniLibs/arm64-v8a/` and `app/src/main/assets/blender/` are gitignored: they hold the
built engine (~125 MB), the 120 shared libraries it loads (~260 MB), the CPython stdlib, Blender's
scripts and the datafiles — some 600 MB of staged artifacts, *delivered* rather than source. A clean
clone builds and runs the slicer and has no Blender engine in it;
`scripts/fetch-blender-engine-android.sh` stages all of it from the release that carries it
(`blender-engine-arm64-v1.2.0.zip`, versioned separately from the app; the fetch script's default tag
says which) or from `BLENDER_ENGINE_DIR`. What is versioned here is the source that matters: the JNI
wrapper, the creator glue and the engine patches under [native/blender/](../native/blender/README.md).

Anything edited under `assets/blender` is one clean clone away from not existing: edit it, then copy
the change back to `native/blender/` so it is versioned — the startup script that turns on the
modelling addons lives in both places for exactly that reason. The engine's stop/start lifecycle is
part of this: **Stop Blender engine** parks it with a socket shutdown, so the process and its loaded
scene stay resident (Blender's own teardown calls `exit()`), and opening **Modelling** or **Upload
model to Blender** again asks the parked engine to serve instead of loading a second copy. Protocol
and runbook: [BLENDER_MCP_INTEGRATION.md](../BLENDER_MCP_INTEGRATION.md).

**filaSim** is built by `scripts/build-filasim-engine-android.sh` from the pinned upstream source;
`scripts/fetch-filasim-engine-android.sh` stages it from a release asset or a
`filasim-engine-android` workflow artifact on a machine without Rust.

## Verification tasks and CI

Gradle prepares the pinned offline BumpMesh and filaSim assets before `mergeDebugAssets` /
`mergeReleaseAssets`; `preBuild` is where the Blender engine is trimmed and its Android-unusable
assets are pruned. `:app:verifyDebugApkEngines` builds the debug APK and verifies all five packaged
engines — CuraEngine, OrcaSlicer, PrusaSlicer, Blender and the native filaSim engine — including the
Blender runtime libraries, numpy assets and the licence texts the GPL requires to ship with the binary.

The per-engine tasks stay individually runnable and each one also runs the checks it depends on:
`:app:verifyDebugApkContents` verifies the packaged CuraEngine and its definition catalogue;
`:app:verifyDebugApkOrcaContents` adds the OrcaSlicer console and its profile tree;
`:app:verifyDebugApkPrusaContents` adds PrusaSlicer and its resources;
`:app:verifyDebugApkBlenderContents` adds the Blender engine, its runtime libraries, its assets and
its licences; `:app:verifyDebugApkFilaSimContents` adds the native filaSim engine. The same checks run
against a release build with `./gradlew :app:verifyReleaseApkEngines` — they were debug-only, so a
release APK could ship short of an engine runtime library with every task green.

GitHub Actions cross-builds the engine artifacts the fetch scripts consume, builds the WASM engine,
runs the unit/regression and definition audits, verifies packaged assets and uploads the APK.

## Signing and releases

The published APK is the **release** variant - `android:debuggable` off, so no `adb run-as`, heap
dumps, WebView remote debugging or debug-only certificate trust - signed with the private release key
`keystore/release.jks`; see [keystore/README.md](../keystore/README.md).

The key is not in this repository. A local build reads `keystore.properties` (gitignored):

    storeFile=keystore/release.jks
    storePassword=<password>
    keyAlias=trioslicer
    keyPassword=<password>

CI writes the same file from the `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and
`KEY_PASSWORD` repository secrets before the build. A release build with no usable key stops at
`preReleaseBuild` instead of producing `app-release-unsigned.apk`, and the workflow pins the signer's
certificate digest, so a build that falls back to another key fails instead of shipping. The debug
build type keeps the SDK's own throwaway `~/.android/debug.keystore`: a debug APK is a local artifact
whose identity means nothing outside the machine that built it.

**The retired key.** Every release up to and including 1.3.5 was signed with a debug key that *was*
committed here, so its certificate is public and anyone with a clone can sign an APK Android installs
as an update over such an install. It is retired: a release-key build cannot install over an install
signed with it (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), so that install has to be uninstalled once -
data included - before the next one goes on.

Verify a downloaded APK (`apksigner` ships in the Android SDK's build-tools; the APK carries a v2
signature, which `keytool -printcert -jarfile` cannot read):

    apksigner verify --print-certs app-release.apk

    Signer #1 certificate SHA-256 digest: e4d88ac927ecb945e256e783ae431254785fd110fa9c78559f89d832128ea5d7

## Platform notes

**Since 1.3.0 this is a Linux project.** It is developed on Debian 13 against this checkout: `main` is
the Linux line, and `windows-build` holds the frozen Windows tree the project was ported from at
1.2.x. Nothing about the app changed for the phone — same package id, same G-code markers - but the
build, the engine cross-compiles and the CI all run on Linux now.

**On Windows**, the staging scripts still run under Git Bash or WSL — they need only bash, curl and
unzip — but `setup.sh` itself wants a Unix JDK and a POSIX SDK path, which is what CI has: build with
`gradlew.bat` and the commands above instead, and hand `env-setup.sh` the Windows adb
(`ADB=adb.exe`).

## Checking the environment

`./scripts/env-setup.sh` checks everything *around* the app — the build toolchain, the phone over adb,
the harness, the GPU box — and prints what is reachable, what is missing, and the command to fix each,
exiting non-zero if anything is missing so it can gate CI. None of the three is bundled: they are yours
to run, so it reports rather than assumes. Addresses come from the environment (`PHONE_TAILNET`,
`PHONE_LAN`, `HARNESS_ORIGIN`, `GPU_BOX`) because none of them belong in a repository.

## Feature notes

- Engines: [CURAENGINE_ANDROID.md](CURAENGINE_ANDROID.md), [PRUSASLICER_ANDROID.md](PRUSASLICER_ANDROID.md), [ORCAENGINE_ANDROID.md](ORCAENGINE_ANDROID.md)
- Blender wrapper: [native/blender/README.md](../native/blender/README.md)
- Smart Infill and thermal FEA: [smart-infill.md](smart-infill.md), [native-smart-infill-handover.md](native-smart-infill-handover.md)
- Non-planar slicing: [non-planar-curvislicer.md](non-planar-curvislicer.md)
- OctoPrint: [octoprint-integration.md](octoprint-integration.md)
- The assistant and its harness: [AI_ASSISTANT.md](../AI_ASSISTANT.md), [skills/](skills/)
- UI conventions: [ui-style-guide.md](ui-style-guide.md)
- Audit and quality records: [quality-pass-audit.md](quality-pass-audit.md), [bug-audit.md](bug-audit.md)
