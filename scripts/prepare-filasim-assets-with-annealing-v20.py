#!/usr/bin/env python3
"""Zoned engine bay plus movable object, vehicle-axis and React-isolated UI fixes."""
from __future__ import annotations

import importlib.util
import pathlib
import subprocess
import sys

V19 = pathlib.Path(__file__).with_name("prepare-filasim-assets-with-annealing-v19.py")
ZONE_AXIS_TRANSFORM = pathlib.Path(__file__).with_name("filasim-engine-bay-zone-axis-fix.py")
PLACEMENT_TRANSFORM = pathlib.Path(__file__).with_name(
    "filasim-engine-bay-part-placement-viewer.py"
)
CALIBRATED_TRANSFORM = pathlib.Path(__file__).with_name(
    "filasim-nearby-hot-object-calibrated.py"
)
PLACEMENT_TEST = pathlib.Path(__file__).with_name("test-engine-bay-part-placement.mjs")
ZONE_AXIS_TEST = pathlib.Path(__file__).with_name("test-engine-bay-zone-axis.mjs")
REACT_SAFE_TEST = pathlib.Path(__file__).with_name(
    "test-nearby-hot-object-react-safe-remount.mjs"
)
PROGRESS_ACTION_ROW_TEST = pathlib.Path(__file__).with_name(
    "test-nearby-hot-object-progress-action-row.mjs"
)
ENGINE_LAYOUT_TEST = pathlib.Path(__file__).with_name(
    "test-nearby-hot-object-fixed-engine-layout.mjs"
)
PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[1]
PLACEMENT_RUNTIME = (
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-02p-engine-bay-part-placement.js"
)
REACT_SAFE_RUNTIME = (
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-02q-react-safe-remount.js"
)
ENGINE_LAYOUT_RUNTIME = (
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-02s-fixed-engine-layout.js"
)
PROGRESS_WORKSPACE_RUNTIME = (
    PROJECT_ROOT / "app/src/main/filasim/thermal-integrity-workspace.js"
)
for path in (
    V19,
    ZONE_AXIS_TRANSFORM,
    PLACEMENT_TRANSFORM,
    CALIBRATED_TRANSFORM,
    PLACEMENT_TEST,
    ZONE_AXIS_TEST,
    REACT_SAFE_TEST,
    PROGRESS_ACTION_ROW_TEST,
    ENGINE_LAYOUT_TEST,
    PLACEMENT_RUNTIME,
    REACT_SAFE_RUNTIME,
    ENGINE_LAYOUT_RUNTIME,
    PROGRESS_WORKSPACE_RUNTIME,
):
    if not path.is_file():
        raise RuntimeError(f"Engine-bay v20 component is missing: {path}")

spec = importlib.util.spec_from_file_location("enderslicer_annealing_v19", V19)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Unable to load {V19}")
v19 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v19)
thermal = v19.thermal

# The zoned core is created by v19. Apply the vehicle-axis correction after it,
# then add the viewer placement transform. The order is intentional.
for transform in (ZONE_AXIS_TRANSFORM, PLACEMENT_TRANSFORM):
    if transform not in thermal.THERMAL_TRANSFORMS:
        thermal.THERMAL_TRANSFORMS = (*thermal.THERMAL_TRANSFORMS, transform)
if CALIBRATED_TRANSFORM not in thermal.THERMAL_TRANSFORMS:
    thermal.THERMAL_TRANSFORMS = (*thermal.THERMAL_TRANSFORMS, CALIBRATED_TRANSFORM)
for marker in (
    ".enderslicer-engine-bay-zone-axis-fix-v1",
    ".enderslicer-engine-bay-part-placement-viewer-v1",
    ".enderslicer-nearby-hot-object-calibrated-v1",
):
    if marker not in thermal.THERMAL_MARKERS:
        thermal.THERMAL_MARKERS = (*thermal.THERMAL_MARKERS, marker)

_base_ui = thermal.patch_thermal_ui_runtime


def patch_part_placement_runtime(target: pathlib.Path) -> None:
    _base_ui(target)
    subprocess.run(
        [
            "node",
            str(PLACEMENT_TEST),
            str(PLACEMENT_RUNTIME),
            str(PLACEMENT_TRANSFORM),
        ],
        check=True,
    )
    subprocess.run(
        [
            "node",
            str(ZONE_AXIS_TEST),
            str(PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-02n-engine-bay-zoned-mapping-fix.js"),
            str(ZONE_AXIS_TRANSFORM),
        ],
        check=True,
    )
    subprocess.run(
        ["node", str(REACT_SAFE_TEST), str(REACT_SAFE_RUNTIME)],
        check=True,
    )
    subprocess.run(
        ["node", str(PROGRESS_ACTION_ROW_TEST), str(PROGRESS_WORKSPACE_RUNTIME)],
        check=True,
    )
    subprocess.run(
        ["node", str(ENGINE_LAYOUT_TEST), str(ENGINE_LAYOUT_RUNTIME)],
        check=True,
    )
    text = target.read_text(encoding="utf-8")
    runtime = (
        PLACEMENT_RUNTIME.read_text(encoding="utf-8")
        + REACT_SAFE_RUNTIME.read_text(encoding="utf-8")
        + ENGINE_LAYOUT_RUNTIME.read_text(encoding="utf-8")
    )
    anchor = "  // Preserve installUi callback name for MutationObserver guard."
    if runtime not in text:
        if text.count(anchor) != 1:
            raise RuntimeError("Expected one observer-safe installer anchor in Nearby Hot Object runtime")
        target.write_text(text.replace(anchor, runtime + anchor, 1), encoding="utf-8")
    subprocess.run(["node", "--check", str(target)], check=True)
    verified = target.read_text(encoding="utf-8")
    for contract in (
        "Move plastic object X/Y",
        "Move plastic object Z",
        "partPlacementXMm",
        "fixed-voxel-grid-inverse-world-transform-v1",
        "complete-object-bounding-box-inside-closed-envelope-v1",
        "thermalPointFromViewer",
        "solverEnvelopeOffsets",
        "EnderSlicerPartPlacementTestApi",
        "Y/depth runs front -> rear",
        "installUiInReactIsolatedShadowRoot",
        "appendNearbyHotObjectIntoShadow",
        "__enderSlicerThermalShadowLookup",
        "EnderSlicerNearbyReactSafeMountTestApi",
        "installUi = function installUi()",
        "ENGINE_LAYOUT_PRESETS",
        "engine_turbo_cluster",
        "Engine + turbocharger cluster",
        "Engine yaw / rotation (degrees)",
        "Engine pitch (degrees)",
        "EnderSlicerEngineLayoutTestApi",
    ):
        if contract not in verified:
            raise RuntimeError(f"Engine-bay v20 runtime is missing {contract!r}")


thermal.patch_thermal_ui_runtime = patch_part_placement_runtime
thermal.THERMAL_PACKAGE_MARKER_TEXT = v19.thermal.THERMAL_PACKAGE_MARKER_TEXT.replace(
    "engine-bay-zoned-result-compat-v1\n",
    "engine-bay-zoned-result-compat-v1,"
    "engine-bay-zone-vehicle-axis-v1,"
    "engine-bay-part-placement-viewer-v1,"
    "engine-bay-part-placement-runtime-v1,"
    "nearby-hot-object-react-shadow-isolation-v2,"
    "nearby-hot-object-progress-action-row-v1,"
    "nearby-hot-object-fixed-engine-layout-v1\n",
)

if __name__ == "__main__":
    try:
        raise SystemExit(thermal.BASE.main())
    except Exception as error:
        print(f"engine-bay movable/react-isolated filaSim v20 asset preparation failed: {error}", file=sys.stderr)
        raise
