#!/usr/bin/env python3
"""Expose the 12-zone engine-bay environment through filaSim WASM/TypeScript."""
from __future__ import annotations

import pathlib

MARKER = "EnderSlicer 12-zone engine-bay thermal API v1"
SOURCE_MARKER = ".enderslicer-engine-bay-zoned-environment-api-v1"


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
            raise RuntimeError(f"Zoned environment API target is missing: {path}")

    replace_once(
        wasm,
        """    emissivity: f64,
    heated_face: String,
""",
        """    emissivity: f64,
    spatial_environment_enabled: bool,
    environment_width_mm: f64,
    environment_depth_mm: f64,
    environment_height_mm: f64,
    environment_offset_x_mm: f64,
    environment_offset_y_mm: f64,
    environment_offset_z_mm: f64,
    environment_air_temperatures_c: [f64; 12],
    environment_wall_temperatures_c: [f64; 12],
    environment_convection_w_m2_k: [f64; 12],
    environment_wall_emissivities: [f64; 12],
    heated_face: String,
""",
        "WASM zoned option fields",
    )
    replace_once(
        wasm,
        """            emissivity: 0.9,
            heated_face: "zmax".into(),
""",
        """            emissivity: 0.9,
            spatial_environment_enabled: false,
            environment_width_mm: 800.0,
            environment_depth_mm: 600.0,
            environment_height_mm: 500.0,
            environment_offset_x_mm: 0.0,
            environment_offset_y_mm: 0.0,
            environment_offset_z_mm: 0.0,
            environment_air_temperatures_c: [23.0; 12],
            environment_wall_temperatures_c: [23.0; 12],
            environment_convection_w_m2_k: [8.0; 12],
            environment_wall_emissivities: [0.85; 12],
            heated_face: "zmax".into(),
""",
        "WASM zoned defaults",
    )
    replace_count(
        wasm,
        """            emissivity: opts.emissivity,
            heated_face,
""",
        """            emissivity: opts.emissivity,
            spatial_environment_enabled: opts.spatial_environment_enabled,
            environment_size_mm: [
                opts.environment_width_mm,
                opts.environment_depth_mm,
                opts.environment_height_mm,
            ],
            environment_offset_mm: [
                opts.environment_offset_x_mm,
                opts.environment_offset_y_mm,
                opts.environment_offset_z_mm,
            ],
            environment_air_temperatures_c: opts.environment_air_temperatures_c,
            environment_wall_temperatures_c: opts.environment_wall_temperatures_c,
            environment_convection_w_m2_k: opts.environment_convection_w_m2_k,
            environment_wall_emissivities: opts.environment_wall_emissivities,
            heated_face,
""",
        2,
        "ThermalOptions zoned constructors",
    )
    replace_once(
        wasm,
        """            "totalExteriorAreaMm2": summary.total_exterior_area_mm2,
            "sourceCenterMm": summary.source_center_mm,
""",
        """            "totalExteriorAreaMm2": summary.total_exterior_area_mm2,
            "spatialZoneExteriorAreaMm2": summary.spatial_zone_exterior_area_mm2,
            "sourceCenterMm": summary.source_center_mm,
""",
        "zoned preflight JSON",
    )

    replace_once(
        client,
        """  emissivity: number;
  heatedFace: "xmin" | "xmax" | "ymin" | "ymax" | "zmin" | "zmax";
""",
        """  emissivity: number;
  spatialEnvironmentEnabled: boolean;
  environmentWidthMm: number;
  environmentDepthMm: number;
  environmentHeightMm: number;
  environmentOffsetXMm: number;
  environmentOffsetYMm: number;
  environmentOffsetZMm: number;
  environmentAirTemperaturesC: [number, number, number, number, number, number, number, number, number, number, number, number];
  environmentWallTemperaturesC: [number, number, number, number, number, number, number, number, number, number, number, number];
  environmentConvectionWm2K: [number, number, number, number, number, number, number, number, number, number, number, number];
  environmentWallEmissivities: [number, number, number, number, number, number, number, number, number, number, number, number];
  heatedFace: "xmin" | "xmax" | "ymin" | "ymax" | "zmin" | "zmax";
""",
        "TypeScript zoned options",
    )
    replace_once(
        client,
        """  totalExteriorAreaMm2: number;
  sourceCenterMm: [number, number, number];
""",
        """  totalExteriorAreaMm2: number;
  spatialZoneExteriorAreaMm2: [number, number, number, number, number, number, number, number, number, number, number, number];
  sourceCenterMm: [number, number, number];
""",
        "TypeScript zoned preflight",
    )

    for path, contract in (
        (wasm, "spatial_environment_enabled: bool"),
        (wasm, "environment_air_temperatures_c: [f64; 12]"),
        (wasm, '"spatialZoneExteriorAreaMm2"'),
        (client, "spatialEnvironmentEnabled: boolean"),
        (client, "spatialZoneExteriorAreaMm2:"),
    ):
        if contract not in path.read_text(encoding="utf-8"):
            raise RuntimeError(f"Zoned environment API contract {contract!r} is missing from {path}")
    (source_root / SOURCE_MARKER).write_text(MARKER + "\n", encoding="utf-8")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
