#!/usr/bin/env python3
"""Extend the validated format-8 Thermal Integrity preparer with bugfix round 2."""

from __future__ import annotations

import importlib.util
import pathlib
import subprocess
import sys


BASE_PREPARER = pathlib.Path(__file__).with_name(
    "prepare-filasim-assets-with-thermal-integrity-v8.py"
)
ROUND2 = pathlib.Path(__file__).with_name("filasim-thermal-integrity-bugfix-round2.py")

if not BASE_PREPARER.is_file() or not ROUND2.is_file():
    raise RuntimeError("Thermal Integrity v9 preparation components are missing")

spec = importlib.util.spec_from_file_location("enderslicer_thermal_v8", BASE_PREPARER)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Unable to load Thermal Integrity preparer: {BASE_PREPARER}")
thermal = importlib.util.module_from_spec(spec)
spec.loader.exec_module(thermal)

round2_marker = ".enderslicer-thermal-integrity-bugfix-round2"
if ROUND2 not in thermal.THERMAL_TRANSFORMS:
    thermal.THERMAL_TRANSFORMS = (*thermal.THERMAL_TRANSFORMS, ROUND2)
if round2_marker not in thermal.THERMAL_MARKERS:
    thermal.THERMAL_MARKERS = (*thermal.THERMAL_MARKERS, round2_marker)


def apply_thermal_transforms_v9(source_root: pathlib.Path) -> None:
    source_root = source_root.resolve()
    marker_paths = tuple(source_root / name for name in thermal.THERMAL_MARKERS)
    marker_state = tuple(path.is_file() for path in marker_paths)
    first_missing = next(
        (index for index, present in enumerate(marker_state) if not present),
        len(marker_state),
    )
    if any(marker_state[first_missing:]):
        missing = [
            path.name
            for path, present in zip(marker_paths, marker_state)
            if not present
        ]
        raise RuntimeError(
            "Thermal-integrity source is only partially transformed; missing markers: "
            + ", ".join(missing)
        )

    for transform in thermal.THERMAL_TRANSFORMS[first_missing:]:
        if not transform.is_file():
            raise RuntimeError(f"Thermal-integrity transform is missing: {transform}")
        subprocess.run(
            [sys.executable, str(transform), str(source_root)],
            cwd=thermal.PROJECT_ROOT,
            check=True,
        )

    missing = [path.name for path in marker_paths if not path.is_file()]
    if missing:
        raise RuntimeError(
            "Thermal-integrity source markers are missing after transformation: "
            + ", ".join(missing)
        )

    core = source_root / "crates/filasim-core/src/thermal.rs"
    wasm = source_root / "crates/filasim-wasm/src/lib.rs"
    worker = source_root / "web/src/worker/engine.worker.ts"
    protocol = source_root / "web/src/engine/EngineProtocol.ts"
    rail = source_root / "web/src/ui/StepRail.tsx"
    panel = source_root / "web/src/ui/StepPanel.tsx"
    topbar = source_root / "web/src/ui/TopBar.tsx"
    required_contracts = (
        (core, "solve_thermal"),
        (core, "progress::publish"),
        (core, "MAX_SUPPORTED_TEMPERATURE_C"),
        (core, "radiation_tangent_w_m2"),
        (core, "solve_nonlinear_temperature"),
        (core, "MIN_LINE_SEARCH_ALPHA"),
        (core, "steady thermal radiation iteration"),
        (core, "did not converge within"),
        (core, "steady thermal solve violated global energy balance"),
        (core, "benchy_like_five_watt_radiative_case_converges_and_balances"),
        (core, "transient_radiation_uses_the_same_stable_nonlinear_path"),
        (wasm, "solve_thermal_integrity"),
        (wasm, "Preparing voxel model"),
        (wasm, "MAX_MOBILE_GRID_CELLS"),
        (wasm, "service limit must exceed its reference temperature"),
        (worker, "thermalIntegrity"),
        (worker, "progress: true"),
        (protocol, "thermalIntegrity"),
        (rail, "enderslicer-thermal-workspace"),
        (rail, "if (!s.model && thermalActive)"),
        (panel, "enderslicer-thermal-integrity-mount"),
        (panel, "if (!s.model && thermalActive)"),
        (topbar, 'new CustomEvent<boolean>("enderslicer-thermal-workspace"'),
    )
    for path, marker in required_contracts:
        if not path.is_file() or marker not in path.read_text(encoding="utf-8"):
            raise RuntimeError(
                f"Thermal-integrity v9 contract {marker!r} is missing from {path}"
            )

    # Later feature transforms may intentionally replace the visible Thermal
    # Integrity station while retaining its event, mount and solver contracts.
    # Accept either the legacy label or the Nearby Hot Object replacement, but
    # require exactly one known workflow identity rather than dropping this
    # validation altogether.
    workflow_labels = (
        "Thermal Integrity — service-temperature",
        "Nearby Hot Object — radiative and ambient heating",
    )
    rail_text = rail.read_text(encoding="utf-8") if rail.is_file() else ""
    if not any(label in rail_text for label in workflow_labels):
        raise RuntimeError(
            "Thermal-integrity v9 workflow label is missing from "
            f"{rail}; expected one of {workflow_labels!r}"
        )


# The v8 Android export hook resolves this module global at call time.
thermal.apply_thermal_transforms = apply_thermal_transforms_v9

thermal.THERMAL_PACKAGE_MARKER_TEXT = (
    "format=1\n"
    f"filasim={thermal.BASE.FILASIM_COMMIT}\n"
    "transforms=solver,hardening,audit-fixes,progress-v2,react-tab-v1,bugfix-round1,bugfix-round2\n"
)

if __name__ == "__main__":
    try:
        raise SystemExit(thermal.BASE.main())
    except Exception as error:
        print(f"thermal filaSim v9 asset preparation failed: {error}", file=sys.stderr)
        raise
