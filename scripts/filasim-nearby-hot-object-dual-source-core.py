#!/usr/bin/env python3
"""Add an optional independently positioned second radiant heat source."""
from __future__ import annotations

import pathlib

MARKER = "EnderSlicer nearby hot object dual-source thermal core v1"
SOURCE_MARKER = ".enderslicer-nearby-hot-object-dual-source-core-v1"


def replace_once(path: pathlib.Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one {label} in {path}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def apply(source_root: pathlib.Path) -> None:
    source_root = source_root.resolve()
    thermal = source_root / "crates/filasim-core/src/thermal.rs"
    if not thermal.is_file():
        raise RuntimeError(f"Dual-source thermal target is missing: {thermal}")

    replace_once(
        thermal,
        """    pub source_part_emissivity: f64,
    pub use_fixed_temperature_surface: bool,
""",
        """    pub source_part_emissivity: f64,
    pub secondary_enabled: bool,
    pub secondary_target_mm: [f64; 3],
    pub secondary_outward_normal: [f64; 3],
    pub secondary_gap_mm: f64,
    pub secondary_diameter_mm: f64,
    pub secondary_temperature_c: f64,
    pub secondary_emissivity: f64,
    pub secondary_part_emissivity: f64,
    pub use_fixed_temperature_surface: bool,
""",
        "dual-source options",
    )
    replace_once(
        thermal,
        """    pub total_exterior_area_mm2: f64,
}
""",
        """    pub total_exterior_area_mm2: f64,
    pub primary_initial_absorbed_w: f64,
    pub secondary_initial_absorbed_w: f64,
    pub secondary_visible_area_mm2: f64,
    pub secondary_view_factor_area_mm2: f64,
    pub secondary_center_mm: [f64; 3],
}
""",
        "dual-source preflight fields",
    )
    replace_once(
        thermal,
        """    pub source_absorbed_w: f64,
    pub source_view_factor_area_mm2: f64,
    pub source_center_mm: [f64; 3],
}
""",
        """    pub source_absorbed_w: f64,
    pub source1_absorbed_w: f64,
    pub source2_absorbed_w: f64,
    pub source_view_factor_area_mm2: f64,
    pub source_center_mm: [f64; 3],
    pub source2_view_factor_area_mm2: f64,
    pub source2_center_mm: [f64; 3],
}
""",
        "dual-source thermal result fields",
    )
    replace_once(
        thermal,
        """    source_view_factor_area_mm2: f64,
    source_center_mm: [f64; 3],
}
""",
        """    source_view_factor_area_mm2: f64,
    source_center_mm: [f64; 3],
    source2_view_factor: Vec<f64>,
    source2_temperature_c: f64,
    source2_effective_emissivity: f64,
    source2_view_factor_area_mm2: f64,
    source2_center_mm: [f64; 3],
}
""",
        "dual-source thermal system fields",
    )
    replace_once(
        thermal,
        """        source_view_factor_area_mm2: 0.0,
        source_center_mm: [0.0; 3],
    })
}
""",
        """        source_view_factor_area_mm2: 0.0,
        source_center_mm: [0.0; 3],
        source2_view_factor: Vec::new(),
        source2_temperature_c: options.ambient_temperature_c,
        source2_effective_emissivity: 0.0,
        source2_view_factor_area_mm2: 0.0,
        source2_center_mm: [0.0; 3],
    })
}
""",
        "dual-source thermal system defaults",
    )

    replace_once(
        thermal,
        """    let absorbed = source_absorbed_w(&system, &temperature);
    let flux = if system.heated_area_mm2 > 0.0 {
        absorbed / (system.heated_area_mm2 * 1e-6)
""",
        """    let primary_absorbed = primary_source_absorbed_w(&system, &temperature);
    let secondary_absorbed = secondary_source_absorbed_w(&system, &temperature);
    let absorbed = primary_absorbed + secondary_absorbed;
    let flux = if system.heated_area_mm2 > 0.0 {
        absorbed / (system.heated_area_mm2 * 1e-6)
""",
        "dual-source preflight absorption",
    )
    replace_once(
        thermal,
        """        total_exterior_area_mm2: system.boundaries.iter()
            .filter(|boundary| !boundary.cooled)
            .map(|boundary| boundary.area_mm2)
            .sum(),
    })
}
""",
        """        total_exterior_area_mm2: system.boundaries.iter()
            .filter(|boundary| !boundary.cooled)
            .map(|boundary| boundary.area_mm2)
            .sum(),
        primary_initial_absorbed_w: primary_absorbed,
        secondary_initial_absorbed_w: secondary_absorbed,
        secondary_visible_area_mm2: secondary_visible_area_mm2(&system),
        secondary_view_factor_area_mm2: system.source2_view_factor_area_mm2,
        secondary_center_mm: system.source2_center_mm,
    })
}
""",
        "dual-source preflight result",
    )

    replace_once(
        thermal,
        """    system.source_view_factor = vec![0.0; system.boundaries.len()];
    system.source_view_factor_area_mm2 = 0.0;
""",
        """    system.source_view_factor = vec![0.0; system.boundaries.len()];
    system.source_view_factor_area_mm2 = 0.0;
    system.source2_view_factor = vec![0.0; system.boundaries.len()];
    system.source2_temperature_c = source.secondary_temperature_c;
    system.source2_effective_emissivity = 0.0;
    system.source2_view_factor_area_mm2 = 0.0;
    system.source2_center_mm = [0.0; 3];
""",
        "dual-source system initialization",
    )
    replace_once(
        thermal,
        """    if system.source_view_factor_area_mm2 <= 0.0 {
        return Err(
            "the nearby hot object is not visible from any exterior model surface; select another point"
                .into(),
        );
    }
    Ok(system)
}

fn effective_emissivity""",
        """    if system.source_view_factor_area_mm2 <= 0.0 {
        return Err(
            "the nearby hot object is not visible from any exterior model surface; select another point"
                .into(),
        );
    }
    if source.secondary_enabled {
        configure_secondary_source(grid, &mut system, source)?;
    }
    Ok(system)
}

fn configure_secondary_source(
    grid: &VoxelGrid,
    system: &mut ThermalSystem,
    source: &NearbyHotObjectOptions,
) -> Result<(), String> {
    for (label, value) in [
        ("secondary target X", source.secondary_target_mm[0]),
        ("secondary target Y", source.secondary_target_mm[1]),
        ("secondary target Z", source.secondary_target_mm[2]),
        ("secondary normal X", source.secondary_outward_normal[0]),
        ("secondary normal Y", source.secondary_outward_normal[1]),
        ("secondary normal Z", source.secondary_outward_normal[2]),
        ("secondary gap", source.secondary_gap_mm),
        ("secondary diameter", source.secondary_diameter_mm),
        ("secondary temperature", source.secondary_temperature_c),
        ("secondary emissivity", source.secondary_emissivity),
        ("secondary source-side part emissivity", source.secondary_part_emissivity),
    ] {
        if !value.is_finite() {
            return Err(format!("secondary nearby hot object {label} is not finite"));
        }
    }
    if !(0.0..=100_000.0).contains(&source.secondary_gap_mm) {
        return Err("secondary nearby hot object gap must be within 0..100000 mm".into());
    }
    if !(0.1..=100_000.0).contains(&source.secondary_diameter_mm) {
        return Err("secondary nearby hot object diameter must be within 0.1..100000 mm".into());
    }
    if source.secondary_temperature_c <= MIN_ABSOLUTE_TEMPERATURE_C
        || source.secondary_temperature_c > MAX_SUPPORTED_TEMPERATURE_C
    {
        return Err("secondary nearby hot object temperature is outside the supported range".into());
    }
    if !(0.0..=1.0).contains(&source.secondary_emissivity)
        || !(0.0..=1.0).contains(&source.secondary_part_emissivity)
    {
        return Err("secondary nearby hot object emissivities must be within 0..1".into());
    }
    let normal_length = (
        source.secondary_outward_normal[0].powi(2)
            + source.secondary_outward_normal[1].powi(2)
            + source.secondary_outward_normal[2].powi(2)
    ).sqrt();
    if normal_length <= 1e-9 {
        return Err("secondary nearby hot object requires a valid picked surface normal".into());
    }
    let normal = [
        source.secondary_outward_normal[0] / normal_length,
        source.secondary_outward_normal[1] / normal_length,
        source.secondary_outward_normal[2] / normal_length,
    ];
    let radius = source.secondary_diameter_mm * 0.5;
    let center_distance = source.secondary_gap_mm + radius;
    let center = [
        source.secondary_target_mm[0] + normal[0] * center_distance,
        source.secondary_target_mm[1] + normal[1] * center_distance,
        source.secondary_target_mm[2] + normal[2] * center_distance,
    ];
    system.source2_temperature_c = source.secondary_temperature_c;
    system.source2_effective_emissivity = effective_emissivity(
        source.secondary_part_emissivity,
        source.secondary_emissivity,
    );
    system.source2_center_mm = center;

    let mut minimum_clearance = f64::INFINITY;
    let mut visible_area = 0.0;
    for (index, boundary) in system.boundaries.iter().enumerate() {
        if boundary.cooled {
            continue;
        }
        let face_center = boundary_center_mm(grid, boundary);
        let to_source = [
            center[0] - face_center[0],
            center[1] - face_center[1],
            center[2] - face_center[2],
        ];
        let distance2 = to_source[0].powi(2) + to_source[1].powi(2) + to_source[2].powi(2);
        if distance2 <= 1e-18 {
            return Err("secondary nearby hot object centre coincides with the model surface".into());
        }
        let distance = distance2.sqrt();
        minimum_clearance = minimum_clearance.min(distance - radius);
        let direction = [
            to_source[0] / distance,
            to_source[1] / distance,
            to_source[2] / distance,
        ];
        let face_normal = thermal_face_normal(boundary.face);
        let cosine = (
            face_normal[0] * direction[0]
                + face_normal[1] * direction[1]
                + face_normal[2] * direction[2]
        ).max(0.0);
        if cosine <= 0.0 {
            continue;
        }
        let geometric = (cosine * radius.powi(2) / distance2).clamp(0.0, 1.0);
        if geometric <= 1e-12
            || ray_blocked_by_part(grid, &system.active, boundary.cell, face_center, center)
        {
            continue;
        }
        system.source2_view_factor[index] = geometric;
        visible_area += boundary.area_mm2;
        if system.source_view_factor.get(index).copied().unwrap_or(0.0) <= 0.0 {
            system.heated_area_mm2 += boundary.area_mm2;
        }
        system.source2_view_factor_area_mm2 += boundary.area_mm2 * geometric;
    }
    if minimum_clearance < -grid.h * 0.25 {
        return Err(
            "secondary nearby hot object overlaps the model; select its true nearest point or increase the gap"
                .into(),
        );
    }
    if system.source2_view_factor_area_mm2 <= 0.0 || visible_area <= 0.0 {
        return Err(
            "the secondary nearby hot object is not visible from any exterior model surface; select another point"
                .into(),
        );
    }
    Ok(())
}

fn secondary_visible_area_mm2(system: &ThermalSystem) -> f64 {
    system.boundaries.iter().zip(&system.source2_view_factor)
        .filter(|(boundary, factor)| !boundary.cooled && **factor > 0.0)
        .map(|(boundary, _)| boundary.area_mm2)
        .sum()
}

fn effective_emissivity""",
        "secondary-source geometry and visibility",
    )

    replace_once(
        thermal,
        """            if source_factor > 0.0 {
                let (source_slope, source_rhs) = radiation_tangent_w_m2(
                    system.source_effective_emissivity,
                    t_surface_c,
                    system.source_temperature_c,
                );
                diagonal[ci] += source_slope * source_factor * area_m2;
                rhs[ci] += source_rhs * source_factor * area_m2;
            }
""",
        """            if source_factor > 0.0 {
                let (source_slope, source_rhs) = radiation_tangent_w_m2(
                    system.source_effective_emissivity,
                    t_surface_c,
                    system.source_temperature_c,
                );
                diagonal[ci] += source_slope * source_factor * area_m2;
                rhs[ci] += source_rhs * source_factor * area_m2;
            }
            let source2_factor = system.source2_view_factor
                .get(boundary_index)
                .copied()
                .unwrap_or(0.0);
            if source2_factor > 0.0 {
                let (source2_slope, source2_rhs) = radiation_tangent_w_m2(
                    system.source2_effective_emissivity,
                    t_surface_c,
                    system.source2_temperature_c,
                );
                diagonal[ci] += source2_slope * source2_factor * area_m2;
                rhs[ci] += source2_rhs * source2_factor * area_m2;
            }
""",
        "secondary-source Newton tangent",
    )
    replace_once(
        thermal,
        """            if source_factor > 0.0 {
                residual[ci] += radiation_flux_w_m2(
                    system.source_effective_emissivity,
                    surface_c,
                    system.source_temperature_c,
                ) * source_factor * area_m2;
            }
""",
        """            if source_factor > 0.0 {
                residual[ci] += radiation_flux_w_m2(
                    system.source_effective_emissivity,
                    surface_c,
                    system.source_temperature_c,
                ) * source_factor * area_m2;
            }
            let source2_factor = system.source2_view_factor
                .get(boundary_index)
                .copied()
                .unwrap_or(0.0);
            if source2_factor > 0.0 {
                residual[ci] += radiation_flux_w_m2(
                    system.source2_effective_emissivity,
                    surface_c,
                    system.source2_temperature_c,
                ) * source2_factor * area_m2;
            }
""",
        "secondary-source nonlinear residual",
    )
    replace_once(
        thermal,
        """    let source_absorbed = source_absorbed_w(system, &temperature);
    let total_heat_input = system.heat_input_w + source_absorbed;
""",
        """    let source1_absorbed = primary_source_absorbed_w(system, &temperature);
    let source2_absorbed = secondary_source_absorbed_w(system, &temperature);
    let source_absorbed = source1_absorbed + source2_absorbed;
    let total_heat_input = system.heat_input_w + source_absorbed;
""",
        "dual-source final absorption",
    )
    replace_once(
        thermal,
        """        source_absorbed_w: source_absorbed,
        source_view_factor_area_mm2: system.source_view_factor_area_mm2,
        source_center_mm: system.source_center_mm,
""",
        """        source_absorbed_w: source_absorbed,
        source1_absorbed_w: source1_absorbed,
        source2_absorbed_w: source2_absorbed,
        source_view_factor_area_mm2: system.source_view_factor_area_mm2,
        source_center_mm: system.source_center_mm,
        source2_view_factor_area_mm2: system.source2_view_factor_area_mm2,
        source2_center_mm: system.source2_center_mm,
""",
        "dual-source result values",
    )
    replace_once(
        thermal,
        """    if options.emissivity <= 0.0 && system.source_effective_emissivity <= 0.0 {
""",
        """    if options.emissivity <= 0.0
        && system.source_effective_emissivity <= 0.0
        && system.source2_effective_emissivity <= 0.0
    {
""",
        "dual-source nonlinear fast path",
    )
    replace_once(
        thermal,
        """fn source_absorbed_w(system: &ThermalSystem, temperature: &[f64]) -> f64 {
    if system.source_effective_emissivity <= 0.0 {
        return 0.0;
    }
    let mut absorbed = 0.0;
    for (boundary, factor) in system.boundaries.iter().zip(&system.source_view_factor) {
        if *factor <= 0.0 || boundary.cooled {
            continue;
        }
        let outward = radiation_flux_w_m2(
            system.source_effective_emissivity,
            temperature[boundary.cell],
            system.source_temperature_c,
        ) * factor * boundary.area_mm2 * 1e-6;
        absorbed -= outward;
    }
    absorbed
}""",
        """fn source_absorbed_w(system: &ThermalSystem, temperature: &[f64]) -> f64 {
    primary_source_absorbed_w(system, temperature)
        + secondary_source_absorbed_w(system, temperature)
}

fn primary_source_absorbed_w(system: &ThermalSystem, temperature: &[f64]) -> f64 {
    if system.source_effective_emissivity <= 0.0 {
        return 0.0;
    }
    let mut absorbed = 0.0;
    for (boundary, factor) in system.boundaries.iter().zip(&system.source_view_factor) {
        if *factor <= 0.0 || boundary.cooled {
            continue;
        }
        let outward = radiation_flux_w_m2(
            system.source_effective_emissivity,
            temperature[boundary.cell],
            system.source_temperature_c,
        ) * factor * boundary.area_mm2 * 1e-6;
        absorbed -= outward;
    }
    absorbed
}

fn secondary_source_absorbed_w(system: &ThermalSystem, temperature: &[f64]) -> f64 {
    if system.source2_effective_emissivity <= 0.0 {
        return 0.0;
    }
    let mut absorbed = 0.0;
    for (boundary, factor) in system.boundaries.iter().zip(&system.source2_view_factor) {
        if *factor <= 0.0 || boundary.cooled {
            continue;
        }
        let outward = radiation_flux_w_m2(
            system.source2_effective_emissivity,
            temperature[boundary.cell],
            system.source2_temperature_c,
        ) * factor * boundary.area_mm2 * 1e-6;
        absorbed -= outward;
    }
    absorbed
}""",
        "dual-source absorbed heat helpers",
    )

    contracts = (
        "secondary_enabled: bool",
        "configure_secondary_source",
        "source1_absorbed_w: f64",
        "source2_absorbed_w: f64",
        "source2_view_factor",
        "secondary_initial_absorbed_w",
    )
    text = thermal.read_text(encoding="utf-8")
    for contract in contracts:
        if contract not in text:
            raise RuntimeError(f"Dual-source thermal contract {contract!r} is missing")
    (source_root / SOURCE_MARKER).write_text(MARKER + "\n", encoding="utf-8")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
