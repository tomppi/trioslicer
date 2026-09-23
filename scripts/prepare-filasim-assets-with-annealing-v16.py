#!/usr/bin/env python3
"""Engine-bay enclosure v15 plus dual heat sources and engine presets."""
from __future__ import annotations

import importlib.util
import pathlib
import subprocess
import sys

V15 = pathlib.Path(__file__).with_name("prepare-filasim-assets-with-annealing-v15.py")
DUAL_TRANSFORMS = (
    pathlib.Path(__file__).with_name("filasim-nearby-hot-object-dual-source-core.py"),
    pathlib.Path(__file__).with_name("filasim-nearby-hot-object-dual-source-api.py"),
)
PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[1]
DUAL_RUNTIME_PARTS = (
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-02d-engine-dual-source.js",
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-02e-engine-scenario-order-fix.js",
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-02f-engine-scenario-analysis-mode.js",
)
DUAL_TEST = pathlib.Path(__file__).with_name("test-nearby-hot-object-dual-source.mjs")
PRESET_ORDER_TEST = pathlib.Path(__file__).with_name("test-engine-scenario-preset-order.mjs")
ANALYSIS_MODE_TEST = pathlib.Path(__file__).with_name("test-engine-scenario-analysis-mode.mjs")
for path in (
    V15,
    *DUAL_TRANSFORMS,
    *DUAL_RUNTIME_PARTS,
    DUAL_TEST,
    PRESET_ORDER_TEST,
    ANALYSIS_MODE_TEST,
):
    if not path.is_file():
        raise RuntimeError(f"Dual-source filaSim component is missing: {path}")

spec = importlib.util.spec_from_file_location("enderslicer_annealing_v15", V15)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Unable to load {V15}")
v15 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v15)
thermal = v15.thermal

for transform in DUAL_TRANSFORMS:
    if transform not in thermal.THERMAL_TRANSFORMS:
        thermal.THERMAL_TRANSFORMS = (*thermal.THERMAL_TRANSFORMS, transform)
for marker in (
    ".enderslicer-nearby-hot-object-dual-source-core-v1",
    ".enderslicer-nearby-hot-object-dual-source-api-v1",
):
    if marker not in thermal.THERMAL_MARKERS:
        thermal.THERMAL_MARKERS = (*thermal.THERMAL_MARKERS, marker)

_base_ui = thermal.patch_thermal_ui_runtime


def patch_dual_source_runtime(target: pathlib.Path) -> None:
    _base_ui(target)
    subprocess.run(["node", str(DUAL_TEST), str(DUAL_RUNTIME_PARTS[0])], check=True)
    subprocess.run(["node", str(PRESET_ORDER_TEST), str(DUAL_RUNTIME_PARTS[1])], check=True)
    subprocess.run(["node", str(ANALYSIS_MODE_TEST), str(DUAL_RUNTIME_PARTS[2])], check=True)
    text = target.read_text(encoding="utf-8")
    runtime = "".join(path.read_text(encoding="utf-8") for path in DUAL_RUNTIME_PARTS)
    anchor = "  // Preserve installUi callback name for MutationObserver guard."
    if runtime not in text:
        if text.count(anchor) != 1:
            raise RuntimeError("Expected one observer-safe installer anchor in Nearby Hot Object runtime")
        text = text.replace(anchor, runtime + anchor, 1)
        target.write_text(text, encoding="utf-8")
    subprocess.run(["node", "--check", str(target)], check=True)
    text = target.read_text(encoding="utf-8")
    for contract in (
        "Petrol engine — normal driving",
        "Petrol turbo — high load",
        "Post-shutdown turbo heat soak",
        "source2Enabled",
        "source2TargetMm",
        "source2AbsorbedW",
        "two-source-piecewise-temperature-stage-coupling-v1",
        "Apply broad environment/source defaults first",
        "ENGINE_SCENARIO_ANALYSIS",
        "installUi = function installUi()",
    ):
        if contract not in text:
            raise RuntimeError(f"Dual-source runtime is missing {contract!r}")


thermal.patch_thermal_ui_runtime = patch_dual_source_runtime
thermal.THERMAL_PACKAGE_MARKER_TEXT = v15.thermal.THERMAL_PACKAGE_MARKER_TEXT.replace(
    "nearby-hot-object-installer-observer-contract-v1\n",
    "nearby-hot-object-installer-observer-contract-v1,"
    "nearby-hot-object-dual-source-core-v1,nearby-hot-object-dual-source-api-v1,"
    "nearby-hot-object-engine-presets-v1,engine-scenario-preset-order-v1,"
    "engine-scenario-analysis-mode-v1\n",
)

if __name__ == "__main__":
    try:
        raise SystemExit(thermal.BASE.main())
    except Exception as error:
        print(f"dual-source engine-preset filaSim v16 asset preparation failed: {error}", file=sys.stderr)
        raise
