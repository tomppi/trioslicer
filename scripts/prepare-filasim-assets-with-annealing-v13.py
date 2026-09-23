#!/usr/bin/env python3
"""Thermal Integrity v12 plus geometry-aware oven annealing support."""
from __future__ import annotations

import importlib.util
import pathlib
import shutil
import subprocess
import sys

V12 = pathlib.Path(__file__).with_name("prepare-filasim-assets-with-thermal-integrity-v12.py")
ANNEALING_TRANSFORM = pathlib.Path(__file__).with_name("filasim-annealing-cycle.py")
MATERIAL_TRANSFORM = pathlib.Path(__file__).with_name("filasim-annealing-material-source.py")
ANNEALING_3D_RESULT_FIX = pathlib.Path(__file__).with_name("filasim-annealing-3d-result-fix.py")
ANNEALING_PARTIAL_DURATION = pathlib.Path(__file__).with_name("filasim-annealing-partial-duration.py")
ANNEALING_SHORT_DURATION_STABILITY = pathlib.Path(__file__).with_name(
    "filasim-annealing-short-duration-stability.py"
)
PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[1]
MATERIAL_RUNTIME = PROJECT_ROOT / "app/src/main/filasim/material-profile-source.js"
THERMAL_MATERIAL_ADAPTER = PROJECT_ROOT / "app/src/main/filasim/thermal-material-profile-adapter.js"
ANNEALING_OBSERVER_GUARD = PROJECT_ROOT / "app/src/main/filasim/annealing-calculator-observer-guard.js"
ANNEALING_STEP_BUDGET_GUARD = PROJECT_ROOT / "app/src/main/filasim/annealing-step-budget-guard.js"
ANNEALING_UI_PARTS = (
    PROJECT_ROOT / "app/src/main/filasim/annealing-calculator-01-core.js",
    PROJECT_ROOT / "app/src/main/filasim/annealing-calculator-02-ui.js",
    PROJECT_ROOT / "app/src/main/filasim/annealing-calculator-03-cycle.js",
    PROJECT_ROOT / "app/src/main/filasim/annealing-calculator-03a-workload-preflight.js",
    PROJECT_ROOT / "app/src/main/filasim/annealing-calculator-03b-materials.js",
    PROJECT_ROOT / "app/src/main/filasim/annealing-calculator-03c-partial-duration.js",
    PROJECT_ROOT / "app/src/main/filasim/annealing-calculator-04-report.js",
)
for path in (
    V12,
    ANNEALING_TRANSFORM,
    MATERIAL_TRANSFORM,
    ANNEALING_3D_RESULT_FIX,
    ANNEALING_PARTIAL_DURATION,
    ANNEALING_SHORT_DURATION_STABILITY,
    MATERIAL_RUNTIME,
    THERMAL_MATERIAL_ADAPTER,
    ANNEALING_OBSERVER_GUARD,
    ANNEALING_STEP_BUDGET_GUARD,
    *ANNEALING_UI_PARTS,
):
    if not path.is_file():
        raise RuntimeError(f"Annealing v13 component is missing: {path}")

spec = importlib.util.spec_from_file_location("enderslicer_thermal_v12", V12)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Unable to load {V12}")
v12 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v12)
thermal = v12.thermal

for transform in (
    ANNEALING_TRANSFORM,
    MATERIAL_TRANSFORM,
    ANNEALING_3D_RESULT_FIX,
    ANNEALING_PARTIAL_DURATION,
    ANNEALING_SHORT_DURATION_STABILITY,
):
    if transform not in thermal.THERMAL_TRANSFORMS:
        thermal.THERMAL_TRANSFORMS = (*thermal.THERMAL_TRANSFORMS, transform)
for marker in (
    ".enderslicer-annealing-cycle-v1",
    ".enderslicer-filasim-material-source-v1",
    ".enderslicer-annealing-3d-result-fix-v1",
    ".enderslicer-annealing-partial-duration-v1",
    ".enderslicer-annealing-short-duration-stability-v1",
):
    if marker not in thermal.THERMAL_MARKERS:
        thermal.THERMAL_MARKERS = (*thermal.THERMAL_MARKERS, marker)

MATERIAL_UI_NAME = "material-profile-source.js"
THERMAL_ADAPTER_NAME = "thermal-material-profile-adapter.js"
ANNEALING_OBSERVER_GUARD_NAME = "annealing-calculator-observer-guard.js"
ANNEALING_STEP_BUDGET_GUARD_NAME = "annealing-step-budget-guard.js"
ANNEALING_UI_NAME = "annealing-calculator.js"
MATERIAL_UI_TAG = f'<script src="./{MATERIAL_UI_NAME}"></script>'
THERMAL_ADAPTER_TAG = f'<script src="./{THERMAL_ADAPTER_NAME}"></script>'
ANNEALING_OBSERVER_GUARD_TAG = f'<script src="./{ANNEALING_OBSERVER_GUARD_NAME}"></script>'
ANNEALING_STEP_BUDGET_GUARD_TAG = f'<script src="./{ANNEALING_STEP_BUDGET_GUARD_NAME}"></script>'
ANNEALING_UI_TAG = f'<script src="./{ANNEALING_UI_NAME}"></script>'
_base_inject = thermal.BASE.inject_bridge


def copy_checked(source: pathlib.Path, target: pathlib.Path, contracts: tuple[str, ...]) -> None:
    shutil.copyfile(source, target)
    subprocess.run(["node", "--check", str(target)], check=True)
    verified = target.read_text(encoding="utf-8")
    for contract in contracts:
        if contract not in verified:
            raise RuntimeError(f"Generated runtime {target.name} is missing {contract!r}")


def build_annealing_runtime(target: pathlib.Path) -> None:
    source = "\n".join(path.read_text(encoding="utf-8").rstrip() for path in ANNEALING_UI_PARTS) + "\n"
    target.write_text(source, encoding="utf-8")
    subprocess.run(["node", "--check", str(target)], check=True)
    verified = target.read_text(encoding="utf-8")
    for contract in (
        "coldest material voxel",
        "readinessTemperatureC",
        "Simulate Oven Exposure & Cooling",
        "partial temperature field is shown",
        "initialTemperatureFieldC",
        "materialVolumeAtThreshold",
        "Spool-specific dimensional calibration",
        "filaSimMaterialProfile",
        "runCycleWithVoxelBudgetAndPartialResults",
        "120 million solid-cell steps",
        "thermalOnly: true",
    ):
        if contract not in verified:
            raise RuntimeError(f"Generated annealing runtime is missing {contract!r}")


def inject_annealing_runtime(index_file: pathlib.Path) -> None:
    _base_inject(index_file)
    copy_checked(
        MATERIAL_RUNTIME,
        index_file.with_name(MATERIAL_UI_NAME),
        ("EnderSlicerFilaSimProfiles", "resolveMaterialFromSnapshot", "SUPPLEMENTS"),
    )
    copy_checked(
        THERMAL_MATERIAL_ADAPTER,
        index_file.with_name(THERMAL_ADAPTER_NAME),
        (
            "filaSim's live material library",
            "ti-material-source",
            "recordsAddThermalGroup",
            "syncThermalMaterialOnMount",
        ),
    )
    copy_checked(
        ANNEALING_OBSERVER_GUARD,
        index_file.with_name(ANNEALING_OBSERVER_GUARD_NAME),
        (
            "recordsAddAnnealingMount",
            "installFilaSimMaterialUi",
            "must not call installUi again",
        ),
    )
    copy_checked(
        ANNEALING_STEP_BUDGET_GUARD,
        index_file.with_name(ANNEALING_STEP_BUDGET_GUARD_NAME),
        (
            "MAX_STAGE_STEPS = 2000",
            "MAX_TRANSIENT_CELL_STEPS = 120_000_000",
            "applyForSolidCells",
            "automatically increased",
            "#ac-run",
        ),
    )
    build_annealing_runtime(index_file.with_name(ANNEALING_UI_NAME))

    text = index_file.read_text(encoding="utf-8")
    tags = (
        MATERIAL_UI_TAG,
        THERMAL_ADAPTER_TAG,
        ANNEALING_OBSERVER_GUARD_TAG,
        ANNEALING_STEP_BUDGET_GUARD_TAG,
        ANNEALING_UI_TAG,
    )
    for tag in tags:
        text = text.replace(f"  {tag}\n", "").replace(tag, "")
    chain = (
        f"{thermal.THERMAL_UI_TAG}\n"
        f"  {MATERIAL_UI_TAG}\n"
        f"  {THERMAL_ADAPTER_TAG}\n"
        f"  {ANNEALING_OBSERVER_GUARD_TAG}\n"
        f"  {ANNEALING_STEP_BUDGET_GUARD_TAG}\n"
        f"  {ANNEALING_UI_TAG}"
    )
    if thermal.THERMAL_UI_TAG in text:
        text = text.replace(thermal.THERMAL_UI_TAG, chain, 1)
    elif "</body>" in text:
        text = text.replace("</body>", f"  {chain}\n</body>", 1)
    else:
        raise RuntimeError("Unable to inject material and annealing runtimes into index.html")
    index_file.write_text(text, encoding="utf-8")

    verified = index_file.read_text(encoding="utf-8")
    for tag in tags:
        if verified.count(tag) != 1:
            raise RuntimeError(f"Runtime tag was not retained exactly once: {tag}")
    order = [
        verified.index(thermal.THERMAL_UI_TAG),
        verified.index(MATERIAL_UI_TAG),
        verified.index(THERMAL_ADAPTER_TAG),
        verified.index(ANNEALING_OBSERVER_GUARD_TAG),
        verified.index(ANNEALING_STEP_BUDGET_GUARD_TAG),
        verified.index(ANNEALING_UI_TAG),
    ]
    if order != sorted(order) or len(set(order)) != len(order):
        raise RuntimeError(
            "Thermal, material-source, adapter, Anneal observer guard, step-budget guard and runtime order is invalid"
        )


thermal.BASE.inject_bridge = inject_annealing_runtime
thermal.THERMAL_PACKAGE_MARKER_TEXT = (
    "format=1\n"
    f"filasim={thermal.BASE.FILASIM_COMMIT}\n"
    "transforms=solver,hardening,audit-fixes,progress-v2,react-tab-v1,"
    "bugfix-round1,bugfix-round2,linear-fast-path-v1,physical-model-v1,"
    "annealing-v8,filasim-material-source-v1,observer-guard-v1,step-budget-v2,"
    "3d-result-fix-v1,partial-duration-v1,short-duration-stability-v1\n"
)

if __name__ == "__main__":
    try:
        raise SystemExit(thermal.BASE.main())
    except Exception as error:
        print(f"annealing filaSim v13 asset preparation failed: {error}", file=sys.stderr)
        raise
