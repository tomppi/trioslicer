#!/usr/bin/env python3
"""Replace Thermal Integrity's contact heater with a picked nearby hot object.

The source is modelled as a diffuse hot sphere. The user selects the nearest
point on the part; its outward normal positions the source centre at
`gap + radius`. A one-time view-factor/visibility pass produces a reusable
radiative boundary field for steady and transient solves.
"""
from __future__ import annotations

import pathlib

MARKER = "EnderSlicer nearby hot object thermal core v1"


def replace_once(path: pathlib.Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one {label} in {path}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def insert_before_once(path: pathlib.Path, marker: str, insertion: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if insertion in text:
        return
    count = text.count(marker)
    if count != 1:
        raise RuntimeError(f"Expected one {label} marker in {path}, found {count}")
    path.write_text(text.replace(marker, insertion + marker, 1), encoding="utf-8")


def apply(source_root: pathlib.Path) -> None:
    source_root = source_root.resolve()
    thermal = source_root / "crates/filasim-core/src/thermal.rs"
    if not thermal.is_file():
        raise RuntimeError(f"Nearby-hot-object target is missing: {thermal}")

    replace_once(
        thermal,
        """    pub exposed_heated_area_mm2: f64,
    pub exposed_cooled_area_mm2: f64,
}
""",
        """    pub exposed_heated_area_mm2: f64,
    pub exposed_cooled_area_mm2: f64,
    pub source_absorbed_w: f64,
    pub source_view_factor_area_mm2: f64,
    pub source_center_mm: [f64; 3],
}
""",
        "ThermalResult nearby-source fields",
    )
    replace_once(
        thermal,
        """    heat_input_w: f64,
    heated_area_mm2: f64,
    cooled_area_mm2: f64,
    h_mm: f64,
}
""",
        """    heat_input_w: f64,
    heated_area_mm2: f64,
    cooled_area_mm2: f64,
    h_mm: f64,
    source_view_factor: Vec<f64>,
    source_temperature_c: f64,
    source_effective_emissivity: f64,
    source_view_factor_area_mm2: f64,
    source_center_mm: [f64; 3],
}
""",
        "ThermalSystem nearby-source fields",
    )
    replace_once(
        thermal,
        """        heat_input_w: options.heat_power_w + options.volumetric_power_w,
        heated_area_mm2,
        cooled_area_mm2,
        h_mm: grid.h,
    })
}
""",
        """        heat_input_w: options.heat_power_w + options.volumetric_power_w,
        heated_area_mm2,
        cooled_area_mm2,
        h_mm: grid.h,
        source_view_factor: Vec::new(),
        source_temperature_c: options.ambient_temperature_c,
        source_effective_emissivity: 0.0,
        source_view_factor_area_mm2: 0.0,
        source_center_mm: [0.0; 3],
    })
}
""",
        "ThermalSystem defaults",
    )

    fragment = pathlib.Path(__file__).with_name("filasim-nearby-hot-object-core.rs")
    if not fragment.is_file():
        raise RuntimeError(f"Nearby-hot-object Rust fragment is missing: {fragment}")
    nearby_core = fragment.read_text(encoding="utf-8")
    insert_before_once(thermal, "pub fn solve_thermal(\n", nearby_core, "nearby hot object core")

    replace_once(
        thermal,
        """            diagonal[ci] += convection_g + radiation_g;
            rhs[ci] += convection_g * options.ambient_temperature_c
                + radiation_rhs_w_m2 * area_m2;
        }
""",
        """            diagonal[ci] += convection_g + radiation_g;
            rhs[ci] += convection_g * options.ambient_temperature_c
                + radiation_rhs_w_m2 * area_m2;
            let source_factor = system.source_view_factor
                .get(boundary_index)
                .copied()
                .unwrap_or(0.0);
            if source_factor > 0.0 {
                let (source_slope, source_rhs) = radiation_tangent_w_m2(
                    system.source_effective_emissivity,
                    t_surface_c,
                    system.source_temperature_c,
                );
                diagonal[ci] += source_slope * source_factor * area_m2;
                rhs[ci] += source_rhs * source_factor * area_m2;
            }
        }
""",
        "hot-object Newton tangent",
    )
    replace_once(
        thermal,
        """    for boundary in &system.boundaries {
        let ci = boundary.cell;
        if boundary.cooled {
""",
        """    for (boundary_index, boundary) in system.boundaries.iter().enumerate() {
        let ci = boundary.cell;
        if boundary.cooled {
""",
        "indexed linear boundary loop",
    )

    replace_once(
        thermal,
        """            residual[ci] += radiation_flux_w_m2(
                options.emissivity,
                surface_c,
                options.ambient_temperature_c,
            ) * area_m2;
        }
""",
        """            residual[ci] += radiation_flux_w_m2(
                options.emissivity,
                surface_c,
                options.ambient_temperature_c,
            ) * area_m2;
            let source_factor = system.source_view_factor
                .get(boundary_index)
                .copied()
                .unwrap_or(0.0);
            if source_factor > 0.0 {
                residual[ci] += radiation_flux_w_m2(
                    system.source_effective_emissivity,
                    surface_c,
                    system.source_temperature_c,
                ) * source_factor * area_m2;
            }
        }
""",
        "hot-object nonlinear residual",
    )
    text = thermal.read_text(encoding="utf-8")
    old_loop = "    for boundary in &system.boundaries {\n        let ci = boundary.cell;\n        let surface_c = temperature[ci];\n"
    new_loop = "    for (boundary_index, boundary) in system.boundaries.iter().enumerate() {\n        let ci = boundary.cell;\n        let surface_c = temperature[ci];\n"
    if new_loop not in text:
        if text.count(old_loop) != 1:
            raise RuntimeError("Expected one nonlinear boundary loop")
        thermal.write_text(text.replace(old_loop, new_loop, 1), encoding="utf-8")

    replace_once(
        thermal,
        """    let heat_rejected = heat_rejected_w(options, system, &temperature);
""",
        """    let heat_rejected = heat_rejected_w(options, system, &temperature);
    let source_absorbed = source_absorbed_w(system, &temperature);
    let total_heat_input = system.heat_input_w + source_absorbed;
""",
        "dynamic source heat input",
    )
    replace_once(
        thermal,
        """    let imbalance = system.heat_input_w - heat_rejected - storage_rate;
    let denominator = system
        .heat_input_w
""",
        """    let imbalance = total_heat_input - heat_rejected - storage_rate;
    let denominator = total_heat_input
""",
        "hot-object energy balance",
    )
    replace_once(
        thermal,
        """        heat_input_w: system.heat_input_w,
""",
        """        heat_input_w: total_heat_input,
""",
        "reported hot-object heat input",
    )
    replace_once(
        thermal,
        """        exposed_heated_area_mm2: system.heated_area_mm2,
        exposed_cooled_area_mm2: system.cooled_area_mm2,
    })
""",
        """        exposed_heated_area_mm2: system.heated_area_mm2,
        exposed_cooled_area_mm2: system.cooled_area_mm2,
        source_absorbed_w: source_absorbed,
        source_view_factor_area_mm2: system.source_view_factor_area_mm2,
        source_center_mm: system.source_center_mm,
    })
""",
        "ThermalResult nearby source result",
    )
    replace_once(
        thermal,
        """    if options.emissivity <= 0.0 {
""",
        """    if options.emissivity <= 0.0 && system.source_effective_emissivity <= 0.0 {
""",
        "nearby-source nonlinear fast-path guard",
    )

    marker = source_root / ".enderslicer-nearby-hot-object-thermal-v1"
    marker.write_text(MARKER + "\n", encoding="utf-8")
    for contract in ("solve_nearby_hot_object", "source_view_factor_area_mm2"):
        if contract not in thermal.read_text(encoding="utf-8"):
            raise RuntimeError(f"Nearby-hot-object thermal contract {contract!r} is missing")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
