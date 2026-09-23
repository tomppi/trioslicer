#!/usr/bin/env python3
"""Stabilize short fixed-duration annealing simulations.

A requested duration that is not an integer multiple of the maximum timestep
must use an even partition instead of a tiny final remainder step. Nonlinear
radiation stagnation tolerance is also scaled by sqrt(active cells), matching
the L2 whole-system residual while remaining physically negligible.
"""

from __future__ import annotations

import pathlib


MARKER = "EnderSlicer short-duration annealing stability v1"
SOURCE_MARKER = ".enderslicer-annealing-short-duration-stability-v1"


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
        raise RuntimeError(f"Short-duration stability target is missing: {thermal}")

    replace_once(
        thermal,
        '''/// Absolute whole-system residual floor. 1e-7 W is negligible relative to
/// user-scale heat loads but above accumulated floating-point cancellation.
const NONLINEAR_ABS_RESIDUAL_W: f64 = 1e-7;
''',
        '''/// Minimum whole-system residual floor. The nonlinear residual is an
/// L2 norm over active cells, so accumulated roundoff scales with sqrt(N).
const NONLINEAR_ABS_RESIDUAL_W: f64 = 1e-7;
const NONLINEAR_ROUNDOFF_W_PER_SQRT_CELL: f64 = 1e-9;
''',
        "scale-aware nonlinear residual constants",
    )

    replace_once(
        thermal,
        '''    let residual_target = (options.tolerance
        * initial_residual.max(system.heat_input_w.abs()).max(1e-6))
        .max(NONLINEAR_ABS_RESIDUAL_W);
''',
        '''    let active_cells = system.active.iter().filter(|active| **active).count();
    let roundoff_floor = NONLINEAR_ROUNDOFF_W_PER_SQRT_CELL
        * (active_cells.max(1) as f64).sqrt();
    let residual_target = (options.tolerance
        * initial_residual.max(system.heat_input_w.abs()).max(1e-6))
        .max(NONLINEAR_ABS_RESIDUAL_W.max(roundoff_floor));
''',
        "scale-aware nonlinear residual target",
    )

    replace_once(
        thermal,
        '''    let steps = (options.duration_seconds / options.time_step_seconds).ceil() as usize;
    let mut temperature = options.initial_temperature_field_c.clone().unwrap_or_else(|| {
''',
        '''    let steps = (options.duration_seconds / options.time_step_seconds).ceil() as usize;
    // Treat the requested timestep as a maximum and divide the selected
    // duration evenly. This avoids a tiny final remainder step (for example,
    // 360 s with a 22 s maximum would otherwise end with an 8 s step).
    let effective_dt = options.duration_seconds / steps as f64;
    let mut temperature = options.initial_temperature_field_c.clone().unwrap_or_else(|| {
''',
        "uniform transient partition",
    )

    replace_once(
        thermal,
        '''        let elapsed_before = step as f64 * options.time_step_seconds;
        let dt = options
            .time_step_seconds
            .min(options.duration_seconds - elapsed_before)
            .max(1e-9);
        final_dt = dt;
''',
        '''        let elapsed_before = step as f64 * effective_dt;
        let dt = effective_dt;
        final_dt = dt;
''',
        "remove short remainder timestep",
    )

    replace_once(
        thermal,
        '''        let time = (elapsed_before + dt).min(options.duration_seconds);
''',
        '''        let time = if step + 1 == steps {
            options.duration_seconds
        } else {
            elapsed_before + dt
        };
''',
        "exact fixed-duration endpoint",
    )

    text = thermal.read_text(encoding="utf-8")
    test_marker = "minute_scale_duration_uses_uniform_timestep_partition"
    if test_marker not in text:
        thermal.write_text(
            text.rstrip()
            + r'''

#[cfg(test)]
mod short_duration_stability_v1_tests {
    use super::*;

    fn short_options() -> ThermalOptions {
        ThermalOptions {
            transient: true,
            conductivity_w_m_k: [0.18, 0.18, 0.13],
            density_kg_m3: 1_240.0,
            specific_heat_j_kg_k: 1_800.0,
            conductivity_exponent: 1.0,
            ambient_temperature_c: 75.0,
            initial_temperature_c: 23.0,
            initial_temperature_field_c: None,
            cooled_temperature_c: 75.0,
            convection_w_m2_k: 18.0,
            emissivity: 0.9,
            heated_face: ThermalFace::ZMax,
            cooled_face: ThermalFace::ZMin,
            heat_power_w: 0.0,
            volumetric_power_w: 0.0,
            duration_seconds: 360.0,
            time_step_seconds: 22.0,
            fixed_surface_enabled: false,
            readiness_temperature_c: Some(73.0),
            readiness_hold_seconds: 3_600.0,
            readiness_cooling: false,
            stop_when_ready: false,
            tolerance: 1e-7,
        }
    }

    #[test]
    fn minute_scale_duration_uses_uniform_timestep_partition() {
        let grid = VoxelGrid::solid_box(3, 2, 2, 1.0);
        let material = vec![1.0; grid.cell_count()];
        let result = solve_thermal(&grid, &material, &short_options()).unwrap();
        assert_eq!(result.time_steps, 17);
        assert!((result.final_time_seconds - 360.0).abs() < 1e-12);
        let times: Vec<f64> = result.history.chunks_exact(3).map(|row| row[0]).collect();
        let expected = 360.0 / 17.0;
        for pair in times.windows(2) {
            assert!(((pair[1] - pair[0]) - expected).abs() < 1e-9);
        }
        assert!(result.readiness_complete_time_seconds.is_none());
    }

    #[test]
    fn screenshot_scale_residual_floor_accepts_machine_stagnation() {
        let active_cells = 56_629usize;
        let floor = NONLINEAR_ABS_RESIDUAL_W.max(
            NONLINEAR_ROUNDOFF_W_PER_SQRT_CELL * (active_cells as f64).sqrt(),
        );
        assert!(floor > 1.436e-7, "floor={floor:.3e}");
        assert!(floor < 1e-6, "floor must remain physically negligible: {floor:.3e}");
    }
}
'''
            + "\n",
            encoding="utf-8",
        )

    verified = thermal.read_text(encoding="utf-8")
    for contract in (
        "let effective_dt = options.duration_seconds / steps as f64",
        "NONLINEAR_ROUNDOFF_W_PER_SQRT_CELL",
        test_marker,
        "screenshot_scale_residual_floor_accepts_machine_stagnation",
    ):
        if contract not in verified:
            raise RuntimeError(f"Short-duration stability contract {contract!r} is missing")
    if "options.duration_seconds - elapsed_before" in verified:
        raise RuntimeError("Transient solver still contains a short final remainder step")

    (source_root / SOURCE_MARKER).write_text(MARKER + "\n", encoding="utf-8")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
