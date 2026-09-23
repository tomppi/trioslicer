#!/usr/bin/env python3
"""Engine-bay v16 plus draggable heat-source markers and calculation-gap guides."""
from __future__ import annotations

import importlib.util
import pathlib
import subprocess
import sys

V16 = pathlib.Path(__file__).with_name("prepare-filasim-assets-with-annealing-v16.py")
MARKER_DRAG_TRANSFORM = pathlib.Path(__file__).with_name(
    "filasim-nearby-hot-object-marker-drag-viewer.py"
)
MARKER_DRAG_TEST = pathlib.Path(__file__).with_name(
    "test-nearby-hot-object-marker-drag.mjs"
)
PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[1]
MARKER_DRAG_RUNTIME = (
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-02g-marker-drag-ui.js"
)
for path in (V16, MARKER_DRAG_TRANSFORM, MARKER_DRAG_TEST, MARKER_DRAG_RUNTIME):
    if not path.is_file():
        raise RuntimeError(f"Marker-drag filaSim component is missing: {path}")

spec = importlib.util.spec_from_file_location("enderslicer_annealing_v16", V16)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Unable to load {V16}")
v16 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v16)
thermal = v16.thermal

if MARKER_DRAG_TRANSFORM not in thermal.THERMAL_TRANSFORMS:
    thermal.THERMAL_TRANSFORMS = (*thermal.THERMAL_TRANSFORMS, MARKER_DRAG_TRANSFORM)
marker = ".enderslicer-nearby-hot-object-marker-drag-viewer-v1"
if marker not in thermal.THERMAL_MARKERS:
    thermal.THERMAL_MARKERS = (*thermal.THERMAL_MARKERS, marker)

_base_ui = thermal.patch_thermal_ui_runtime


def patch_marker_drag_runtime(target: pathlib.Path) -> None:
    _base_ui(target)
    subprocess.run(
        [
            "node",
            str(MARKER_DRAG_TEST),
            str(MARKER_DRAG_RUNTIME),
            str(MARKER_DRAG_TRANSFORM),
        ],
        check=True,
    )
    text = target.read_text(encoding="utf-8")
    runtime = MARKER_DRAG_RUNTIME.read_text(encoding="utf-8")
    anchor = "  // Preserve installUi callback name for MutationObserver guard."
    if runtime not in text:
        if text.count(anchor) != 1:
            raise RuntimeError("Expected one observer-safe installer anchor in Nearby Hot Object runtime")
        target.write_text(text.replace(anchor, runtime + anchor, 1), encoding="utf-8")
    subprocess.run(["node", "--check", str(target)], check=True)
    verified = target.read_text(encoding="utf-8")
    for contract in (
        "Show source markers and calculation-gap guides",
        "Mark sources in 3D",
        "Drag primary source",
        "Drag turbo / exhaust source",
        "enderslicer-nearby-hot-object-drag-mode",
        "renderCombinedHeatSourceMarkers",
        "installUi = function installUi()",
    ):
        if contract not in verified:
            raise RuntimeError(f"Marker-drag runtime is missing {contract!r}")


thermal.patch_thermal_ui_runtime = patch_marker_drag_runtime
thermal.THERMAL_PACKAGE_MARKER_TEXT = v16.thermal.THERMAL_PACKAGE_MARKER_TEXT.replace(
    "engine-scenario-preset-order-v1\n",
    "engine-scenario-preset-order-v1,nearby-hot-object-marker-drag-viewer-v1,"
    "nearby-hot-object-marker-drag-ui-v1\n",
)

if __name__ == "__main__":
    try:
        raise SystemExit(thermal.BASE.main())
    except Exception as error:
        print(f"marker-drag filaSim v17 asset preparation failed: {error}", file=sys.stderr)
        raise
