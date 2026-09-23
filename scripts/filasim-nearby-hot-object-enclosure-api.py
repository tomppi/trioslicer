#!/usr/bin/env python3
"""Expose Nearby Hot Object enclosure metadata through WASM and TypeScript."""
from __future__ import annotations

import pathlib

MARKER = "EnderSlicer nearby hot object enclosure API v1"
SOURCE_MARKER = ".enderslicer-nearby-hot-object-enclosure-api-v1"


def replace_once(path: pathlib.Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one {label} in {path}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_exact_count(path: pathlib.Path, old: str, new: str, expected: int, label: str) -> None:
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
            raise RuntimeError(f"Nearby enclosure API target is missing: {path}")

    replace_once(
        wasm,
        """    source_temperature_c: f64,
    source_emissivity: f64,
    use_fixed_temperature_surface: bool,
""",
        """    source_temperature_c: f64,
    source_emissivity: f64,
    source_part_emissivity: f64,
    use_fixed_temperature_surface: bool,
""",
        "WASM source-side part emissivity option",
    )
    replace_once(
        wasm,
        """            source_temperature_c: 300.0,
            source_emissivity: 0.9,
            use_fixed_temperature_surface: false,
""",
        """            source_temperature_c: 300.0,
            source_emissivity: 0.9,
            source_part_emissivity: 0.9,
            use_fixed_temperature_surface: false,
""",
        "WASM source-side emissivity default",
    )
    replace_exact_count(
        wasm,
        """            source_temperature_c: opts.source_temperature_c,
            source_emissivity: opts.source_emissivity,
            use_fixed_temperature_surface: opts.use_fixed_temperature_surface,
""",
        """            source_temperature_c: opts.source_temperature_c,
            source_emissivity: opts.source_emissivity,
            source_part_emissivity: opts.source_part_emissivity,
            use_fixed_temperature_surface: opts.use_fixed_temperature_surface,
""",
        2,
        "nearby-source constructor",
    )
    replace_once(
        wasm,
        """            "sourceViewFactorAreaMm2": summary.view_factor_area_mm2,
            "sourceCenterMm": summary.source_center_mm,
""",
        """            "sourceViewFactorAreaMm2": summary.view_factor_area_mm2,
            "totalExteriorAreaMm2": summary.total_exterior_area_mm2,
            "sourceCenterMm": summary.source_center_mm,
""",
        "preflight exterior area JSON",
    )

    replace_once(
        client,
        """  sourceTemperatureC: number;
  sourceEmissivity: number;
  useFixedTemperatureSurface: boolean;
""",
        """  sourceTemperatureC: number;
  sourceEmissivity: number;
  /** Printed-surface emissivity used only for the selected hot object. */
  sourcePartEmissivity: number;
  useFixedTemperatureSurface: boolean;
""",
        "TypeScript source-side part emissivity",
    )
    replace_once(
        client,
        """  sourceInitialAbsorbedW: number;
  sourceViewFactorAreaMm2: number;
  sourceCenterMm: [number, number, number];
""",
        """  sourceInitialAbsorbedW: number;
  sourceViewFactorAreaMm2: number;
  totalExteriorAreaMm2: number;
  sourceCenterMm: [number, number, number];
""",
        "TypeScript preflight exterior area",
    )

    for path, contract in (
        (wasm, "source_part_emissivity: f64"),
        (wasm, '"totalExteriorAreaMm2"'),
        (client, "sourcePartEmissivity: number"),
        (client, "totalExteriorAreaMm2: number"),
    ):
        if contract not in path.read_text(encoding="utf-8"):
            raise RuntimeError(f"Nearby enclosure API contract {contract!r} is missing from {path}")
    (source_root / SOURCE_MARKER).write_text(MARKER + "\n", encoding="utf-8")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
