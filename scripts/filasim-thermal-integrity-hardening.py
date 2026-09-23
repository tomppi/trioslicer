#!/usr/bin/env python3
"""Harden the generated filaSim thermal solver against voxel-boundary artifacts."""

from __future__ import annotations

import pathlib


MARKER = "EnderSlicer thermal integrity voxel-boundary hardening v1"


def replace_once(path: pathlib.Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise RuntimeError(f"Unable to locate {label} in {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def apply(source_root: pathlib.Path) -> None:
    source_root = source_root.resolve()
    thermal = source_root / "crates/filasim-core/src/thermal.rs"
    wasm = source_root / "crates/filasim-wasm/src/lib.rs"
    if not thermal.is_file() or not wasm.is_file():
        raise RuntimeError("Apply filasim-thermal-integrity-patch.py before hardening")

    replace_once(
        thermal,
        '''struct BoundaryFace {
    cell: usize,
    face: ThermalFace,
    area_mm2: f64,
    conductivity_w_mm_k: f64,
}
''',
        '''struct BoundaryFace {
    cell: usize,
    face: ThermalFace,
    area_mm2: f64,
    conductivity_w_mm_k: f64,
    /// True only on the selected GLOBAL extreme face. A downward-facing step
    /// or an internal cavity is not silently treated as a mounting/heater face.
    heated: bool,
    cooled: bool,
}
''',
        "boundary-face classification",
    )

    replace_once(
        thermal,
        '''    let mut active = vec![false; n];
    let mut conductivity = vec![[0.0; 3]; n];
    let mut capacity_j_k = vec![0.0; n];
    let cell_volume_m3 = grid.h.powi(3) * 1e-9;
    for ci in 0..n {
        let vf = material_fraction[ci] as f64;
        if grid.scale[ci] <= 0.0 || vf <= 1e-7 {
            continue;
        }
        active[ci] = true;
        let blend = vf.powf(options.conductivity_exponent);
        for axis in 0..3 {
            // Convert W/(m·K) to W/(mm·K).
            let k = K_AIR_W_M_K + (options.conductivity_w_m_k[axis] - K_AIR_W_M_K) * blend;
            conductivity[ci][axis] = k.max(K_AIR_W_M_K * 0.25) / 1_000.0;
        }
        capacity_j_k[ci] =
            options.density_kg_m3 * options.specific_heat_j_kg_k * cell_volume_m3 * vf;
    }
''',
        '''    let mut active = vec![false; n];
    let mut conductivity = vec![[0.0; 3]; n];
    let mut capacity_j_k = vec![0.0; n];
    let mut face_area_fraction = vec![0.0; n];
    let cell_volume_m3 = grid.h.powi(3) * 1e-9;
    for ci in 0..n {
        let occupancy = grid.scale[ci] as f64;
        let vf = material_fraction[ci] as f64;
        if occupancy <= 0.0 || vf <= 1e-7 {
            continue;
        }
        active[ci] = true;
        // `vf` contains both geometric cut-cell occupancy and printed material
        // density. Conductivity is an intrinsic material property, so remove
        // occupancy before applying the infill law; occupancy instead reduces
        // the finite-volume face area. This keeps curved cut cells from looking
        // like low-density polymer and avoids a staircase conductivity bias.
        let relative_density = (vf / occupancy.max(1e-12)).clamp(0.0, 1.0);
        let blend = relative_density.powf(options.conductivity_exponent);
        for axis in 0..3 {
            // Convert W/(m·K) to W/(mm·K).
            let k = K_AIR_W_M_K + (options.conductivity_w_m_k[axis] - K_AIR_W_M_K) * blend;
            conductivity[ci][axis] = k.max(K_AIR_W_M_K * 0.25) / 1_000.0;
        }
        face_area_fraction[ci] = occupancy.clamp(0.0, 1.0).powf(2.0 / 3.0).max(0.01);
        // Heat capacity scales with actual polymer volume, therefore `vf`
        // (not the occupancy-decoupled relative density) remains correct here.
        capacity_j_k[ci] =
            options.density_kg_m3 * options.specific_heat_j_kg_k * cell_volume_m3 * vf;
    }
''',
        "occupancy-decoupled thermal properties",
    )

    replace_once(
        thermal,
        '''                    let harmonic = 2.0 * ka * kb / (ka + kb).max(1e-30);
                    let g = harmonic * grid.h;
''',
        '''                    let harmonic = 2.0 * ka * kb / (ka + kb).max(1e-30);
                    let shared_area_fraction =
                        face_area_fraction[ci].min(face_area_fraction[cj]).max(0.01);
                    let g = harmonic * grid.h * shared_area_fraction;
''',
        "cut-cell face conductance",
    )

    replace_once(
        thermal,
        '''    let mut boundaries = Vec::new();
    let mut heated_area_mm2 = 0.0;
    let mut cooled_area_mm2 = 0.0;
''',
        '''    // Ambient convection/radiation applies only to void connected to
    // the outside of the padded grid. Sealed cavities do not magically receive
    // ambient airflow. Open internal passages remain exterior-connected.
    let exterior_void = exterior_void_mask(&active, grid.nx, grid.ny, grid.nz);
    let mut min_x = usize::MAX;
    let mut min_y = usize::MAX;
    let mut min_z = usize::MAX;
    let mut max_x = 0usize;
    let mut max_y = 0usize;
    let mut max_z = 0usize;
    for cz in 0..grid.nz {
        for cy in 0..grid.ny {
            for cx in 0..grid.nx {
                let ci = grid.cell_index(cx, cy, cz);
                if active[ci] {
                    min_x = min_x.min(cx);
                    min_y = min_y.min(cy);
                    min_z = min_z.min(cz);
                    max_x = max_x.max(cx);
                    max_y = max_y.max(cy);
                    max_z = max_z.max(cz);
                }
            }
        }
    }

    let mut boundaries = Vec::new();
    let mut heated_area_mm2 = 0.0;
    let mut cooled_area_mm2 = 0.0;
''',
        "exterior void and global face extents",
    )

    replace_once(
        thermal,
        '''                let area_fraction = (grid.scale[ci] as f64).clamp(0.0, 1.0).powf(2.0 / 3.0);
                let area = grid.h * grid.h * area_fraction.max(0.05);
''',
        '''                let area = grid.h * grid.h * face_area_fraction[ci];
''',
        "boundary cut-cell area",
    )

    replace_once(
        thermal,
        '''                    let exposed = nx < 0
                        || ny < 0
                        || nz < 0
                        || nx >= grid.nx as isize
                        || ny >= grid.ny as isize
                        || nz >= grid.nz as isize
                        || !active[grid.cell_index(nx as usize, ny as usize, nz as usize)];
                    if !exposed {
                        continue;
                    }
                    boundaries.push(BoundaryFace {
                        cell: ci,
                        face,
                        area_mm2: area,
                        conductivity_w_mm_k: conductivity[ci][axis],
                    });
                    if face == options.heated_face {
                        heated_area_mm2 += area;
                    }
                    if face == options.cooled_face {
                        cooled_area_mm2 += area;
                    }
''',
        '''                    let outside = nx < 0
                        || ny < 0
                        || nz < 0
                        || nx >= grid.nx as isize
                        || ny >= grid.ny as isize
                        || nz >= grid.nz as isize;
                    let exposed = if outside {
                        true
                    } else {
                        let neighbor = grid.cell_index(nx as usize, ny as usize, nz as usize);
                        !active[neighbor] && exterior_void[neighbor]
                    };
                    if !exposed {
                        continue;
                    }
                    let at_global_extreme = match face {
                        ThermalFace::XMin => cx == min_x,
                        ThermalFace::XMax => cx == max_x,
                        ThermalFace::YMin => cy == min_y,
                        ThermalFace::YMax => cy == max_y,
                        ThermalFace::ZMin => cz == min_z,
                        ThermalFace::ZMax => cz == max_z,
                    };
                    let heated = face == options.heated_face && at_global_extreme;
                    let cooled = face == options.cooled_face && at_global_extreme;
                    boundaries.push(BoundaryFace {
                        cell: ci,
                        face,
                        area_mm2: area,
                        conductivity_w_mm_k: conductivity[ci][axis],
                        heated,
                        cooled,
                    });
                    if heated {
                        heated_area_mm2 += area;
                    }
                    if cooled {
                        cooled_area_mm2 += area;
                    }
''',
        "exterior-only and global-extreme boundary selection",
    )

    replace_once(
        thermal,
        '''    if options.heat_power_w > 0.0 && heated_area_mm2 <= 0.0 {
        return Err("the selected heated face has no exposed voxel surface".into());
    }
    if cooled_area_mm2 <= 0.0
        && options.convection_w_m2_k <= 0.0
        && options.emissivity <= 0.0
        && !options.transient
    {
        return Err("steady-state heat solve has no cooling boundary and is singular".into());
    }
''',
        '''    if options.heat_power_w > 0.0 && heated_area_mm2 <= 0.0 {
        return Err("the selected heated global face has no exposed voxel surface".into());
    }
    if cooled_area_mm2 <= 0.0 {
        return Err("the selected fixed-temperature global face has no exposed voxel surface".into());
    }
''',
        "selected boundary presence checks",
    )

    replace_once(
        thermal,
        '''        for boundary in &boundaries {
            if boundary.face == options.heated_face {
                source_w[boundary.cell] +=
                    options.heat_power_w * boundary.area_mm2 / heated_area_mm2;
            }
        }
''',
        '''        for boundary in &boundaries {
            if boundary.heated {
                source_w[boundary.cell] +=
                    options.heat_power_w * boundary.area_mm2 / heated_area_mm2;
            }
        }
''',
        "selected heated face source",
    )

    replace_once(
        thermal,
        '''fn solve_steady(
''',
        '''fn exterior_void_mask(active: &[bool], nx: usize, ny: usize, nz: usize) -> Vec<bool> {
    use std::collections::VecDeque;
    let mut exterior = vec![false; active.len()];
    let mut queue = VecDeque::new();
    let mut seed = |x: usize, y: usize, z: usize, exterior: &mut [bool], queue: &mut VecDeque<usize>| {
        let index = (z * ny + y) * nx + x;
        if !active[index] && !exterior[index] {
            exterior[index] = true;
            queue.push_back(index);
        }
    };
    for z in 0..nz {
        for y in 0..ny {
            seed(0, y, z, &mut exterior, &mut queue);
            if nx > 1 {
                seed(nx - 1, y, z, &mut exterior, &mut queue);
            }
        }
        for x in 0..nx {
            seed(x, 0, z, &mut exterior, &mut queue);
            if ny > 1 {
                seed(x, ny - 1, z, &mut exterior, &mut queue);
            }
        }
    }
    for y in 0..ny {
        for x in 0..nx {
            seed(x, y, 0, &mut exterior, &mut queue);
            if nz > 1 {
                seed(x, y, nz - 1, &mut exterior, &mut queue);
            }
        }
    }
    while let Some(index) = queue.pop_front() {
        let x = index % nx;
        let y = (index / nx) % ny;
        let z = index / (nx * ny);
        for (dx, dy, dz) in [
            (-1isize, 0isize, 0isize),
            (1, 0, 0),
            (0, -1, 0),
            (0, 1, 0),
            (0, 0, -1),
            (0, 0, 1),
        ] {
            let xx = x as isize + dx;
            let yy = y as isize + dy;
            let zz = z as isize + dz;
            if xx < 0 || yy < 0 || zz < 0 || xx >= nx as isize || yy >= ny as isize || zz >= nz as isize {
                continue;
            }
            let neighbor = (zz as usize * ny + yy as usize) * nx + xx as usize;
            if !active[neighbor] && !exterior[neighbor] {
                exterior[neighbor] = true;
                queue.push_back(neighbor);
            }
        }
    }
    exterior
}

fn solve_steady(
''',
        "external-void flood fill",
    )

    replace_once(
        thermal,
        '''        if boundary.face == options.cooled_face {
''',
        '''        if boundary.cooled {
''',
        "linear-system cooled boundary",
    )
    replace_once(
        thermal,
        '''        if boundary.face == options.cooled_face {
''',
        '''        if boundary.cooled {
''',
        "energy-balance cooled boundary",
    )

    text = thermal.read_text(encoding="utf-8")
    test_marker = "    #[test]\n    fn voxel_boundary_hardening_marker()"
    if test_marker not in text:
        insertion = r'''

    #[test]
    fn voxel_boundary_hardening_marker() {
        assert_eq!(MARKER_FOR_TESTS, "voxel-boundary-hardening-v1");
    }

    #[test]
    fn sealed_cavity_does_not_receive_ambient_boundary_faces() {
        let mut grid = VoxelGrid::solid_box(3, 3, 3, 1.0);
        let center = grid.cell_index(1, 1, 1);
        grid.scale[center] = 0.0;
        let mut material = grid.scale.clone();
        material[center] = 0.0;
        let system = build_system(&grid, &material, &base_options()).unwrap();
        assert_eq!(system.boundaries.len(), 6 * 3 * 3);
    }

    #[test]
    fn selected_face_uses_only_the_global_extreme_not_a_step_underside() {
        let mut grid = VoxelGrid::solid_box(2, 1, 2, 1.0);
        // Connected staircase: lower row [solid, solid], upper row [void, solid].
        grid.scale[grid.cell_index(0, 0, 1)] = 0.0;
        let material = grid.scale.clone();
        let mut options = base_options();
        options.heated_face = ThermalFace::XMin;
        options.cooled_face = ThermalFace::XMax;
        let system = build_system(&grid, &material, &options).unwrap();
        assert!((system.heated_area_mm2 - 1.0).abs() < 1e-12);
        assert!((system.cooled_area_mm2 - 2.0).abs() < 1e-12);
    }

    #[test]
    fn cut_cell_uses_intrinsic_conductivity_and_reduced_face_area() {
        let mut grid = VoxelGrid::solid_box(2, 1, 1, 1.0);
        grid.scale.fill(0.125);
        let material = grid.scale.clone();
        let system = build_system(&grid, &material, &base_options()).unwrap();
        // occupancy^(2/3) = 0.25; k = 0.2 W/(m K) = 0.0002 W/(mm K).
        let expected = 0.0002 * 0.25;
        assert!((system.conductance[0][1] - expected).abs() < 1e-12);
    }
'''
        closing = text.rfind("\n}\n")
        if closing < 0:
            raise RuntimeError("Unable to locate thermal.rs test-module closing brace")
        text = text[:closing] + insertion + text[closing:]
        text = text.replace(
            "const MIN_ABSOLUTE_TEMPERATURE_C: f64 = -273.15;\n",
            "const MIN_ABSOLUTE_TEMPERATURE_C: f64 = -273.15;\n"
            "#[cfg(test)]\nconst MARKER_FOR_TESTS: &str = \"voxel-boundary-hardening-v1\";\n",
            1,
        )
        thermal.write_text(text, encoding="utf-8")

    replace_once(
        wasm,
        '''        let mut temperature_eps = base_eps.clone();
        let mut minimum_modulus_retention: f64 = 1.0;
        let mut minimum_strength_retention: f64 = 1.0;
        for ci in 0..temperature_eps.len() {
            if temperature_eps[ci] <= 0.0 {
                continue;
            }
            let temp = thermal.temperatures_c[ci] as f64;
            let er = filasim_core::thermal::property_retention(
                temp,
                reference_temperature,
                service_limit,
                modulus_floor,
            );
            let sr = filasim_core::thermal::property_retention(
                temp,
                reference_temperature,
                service_limit,
                strength_floor,
            );
            minimum_modulus_retention = minimum_modulus_retention.min(er);
            minimum_strength_retention = minimum_strength_retention.min(sr);
            temperature_eps[ci] *= er as f32;
        }
''',
        '''        let mut temperature_eps = base_eps.clone();
        let mut material_stiffness = vec![0.0f32; base_eps.len()];
        let mut relative_density = vec![0.0f64; material_fraction.len()];
        let mut minimum_modulus_retention: f64 = 1.0;
        let mut minimum_strength_retention: f64 = 1.0;
        for ci in 0..temperature_eps.len() {
            let occupancy = grid.scale[ci] as f64;
            if temperature_eps[ci] <= 0.0 || occupancy <= 0.0 {
                continue;
            }
            relative_density[ci] =
                (material_fraction[ci] as f64 / occupancy.max(1e-12)).clamp(0.0, 1.0);
            let temp = thermal.temperatures_c[ci] as f64;
            let er = filasim_core::thermal::property_retention(
                temp,
                reference_temperature,
                service_limit,
                modulus_floor,
            );
            let sr = filasim_core::thermal::property_retention(
                temp,
                reference_temperature,
                service_limit,
                strength_floor,
            );
            minimum_modulus_retention = minimum_modulus_retention.min(er);
            minimum_strength_retention = minimum_strength_retention.min(sr);
            temperature_eps[ci] *= er as f32;
            // The structural operator uses occupancy-scaled stiffness. Stress
            // is a material quantity, so remove the geometric occupancy before
            // evaluating it; otherwise cut cells create false low/high safety
            // factors along curved voxel boundaries.
            material_stiffness[ci] = (temperature_eps[ci] as f64 / occupancy.max(1e-12)) as f32;
        }
''',
        "occupancy-corrected structural material fields",
    )

    replace_once(
        wasm,
        '''            &temperature_eps,
            &thermal.temperatures_c,
''',
        '''            &material_stiffness,
            &thermal.temperatures_c,
''',
        "material-stress stiffness field",
    )

    replace_once(
        wasm,
        '''            let density_strength = (material_fraction[ci] as f64)
                .powf(strength_exponent)
                .clamp(0.01, 1.0);
''',
        '''            let density_strength = relative_density[ci]
                .powf(strength_exponent)
                .clamp(0.01, 1.0);
''',
        "occupancy-corrected strength allowable",
    )

    marker = source_root / ".enderslicer-thermal-integrity-hardening"
    marker.write_text(MARKER + "\n", encoding="utf-8")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
