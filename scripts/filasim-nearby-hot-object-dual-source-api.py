#!/usr/bin/env python3
"""Expose the optional second radiant source through WASM and TypeScript."""
from __future__ import annotations

import pathlib

MARKER = "EnderSlicer nearby hot object dual-source API v1"
SOURCE_MARKER = ".enderslicer-nearby-hot-object-dual-source-api-v1"


def replace_once(path: pathlib.Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one {label} in {path}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_count(path: pathlib.Path, old: str, new: str, expected: int, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text and old not in text:
        return
    count = text.count(old)
    if count != expected:
        raise RuntimeError(f"Expected {expected} {label} blocks in {path}, found {count}")
    path.write_text(text.replace(old, new), encoding="utf-8")


def apply(source_root: pathlib.Path) -> None:
    source_root = source_root.resolve()
    wasm = source_root / "crates/filasim-wasm/src/lib.rs"
    client = source_root / "web/src/engine/EngineClient.ts"
    for path in (wasm, client):
        if not path.is_file():
            raise RuntimeError(f"Dual-source API target is missing: {path}")

    replace_once(
        wasm,
        """    source_part_emissivity: f64,
    use_fixed_temperature_surface: bool,
""",
        """    source_part_emissivity: f64,
    source2_enabled: bool,
    source2_target_mm: [f64; 3],
    source2_normal: [f64; 3],
    source2_gap_mm: f64,
    source2_diameter_mm: f64,
    source2_temperature_c: f64,
    source2_emissivity: f64,
    source2_part_emissivity: f64,
    use_fixed_temperature_surface: bool,
""",
        "WASM dual-source option fields",
    )
    replace_once(
        wasm,
        """            source_part_emissivity: 0.9,
            use_fixed_temperature_surface: false,
""",
        """            source_part_emissivity: 0.9,
            source2_enabled: false,
            source2_target_mm: [0.0, 0.0, 0.0],
            source2_normal: [0.0, 0.0, 1.0],
            source2_gap_mm: 40.0,
            source2_diameter_mm: 80.0,
            source2_temperature_c: 600.0,
            source2_emissivity: 0.85,
            source2_part_emissivity: 0.9,
            use_fixed_temperature_surface: false,
""",
        "WASM dual-source defaults",
    )
    replace_count(
        wasm,
        """            source_part_emissivity: opts.source_part_emissivity,
            use_fixed_temperature_surface: opts.use_fixed_temperature_surface,
""",
        """            source_part_emissivity: opts.source_part_emissivity,
            secondary_enabled: opts.source2_enabled,
            secondary_target_mm: opts.source2_target_mm,
            secondary_outward_normal: opts.source2_normal,
            secondary_gap_mm: opts.source2_gap_mm,
            secondary_diameter_mm: opts.source2_diameter_mm,
            secondary_temperature_c: opts.source2_temperature_c,
            secondary_emissivity: opts.source2_emissivity,
            secondary_part_emissivity: opts.source2_part_emissivity,
            use_fixed_temperature_surface: opts.use_fixed_temperature_surface,
""",
        2,
        "dual-source constructors",
    )
    replace_once(
        wasm,
        """            "sourceInitialAbsorbedW": summary.initial_absorbed_w,
            "sourceViewFactorAreaMm2": summary.view_factor_area_mm2,
            "totalExteriorAreaMm2": summary.total_exterior_area_mm2,
            "sourceCenterMm": summary.source_center_mm,
""",
        """            "sourceInitialAbsorbedW": summary.initial_absorbed_w,
            "source1InitialAbsorbedW": summary.primary_initial_absorbed_w,
            "source2InitialAbsorbedW": summary.secondary_initial_absorbed_w,
            "sourceViewFactorAreaMm2": summary.view_factor_area_mm2,
            "source2VisibleAreaMm2": summary.secondary_visible_area_mm2,
            "source2ViewFactorAreaMm2": summary.secondary_view_factor_area_mm2,
            "totalExteriorAreaMm2": summary.total_exterior_area_mm2,
            "sourceCenterMm": summary.source_center_mm,
            "source2CenterMm": summary.secondary_center_mm,
""",
        "dual-source preflight JSON",
    )
    replace_count(
        wasm,
        """                "sourceAbsorbedW": thermal.source_absorbed_w,
                "sourceViewFactorAreaMm2": thermal.source_view_factor_area_mm2,
                "sourceCenterMm": thermal.source_center_mm,
""",
        """                "sourceAbsorbedW": thermal.source_absorbed_w,
                "source1AbsorbedW": thermal.source1_absorbed_w,
                "source2AbsorbedW": thermal.source2_absorbed_w,
                "sourceViewFactorAreaMm2": thermal.source_view_factor_area_mm2,
                "sourceCenterMm": thermal.source_center_mm,
                "source2ViewFactorAreaMm2": thermal.source2_view_factor_area_mm2,
                "source2CenterMm": thermal.source2_center_mm,
""",
        1,
        "invalid-result dual-source stats",
    )
    replace_count(
        wasm,
        """            "sourceAbsorbedW": thermal.source_absorbed_w,
            "sourceViewFactorAreaMm2": thermal.source_view_factor_area_mm2,
            "sourceCenterMm": thermal.source_center_mm,
""",
        """            "sourceAbsorbedW": thermal.source_absorbed_w,
            "source1AbsorbedW": thermal.source1_absorbed_w,
            "source2AbsorbedW": thermal.source2_absorbed_w,
            "sourceViewFactorAreaMm2": thermal.source_view_factor_area_mm2,
            "sourceCenterMm": thermal.source_center_mm,
            "source2ViewFactorAreaMm2": thermal.source2_view_factor_area_mm2,
            "source2CenterMm": thermal.source2_center_mm,
""",
        1,
        "valid-result dual-source stats",
    )

    replace_once(
        client,
        """  sourcePartEmissivity: number;
  useFixedTemperatureSurface: boolean;
""",
        """  sourcePartEmissivity: number;
  source2Enabled: boolean;
  source2TargetMm: [number, number, number];
  source2Normal: [number, number, number];
  source2GapMm: number;
  source2DiameterMm: number;
  source2TemperatureC: number;
  source2Emissivity: number;
  source2PartEmissivity: number;
  useFixedTemperatureSurface: boolean;
""",
        "TypeScript dual-source options",
    )
    replace_once(
        client,
        """  sourceInitialAbsorbedW: number;
  sourceViewFactorAreaMm2: number;
  totalExteriorAreaMm2: number;
  sourceCenterMm: [number, number, number];
""",
        """  sourceInitialAbsorbedW: number;
  source1InitialAbsorbedW: number;
  source2InitialAbsorbedW: number;
  sourceViewFactorAreaMm2: number;
  source2VisibleAreaMm2: number;
  source2ViewFactorAreaMm2: number;
  totalExteriorAreaMm2: number;
  sourceCenterMm: [number, number, number];
  source2CenterMm: [number, number, number];
""",
        "TypeScript dual-source preflight",
    )
    replace_once(
        client,
        """  sourceAbsorbedW: number;
  sourceViewFactorAreaMm2: number;
  sourceCenterMm: [number, number, number];
""",
        """  sourceAbsorbedW: number;
  source1AbsorbedW: number;
  source2AbsorbedW: number;
  sourceViewFactorAreaMm2: number;
  sourceCenterMm: [number, number, number];
  source2ViewFactorAreaMm2: number;
  source2CenterMm: [number, number, number];
""",
        "TypeScript dual-source result stats",
    )

    for path, contract in (
        (wasm, "source2_enabled: bool"),
        (wasm, '"source2InitialAbsorbedW"'),
        (wasm, '"source2AbsorbedW"'),
        (client, "source2Enabled: boolean"),
        (client, "source2CenterMm: [number, number, number]"),
    ):
        if contract not in path.read_text(encoding="utf-8"):
            raise RuntimeError(f"Dual-source API contract {contract!r} is missing from {path}")
    (source_root / SOURCE_MARKER).write_text(MARKER + "\n", encoding="utf-8")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
