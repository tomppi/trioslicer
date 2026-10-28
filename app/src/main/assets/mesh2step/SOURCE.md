# Mesh-Mix-S2C engine assets

Upstream: `STL2CURVES-MMM/Mesh-Mix-S2C` (Manifold Mesh Mix "mesh to STEP")
Tag: `v1.6.0`
Commit: `4cf72ea5900f40d99116bda7877a8e7550ec604f`
Licence: GNU Affero General Public License v3.0 only (`AGPL-3.0-only`),
copyright the Mesh-Mix-S2C contributors; the upstream `LICENSE` and
`THIRD_PARTY_NOTICES.md` are staged verbatim beside this file.

The vendored files - everything under `mmm/` and `mmm_engine.py` - are byte-for-byte
copies of `engine/` at that commit. Do not edit them; the SHA256SUMS manifest pins them and
`scripts/verify-mesh2step-assets.py` fails on any change. The staging command is:

    scripts/stage-mesh2step-engine.sh

Additions from TrioSlicer (both AGPL-3.0-only, SPDX headers in the files):

- `launcher.py` - starts `mmm.cli` after installing the OCP compatibility module; this is the
  script the Kotlin seam runs on the app's CPython.
- `ocp_compat.py` - maps the OCP 8.0.1 `OCP.collections` names the engine imports onto the
  generic collection classes in the OCP 7.9.3.1 payload the app already ships. The payload is
  not modified and does not need rebuilding.
- `engine_compat.py` - the app's OCP build does not expose `STEPControl_Reader.TransferRoots()`
  (the engine's STEP read-back verification calls it), so this adapter reads STEP files through
  the same `STEPCAFControl_Reader` path the engine already uses for multi-part files. It does
  nothing on an OCP that has `TransferRoots`.

Runtime: the app's only CPython (the Klipper payload, 3.11) with the existing CAD payload's
`OCP`, `numpy` and `scipy` on `PYTHONPATH`.

## The six optional accelerators (real-program parity)

The upstream program pins all six of these in its requirements and collects them into its
packaged builds; this app ships the same set so a converted mesh goes through the same
repair, merge, decimation and spatial paths. They are drop-in imports, not engine patches;
launcher.py only puts this lib/ directory on sys.path. Every source is pinned by sha256 in
.source-version, and each build script reproduces its package from that source.

- trimesh 5.1.1 (MIT, pure Python, wheel sha256 c5be85a3...): non-STL input loading.
- manifold3d 3.5.4 (Apache-2.0, sdist sha256 5bd88c48...): Manifold round trips for
  overlap merging, hole triangulation and decimation cleanup.
- fast_simplification 0.2.0 (MIT, sdist sha256 ed02ea6f...): quadric decimation for large
  meshes and previews.
- pymeshfix 0.18.1 (AGPL-3.0, wrapping MeshFix GPL-3.0; sdist sha256 c3386a12...): full
  MeshFix repair for holes and self-intersections when the engine chooses it.
- rtree 1.4.1 (MIT, sdist sha256 c6b1b355...) with libspatialindex 2.1.0 (MIT, sha256
  a04513ce...): R-tree candidate search in the mesh checks. The two spatialindex shared
  libraries are staged in lib/rtree/lib and load from each other via $ORIGIN.
- pynanoinstantmeshes 0.0.3 (BSD-3-Clause, sdist sha256 d827eba6...): the Smooth preset's
  quad remesher; build details below.

Build recipes (all cross-compile for the app's bionic CPython 3.11 / arm64 with the NDK
clang at API 24, the .build/py311-android prefix and its host interpreter; they patch only
work copies, never our assets):

- scripts/build-trimesh-android.sh (vendors the pure-Python wheel)
- scripts/build-manifold3d-android.sh (CMake + nanobind; MANIFOLD_CROSS_SECTION uses the
  built-in boolean2 backend and parallel/TBB/downloads are off, matching the app's JNI
  Manifold build)
- scripts/build-fast_simplification-android.sh (CMake + nanobind, two modules)
- scripts/build-pymeshfix-android.sh (CMake + nanobind, MeshFix sources; the NDK's
  -Werror=format-security is relaxed for this third-party code)
- scripts/build-rtree-android.sh (libspatialindex 2.1.0 CMake shared build + the pure-Python
  rtree package; bulk insert needs spatialindex >= 2.1, so 2.0.0 is not enough)
- scripts/build-pynanoinstantmeshes-android.sh (nanobind; see below)

The compiled modules have DT_NEEDED on libpython3.11.so.1.0, libc++_shared.so and the
Android system libraries, all already on the runtime path the seam sets. Licences are staged
under lib/LICENSES (and lib/pynanoinstantmeshes/licenses for the Smooth accelerator):
trimesh MIT, manifold3d Apache-2.0, fast_simplification MIT, MeshFix GPL-3.0,
PyMeshFix AGPL-3.0, rtree MIT, libspatialindex MIT, Instant Meshes BSD-3, Eigen MPL-2.0,
TBB Apache-2.0 and PyNanoInstantMeshes BSD-3.

## PyNanoInstantMeshes build detail

The engine's mmm/smooth.py imports pynanoinstantmeshes for its quad remeshing pass; the
vendored engine code is unmodified and the binding is a drop-in import from lib/. Build
scripts/build-pynanoinstantmeshes-android.sh reproduces it from the pinned sdist. It patches
only build files in a work copy: nanobind 3.1.0 (wheel sha256 96984dbe...) drops the cp312
stable-ABI flag for our cp311 target; the vendored TBB 2020.2 is built static for Android
(no -lpthread on bionic, no host try_run, generated version template); the Instant Meshes
sources are compiled as C++14 because they still use std::unary_function, while the nanobind
module is C++17. Toolchain: NDK clang for arm64 API 24 (matching the Klipper Python build),
the Android CPython 3.11 prefix .build/py311-android, and the target extension suffix
.cpython-311.so. Runtime: DT_NEEDED on libpython3.11.so.1.0, libc++_shared.so and the Android
system libraries. Licences staged beside it in lib/pynanoinstantmeshes/licenses/: Instant
Meshes BSD-3 (native/LICENSE.txt), Eigen MPL-2.0, TBB Apache-2.0 and the binding's BSD-3
notice.
