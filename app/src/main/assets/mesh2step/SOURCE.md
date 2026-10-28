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
`OCP`, `numpy` and `scipy` on `PYTHONPATH`. No optional accelerator is required for a clean
mechanical STL; `trimesh`, `manifold3d`, `rtree`, `pymeshfix`, `fast_simplification` and
`pynanoinstantmeshes` are not shipped, so the factory fallbacks are used and features that
require them (quad remeshing for the Smooth preset, MeshFix repair of damaged meshes, non-STL
input formats) fail with the engine's own error instead of being silently substituted.