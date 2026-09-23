#!/usr/bin/env python3
"""Prepare format-8 filaSim with shaped, zoned and movable-object engine-bay analysis."""

from __future__ import annotations

import importlib.util
import pathlib
import runpy
import sys

THERMAL_PREPARER = pathlib.Path(__file__).with_name(
    "prepare-filasim-assets-with-annealing-v20.py"
)
FINALIZER = pathlib.Path(__file__).with_name("finalize-filasim-apk-manifest.py")

for required in (THERMAL_PREPARER, FINALIZER):
    if not required.is_file():
        raise RuntimeError(f"filaSim preparation component is missing: {required}")

exit_code = 0
try:
    runpy.run_path(str(THERMAL_PREPARER), run_name="__main__")
except SystemExit as error:
    exit_code = 0 if error.code is None else int(error.code)

if exit_code != 0:
    raise SystemExit(exit_code)

project_root = pathlib.Path(__file__).resolve().parents[1]
if "--project-root" in sys.argv:
    index = sys.argv.index("--project-root")
    if index + 1 >= len(sys.argv):
        raise RuntimeError("--project-root requires a value")
    project_root = pathlib.Path(sys.argv[index + 1]).resolve()

spec = importlib.util.spec_from_file_location("enderslicer_filasim_manifest", FINALIZER)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Unable to load filaSim package manifest finalizer: {FINALIZER}")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
module.finalize(project_root / "app/src/main/assets/filasim")
print("Finalized package-safe filaSim source marker and SHA256SUMS")
