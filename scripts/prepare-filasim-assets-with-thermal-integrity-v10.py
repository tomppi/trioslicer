#!/usr/bin/env python3
"""Final Thermal Integrity logic-audit preparer.

Composes the stable nonlinear radiation solver, exact linear fast path, and
Android UI labels that explicitly describe the global-extreme boundary planes.
"""

from __future__ import annotations

import importlib.util
import pathlib
import sys


V9_PREPARER = pathlib.Path(__file__).with_name(
    "prepare-filasim-assets-with-thermal-integrity-v9.py"
)
LINEAR_FAST_PATH = pathlib.Path(__file__).with_name(
    "filasim-thermal-integrity-linear-fast-path.py"
)

for required in (V9_PREPARER, LINEAR_FAST_PATH):
    if not required.is_file():
        raise RuntimeError(f"Thermal Integrity v10 component is missing: {required}")

spec = importlib.util.spec_from_file_location("enderslicer_thermal_v9", V9_PREPARER)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Unable to load Thermal Integrity v9 preparer: {V9_PREPARER}")
v9 = importlib.util.module_from_spec(spec)
spec.loader.exec_module(v9)

marker = ".enderslicer-thermal-integrity-linear-fast-path-v1"
if LINEAR_FAST_PATH not in v9.thermal.THERMAL_TRANSFORMS:
    v9.thermal.THERMAL_TRANSFORMS = (
        *v9.thermal.THERMAL_TRANSFORMS,
        LINEAR_FAST_PATH,
    )
if marker not in v9.thermal.THERMAL_MARKERS:
    v9.thermal.THERMAL_MARKERS = (*v9.thermal.THERMAL_MARKERS, marker)

_base_patch_thermal_ui_runtime = v9.thermal.patch_thermal_ui_runtime


def patch_thermal_ui_runtime_v10(target: pathlib.Path) -> None:
    _base_patch_thermal_ui_runtime(target)
    text = target.read_text(encoding="utf-8")
    replacements = (
        ('xmin: "X− face"', 'xmin: "Global X− plane"'),
        ('xmax: "X+ face"', 'xmax: "Global X+ plane"'),
        ('ymin: "Y− face"', 'ymin: "Global Y− plane"'),
        ('ymax: "Y+ face"', 'ymax: "Global Y+ plane"'),
        ('zmin: "Z− face"', 'zmin: "Global Z− plane"'),
        ('zmax: "Z+ face"', 'zmax: "Global Z+ plane"'),
        ('<span>Heated surface</span>', '<span>Heated global plane</span>'),
        (
            '<span>Fixed-temperature surface</span>',
            '<span>Fixed-temperature global plane</span>',
        ),
        (
            'The heated and fixed-temperature surfaces must be different.',
            'The heated and fixed-temperature global planes must be different.',
        ),
    )
    for old, new in replacements:
        if new in text:
            continue
        count = text.count(old)
        if count != 1:
            raise RuntimeError(
                f"Expected one Thermal boundary-label contract {old!r}, found {count}"
            )
        text = text.replace(old, new, 1)
    target.write_text(text, encoding="utf-8")

    verified = target.read_text(encoding="utf-8")
    for marker_text in (
        "Global X− plane",
        "Global Z+ plane",
        "Heated global plane",
        "Fixed-temperature global plane",
    ):
        if marker_text not in verified:
            raise RuntimeError(
                f"Thermal global-plane label is missing after packaging: {marker_text}"
            )


# v8's runtime injector resolves this module global at call time.
v9.thermal.patch_thermal_ui_runtime = patch_thermal_ui_runtime_v10

# This marker intentionally lists pinned-source transforms only. The UI label
# correction above is an Android packaging patch, not a filaSim source transform.
v9.thermal.THERMAL_PACKAGE_MARKER_TEXT = (
    "format=1\n"
    f"filasim={v9.thermal.BASE.FILASIM_COMMIT}\n"
    "transforms=solver,hardening,audit-fixes,progress-v2,react-tab-v1,bugfix-round1,bugfix-round2,linear-fast-path-v1\n"
)

if __name__ == "__main__":
    try:
        raise SystemExit(v9.thermal.BASE.main())
    except Exception as error:
        print(f"thermal filaSim v10 asset preparation failed: {error}", file=sys.stderr)
        raise
