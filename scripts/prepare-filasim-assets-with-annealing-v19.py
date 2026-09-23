#!/usr/bin/env python3
"""Shaped engine bay plus 12-zone high-accuracy thermal environment."""
from __future__ import annotations

import importlib.util
import pathlib
import subprocess
import sys

V18 = pathlib.Path(__file__).with_name("prepare-filasim-assets-with-annealing-v18.py")
ZONED_TRANSFORMS = (
    pathlib.Path(__file__).with_name("filasim-engine-bay-zoned-environment-core.py"),
    pathlib.Path(__file__).with_name("filasim-engine-bay-zoned-environment-api.py"),
    pathlib.Path(__file__).with_name("filasim-engine-bay-zoned-environment-literals.py"),
)
ZONED_TEST = pathlib.Path(__file__).with_name("test-engine-bay-zoned-environment.mjs")
ZONED_MAPPING_TEST = pathlib.Path(__file__).with_name("test-engine-bay-zoned-mapping.mjs")
ZONED_RESULT_TEST = pathlib.Path(__file__).with_name("test-engine-bay-zoned-result-compat.mjs")
PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[1]
ZONED_RUNTIME_PARTS = (
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-02m-engine-bay-zoned-environment.js",
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-02n-engine-bay-zoned-mapping-fix.js",
    PROJECT_ROOT / "app/src/main/filasim/nearby-hot-object-02o-engine-bay-zoned-result-compat.js",
)
for path in (
    V18,
    *ZONED_TRANSFORMS,
    ZONED_TEST,
    ZONED_MAPPING_TEST,
    ZONED_RESULT_TEST,
    *ZONED_RUNTIME_PARTS,
):
    if not path.is_file():
        raise RuntimeError(f"Zoned engine-bay filaSim component is missing: {path}")

spec = importlib.util.spec_from_file_location("enderslicer_annealing_v18", V18)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Unable to load {V18}")
v18 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v18)
thermal = v18.thermal

for transform in ZONED_TRANSFORMS:
    if transform not in thermal.THERMAL_TRANSFORMS:
        thermal.THERMAL_TRANSFORMS = (*thermal.THERMAL_TRANSFORMS, transform)
for marker in (
    ".enderslicer-engine-bay-zoned-environment-core-v1",
    ".enderslicer-engine-bay-zoned-environment-api-v1",
    ".enderslicer-engine-bay-zoned-environment-literals-v1",
):
    if marker not in thermal.THERMAL_MARKERS:
        thermal.THERMAL_MARKERS = (*thermal.THERMAL_MARKERS, marker)

_base_ui = thermal.patch_thermal_ui_runtime


def patch_zoned_environment_runtime(target: pathlib.Path) -> None:
    _base_ui(target)
    subprocess.run(
        [
            "node",
            str(ZONED_TEST),
            str(ZONED_RUNTIME_PARTS[0]),
            str(ZONED_TRANSFORMS[0]),
            str(ZONED_TRANSFORMS[1]),
        ],
        check=True,
    )
    subprocess.run(
        ["node", str(ZONED_MAPPING_TEST), str(ZONED_RUNTIME_PARTS[1])],
        check=True,
    )
    subprocess.run(
        ["node", str(ZONED_RESULT_TEST), str(ZONED_RUNTIME_PARTS[2])],
        check=True,
    )
    text = target.read_text(encoding="utf-8")
    runtime = "".join(path.read_text(encoding="utf-8") for path in ZONED_RUNTIME_PARTS)
    anchor = "  // Preserve installUi callback name for MutationObserver guard."
    if runtime not in text:
        if text.count(anchor) != 1:
            raise RuntimeError("Expected one observer-safe installer anchor in Nearby Hot Object runtime")
        target.write_text(text.replace(anchor, runtime + anchor, 1), encoding="utf-8")
    subprocess.run(["node", "--check", str(target)], check=True)
    verified = target.read_text(encoding="utf-8")
    for contract in (
        "ENGINE_BAY_ZONE_COUNT = 12",
        "3x2x2-local-air-wall-network-v1",
        "ENGINE_BAY_CORRECTION_PASSES = 2",
        "High — 12 local zones, two correction passes",
        "runTransientZonedEngineBay",
        "runSteadyZonedEngineBay",
        "spatialEnvironmentEnabled",
        "EnderSlicerZonedEnvironmentTestApi",
        "partZoneMeanTemperaturesInEngineBayCoordinates",
        "EnderSlicerZonedMappingTestApi",
        "normalizeZonedEnvironmentResult",
        "nearby-hot-object-plus-12-zone-engine-bay-v1",
        "EnderSlicerZonedResultCompatTestApi",
        "installUi = function installUi()",
    ):
        if contract not in verified:
            raise RuntimeError(f"Zoned environment runtime is missing {contract!r}")


thermal.patch_thermal_ui_runtime = patch_zoned_environment_runtime
thermal.THERMAL_PACKAGE_MARKER_TEXT = v18.thermal.THERMAL_PACKAGE_MARKER_TEXT.replace(
    "engine-bay-shaped-envelope-runtime-v1\n",
    "engine-bay-shaped-envelope-runtime-v1,"
    "engine-bay-zoned-environment-core-v1,"
    "engine-bay-zoned-environment-api-v1,"
    "engine-bay-zoned-environment-literals-v1,"
    "engine-bay-zoned-environment-runtime-v1,"
    "engine-bay-zoned-coordinate-mapping-v1,"
    "engine-bay-zoned-result-compat-v1\n",
)

if __name__ == "__main__":
    try:
        raise SystemExit(thermal.BASE.main())
    except Exception as error:
        print(f"zoned engine-bay filaSim v19 asset preparation failed: {error}", file=sys.stderr)
        raise
