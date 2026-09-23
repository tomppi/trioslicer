#!/usr/bin/env python3
"""Expose the nearby hot object thermal model through filaSim WASM and TypeScript."""
from __future__ import annotations

import pathlib
import re

MARKER = "EnderSlicer nearby hot object API v1"


def replace_once(path: pathlib.Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one {label} in {path}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_regex_once(
    path: pathlib.Path,
    pattern: str,
    replacement: str,
    label: str,
) -> None:
    text = path.read_text(encoding="utf-8")
    if replacement in text:
        return
    updated, count = re.subn(pattern, replacement, text, count=1, flags=re.MULTILINE | re.DOTALL)
    if count != 1:
        raise RuntimeError(f"Expected one {label} in {path}, found {count}")
    path.write_text(updated, encoding="utf-8")


def apply(source_root: pathlib.Path) -> None:
    source_root = source_root.resolve()
    wasm = source_root / "crates/filasim-wasm/src/lib.rs"
    client = source_root / "web/src/engine/EngineClient.ts"
    for path in (wasm, client):
        if not path.is_file():
            raise RuntimeError(f"Nearby-hot-object API target is missing: {path}")

    replace_once(
        wasm,
        """    heated_face: String,
    cooled_face: String,
    heat_power_w: f64,
""",
        """    heated_face: String,
    cooled_face: String,
    source_target_mm: [f64; 3],
    source_normal: [f64; 3],
    source_gap_mm: f64,
    source_diameter_mm: f64,
    source_temperature_c: f64,
    source_emissivity: f64,
    use_fixed_temperature_surface: bool,
    heat_power_w: f64,
""",
        "WASM nearby-source option fields",
    )
    replace_once(
        wasm,
        """            heated_face: "zmax".into(),
            cooled_face: "zmin".into(),
            heat_power_w: 5.0,
""",
        """            heated_face: "zmax".into(),
            cooled_face: "zmin".into(),
            source_target_mm: [0.0, 0.0, 0.0],
            source_normal: [0.0, 0.0, 1.0],
            source_gap_mm: 25.0,
            source_diameter_mm: 50.0,
            source_temperature_c: 300.0,
            source_emissivity: 0.9,
            use_fixed_temperature_surface: false,
            heat_power_w: 0.0,
""",
        "WASM nearby-source defaults",
    )

    nearby_ctor = """        let nearby_source = filasim_core::thermal::NearbyHotObjectOptions {
            target_mm: opts.source_target_mm,
            outward_normal: opts.source_normal,
            gap_mm: opts.source_gap_mm,
            diameter_mm: opts.source_diameter_mm,
            source_temperature_c: opts.source_temperature_c,
            source_emissivity: opts.source_emissivity,
            use_fixed_temperature_surface: opts.use_fixed_temperature_surface,
        };
"""

    # Keep this replacement exact and bounded. In filaSim the preflight call
    # terminates with `).map_err(err)?;` on the same line. The earlier DOTALL
    # regex expected `.map_err` on a new line and therefore continued scanning
    # into the later solve call, deleting the intervening preflight JSON.
    replace_once(
        wasm,
        """        let summary = filasim_core::thermal::thermal_boundary_summary(
            &grid, &material_fraction, &thermal_options,
        ).map_err(err)?;
""",
        nearby_ctor + """        let summary = filasim_core::thermal::nearby_hot_object_preflight(
            &grid,
            &material_fraction,
            &thermal_options,
            &nearby_source,
        )
        .map_err(err)?;
""",
        "nearby-source preflight call",
    )
    replace_once(
        wasm,
        """            "heatedAreaMm2": summary.heated_area_mm2,
            "cooledAreaMm2": summary.cooled_area_mm2,
            "effectiveHeatFluxWm2": summary.effective_heat_flux_w_m2,
            "heatPowerW": opts.heat_power_w,
            "heaterBoundaryModel": "contact-all-exterior-faces-with-selected-normal",
            "sinkBoundaryModel": "fixed-temperature-global-extreme-plane",
""",
        """            "heatedAreaMm2": summary.visible_area_mm2,
            "cooledAreaMm2": if opts.use_fixed_temperature_surface { 1.0 } else { 0.0 },
            "effectiveHeatFluxWm2": summary.effective_heat_flux_w_m2,
            "heatPowerW": summary.initial_absorbed_w,
            "sourceInitialAbsorbedW": summary.initial_absorbed_w,
            "sourceViewFactorAreaMm2": summary.view_factor_area_mm2,
            "sourceCenterMm": summary.source_center_mm,
            "heaterBoundaryModel": "nearby-diffuse-hot-sphere-view-factor",
            "sinkBoundaryModel": if opts.use_fixed_temperature_surface {
                "fixed-temperature-global-extreme-plane"
            } else { "ambient-only" },
""",
        "nearby-source preflight JSON",
    )
    replace_regex_once(
        wasm,
        r"^        let thermal =\s*filasim_core::thermal::solve_thermal\(.*?^\s*\.map_err\(err\)\?;\n",
        nearby_ctor + """        let thermal =
            filasim_core::thermal::solve_nearby_hot_object(
                &grid,
                &material_fraction,
                &thermal_options,
                &nearby_source,
            )
            .map_err(err)?;
""",
        "nearby-source solve call",
    )

    text = wasm.read_text(encoding="utf-8")
    text = text.replace(
        "opts.heat_power_w / (thermal.exposed_heated_area_mm2 * 1e-6)",
        "thermal.source_absorbed_w / (thermal.exposed_heated_area_mm2 * 1e-6)",
    )
    text = text.replace(
        '"contact-all-exterior-faces-with-selected-normal"',
        '"nearby-diffuse-hot-sphere-view-factor"',
    )
    diagnostic_anchor = '"effectiveHeatFluxWm2": effective_flux,\n'
    diagnostic_new = diagnostic_anchor + '''                "sourceAbsorbedW": thermal.source_absorbed_w,
                "sourceViewFactorAreaMm2": thermal.source_view_factor_area_mm2,
                "sourceCenterMm": thermal.source_center_mm,
'''
    if diagnostic_new not in text:
        if text.count(diagnostic_anchor) != 1:
            raise RuntimeError("Expected one invalid-result effective-flux anchor")
        text = text.replace(diagnostic_anchor, diagnostic_new, 1)
    valid_anchor = '''            "effectiveHeatFluxWm2": if thermal.exposed_heated_area_mm2 > 0.0 {
                thermal.source_absorbed_w / (thermal.exposed_heated_area_mm2 * 1e-6)
            } else { 0.0 },
'''
    valid_new = valid_anchor + '''            "sourceAbsorbedW": thermal.source_absorbed_w,
            "sourceViewFactorAreaMm2": thermal.source_view_factor_area_mm2,
            "sourceCenterMm": thermal.source_center_mm,
'''
    if valid_new not in text:
        if text.count(valid_anchor) != 1:
            raise RuntimeError("Expected one valid-result effective-flux anchor")
        text = text.replace(valid_anchor, valid_new, 1)
    wasm.write_text(text, encoding="utf-8")

    replace_once(
        client,
        """  heatedFace: "xmin" | "xmax" | "ymin" | "ymax" | "zmin" | "zmax";
  cooledFace: "xmin" | "xmax" | "ymin" | "ymax" | "zmin" | "zmax";
  heatPowerW: number;
""",
        """  heatedFace: "xmin" | "xmax" | "ymin" | "ymax" | "zmin" | "zmax";
  cooledFace: "xmin" | "xmax" | "ymin" | "ymax" | "zmin" | "zmax";
  sourceTargetMm: [number, number, number];
  sourceNormal: [number, number, number];
  sourceGapMm: number;
  sourceDiameterMm: number;
  sourceTemperatureC: number;
  sourceEmissivity: number;
  useFixedTemperatureSurface: boolean;
  heatPowerW: number;
""",
        "EngineClient nearby-source options",
    )
    text = client.read_text(encoding="utf-8")
    text = text.replace(
        'heaterBoundaryModel: "contact-all-exterior-faces-with-selected-normal";',
        'heaterBoundaryModel: "nearby-diffuse-hot-sphere-view-factor";',
    )
    if "sourceInitialAbsorbedW: number;" not in text:
        text = text.replace(
            "  heatPowerW: number;\n",
            "  heatPowerW: number;\n  sourceInitialAbsorbedW: number;\n  sourceViewFactorAreaMm2: number;\n  sourceCenterMm: [number, number, number];\n",
            1,
        )
    stats_anchor = "  effectiveHeatFluxWm2: number;\n  heaterBoundaryModel:"
    if "  sourceAbsorbedW: number;\n  sourceViewFactorAreaMm2: number;\n  sourceCenterMm:" not in text:
        text = text.replace(
            stats_anchor,
            "  effectiveHeatFluxWm2: number;\n  sourceAbsorbedW: number;\n  sourceViewFactorAreaMm2: number;\n  sourceCenterMm: [number, number, number];\n  heaterBoundaryModel:",
            1,
        )
    client.write_text(text, encoding="utf-8")

    marker = source_root / ".enderslicer-nearby-hot-object-api-v1"
    marker.write_text(MARKER + "\n", encoding="utf-8")
    required_contracts = (
        (wasm, "source_target_mm"),
        (wasm, "pub fn thermal_integrity_preflight"),
        (wasm, "nearby_hot_object_preflight"),
        (wasm, '"sourceInitialAbsorbedW"'),
        (wasm, "solve_nearby_hot_object"),
        (client, "sourceTargetMm"),
        (client, "sourceInitialAbsorbedW"),
    )
    for path, contract in required_contracts:
        if contract not in path.read_text(encoding="utf-8"):
            raise RuntimeError(f"Nearby-hot-object API contract {contract!r} missing from {path}")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
