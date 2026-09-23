#!/usr/bin/env python3
"""Patch the finite-volume thermal core for oven annealing readiness."""
from __future__ import annotations

import pathlib
import re

from filasim_annealing_common import append_once, replace_once


def patch_thermal_core(thermal: pathlib.Path) -> None:
    replace_once(
        thermal,
        """    pub time_step_seconds: f64,
    pub tolerance: f64,
""",
        """    pub time_step_seconds: f64,
    /// When false, no global fixed-temperature plane is applied. Every exposed
    /// non-contact surface exchanges heat with ambient by convection/radiation,
    /// which is the correct boundary model for a freely placed part in an oven.
    pub fixed_surface_enabled: bool,
    /// Optional transient completion threshold. Heating uses the coldest active
    /// material cell; cooling uses the hottest active material cell.
    pub readiness_temperature_c: Option<f64>,
    pub readiness_hold_seconds: f64,
    pub readiness_cooling: bool,
    pub stop_when_ready: bool,
    pub tolerance: f64,
""",
        "annealing thermal options",
    )
    replace_once(
        thermal,
        """    pub peak_time_seconds: f64,
    pub exposed_heated_area_mm2: f64,
""",
        """    pub peak_time_seconds: f64,
    /// First simulated time at which the readiness threshold was met.
    pub readiness_reached_time_seconds: Option<f64>,
    /// First time at which the threshold remained met for the requested hold.
    pub readiness_complete_time_seconds: Option<f64>,
    pub exposed_heated_area_mm2: f64,
""",
        "annealing result timing fields",
    )
    replace_once(
        thermal,
        """    if options.heated_face == options.cooled_face && options.heat_power_w > 0.0 {
        return Err("the heated and fixed-temperature faces must be different".into());
    }
""",
        """    if options.fixed_surface_enabled
        && options.heated_face == options.cooled_face
        && options.heat_power_w > 0.0
    {
        return Err("the heated and fixed-temperature faces must be different".into());
    }
    if !options.readiness_hold_seconds.is_finite()
        || !(0.0..=31_536_000.0).contains(&options.readiness_hold_seconds)
    {
        return Err("readiness hold must be within 0 seconds and one year".into());
    }
    if let Some(target) = options.readiness_temperature_c {
        if !target.is_finite() || target <= MIN_ABSOLUTE_TEMPERATURE_C || target > 1_500.0 {
            return Err("readiness temperature is outside the supported physical range".into());
        }
        if !options.transient {
            return Err("readiness tracking requires a transient thermal solve".into());
        }
    }
""",
        "annealing option validation",
    )
    replace_once(
        thermal,
        """                    let heated = face == options.heated_face;
                    let cooled = face == options.cooled_face && at_global_extreme;
""",
        """                    let heated = options.heat_power_w > 0.0
                        && face == options.heated_face;
                    let cooled = options.fixed_surface_enabled
                        && face == options.cooled_face
                        && at_global_extreme;
""",
        "optional contact/fixed boundaries",
    )
    replace_once(
        thermal,
        """    if cooled_area_mm2 <= 0.0 {
        return Err("the selected fixed-temperature global face has no exposed voxel surface".into());
    }
""",
        """    if options.fixed_surface_enabled && cooled_area_mm2 <= 0.0 {
        return Err("the selected fixed-temperature global face has no exposed voxel surface".into());
    }
""",
        "optional fixed-surface presence check",
    )

    replace_once(
        thermal,
        """    let mut last_old = temperature.clone();
    let mut final_dt = options.time_step_seconds;

    for step in 0..steps {
""",
        """    let mut last_old = temperature.clone();
    let mut final_dt = options.time_step_seconds;
    let mut readiness_started_at: Option<f64> = None;
    let mut readiness_reached_time_seconds: Option<f64> = None;
    let mut readiness_complete_time_seconds: Option<f64> = None;
    let mut completed_steps = 0usize;
    let mut completed_time = 0.0;

    for step in 0..steps {
""",
        "transient readiness state",
    )
    replace_once(
        thermal,
        """        let stats = temperature_extremes(grid, material_fraction, &temperature, &system.active);
        history.extend_from_slice(&[time, stats.2, stats.1]);
        if stats.2 > peak_temperature {
            peak_temperature = stats.2;
            peak_time = time;
        }
    }

    finish_result(
""",
        """        let stats = temperature_extremes(grid, material_fraction, &temperature, &system.active);
        history.extend_from_slice(&[time, stats.2, stats.1]);
        completed_steps = step + 1;
        completed_time = time;
        if stats.2 > peak_temperature {
            peak_temperature = stats.2;
            peak_time = time;
        }
        if let Some(target) = options.readiness_temperature_c {
            let threshold_met = if options.readiness_cooling {
                // Cooling is safe only when the hottest material voxel is below
                // the target; surface cooling alone is not sufficient.
                stats.2 <= target
            } else {
                // Annealing soak begins only when the coldest material voxel has
                // reached the target. Thick sections therefore control time.
                stats.0 >= target
            };
            if threshold_met {
                let started = *readiness_started_at.get_or_insert(time);
                if readiness_reached_time_seconds.is_none() {
                    readiness_reached_time_seconds = Some(time);
                }
                if time - started + 1e-9 >= options.readiness_hold_seconds {
                    readiness_complete_time_seconds = Some(time);
                    if options.stop_when_ready {
                        break;
                    }
                }
            } else {
                readiness_started_at = None;
                readiness_complete_time_seconds = None;
            }
        }
    }

    let mut result = finish_result(
""",
        "transient threshold evaluation",
    )
    replace_once(
        thermal,
        """        steps,
        options.duration_seconds,
        Some((&last_old, final_dt, peak_temperature, peak_time)),
    )
}
""",
        """        completed_steps,
        completed_time,
        Some((&last_old, final_dt, peak_temperature, peak_time)),
    )?;
    result.readiness_reached_time_seconds = readiness_reached_time_seconds;
    result.readiness_complete_time_seconds = readiness_complete_time_seconds;
    Ok(result)
}
""",
        "transient readiness result",
    )
    replace_once(
        thermal,
        """        peak_temperature_c: peak_temperature,
        peak_time_seconds: peak_time,
        exposed_heated_area_mm2: system.heated_area_mm2,
""",
        """        peak_temperature_c: peak_temperature,
        peak_time_seconds: peak_time,
        readiness_reached_time_seconds: None,
        readiness_complete_time_seconds: None,
        exposed_heated_area_mm2: system.heated_area_mm2,
""",
        "result defaults",
    )

    text = thermal.read_text(encoding="utf-8")
    text, count = re.subn(
        r"(?m)^(\s*)tolerance: 1e-8,\s*$",
        r"\1fixed_surface_enabled: true,\n"
        r"\1readiness_temperature_c: None,\n"
        r"\1readiness_hold_seconds: 0.0,\n"
        r"\1readiness_cooling: false,\n"
        r"\1stop_when_ready: false,\n"
        r"\1tolerance: 1e-8,",
        text,
    )
    if count < 2:
        raise RuntimeError(f"Expected thermal test constructors, patched {count}")
    thermal.write_text(text, encoding="utf-8")

    append_once(
        thermal,
        "geometry_aware_annealing_thickness_test",
        r'''
#[cfg(test)]
mod annealing_cycle_v1_tests {
    use super::*;

    fn oven_options() -> ThermalOptions {
        ThermalOptions {
            transient: true,
            conductivity_w_m_k: [0.18, 0.18, 0.13],
            density_kg_m3: 1_240.0,
            specific_heat_j_kg_k: 1_800.0,
            conductivity_exponent: 1.0,
            ambient_temperature_c: 80.0,
            initial_temperature_c: 23.0,
            cooled_temperature_c: 80.0,
            convection_w_m2_k: 18.0,
            emissivity: 0.9,
            heated_face: ThermalFace::ZMax,
            cooled_face: ThermalFace::ZMin,
            heat_power_w: 0.0,
            volumetric_power_w: 0.0,
            duration_seconds: 14_400.0,
            time_step_seconds: 10.0,
            fixed_surface_enabled: false,
            readiness_temperature_c: Some(78.0),
            readiness_hold_seconds: 0.0,
            readiness_cooling: false,
            stop_when_ready: true,
            tolerance: 1e-7,
        }
    }

    #[test]
    fn oven_mode_has_no_contact_or_fixed_temperature_area() {
        let grid = VoxelGrid::solid_box(4, 3, 2, 1.0);
        let material = vec![1.0; grid.cell_count()];
        let result = solve_thermal(&grid, &material, &oven_options()).unwrap();
        assert_eq!(result.exposed_heated_area_mm2, 0.0);
        assert_eq!(result.exposed_cooled_area_mm2, 0.0);
        assert!(result.readiness_complete_time_seconds.is_some());
    }

    #[test]
    fn thicker_part_takes_longer_for_its_coldest_voxel_to_reach_target() {
        let thin = VoxelGrid::solid_box(8, 8, 2, 1.0);
        let thick = VoxelGrid::solid_box(8, 8, 8, 1.0);
        let thin_result = solve_thermal(
            &thin, &vec![1.0; thin.cell_count()], &oven_options(),
        ).unwrap();
        let thick_result = solve_thermal(
            &thick, &vec![1.0; thick.cell_count()], &oven_options(),
        ).unwrap();
        let thin_time = thin_result.readiness_complete_time_seconds.unwrap();
        let thick_time = thick_result.readiness_complete_time_seconds.unwrap();
        assert!(thick_time > thin_time, "thin={thin_time}, thick={thick_time}");
    }

    #[test]
    fn cooling_completion_uses_the_hottest_voxel() {
        let grid = VoxelGrid::solid_box(6, 6, 6, 1.0);
        let material = vec![1.0; grid.cell_count()];
        let mut opts = oven_options();
        opts.ambient_temperature_c = 23.0;
        opts.initial_temperature_c = 80.0;
        opts.readiness_temperature_c = Some(45.0);
        opts.readiness_cooling = true;
        let result = solve_thermal(&grid, &material, &opts).unwrap();
        assert!(result.readiness_complete_time_seconds.is_some());
        assert!(result.maximum_temperature_c <= 45.0 + 0.2);
    }
}
''',
    )
