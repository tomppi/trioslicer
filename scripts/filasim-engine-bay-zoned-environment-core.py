#!/usr/bin/env python3
"""Add a 12-zone spatial engine-bay boundary field to filaSim thermal solves."""
from __future__ import annotations

import pathlib

MARKER = "EnderSlicer 12-zone engine-bay thermal core v1"
SOURCE_MARKER = ".enderslicer-engine-bay-zoned-environment-core-v1"


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
    thermal = source_root / "crates/filasim-core/src/thermal.rs"
    if not thermal.is_file():
        raise RuntimeError(f"Zoned environment thermal target is missing: {thermal}")

    replace_once(
        thermal,
        """    pub emissivity: f64,
    pub heated_face: ThermalFace,
""",
        """    pub emissivity: f64,
    /// Optional 3 x 2 x 2 local engine-bay environment. Zone order is
    /// longitudinal X (front/middle/rear) fastest, then lateral Y, then Z.
    pub spatial_environment_enabled: bool,
    pub environment_size_mm: [f64; 3],
    pub environment_offset_mm: [f64; 3],
    pub environment_air_temperatures_c: [f64; 12],
    pub environment_wall_temperatures_c: [f64; 12],
    pub environment_convection_w_m2_k: [f64; 12],
    pub environment_wall_emissivities: [f64; 12],
    pub heated_face: ThermalFace,
""",
        "ThermalOptions zoned fields",
    )
    replace_once(
        thermal,
        """    conductivity_w_mm_k: f64,
    /// True only on the selected GLOBAL extreme face. A downward-facing step
""",
        """    conductivity_w_mm_k: f64,
    environment_zone: usize,
    /// True only on the selected GLOBAL extreme face. A downward-facing step
""",
        "BoundaryFace environment zone",
    )
    replace_once(
        thermal,
        """    pub total_exterior_area_mm2: f64,
    pub primary_initial_absorbed_w: f64,
""",
        """    pub total_exterior_area_mm2: f64,
    pub spatial_zone_exterior_area_mm2: [f64; 12],
    pub primary_initial_absorbed_w: f64,
""",
        "preflight zoned exterior areas",
    )
    replace_once(
        thermal,
        """                        conductivity_w_mm_k: conductivity[ci][axis],
                        heated,
                        cooled,
""",
        """                        conductivity_w_mm_k: conductivity[ci][axis],
                        environment_zone: spatial_environment_zone(
                            grid,
                            options,
                            [cx, cy, cz],
                            [min_x, min_y, min_z],
                            [max_x, max_y, max_z],
                        ),
                        heated,
                        cooled,
""",
        "boundary environment-zone classification",
    )
    replace_once(
        thermal,
        """        total_exterior_area_mm2: system.boundaries.iter()
            .filter(|boundary| !boundary.cooled)
            .map(|boundary| boundary.area_mm2)
            .sum(),
        primary_initial_absorbed_w: primary_absorbed,
""",
        """        total_exterior_area_mm2: system.boundaries.iter()
            .filter(|boundary| !boundary.cooled)
            .map(|boundary| boundary.area_mm2)
            .sum(),
        spatial_zone_exterior_area_mm2: spatial_zone_exterior_areas(&system),
        primary_initial_absorbed_w: primary_absorbed,
""",
        "preflight zoned exterior-area result",
    )

    validation = r'''    if options.spatial_environment_enabled {
        for (label, value) in [
            ("environment width", options.environment_size_mm[0]),
            ("environment depth", options.environment_size_mm[1]),
            ("environment height", options.environment_size_mm[2]),
            ("environment offset X", options.environment_offset_mm[0]),
            ("environment offset Y", options.environment_offset_mm[1]),
            ("environment offset Z", options.environment_offset_mm[2]),
        ] {
            if !value.is_finite() {
                return Err(format!("{label} is not finite"));
            }
        }
        if options.environment_size_mm.iter().any(|value| *value <= 0.1 || *value > 100_000.0) {
            return Err("spatial environment dimensions must be within 0.1..100000 mm".into());
        }
        for value in options.environment_air_temperatures_c
            .iter()
            .chain(options.environment_wall_temperatures_c.iter())
        {
            if !value.is_finite() || *value <= MIN_ABSOLUTE_TEMPERATURE_C || *value > 1_500.0 {
                return Err("spatial environment temperature is outside the supported range".into());
            }
        }
        if options.environment_convection_w_m2_k
            .iter()
            .any(|value| !value.is_finite() || *value < 0.0 || *value > 100_000.0)
        {
            return Err("spatial environment convection must be within 0..100000 W/(m²·K)".into());
        }
        if options.environment_wall_emissivities
            .iter()
            .any(|value| !value.is_finite() || *value < 0.0 || *value > 1.0)
        {
            return Err("spatial environment wall emissivity must be within 0..1".into());
        }
    }
'''
    replace_once(
        thermal,
        """    if !options.heat_power_w.is_finite() || !(0.0..=100_000.0).contains(&options.heat_power_w) {
""",
        validation + """    if !options.heat_power_w.is_finite() || !(0.0..=100_000.0).contains(&options.heat_power_w) {
""",
        "zoned environment validation",
    )

    helpers = r'''
fn spatial_environment_zone(
    grid: &VoxelGrid,
    options: &ThermalOptions,
    cell_xyz: [usize; 3],
    active_min: [usize; 3],
    active_max: [usize; 3],
) -> usize {
    if !options.spatial_environment_enabled {
        return 0;
    }
    let part_center = [
        grid.origin[0] + (active_min[0] as f64 + active_max[0] as f64 + 1.0) * grid.h * 0.5,
        grid.origin[1] + (active_min[1] as f64 + active_max[1] as f64 + 1.0) * grid.h * 0.5,
        grid.origin[2] + (active_min[2] as f64 + active_max[2] as f64 + 1.0) * grid.h * 0.5,
    ];
    let center = [
        grid.origin[0] + (cell_xyz[0] as f64 + 0.5) * grid.h,
        grid.origin[1] + (cell_xyz[1] as f64 + 0.5) * grid.h,
        grid.origin[2] + (cell_xyz[2] as f64 + 0.5) * grid.h,
    ];
    let mut normalized = [0.5; 3];
    for axis in 0..3 {
        let minimum = part_center[axis] + options.environment_offset_mm[axis]
            - options.environment_size_mm[axis] * 0.5;
        normalized[axis] = ((center[axis] - minimum) / options.environment_size_mm[axis])
            .clamp(0.0, 0.999_999_999);
    }
    let longitudinal = (normalized[0] * 3.0).floor() as usize;
    let lateral = usize::from(normalized[1] >= 0.5);
    let vertical = usize::from(normalized[2] >= 0.5);
    longitudinal + 3 * lateral + 6 * vertical
}

fn boundary_environment(
    options: &ThermalOptions,
    boundary: &BoundaryFace,
) -> (f64, f64, f64, f64) {
    if !options.spatial_environment_enabled {
        return (
            options.ambient_temperature_c,
            options.ambient_temperature_c,
            options.convection_w_m2_k,
            options.emissivity,
        );
    }
    let zone = boundary.environment_zone.min(11);
    (
        options.environment_air_temperatures_c[zone],
        options.environment_wall_temperatures_c[zone],
        options.environment_convection_w_m2_k[zone],
        effective_emissivity(
            options.emissivity,
            options.environment_wall_emissivities[zone],
        ),
    )
}

fn spatial_zone_exterior_areas(system: &ThermalSystem) -> [f64; 12] {
    let mut areas = [0.0; 12];
    for boundary in &system.boundaries {
        if !boundary.cooled {
            areas[boundary.environment_zone.min(11)] += boundary.area_mm2;
        }
    }
    areas
}

'''
    replace_once(
        thermal,
        "fn solve_steady(\n",
        helpers + "fn solve_steady(\n",
        "zoned environment helpers",
    )

    replace_count(
        thermal,
        """            let t_surface_c = radiation_temperature[ci];
            let (radiation_slope_w_m2_k, radiation_rhs_w_m2) = radiation_tangent_w_m2(
                options.emissivity,
                t_surface_c,
                options.ambient_temperature_c,
            );
            let area_m2 = boundary.area_mm2 * 1e-6;
            let convection_g = options.convection_w_m2_k * area_m2;
            let radiation_g = radiation_slope_w_m2_k * area_m2;
            diagonal[ci] += convection_g + radiation_g;
            rhs[ci] += convection_g * options.ambient_temperature_c
                + radiation_rhs_w_m2 * area_m2;
""",
        """            let t_surface_c = radiation_temperature[ci];
            let (air_c, wall_c, convection_w_m2_k, wall_effective_emissivity) =
                boundary_environment(options, boundary);
            let (radiation_slope_w_m2_k, radiation_rhs_w_m2) = radiation_tangent_w_m2(
                wall_effective_emissivity,
                t_surface_c,
                wall_c,
            );
            let area_m2 = boundary.area_mm2 * 1e-6;
            let convection_g = convection_w_m2_k * area_m2;
            let radiation_g = radiation_slope_w_m2_k * area_m2;
            diagonal[ci] += convection_g + radiation_g;
            rhs[ci] += convection_g * air_c
                + radiation_rhs_w_m2 * area_m2;
""",
        1,
        "linear zoned boundary assembly",
    )
    replace_count(
        thermal,
        """            let area_m2 = boundary.area_mm2 * 1e-6;
            residual[ci] += options.convection_w_m2_k
                * area_m2
                * (surface_c - options.ambient_temperature_c);
            residual[ci] += radiation_flux_w_m2(
                options.emissivity,
                surface_c,
                options.ambient_temperature_c,
            ) * area_m2;
""",
        """            let area_m2 = boundary.area_mm2 * 1e-6;
            let (air_c, wall_c, convection_w_m2_k, wall_effective_emissivity) =
                boundary_environment(options, boundary);
            residual[ci] += convection_w_m2_k
                * area_m2
                * (surface_c - air_c);
            residual[ci] += radiation_flux_w_m2(
                wall_effective_emissivity,
                surface_c,
                wall_c,
            ) * area_m2;
""",
        1,
        "nonlinear zoned boundary residual",
    )
    replace_count(
        thermal,
        """            out += options.convection_w_m2_k
                * boundary.area_mm2
                * 1e-6
                * (t - options.ambient_temperature_c);
            let tk = (t + 273.15).max(1.0);
            let tak = (options.ambient_temperature_c + 273.15).max(1.0);
            out += options.emissivity
                * SIGMA_SB_W_M2_K4
                * boundary.area_mm2
                * 1e-6
                * (tk.powi(4) - tak.powi(4));
""",
        """            let (air_c, wall_c, convection_w_m2_k, wall_effective_emissivity) =
                boundary_environment(options, boundary);
            out += convection_w_m2_k
                * boundary.area_mm2
                * 1e-6
                * (t - air_c);
            let tk = (t + 273.15).max(1.0);
            let twk = (wall_c + 273.15).max(1.0);
            out += wall_effective_emissivity
                * SIGMA_SB_W_M2_K4
                * boundary.area_mm2
                * 1e-6
                * (tk.powi(4) - twk.powi(4));
""",
        1,
        "zoned boundary energy accounting",
    )

    contracts = (
        "spatial_environment_enabled: bool",
        "environment_air_temperatures_c: [f64; 12]",
        "environment_zone: usize",
        "spatial_environment_zone(",
        "boundary_environment(",
        "spatial_zone_exterior_area_mm2: [f64; 12]",
        "spatial_zone_exterior_areas(&system)",
    )
    text = thermal.read_text(encoding="utf-8")
    for contract in contracts:
        if contract not in text:
            raise RuntimeError(f"Zoned environment thermal contract {contract!r} is missing")
    (source_root / SOURCE_MARKER).write_text(MARKER + "\n", encoding="utf-8")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
