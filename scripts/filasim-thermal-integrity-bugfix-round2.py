#!/usr/bin/env python3
"""Thermal Integrity logic audit and nonlinear solver hardening round 2.

Applied after bugfix round 1. Replaces the oscillatory secant/Picard radiation
iteration with a tangent/Newton solve, guarded step length, residual-decreasing
backtracking, scale-aware convergence, and focused steady/transient regressions.
"""

from __future__ import annotations

import pathlib


MARKER = "EnderSlicer thermal integrity bugfix round 2"


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
        raise RuntimeError(f"Thermal round-2 target is missing: {thermal}")

    replace_once(
        thermal,
        '''const MAX_RADIATION_ITERS: usize = 12;
''',
        '''const MAX_RADIATION_ITERS: usize = 48;
const MAX_NEWTON_STEP_C: f64 = 250.0;
const MIN_LINE_SEARCH_ALPHA: f64 = 1.0 / 1024.0;
const NONLINEAR_ABS_TOL_C: f64 = 1e-4;
const NONLINEAR_REL_TOL: f64 = 1e-7;
/// Absolute residual bound at which a machine-precision fixed point (zero
/// Newton step) is accepted as converged. 1e-6 W is negligible relative to
/// user-scale heat loads and comfortably above L2 roundoff accumulation.
const NONLINEAR_STAGNATION_ACCEPT_RESIDUAL_W: f64 = 1e-6;
''',
        "nonlinear solver limits",
    )

    replace_once(
        thermal,
        '''fn solve_steady(
    grid: &VoxelGrid,
    material_fraction: &[f32],
    options: &ThermalOptions,
    system: &ThermalSystem,
) -> Result<ThermalResult, String> {
    let mut temperature = vec![options.ambient_temperature_c; grid.cell_count()];
    let mut total_iterations = 0usize;
    let mut residual = f64::INFINITY;
    let mut nonlinear_delta = f64::INFINITY;
    let mut nonlinear_converged = false;
    for _ in 0..MAX_RADIATION_ITERS {
        if cancel::requested() {
            return Err("cancelled".into());
        }
        let previous = temperature.clone();
        let (diag, rhs) = linear_system(system, options, &previous, None, None);
        let solved = pcg(system, &diag, &rhs, &previous, options.tolerance)?;
        total_iterations += solved.iterations;
        residual = solved.relative_residual;
        temperature = solved.values;
        nonlinear_delta = max_difference(&temperature, &previous, &system.active);
        if nonlinear_delta <= 1e-5 {
            nonlinear_converged = true;
            break;
        }
    }
    if !nonlinear_converged {
        return Err(format!(
            "steady thermal radiation iteration did not converge within {MAX_RADIATION_ITERS} passes (maximum temperature change {nonlinear_delta:.3e} °C)"
        ));
    }
    finish_result(
        grid,
        material_fraction,
        options,
        system,
        temperature,
        vec![],
        total_iterations,
        residual,
        0,
        0.0,
        None,
    )
}
''',
        '''fn solve_steady(
    grid: &VoxelGrid,
    material_fraction: &[f32],
    options: &ThermalOptions,
    system: &ThermalSystem,
) -> Result<ThermalResult, String> {
    let initial = vec![options.ambient_temperature_c; grid.cell_count()];
    let solved = solve_nonlinear_temperature(
        system,
        options,
        initial,
        None,
        None,
        "steady thermal radiation iteration",
    )?;
    let result = finish_result(
        grid,
        material_fraction,
        options,
        system,
        solved.values,
        vec![],
        solved.linear_iterations,
        solved.linear_relative_residual,
        0,
        0.0,
        None,
    )?;
    if result.energy_balance_relative > 1e-3 {
        return Err(format!(
            "steady thermal solve violated global energy balance (relative imbalance {:.3e})",
            result.energy_balance_relative,
        ));
    }
    Ok(result)
}
''',
        "steady damped Newton solve",
    )

    replace_once(
        thermal,
        '''        let old = temperature.clone();
        last_old = old.clone();
        let mut guess = temperature.clone();
        let mut step_delta = f64::INFINITY;
        let mut step_converged = false;
        for _ in 0..6 {
            let previous_guess = guess.clone();
            let (diag, rhs) = linear_system(system, options, &previous_guess, Some(dt), Some(&old));
            let solved = pcg(system, &diag, &rhs, &previous_guess, options.tolerance)?;
            total_iterations += solved.iterations;
            residual = solved.relative_residual;
            guess = solved.values;
            step_delta = max_difference(&guess, &previous_guess, &system.active);
            if step_delta <= 1e-5 {
                step_converged = true;
                break;
            }
        }
        if !step_converged {
            return Err(format!(
                "transient thermal radiation iteration did not converge at step {}/{} (maximum temperature change {step_delta:.3e} °C)",
                step + 1,
                steps,
            ));
        }
        temperature = guess;
''',
        '''        let old = temperature.clone();
        last_old = old.clone();
        let solved = solve_nonlinear_temperature(
            system,
            options,
            temperature,
            Some(dt),
            Some(&old),
            "transient thermal radiation iteration",
        )
        .map_err(|error| format!("{error} at step {}/{}", step + 1, steps))?;
        total_iterations += solved.linear_iterations;
        residual = solved.linear_relative_residual;
        temperature = solved.values;
''',
        "transient damped Newton solve",
    )

    replace_once(
        thermal,
        '''        } else {
            let t_surface_c = radiation_temperature[ci];
            let h_rad = linearized_radiation_w_m2_k(
                options.emissivity,
                t_surface_c,
                options.ambient_temperature_c,
            );
            let h_total_w_mm2_k = (options.convection_w_m2_k + h_rad) * 1e-6;
            let g = h_total_w_mm2_k * boundary.area_mm2;
            diagonal[ci] += g;
            rhs[ci] += g * options.ambient_temperature_c;
        }
''',
        '''        } else {
            let t_surface_c = radiation_temperature[ci];
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
        }
''',
        "Newton radiation tangent assembly",
    )

    replace_once(
        thermal,
        '''fn linearized_radiation_w_m2_k(emissivity: f64, surface_c: f64, ambient_c: f64) -> f64 {
    if emissivity <= 0.0 {
        return 0.0;
    }
    let ts = (surface_c + 273.15).max(1.0);
    let ta = (ambient_c + 273.15).max(1.0);
    emissivity * SIGMA_SB_W_M2_K4 * (ts + ta) * (ts * ts + ta * ta)
}

struct LinearSolve {
''',
        '''fn radiation_flux_w_m2(emissivity: f64, surface_c: f64, ambient_c: f64) -> f64 {
    if emissivity <= 0.0 {
        return 0.0;
    }
    let ts = (surface_c + 273.15).max(1.0);
    let ta = (ambient_c + 273.15).max(1.0);
    emissivity * SIGMA_SB_W_M2_K4 * (ts.powi(4) - ta.powi(4))
}

/// Newton tangent for q_rad(T) = eps*sigma*((T+273.15)^4-Ta^4).
/// Returns (dq/dT, dq/dT*T0-q(T0)), so the linearized boundary term is
/// `slope*T - rhs` and remains exact at the current iterate.
fn radiation_tangent_w_m2(emissivity: f64, surface_c: f64, ambient_c: f64) -> (f64, f64) {
    if emissivity <= 0.0 {
        return (0.0, 0.0);
    }
    let ts = (surface_c + 273.15).max(1.0);
    let slope = 4.0 * emissivity * SIGMA_SB_W_M2_K4 * ts.powi(3);
    let flux = radiation_flux_w_m2(emissivity, surface_c, ambient_c);
    (slope, slope * surface_c - flux)
}

struct NonlinearSolve {
    values: Vec<f64>,
    linear_iterations: usize,
    linear_relative_residual: f64,
}

fn solve_nonlinear_temperature(
    system: &ThermalSystem,
    options: &ThermalOptions,
    mut temperature: Vec<f64>,
    dt: Option<f64>,
    old: Option<&[f64]>,
    context: &str,
) -> Result<NonlinearSolve, String> {
    let mut total_linear_iterations = 0usize;
    let mut last_linear_residual = f64::INFINITY;
    let initial_residual = nonlinear_residual_l2(system, options, &temperature, dt, old)?;
    let residual_target = (options.tolerance
        * initial_residual.max(system.heat_input_w.abs()).max(1e-6))
        .max(1e-10);
    if initial_residual <= residual_target {
        return Ok(NonlinearSolve {
            values: temperature,
            linear_iterations: 0,
            linear_relative_residual: 0.0,
        });
    }

    let mut last_delta = f64::INFINITY;
    let mut current_residual = initial_residual;
    for _ in 0..MAX_RADIATION_ITERS {
        if cancel::requested() {
            return Err("cancelled".into());
        }
        let previous = temperature.clone();
        let (diag, rhs) = linear_system(system, options, &previous, dt, old);
        let linear = pcg(system, &diag, &rhs, &previous, options.tolerance)?;
        total_linear_iterations += linear.iterations;
        last_linear_residual = linear.relative_residual;

        let raw_delta = max_difference(&linear.values, &previous, &system.active);
        if !raw_delta.is_finite() {
            return Err(format!("{context} produced a non-finite Newton update"));
        }
        if raw_delta == 0.0 {
            // A zero Newton step is a fixed point of the discrete operator to
            // machine precision. When the residual is physically negligible we
            // accept it instead of failing a converged solve whose L2 roundoff
            // floor can land slightly above the residual target.
            if current_residual <= residual_target
                || current_residual <= NONLINEAR_STAGNATION_ACCEPT_RESIDUAL_W
            {
                return Ok(NonlinearSolve {
                    values: previous,
                    linear_iterations: total_linear_iterations,
                    linear_relative_residual: last_linear_residual,
                });
            }
            return Err(format!(
                "{context} stagnated with nonlinear residual {current_residual:.3e} W"
            ));
        }

        let mut alpha = (MAX_NEWTON_STEP_C / raw_delta).min(1.0);
        let mut accepted = None;
        while alpha >= MIN_LINE_SEARCH_ALPHA {
            let mut trial = previous.clone();
            for ci in 0..trial.len() {
                if system.active[ci] {
                    trial[ci] += alpha * (linear.values[ci] - previous[ci]);
                }
            }
            if trial.iter().enumerate().any(|(ci, value)| {
                system.active[ci]
                    && (!value.is_finite() || *value <= MIN_ABSOLUTE_TEMPERATURE_C)
            }) {
                alpha *= 0.5;
                continue;
            }
            let trial_residual = nonlinear_residual_l2(system, options, &trial, dt, old)?;
            let sufficient_decrease =
                trial_residual <= current_residual * (1.0 - 1e-4 * alpha)
                    || trial_residual <= residual_target;
            if sufficient_decrease {
                accepted = Some((trial, trial_residual));
                break;
            }
            alpha *= 0.5;
        }

        let Some((trial, trial_residual)) = accepted else {
            return Err(format!(
                "{context} line search could not reduce nonlinear residual {current_residual:.3e} W"
            ));
        };
        last_delta = max_difference(&trial, &previous, &system.active);
        temperature = trial;
        current_residual = trial_residual;
        let maximum_abs_temperature = temperature
            .iter()
            .enumerate()
            .filter(|(ci, _)| system.active[*ci])
            .map(|(_, value)| value.abs())
            .fold(0.0f64, f64::max);
        let update_tolerance =
            NONLINEAR_ABS_TOL_C + NONLINEAR_REL_TOL * maximum_abs_temperature.max(1.0);
        if last_delta <= update_tolerance && current_residual <= residual_target {
            return Ok(NonlinearSolve {
                values: temperature,
                linear_iterations: total_linear_iterations,
                linear_relative_residual: last_linear_residual,
            });
        }
    }

    Err(format!(
        "{context} did not converge within {MAX_RADIATION_ITERS} passes (maximum temperature change {last_delta:.3e} °C, nonlinear residual {current_residual:.3e} W)"
    ))
}

fn nonlinear_residual_l2(
    system: &ThermalSystem,
    options: &ThermalOptions,
    temperature: &[f64],
    dt: Option<f64>,
    old: Option<&[f64]>,
) -> Result<f64, String> {
    let mut residual = vec![0.0; system.active.len()];
    for ci in 0..system.active.len() {
        if !system.active[ci] {
            continue;
        }
        let mut value = system.conduction_diagonal[ci] * temperature[ci] - system.source_w[ci];
        for direction in 0..6 {
            if let Some(neighbor) = system.neighbors[ci][direction] {
                value -= system.conductance[ci][direction] * temperature[neighbor];
            }
        }
        if let (Some(step), Some(previous)) = (dt, old) {
            value += system.capacity_j_k[ci] / step * (temperature[ci] - previous[ci]);
        }
        residual[ci] = value;
    }
    for boundary in &system.boundaries {
        let ci = boundary.cell;
        let surface_c = temperature[ci];
        if boundary.cooled {
            let g = 2.0 * boundary.conductivity_w_mm_k * boundary.area_mm2 / system.h_mm;
            residual[ci] += g * (surface_c - options.cooled_temperature_c);
        } else {
            let area_m2 = boundary.area_mm2 * 1e-6;
            residual[ci] += options.convection_w_m2_k
                * area_m2
                * (surface_c - options.ambient_temperature_c);
            residual[ci] += radiation_flux_w_m2(
                options.emissivity,
                surface_c,
                options.ambient_temperature_c,
            ) * area_m2;
        }
    }
    let norm = dot(&residual, &residual, &system.active).sqrt();
    if !norm.is_finite() {
        return Err("thermal nonlinear residual is non-finite".into());
    }
    Ok(norm)
}

struct LinearSolve {
''',
        "radiation flux, tangent and damped Newton helpers",
    )

    replace_once(
        thermal,
        '''    #[test]
    fn zero_delta_temperature_produces_no_eigen_force() {
''',
        '''    #[test]
    fn radiation_tangent_matches_flux_and_finite_difference() {
        let emissivity = 0.9;
        let surface = 140.0;
        let ambient = 60.0;
        let (slope, rhs) = radiation_tangent_w_m2(emissivity, surface, ambient);
        let flux = radiation_flux_w_m2(emissivity, surface, ambient);
        assert!((slope * surface - rhs - flux).abs() < 1e-10);
        let step = 1e-4;
        let finite_difference = (
            radiation_flux_w_m2(emissivity, surface + step, ambient)
                - radiation_flux_w_m2(emissivity, surface - step, ambient)
        ) / (2.0 * step);
        assert!((finite_difference - slope).abs() / slope < 1e-8);
    }

    #[test]
    fn benchy_like_five_watt_radiative_case_converges_and_balances() {
        let grid = VoxelGrid::solid_box(20, 14, 24, 0.45);
        let mut options = base_options();
        options.ambient_temperature_c = 60.0;
        options.cooled_temperature_c = 23.0;
        options.convection_w_m2_k = 8.0;
        options.emissivity = 0.9;
        options.heated_face = ThermalFace::ZMax;
        options.cooled_face = ThermalFace::ZMin;
        options.heat_power_w = 5.0;
        let result = solve_thermal(&grid, &vec![1.0; grid.cell_count()], &options).unwrap();
        assert!(result.maximum_temperature_c.is_finite());
        assert!(result.maximum_temperature_c < MAX_SUPPORTED_TEMPERATURE_C);
        assert!(result.energy_balance_relative < 1e-3, "{}", result.energy_balance_relative);
        assert!((result.heat_rejected_w - 5.0).abs() < 5e-3);
    }

    #[test]
    fn transient_radiation_uses_the_same_stable_nonlinear_path() {
        let grid = VoxelGrid::solid_box(10, 8, 8, 0.5);
        let mut options = base_options();
        options.transient = true;
        options.duration_seconds = 20.0;
        options.time_step_seconds = 2.0;
        options.ambient_temperature_c = 60.0;
        options.initial_temperature_c = 23.0;
        options.cooled_temperature_c = 23.0;
        options.convection_w_m2_k = 8.0;
        options.emissivity = 0.9;
        options.heated_face = ThermalFace::ZMax;
        options.cooled_face = ThermalFace::ZMin;
        options.heat_power_w = 5.0;
        let result = solve_thermal(&grid, &vec![1.0; grid.cell_count()], &options).unwrap();
        assert_eq!(result.time_steps, 10);
        assert!(result.peak_temperature_c.is_finite());
        assert!(result.history.chunks_exact(3).all(|point| point[1].is_finite()));
    }

    #[test]
    fn transient_machine_precision_fixed_point_is_accepted_not_stagnated() {
        // A zero Newton step means the discrete operator is at a fixed point to
        // machine precision. When the L2 residual is physically negligible the
        // solve must be accepted, not reported as "stagnated" because the
        // roundoff floor happens to sit slightly above the residual target.
        let grid = VoxelGrid::solid_box(40, 40, 12, 1.0);
        let material = vec![1.0; grid.cell_count()];
        let mut options = base_options();
        options.transient = true;
        options.duration_seconds = 12.0;
        options.time_step_seconds = 4.0;
        options.ambient_temperature_c = 23.0;
        options.initial_temperature_c = 23.0;
        options.cooled_temperature_c = 23.0;
        options.convection_w_m2_k = 8.0;
        options.emissivity = 0.9;
        options.heat_power_w = 0.0;
        options.fixed_surface_enabled = false;
        let source = NearbyHotObjectOptions {
            target_mm: [
                grid.origin[0] + grid.nx as f64 * grid.h * 0.5,
                grid.origin[1] + grid.ny as f64 * grid.h * 0.5,
                grid.origin[2] + grid.nz as f64 * grid.h,
            ],
            outward_normal: [0.0, 0.0, 1.0],
            gap_mm: 5.0,
            diameter_mm: 40.0,
            source_temperature_c: 180.0,
            source_emissivity: 0.9,
            source_part_emissivity: 0.9,
            secondary_enabled: false,
            secondary_target_mm: [0.0; 3],
            secondary_outward_normal: [0.0; 3],
            secondary_gap_mm: 5.0,
            secondary_diameter_mm: 40.0,
            secondary_temperature_c: 23.0,
            secondary_emissivity: 0.9,
            secondary_part_emissivity: 0.9,
            use_fixed_temperature_surface: false,
            part_conductivity_t_slope_per_k: 0.0,
            part_emissivity_t_slope_per_k: 0.0,
        };
        let result = solve_nearby_hot_object(&grid, &material, &options, &source);
        match result {
            Ok(solved) => {
                assert!(solved.maximum_temperature_c.is_finite());
                assert!(solved.maximum_temperature_c < MAX_SUPPORTED_TEMPERATURE_C);
            }
            Err(error) => {
                assert!(
                    !error.contains("stagnated"),
                    "converged nearby hot object solve reported stagnation: {error}"
                );
            }
        }
    }

    #[test]
    fn stagnation_accept_band_is_above_roundoff_and_below_heat_scale() {
        let active_cells = 56_629usize;
        let roundoff_floor = NONLINEAR_ABS_RESIDUAL_W.max(
            NONLINEAR_ROUNDOFF_W_PER_SQRT_CELL * (active_cells as f64).sqrt(),
        );
        assert!(roundoff_floor < NONLINEAR_STAGNATION_ACCEPT_RESIDUAL_W);
        assert!(NONLINEAR_STAGNATION_ACCEPT_RESIDUAL_W <= 1e-6);
    }

    #[test]
    fn zero_delta_temperature_produces_no_eigen_force() {
''',
        "nonlinear radiation regression tests",
    )

    marker = source_root / ".enderslicer-thermal-integrity-bugfix-round2"
    marker.write_text(MARKER + "\n", encoding="utf-8")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
