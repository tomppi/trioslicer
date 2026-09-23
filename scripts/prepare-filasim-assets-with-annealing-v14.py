#!/usr/bin/env python3
"""Annealing v13 plus the Nearby Hot Object replacement for Thermal Integrity."""
from __future__ import annotations

import importlib.util
import pathlib
import subprocess
import sys

V13 = pathlib.Path(__file__).with_name("prepare-filasim-assets-with-annealing-v13.py")
HOT_OBJECT_TRANSFORMS = (
    pathlib.Path(__file__).with_name("filasim-nearby-hot-object-thermal.py"),
    pathlib.Path(__file__).with_name("filasim-nearby-hot-object-api.py"),
    pathlib.Path(__file__).with_name("filasim-nearby-hot-object-viewer.py"),
    pathlib.Path(__file__).with_name("filasim-workspace-tab-state-fix.py"),
)
HOT_OBJECT_CORE_FRAGMENT = pathlib.Path(__file__).with_name("filasim-nearby-hot-object-core.rs")
HOT_OBJECT_OBSERVER_TEST = pathlib.Path(__file__).with_name(
    "test-nearby-hot-object-observer-guard.mjs"
)
WORKSPACE_TAB_REMOUNT_TEST = pathlib.Path(__file__).with_name(
    "test-workspace-tab-remount.mjs"
)
PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[1]
HOT_OBJECT_OBSERVER_GUARD = (
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-observer-guard.js"
)
ANNEALING_OBSERVER_GUARD = (
    PROJECT_ROOT / "app/src/main/filasim/annealing-calculator-observer-guard.js"
)
HOT_OBJECT_RUNTIME_PARTS = (
    HOT_OBJECT_OBSERVER_GUARD,
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-01-core.js",
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-02-ui.js",
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-03-run.js",
)
for path in (
    V13,
    *HOT_OBJECT_TRANSFORMS,
    HOT_OBJECT_CORE_FRAGMENT,
    HOT_OBJECT_OBSERVER_TEST,
    WORKSPACE_TAB_REMOUNT_TEST,
    ANNEALING_OBSERVER_GUARD,
    *HOT_OBJECT_RUNTIME_PARTS,
):
    if not path.is_file():
        raise RuntimeError(f"Nearby Hot Object component is missing: {path}")

spec = importlib.util.spec_from_file_location("enderslicer_annealing_v13", V13)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Unable to load {V13}")
v13 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v13)
thermal = v13.thermal

for transform in HOT_OBJECT_TRANSFORMS:
    if transform not in thermal.THERMAL_TRANSFORMS:
        thermal.THERMAL_TRANSFORMS = (*thermal.THERMAL_TRANSFORMS, transform)
for marker in (
    ".enderslicer-nearby-hot-object-thermal-v1",
    ".enderslicer-nearby-hot-object-api-v1",
    ".enderslicer-nearby-hot-object-viewer-v1",
    ".enderslicer-workspace-tab-state-fix-v2",
):
    if marker not in thermal.THERMAL_MARKERS:
        thermal.THERMAL_MARKERS = (*thermal.THERMAL_MARKERS, marker)

_base_ui = thermal.patch_thermal_ui_runtime


def patch_nearby_hot_object_runtime(target: pathlib.Path) -> None:
    # Let the established chain validate its original runtime first, then replace
    # only the visible Thermal Integrity workspace with the single supported
    # Nearby Hot Object workflow. Progress/cancellation and the 3D viewer remain
    # separate runtimes and continue to use the same stable IDs/events.
    _base_ui(target)
    subprocess.run(
        ["node", str(HOT_OBJECT_OBSERVER_TEST), str(HOT_OBJECT_OBSERVER_GUARD)],
        check=True,
    )
    subprocess.run(
        [
            "node",
            str(WORKSPACE_TAB_REMOUNT_TEST),
            str(HOT_OBJECT_OBSERVER_GUARD),
            str(ANNEALING_OBSERVER_GUARD),
            str(HOT_OBJECT_TRANSFORMS[-1]),
        ],
        check=True,
    )
    target.write_text(
        "".join(path.read_text(encoding="utf-8") for path in HOT_OBJECT_RUNTIME_PARTS),
        encoding="utf-8",
    )
    subprocess.run(["node", "--check", str(target)], check=True)
    text = target.read_text(encoding="utf-8")
    for contract in (
        "Prevent Nearby Hot Object MutationObserver feedback loops",
        "EnderSlicerNearbyObserverTestApi",
        "recordsAddThermalMount",
        "installedMount",
        "document.getElementById(MOUNT_ID)",
        "Nearby Hot Object",
        "Select nearest point on model",
        "sourceTargetMm",
        "sourceDiameterMm",
        "enderslicer-thermal-result-3d",
        "vertexTemperatures",
        "The model now shows the final temperature in 3D colours",
    ):
        if contract not in text:
            raise RuntimeError(f"Nearby Hot Object runtime is missing {contract!r}")
    guard_marker = "Prevent Nearby Hot Object MutationObserver feedback loops"
    runtime_marker = "Android-only nearby hot object thermal workspace"
    if text.index(guard_marker) > text.index(runtime_marker):
        raise RuntimeError(
            "Nearby Hot Object observer guard must load before the runtime creates its observer"
        )
    for forbidden in (
        "Total contact-heater power",
        "Contact-heater orientation",
        "Surface heat power (W)",
    ):
        if forbidden in text:
            raise RuntimeError(f"Obsolete contact-heater UI survived: {forbidden!r}")


thermal.patch_thermal_ui_runtime = patch_nearby_hot_object_runtime
thermal.THERMAL_PACKAGE_MARKER_TEXT = (
    "format=1\n"
    f"filasim={thermal.BASE.FILASIM_COMMIT}\n"
    "transforms=solver,hardening,audit-fixes,progress-v2,react-tab-v1,"
    "bugfix-round1,bugfix-round2,linear-fast-path-v1,physical-model-v1,"
    "annealing-v8,filasim-material-source-v1,observer-guard-v2,step-budget-v2,"
    "3d-result-fix-v1,partial-duration-v1,short-duration-stability-v1,"
    "nearby-hot-object-observer-guard-v2,nearby-hot-object-thermal-v1,"
    "nearby-hot-object-api-v1,nearby-hot-object-viewer-v1,"
    "workspace-tab-state-fix-v2,workspace-tab-remount-test-v2\n"
)

if __name__ == "__main__":
    try:
        raise SystemExit(thermal.BASE.main())
    except Exception as error:
        print(f"nearby-hot-object filaSim v14 asset preparation failed: {error}", file=sys.stderr)
        raise
