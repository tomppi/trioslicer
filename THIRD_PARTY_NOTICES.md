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

## Klipper host (`libklipper_exec.so`) and the app's CPython

**This is the app's Python interpreter.** The binary is a small executable linked against
`libpython3.11.so`; the payload beside it is a CPython 3.11 build for Android carrying the
**klippy** tree, so the app can act as the printer's host instead of talking to one. Every
other Python in the app runs on this interpreter — including the CAD engine above, which adds
packages but no second Python.

| component | licence | copyright |
|---|---|---|
| Klipper (klippy) v0.13.0 | GPL-3.0 | Kevin O'Connor and contributors |
| CPython 3.11 | PSF-2.0 | Python Software Foundation |

**Corresponding source.** Klipper is at <https://github.com/Klipper3d/klipper>, pinned at
**v0.13.0**. `scripts/fetch-klipper-android.sh` fetches the pinned host payload and
`scripts/stage-klipper-android.sh` stages it, and that script is the whole diff against
upstream: **one line of `klippy/util.py`** is patched, because its `create_pty()` chmods a
`/dev/pts` node and Android does not allow an app to do that.

One file in the payload is not Klipper's: `klippy/extras/resonance_playback.py` is
TrioSlicer's, adding a single `PLAY_RESONANCES` command so the phone's accelerometer can
measure a vibration sweep. Its source is in this repository at
[`native/klipper-playback/`](native/klipper-playback/), and
`scripts/verify-resonance-playback.py` checks it against Klipper's own generator at every
staging.

The payload also carries CPython's standard library and its extension modules, and the
packages klippy imports: `cffi`, `greenlet`, `jinja2`, `markupsafe`, `pycparser`
and `pyserial` — permissive throughout, and each ships its own licence in the payload.

**On the GPL and the app.** Klipper runs as its own executable in its own process, and the
app talks to it rather than linking it into the app's code. The app is AGPL-3.0-or-later, so
the direction that matters — combining GPL-3.0 code into an AGPL work — is what AGPLv3
section 13 permits, exactly as it does for the Blender engine.

## OpenCASCADE engine (`libocct_exec.so`) and PlaneGCS

The engine that converts **STEP and IGES** to a mesh for import. It statically links
**Open CASCADE Technology 7.6.0** (38 toolkits: TKSTEP, TKIGES, TKBO, TKMesh, TKShHealing
among them) together with **FreeCAD's PlaneGCS** constraint solver, which is what the
`sketch` command runs to solve a profile from stated relationships rather than coordinates.

**Both are LGPL, and both are linked into a binary this project ships, so both are named
here.**

| component | licence | copyright |
|---|---|---|
| Open CASCADE Technology 7.6.0 | LGPL-2.1 **with the Open CASCADE exception** | Open CASCADE SAS |
| PlaneGCS (FreeCAD CAx) | LGPL-2.1-or-later | © 2011 Konstantinos Poulios and contributors |

The OCCT exception is the same one quoted under the CAD engine below, and the statement it
requires is the same: **this engine makes use of and is based on facilities provided by Open
CASCADE Technology.**

**Corresponding source.** OCCT 7.6.0 is at <https://github.com/Open-Cascade-SAS/OCCT>,
PlaneGCS within FreeCAD at <https://github.com/FreeCAD/FreeCAD> (the `src/Mod/Sketcher/App`
solver). Neither is modified: `scripts/build-occt-engine-android.sh` cross-compiles them for
arm64 from the pinned trees and links them, and it is committed.

**On the LGPL and static linking.** The exception above covers material taken from OCCT's
headers; the remainder of the library is LGPL-2.1. The engines are separate executables run
as their own processes rather than shared objects loaded into the app, and each is a
standalone program that the app talks to over a local socket — so the library and the work
that uses it are not presented to a user as one combined work.

## CAD engine (build123d on OCP) and its bundled libraries

The CAD workspace builds exact geometry rather than meshes: **build123d 0.12.0** on **OCP
7.9.3.1**, the official Python bindings for **Open CASCADE Technology 7.9.3**. It runs on the
interpreter the Klipper host already provides, so it needs no second Python and adds no new
executable.

### Open CASCADE Technology, and the exception that matters

**OCCT is LGPL-2.1, and it is statically linked into `OCP.cpython-311.so`.** The licence
ships an exception that is the reason this is workable, so it is quoted rather than
paraphrased:

> The object code (i.e. not a source) form of a "work that uses the Library" can incorporate
> material from a header file that is part of the Library. As a special exception to the GNU
> Lesser General Public License version 2.1, you may distribute such object code incorporating
> material from header files provided with the Open CASCADE Technology libraries ... under
> terms of your choice, **provided that you give prominent notice in supporting documentation
> to this code that it makes use of or is based on facilities provided by the Open CASCADE
> Technology software.**

This notice is that statement: **TrioSlicer's CAD engine makes use of and is based on
facilities provided by Open CASCADE Technology.**

**Corresponding source.** OCCT 7.9.3 is at
<https://github.com/Open-Cascade-SAS/OCCT>, the bindings at
<https://github.com/CadQuery/OCP> (tag `7.9.3.1`), and build123d at
<https://github.com/gumyr/build123d>. The bindings were generated by **pywrap**
(Apache-2.0, part of the OCP repository); eight bindings pywrap omits, all overloaded or
skipped methods, were written by hand and each is named in the build plan. The Android
cross-compile recipe is committed: `scripts/build-occt-engine-android.sh` and, for the
payload, `scripts/stage-cad-android.sh`.

| component | licence |
|---|---|
| Open CASCADE Technology 7.9.3 | LGPL-2.1 **with the Open CASCADE exception** |
| OCP 7.9.3.1 (bindings) | Apache-2.0 |
| pywrap (bindings generator) | Apache-2.0 |
| build123d 0.12.0 | Apache-2.0 |

### Native libraries

| library | licence |
|---|---|
| `libopenblas.so` | BSD-3-Clause (the OpenBLAS project) |
| `libgfortran.so.3` | GPL-3.0 **with the GCC Runtime Library Exception** |
| `libjpeg_chaquopy.so` | libjpeg-turbo (IJG and BSD-style) |
| `libpng16.so` | libpng-2.0 |
| `libomp.so` | Apache-2.0 with LLVM Exceptions |

`libpython3.11.so` is the app's own CPython, covered by the PSF licence below.

### Bundled Python packages

Read from each distribution's own metadata in the payload, or from its source header where
it ships none. Permissive throughout; nothing here is copyleft beyond the LGPL above.

| licence | packages |
|---|---|
| Apache-2.0 | `requests`, `asttokens` |
| BSD-2-Clause | `decorator`, `lib3mf` |
| BSD-3-Clause | `numpy`, `scipy`, `scikit-learn`, `sympy`, `traitlets`, `webcolors`, `prompt_toolkit` |
| ISC | `pexpect` |
| MIT | `anytree`, `bd_materials`, `charset_normalizer`, `dataclasses_json`, `deprecated`, `ezdxf`, `executing`, `jedi`, `marshmallow`, `matplotlib_inline`, `mypy_extensions`, `ocp_gordon`, `ocpsvg`, `packaging`, `parso`, `platformdirs`, `pure_eval`, `pygltflib`, `pygments`, `pyparsing`, `stack_data`, `svgelements`, `svgpathtools`, `svgwrite`, `threejs_materials`, `trianglesolver`, `typing_extensions`, `typing_inspect`, `wcwidth`, `wrapt`, `joblib`, `threadpoolctl` |
| MPL-2.0 | `certifi` |
| HPND (MIT-CMU) | `Pillow` |
| PSF-2.0 | CPython 3.11, the interpreter itself |

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
