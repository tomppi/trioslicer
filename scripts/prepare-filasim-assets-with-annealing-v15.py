#!/usr/bin/env python3
"""Nearby Hot Object v14 plus engine-bay and finite-enclosure coupling."""
from __future__ import annotations

import importlib.util
import pathlib
import subprocess
import sys

V14 = pathlib.Path(__file__).with_name("prepare-filasim-assets-with-annealing-v14.py")
ENCLOSURE_TRANSFORMS = (
    pathlib.Path(__file__).with_name("filasim-nearby-hot-object-enclosure-core.py"),
    pathlib.Path(__file__).with_name("filasim-nearby-hot-object-enclosure-api.py"),
)
ENCLOSURE_TEST = pathlib.Path(__file__).with_name("test-nearby-hot-object-enclosure.mjs")
INSTALLER_OBSERVER_TEST = pathlib.Path(__file__).with_name(
    "test-nearby-hot-object-installer-observer-contract.mjs"
)
PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[1]
ENCLOSURE_RUNTIME_MODEL_PARTS = (
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-02a-enclosure-model-01.js",
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-02a-enclosure-model-02.js",
)
ENCLOSURE_RUNTIME_UI = PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-02b-enclosure-ui.js"
INSTALLER_OBSERVER_CONTRACT = (
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-02c-installer-observer-contract.js"
)
HOT_OBJECT_RUNTIME_PARTS = (
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-observer-guard.js",
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-01-core.js",
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-02-ui.js",
    *ENCLOSURE_RUNTIME_MODEL_PARTS,
    ENCLOSURE_RUNTIME_UI,
    INSTALLER_OBSERVER_CONTRACT,
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-03-run.js",
)
for path in (
    V14,
    *ENCLOSURE_TRANSFORMS,
    ENCLOSURE_TEST,
    INSTALLER_OBSERVER_TEST,
    *HOT_OBJECT_RUNTIME_PARTS,
):
    if not path.is_file():
        raise RuntimeError(f"Engine-bay/enclosure filaSim component is missing: {path}")

spec = importlib.util.spec_from_file_location("enderslicer_annealing_v14", V14)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Unable to load {V14}")
v14 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v14)
thermal = v14.thermal

for transform in ENCLOSURE_TRANSFORMS:
    if transform not in thermal.THERMAL_TRANSFORMS:
        thermal.THERMAL_TRANSFORMS = (*thermal.THERMAL_TRANSFORMS, transform)
for marker in (
    ".enderslicer-nearby-hot-object-enclosure-core-v1",
    ".enderslicer-nearby-hot-object-enclosure-api-v1",
):
    if marker not in thermal.THERMAL_MARKERS:
        thermal.THERMAL_MARKERS = (*thermal.THERMAL_MARKERS, marker)

_base_ui = thermal.patch_thermal_ui_runtime


def patch_enclosure_runtime(target: pathlib.Path) -> None:
    _base_ui(target)
    subprocess.run(
        ["node", str(ENCLOSURE_TEST), *(str(path) for path in ENCLOSURE_RUNTIME_MODEL_PARTS)],
        check=True,
    )
    subprocess.run(
        ["node", str(INSTALLER_OBSERVER_TEST), str(INSTALLER_OBSERVER_CONTRACT)],
        check=True,
    )
    target.write_text(
        "".join(path.read_text(encoding="utf-8") for path in HOT_OBJECT_RUNTIME_PARTS),
        encoding="utf-8",
    )
    subprocess.run(["node", "--check", str(target)], check=True)
    text = target.read_text(encoding="utf-8")
    for contract in (
        "Engine bay — engine running",
        "Engine bay — heat soak after shutdown",
        "finite-well-mixed-air-plus-radiant-wall-stage-coupling-v1",
        "steady-well-mixed-air-plus-radiant-wall-fixed-point-v1",
        "sourcePartEmissivity",
        "totalExteriorAreaMm2",
        "MAX_ENVIRONMENT_COUPLING_STAGES = 24",
        "Built-in environment presets are editable starting assumptions",
        "Preserve installUi callback name for MutationObserver guard",
        "installUi = function installUi()",
    ):
        if contract not in text:
            raise RuntimeError(f"Engine-bay/enclosure runtime is missing {contract!r}")


thermal.patch_thermal_ui_runtime = patch_enclosure_runtime
thermal.THERMAL_PACKAGE_MARKER_TEXT = (
    "format=1\n"
    f"filasim={thermal.BASE.FILASIM_COMMIT}\n"
    "transforms=solver,hardening,audit-fixes,progress-v2,react-tab-v1,"
    "bugfix-round1,bugfix-round2,linear-fast-path-v1,physical-model-v1,"
    "annealing-v8,filasim-material-source-v1,observer-guard-v2,step-budget-v2,"
    "3d-result-fix-v1,partial-duration-v1,short-duration-stability-v1,"
    "nearby-hot-object-observer-guard-v2,nearby-hot-object-thermal-v1,"
    "nearby-hot-object-api-v1,nearby-hot-object-viewer-v1,"
    "workspace-tab-state-fix-v2,workspace-tab-remount-test-v2,"
    "nearby-hot-object-enclosure-core-v1,nearby-hot-object-enclosure-api-v1,"
    "nearby-hot-object-enclosure-runtime-v1,"
    "nearby-hot-object-installer-observer-contract-v1\n"
)

if __name__ == "__main__":
    try:
        raise SystemExit(thermal.BASE.main())
    except Exception as error:
        print(f"engine-bay/enclosure filaSim v15 asset preparation failed: {error}", file=sys.stderr)
        raise
