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

## Optional accelerator: PyNanoInstantMeshes 0.0.3 (Smooth preset)

The one optional accelerator the app ships. The engine's `mmm/smooth.py` imports
`pynanoinstantmeshes` for its quad remeshing pass (the CAD/mechanical presets do not use it);
the vendored engine code is unmodified and the binding is a drop-in import from `lib/`.

- Package: PyNanoInstantMeshes 0.0.3 by Mark Boss, BSD-3-Clause.
  Source sdist sha256: `d827eba612753a4bad6e8f644b8cc6c195e47f4afc6936823c0be5c47bb3c23f`.
- Staged: `lib/pynanoinstantmeshes/__init__.py` (upstream, byte-identical) and
  `lib/pynanoinstantmeshes/_pynim.cpython-311.so`, cross-built here and not taken from PyPI.
- Build: `scripts/build-pynanoinstantmeshes-android.sh` reproduces the module from the pinned
  sdist. It patches only the build files in a work copy (never our assets): nanobind 3.1.0
  (wheel sha256 `96984dbe8154ffb86e653b1b60aaeed6985c710c5e75ad0279eb87943c66292d`) drops the
  cp312 stable-ABI flag for our cp311 target; the vendored TBB 2020.2 is built static for
  Android (no `-lpthread` on bionic, no host `try_run`, generated version template); the
  Instant Meshes sources are compiled as C++14 because they still use `std::unary_function`,
  while the nanobind module is C++17. Toolchain: the NDK clang for arm64 API 24 (matching the
  Klipper Python build), the Android CPython 3.11 prefix `.build/py311-android`, and the
  target extension suffix `.cpython-311.so`.
- Runtime: the module has `DT_NEEDED` on `libpython3.11.so.1.0`, `libc++_shared.so` and the
  Android system libraries. Both non-system libraries are on the runtime path the seam already
  sets (`files/cad/libexec` carries the libpython soname alias and the app's native library
  directory carries `libc++_shared.so`).
- Licences staged beside it in `lib/pynanoinstantmeshes/licenses/`: Instant Meshes BSD-3
  (native/LICENSE.txt), Eigen MPL-2.0, TBB Apache-2.0 and the binding's BSD-3 notice.
- The other optional accelerators (`trimesh`, `manifold3d`, `rtree`, `pymeshfix`,
  `fast_simplification`) remain absent: they are only needed for non-STL input and
  damaged-mesh repair, and those paths still fail with the engine's named error rather than a
  silent substitution.