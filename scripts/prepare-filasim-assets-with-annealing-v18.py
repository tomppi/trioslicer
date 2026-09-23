#!/usr/bin/env python3
"""Marker drag, shaped finite worlds and conservative engine-bay thermal hints."""
from __future__ import annotations

import importlib.util
import pathlib
import subprocess
import sys

V17 = pathlib.Path(__file__).with_name("prepare-filasim-assets-with-annealing-v17.py")
SPATIAL_TRANSFORM_SOURCE = pathlib.Path(__file__).with_name(
    "filasim-nearby-hot-object-spatial-environment-viewer.py"
)
SPATIAL_TRANSFORM = pathlib.Path(__file__).with_name(
    "filasim-nearby-hot-object-spatial-environment-viewer-v2.py"
)
SHAPED_TRANSFORM = pathlib.Path(__file__).with_name(
    "filasim-engine-bay-shaped-envelope-viewer.py"
)
SPATIAL_TEST = pathlib.Path(__file__).with_name(
    "test-nearby-hot-object-spatial-environment.mjs"
)
HINT_TEST = pathlib.Path(__file__).with_name(
    "test-engine-bay-survival-hint.mjs"
)
FAN_HOLD_TEST = pathlib.Path(__file__).with_name(
    "test-engine-bay-paper-soak-fan-hold.mjs"
)
SHAPED_TEST = pathlib.Path(__file__).with_name(
    "test-engine-bay-shaped-envelope.mjs"
)
PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[1]
SPATIAL_RUNTIME_PARTS = (
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-02h-spatial-environment-ui.js",
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-02i-spatial-preset-run-fix.js",
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-02j-engine-bay-survival-hint.js",
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-02k-paper-soak-fan-hold.js",
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-02l-engine-bay-shaped-envelope.js",
)
for path in (
    V17,
    SPATIAL_TRANSFORM_SOURCE,
    SPATIAL_TRANSFORM,
    SHAPED_TRANSFORM,
    SPATIAL_TEST,
    HINT_TEST,
    FAN_HOLD_TEST,
    SHAPED_TEST,
    *SPATIAL_RUNTIME_PARTS,
):
    if not path.is_file():
        raise RuntimeError(f"Spatial environment filaSim component is missing: {path}")

spec = importlib.util.spec_from_file_location("enderslicer_annealing_v17", V17)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Unable to load {V17}")
v17 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v17)
thermal = v17.thermal

for transform in (SPATIAL_TRANSFORM, SHAPED_TRANSFORM):
    if transform not in thermal.THERMAL_TRANSFORMS:
        thermal.THERMAL_TRANSFORMS = (*thermal.THERMAL_TRANSFORMS, transform)
for marker in (
    ".enderslicer-nearby-hot-object-spatial-environment-viewer-v2",
    ".enderslicer-engine-bay-shaped-envelope-viewer-v1",
):
    if marker not in thermal.THERMAL_MARKERS:
        thermal.THERMAL_MARKERS = (*thermal.THERMAL_MARKERS, marker)

_base_ui = thermal.patch_thermal_ui_runtime


def patch_spatial_environment_runtime(target: pathlib.Path) -> None:
    _base_ui(target)
    subprocess.run(
        [
            "node",
            str(SPATIAL_TEST),
            *(str(path) for path in SPATIAL_RUNTIME_PARTS[:2]),
            str(SPATIAL_TRANSFORM_SOURCE),
        ],
        check=True,
    )
    subprocess.run(
        [
            "node",
            str(HINT_TEST),
            str(SPATIAL_RUNTIME_PARTS[2]),
        ],
        check=True,
    )
    subprocess.run(
        [
            "node",
            str(FAN_HOLD_TEST),
            str(SPATIAL_RUNTIME_PARTS[3]),
        ],
        check=True,
    )
    subprocess.run(
        [
            "node",
            str(SHAPED_TEST),
            str(SPATIAL_RUNTIME_PARTS[4]),
            str(SHAPED_TRANSFORM),
        ],
        check=True,
    )
    text = target.read_text(encoding="utf-8")
    runtime = "".join(path.read_text(encoding="utf-8") for path in SPATIAL_RUNTIME_PARTS)
    anchor = "  // Preserve installUi callback name for MutationObserver guard."
    if runtime not in text:
        if text.count(anchor) != 1:
            raise RuntimeError("Expected one observer-safe installer anchor in Nearby Hot Object runtime")
        target.write_text(text.replace(anchor, runtime + anchor, 1), encoding="utf-8")
    subprocess.run(["node", "--check", str(target)], check=True)
    verified = target.read_text(encoding="utf-8")
    for contract in (
        "No model-point marking is required.",
        "Show the enclosure / engine-bay walls in 3D",
        "enclosureVolumeFromDimensions",
        "movable-source-automatic-nearest-surface",
        "entire-voxel-model",
        "applyEnclosureBoxPresetWithFiniteWorld",
        "ENCLOSURE_WORLD_PRESETS",
        "runAnalysisWithAutomaticSourceProjection",
        "EnderSlicerThermalHintTestApi",
        "paper_suv_400s_soak",
        "decisionHint",
        "EnderSlicerPaperSoakFanHoldTestApi",
        "paperSoakStagePlan",
        "PAPER_SOAK_FORCED_VENTILATION_ACH",
        "EnderSlicerEngineBayEnvelopeTestApi",
        "generic-engine-bay-v1",
        "ENGINE_BAY_ENVELOPE_SHAPE_FACTOR",
        "enclosureCalculationClosed",
        "installUi = function installUi()",
    ):
        if contract not in verified:
            raise RuntimeError(f"Spatial environment runtime is missing {contract!r}")


thermal.patch_thermal_ui_runtime = patch_spatial_environment_runtime
thermal.THERMAL_PACKAGE_MARKER_TEXT = v17.thermal.THERMAL_PACKAGE_MARKER_TEXT.replace(
    "nearby-hot-object-marker-drag-ui-v1\n",
    "nearby-hot-object-marker-drag-ui-v1,"
    "nearby-hot-object-spatial-environment-viewer-v2,"
    "nearby-hot-object-spatial-environment-ui-v2,"
    "nearby-hot-object-spatial-preset-run-fix-v2,"
    "engine-bay-thermal-survival-hint-v1,"
    "engine-bay-paper-soak-fan-hold-v1,"
    "engine-bay-shaped-envelope-viewer-v1,"
    "engine-bay-shaped-envelope-runtime-v1\n",
)

if __name__ == "__main__":
    try:
        raise SystemExit(thermal.BASE.main())
    except Exception as error:
        print(f"spatial environment filaSim v18 asset preparation failed: {error}", file=sys.stderr)
        raise
