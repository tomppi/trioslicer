# Third-party notices

## filaSim / Smart Infill Generator

- Project: `CNCKitchen/smartInfillGenerator` (product name: filaSim)
- Pinned source commit: `e7485ec22d4ebe8baca04190404fbb877c90e031`
- License: GNU Affero General Public License v3.0 only (`AGPL-3.0-only`)
- Copyright: CNC Kitchen (Stefan Hermann) and contributors

TrioSlicer builds filaSim's single-threaded Rust/WASM engine and React interface from the pinned source, packages the resulting workspace for offline Android use, and adds an Android-only model/modifier handoff. The APK retains filaSim's license and a source notice. The complete corresponding source, Cargo lockfile and npm lockfile are the upstream repository at the pinned commit together with TrioSlicer's `scripts/prepare-filasim-assets.py` and `app/src/main/filasim/android-bridge.js`.

The APK also ships the same pinned core compiled for Android as a native library (`lib/arm64-v8a/libfilasim_jni.so`), which TrioSlicer's Smart Infill workflow drives directly instead of through the WebView. Its corresponding source is the pinned commit above plus TrioSlicer's `native/filasim/` (the JNI session layer, AGPL-3.0-only, retained in this repository) and the reproducible build script `scripts/build-filasim-engine-android.sh`; `native/filasim/README.md` records the provenance and the exact build commands. The library links only against the Android platform libraries (libc, libm, libdl).

The filaSim web build includes its declared permissive or AGPL-compatible dependencies, including React/React DOM 19, Three.js 0.180, Zustand 5 and meshStep 0.1.1. Exact transitive versions and license metadata are recorded by the pinned `Cargo.lock`, `package-lock.json`, `Cargo.toml`, `package.json` and `deny.toml` files.

## Wave-overhang algorithm research and reference implementation

The native TrioSlicer wavefront generator is an independent CuraEngine adaptation of the propagation method documented by `dennisklappe/OrcaSlicer-WaveOverhangs`, itself based on `stmcculloch/PrusaSlicer-WaveOverhangs`. Those projects and CuraEngine are distributed under the GNU AGPL. The adapted source is retained under `native/curaengine/patches/` with attribution headers.

## BumpMesh / stlTexturizer

- Project: `CNCKitchen/stlTexturizer`
- Pinned source commit: `a6ac179149b8a17c71a9469dd4cb6f866c0c01d1`
- License: GNU Affero General Public License v3.0 only (`AGPL-3.0-only`)
- Copyright: CNC Kitchen (Stefan Hermann) and contributors

The Android build downloads the pinned source archive, retains its license file in the packaged workspace, replaces network module imports with local copies, and adds a small Android host bridge. The original project source remains available from its upstream GitHub repository.

## Three.js

- BumpMesh workspace version: r170 / 0.170.0
- filaSim workspace version: 0.180.x
- License: MIT
- Copyright: Three.js authors

The BumpMesh build retains the upstream license at `assets/bumpmesh/vendor/three/LICENSE`. filaSim's exact dependency version is recorded in its pinned npm lockfile.

## fflate

- Version: 0.8.2
- License: MIT
- Copyright: 101arrowz

The official npm package supplies the browser ESM build used by BumpMesh. Its package metadata, README and license are packaged beside the module.

## meshStep

- BumpMesh workspace version: 0.1.0
- filaSim workspace version: 0.1.1
- License: GNU Affero General Public License v3.0 only (`AGPL-3.0-only`)
- Copyright: CNC Kitchen and contributors

BumpMesh packages the published TypeScript source, generated distribution, metadata, README and AGPL license under `assets/bumpmesh/vendor/meshstep/`. filaSim's corresponding source is available through its pinned dependency and source tree.

## CuraEngine and Cura resources

CuraEngine is developed by UltiMaker and contributors and is licensed under GNU AGPL-3.0-or-later. The repository pins CuraEngine and matching Cura resources to `5.14.0-alpha.0`.

UltiMaker and Cura are trademarks of their respective owners.

## PrusaSlicer engine (`libprusa_slicer_exec.so`) and its preset repository

- Project: `prusa3d/PrusaSlicer`
- Pinned version: `3.0.0-alpha11`
- License: GNU Affero General Public License v3.0 (`AGPL-3.0`)
- Copyright: Prusa Research and the PrusaSlicer contributors

The Android engine is the project's console, cross-compiled from that tag by the
`prusa-engine-3` workflow in this repository and staged from the run's artifact. The packaged
resources under `app/src/main/assets/prusa/resources/` are Prusa's own vendor preset repository
(`prusa-research-fff`), copied unmodified; `app/src/main/assets/prusa-presets.json` is this app's
JSON view of that repository, generated at fetch time by `scripts/prusa-presets-to-json.py`.
`app/src/main/assets/prusa/all-settings.json` is this app's catalogue of those options: its
keys, types and defaults are derived from the resolved configuration the app ships
(`prusa3-base.json`) and its choices from that preset JSON, by
`scripts/generate-prusa-all-settings.py` - the 3.0 console has no dump mode that could
produce it.

PrusaSlicer is derived from Slic3r; both are AGPL-licensed and their notices remain in the
upstream sources packaged here.

## OrcaSlicer engine (`liborca_console_exec.so`) and its profile tree

- Project: `SoftFever/OrcaSlicer`
- Pinned version: `v2.4.2`
- License: GNU Affero General Public License v3.0 or later (`AGPL-3.0-or-later`)
- Copyright: OrcaSlicer contributors, and the PrusaSlicer, Slic3r and Bambu Studio projects it derives from

The Android engine is a headless driver linked against the project's `libslic3r`; its source
is retained under `native/orca/console/` with the build patches beside it in
`native/orca/patches/`. The packaged profile tree under `app/src/main/assets/orca/resources/`
is that release's `resources/profiles`, `resources/info`, `resources/flush` and
`resources/printers`, copied unmodified; the settings catalogue
`app/src/main/assets/orca/all-settings.json` is generated from the engine's own
`--dump-settings` output.

OrcaSlicer is a fork of PrusaSlicer and Bambu Studio, which are in turn derived from Slic3r; all
three are AGPL-licensed and their notices remain in the upstream sources packaged here.

## Arc-overhang research and SuperPleccer

- Original research/prototype: `stmcculloch/arc-overhang`
- Native Multiplex reference: `rvmn/SuperPleccer`
- Licenses: GPL-3.0 for the original prototype and AGPL-3.0 for SuperPleccer

TrioSlicer contains a CuraEngine-oriented native reimplementation of the Multiplex arc-overhang path-generation behavior. Attribution and implementation details are retained in `native/curaengine/patches/ARC_OVERHANG_NOTICE.md` and the native source headers.

## EasyConical conical slicing

- Project: `DigitalGrin/EasyConical`
- License: GNU General Public License v3.0 (`GPL-3.0`)
- Copyright: Alex Herskovitz and contributors

The Android conical-slicing backend is a Kotlin port of EasyConical's forward cone
transformation (`Transformation_MiniLibrary.py`) and G-code back-transformation
(`Backtransformation_MiniLibrary.py`), integrated into the native slicing pipeline
under `app/src/main/java/com/tomppi/enderslicer/conical/`. The underlying
conical-slicing strategy is derived from `CNCKitchen/ConicalSlicer` and the paper
"A Novel Slicing Strategy to Print Overhangs without Support Material" (Wüthrich et
al., Applied Sciences, 2021). The original project source remains available from
its upstream GitHub repository.

## Android Open Source Project and AndroidX

The application uses Android platform APIs and AndroidX libraries under their respective licenses.

## Blender engine (`libblender_exec.so`) and its bundled libraries

The embedded engine is **Blender 3.6.22**, built for Android arm64 from the
`epai` / `APP-android_arm64` port, then patched — see
[`native/blender/patches/`](native/blender/patches/README.md), which publishes
every modified file in full.

**Blender is GPL-2.0-or-later.** Porting it produces a derivative work, so the
port's modifications carry the same licence; nobody can relicense a port of
Blender as their own. TrioSlicer is AGPL-3.0-or-later, and the two combine
lawfully because Blender's "or later" allows it to be taken as GPLv3, which
AGPLv3 section 13 permits linking with.

**Corresponding source.** Blender's source is at
<https://projects.blender.org/blender/blender>, the Android port at
<https://github.com/dshawshank/APP-android_arm64>, and this project's
modifications are published in full under `native/blender/patches/`.

### Bundled libraries

`libblender_exec.so` statically links, and the engine ships alongside, 120
shared libraries. Most are permissive; the copyleft ones are all compatible with
a GPL/AGPL whole, but they are copyleft and their notices must travel with a
binary.

| licence | libraries |
|---|---|
| GPL-2.0-or-later | Blender itself; FFTW (`libfftw3`); Potrace (`libpotrace`) |
| GPL-2.0-or-later | FFmpeg (`libav*`, `libsw*`, `libpostproc`) — confirmed from the binaries, see below |
| LGPL-2.0-or-later | OpenAL (`libopenal`); GMP (`libgmp`, `libgmpxx`) |
| Apache-2.0 | Cycles; OpenImageIO; OpenImageDenoise; Embree; OpenUSD; oneDNN; Draco; OpenPGL; TBB; OpenSSL |
| BSD-3-Clause | Alembic; OpenEXR and Imath (`libIex*`, `libIlmThread*`, `libImath*`); OpenColorIO; zstd |
| MPL-2.0 | OpenVDB |
| Zlib / libpng / MIT / BSL-1.0 / public domain | SDL2; libpng; Brotli, Expat, libxml2, OpenCOLLADA; Boost; SQLite |
| PSF-2.0 / Unicode-3.0 | CPython (`libcpython`); ICU (`libicuc`) |

There is no tracked machine-readable copy of that list. It is read out of the
staged `app/src/main/jniLibs/arm64-v8a/` tree, and both it and
`native/blender/blender-jniLibs/` are gitignored build output, so a clean clone
holds the licence texts below but not the library set itself.

### Licence texts

**Shipped with the engine.** Blender's canonical set is in
`assets/blender/licenses/blender/` — eleven texts: GPL-2.0, GPL-3.0, LGPL-2.1,
Apache-2.0, BSD-2-Clause, BSD-3-Clause, MIT, Zlib, the SPDX identifier list, the
Blender License (BL) and the Blender Foundation member list. Blender's logo and
trademark terms are **not** part of that set and do not ship with the engine;
this repository carries no Blender logo asset either. Beside it,
`assets/blender/licenses/deps/` carries 125 per-dependency texts mirroring
their source packages: Boost, CPython, FFTW, HarfBuzz, ICU, OpenAL, OpenBLAS,
OpenCOLLADA, OpenImageIO, OpenPGL, OpenSubdiv, OpenUSD, OpenVDB, libpng,
PugiXML, SDL, TBB, TIFF and zstd.

The tracked copies are at [`native/blender/assets/licenses/`](native/blender/assets/licenses).
`app/src/main/assets/blender` is gitignored — it is delivery, not source — so
that directory is where they survive a clean clone.

**FFmpeg: GPL-2.0-or-later — settled, read out of the binaries themselves.** No
licence text for it exists anywhere in the source packages, so it was checked
directly, three ways that agree:

1. **`libpostproc` ships.** It is GPL-2.0-or-later only, and is built only when
   `--enable-gpl` is given. Its presence alone decides the question.
2. **The configuration string embedded in `libavutil`, `libavcodec` and
   `libavformat`** reads `--enable-gpl ... --enable-libx264 --enable-libx265`,
   alongside libaom, libbluray, libmp3lame, libopus, libspeex, libtwolame,
   libvpx, libfreetype and libfribidi.
3. **The libraries state it themselves:** "libavcodec license: GPL version 2 or
   later", and the same for `libavutil` and `libavformat`.

**`--enable-nonfree` is absent**, which matters more than the GPL flag does: that
is the one that makes an FFmpeg build undistributable, and it is not there.

Provenance, from the same string: built by **ffmpeg-android-maker-2.12**
(`/home/dadw/ffmpeg-android-maker-2.12/build/external/arm64-v8a/lib`).

The consequence is worth stating plainly: the engine's FFmpeg is **GPL, not
LGPL**, so there is no licence ambiguity left anywhere in the engine. It is GPL
throughout, consistent with Blender, and combinable with this app's
AGPL-3.0-or-later through GPLv3.

Libraries without a per-package text — OpenEXR and Imath, Alembic, Embree,
OpenColorIO, OpenImageDenoise, oneDNN, Draco, OpenSSL and the rest — are covered
by licence *type* in Blender's canonical set above, but a distributor should ship
the per-library text, not rely on the type.

The table above remains an inventory to work from rather than legal advice:
several entries are dual-licensed, and where so, the permissive option should be
taken and recorded.
