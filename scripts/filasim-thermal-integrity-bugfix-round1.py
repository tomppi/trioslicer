#!/usr/bin/env python3
"""Thermal Integrity bug-fix round 1.

Applied after the solver, voxel hardening, audit, progress and React-tab
transforms. This layer fixes correctness and lifecycle defects that can survive
compile-only validation: nonlinear non-convergence, invalid material ranges,
oversized mobile workloads and React workspace state when the model disappears.
"""

from __future__ import annotations

import pathlib


MARKER = "EnderSlicer thermal integrity bugfix round 1"
EVENT = "enderslicer-thermal-workspace"


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
    wasm = source_root / "crates/filasim-wasm/src/lib.rs"
    rail = source_root / "web/src/ui/StepRail.tsx"
    panel = source_root / "web/src/ui/StepPanel.tsx"
    topbar = source_root / "web/src/ui/TopBar.tsx"
    for path in (thermal, wasm, rail, panel, topbar):
        if not path.is_file():
            raise RuntimeError(f"Thermal bug-fix target is missing: {path}")

    replace_once(
        thermal,
        "const MIN_ABSOLUTE_TEMPERATURE_C: f64 = -273.15;\n",
        "const MIN_ABSOLUTE_TEMPERATURE_C: f64 = -273.15;\n"
        "/// Upper result limit accepted by the Android Thermal Integrity report.\n"
        "/// The reusable core solver itself remains valid above this value.\n"
        "pub const MAX_SUPPORTED_TEMPERATURE_C: f64 = 2_000.0;\n",
        "Android reporting temperature constant",
    )

    replace_once(
        thermal,
        '''    let mut total_iterations = 0usize;
    let mut residual = f64::INFINITY;
    for _ in 0..MAX_RADIATION_ITERS {
        let previous = temperature.clone();
        let (diag, rhs) = linear_system(system, options, &previous, None, None);
        let solved = pcg(system, &diag, &rhs, &previous, options.tolerance)?;
        total_iterations += solved.iterations;
        residual = solved.relative_residual;
        temperature = solved.values;
        let delta = max_difference(&temperature, &previous, &system.active);
        if delta <= 1e-5 {
            break;
        }
    }
    finish_result(
''',
        '''    let mut total_iterations = 0usize;
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
''',
        "steady nonlinear convergence enforcement",
    )

    replace_once(
        thermal,
        '''        let old = temperature.clone();
        last_old = old.clone();
        let mut guess = temperature.clone();
        for _ in 0..6 {
            let previous_guess = guess.clone();
            let (diag, rhs) = linear_system(system, options, &previous_guess, Some(dt), Some(&old));
            let solved = pcg(system, &diag, &rhs, &previous_guess, options.tolerance)?;
            total_iterations += solved.iterations;
            residual = solved.relative_residual;
            guess = solved.values;
            if max_difference(&guess, &previous_guess, &system.active) <= 1e-5 {
                break;
            }
        }
        temperature = guess;
''',
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
        "transient nonlinear convergence enforcement",
    )

    replace_once(
        thermal,
        '''    if rel <= tolerance {
        return Ok(LinearSolve { values: x, iterations: 0, relative_residual: rel });
    }

    let mut ap = vec![0.0; n];
''',
        '''    if rel <= tolerance {
        return Ok(LinearSolve { values: x, iterations: 0, relative_residual: rel });
    }
    if !rz.is_finite() || rz <= 0.0 {
        return Err("thermal preconditioner produced a non-positive residual product".into());
    }

    let mut ap = vec![0.0; n];
''',
        "initial PCG residual product validation",
    )

    replace_once(
        thermal,
        '''        let next_rz = dot(&r, &z, &system.active);
        let beta = next_rz / rz.max(1e-300);
''',
        '''        let next_rz = dot(&r, &z, &system.active);
        if !next_rz.is_finite() || next_rz <= 0.0 {
            return Err("thermal PCG lost positive definiteness in the preconditioned residual".into());
        }
        let beta = next_rz / rz;
''',
        "iterative PCG residual product validation",
    )

    replace_once(
        wasm,
        '''        let opts: ThermalIntegrityOpts = serde_json::from_str(opts_json).map_err(err)?;
        let emit_progress = |phase: &str, value: f64, detail: &str| {
''',
        '''        let opts: ThermalIntegrityOpts = serde_json::from_str(opts_json).map_err(err)?;
        if opts.mode != "steady" && opts.mode != "transient" {
            return Err(err("thermal integrity mode must be steady or transient"));
        }
        if opts.service_limit_c <= opts.reference_temperature_c {
            return Err(err("thermal material service limit must exceed its reference temperature"));
        }
        let emit_progress = |phase: &str, value: f64, detail: &str| {
''',
        "WASM option relationship validation",
    )

    replace_once(
        wasm,
        '''        let (grid, levels) = {
            let (grid, levels) = self.grid.as_ref().unwrap();
            (grid.clone(), *levels)
        };
        let heated_face =
''',
        '''        let (grid, levels) = {
            let (grid, levels) = self.grid.as_ref().unwrap();
            (grid.clone(), *levels)
        };
        const MAX_MOBILE_GRID_CELLS: usize = 2_000_000;
        const MAX_MOBILE_SOLID_CELLS: usize = 1_000_000;
        const MAX_TRANSIENT_CELL_STEPS: usize = 120_000_000;
        let grid_cells = grid.cell_count();
        let solid_cells = grid.solid_count();
        if grid_cells > MAX_MOBILE_GRID_CELLS || solid_cells > MAX_MOBILE_SOLID_CELLS {
            return Err(err(&format!(
                "thermal grid is too large for the Android safety budget: {solid_cells} solid / {grid_cells} total cells; reduce Smart Infill resolution"
            )));
        }
        if opts.mode == "transient" {
            let steps = (opts.duration_seconds / opts.time_step_seconds).ceil() as usize;
            let work = solid_cells.saturating_mul(steps);
            if work > MAX_TRANSIENT_CELL_STEPS {
                return Err(err(&format!(
                    "transient thermal workload is too large for Android: {solid_cells} solid cells × {steps} steps; increase voxel size or time step"
                )));
            }
        }
        let heated_face =
''',
        "Android workload safety budget",
    )

    replace_once(
        wasm,
        '''        let thermal =
            filasim_core::thermal::solve_thermal(&grid, &material_fraction, &thermal_options)
                .map_err(err)?;
        emit_progress(
''',
        '''        let thermal =
            filasim_core::thermal::solve_thermal(&grid, &material_fraction, &thermal_options)
                .map_err(err)?;
        if thermal.maximum_temperature_c
            > filasim_core::thermal::MAX_SUPPORTED_TEMPERATURE_C
        {
            return Err(err("thermal result exceeds the Android reportable maximum of 2000 °C"));
        }
        emit_progress(
''',
        "Android reportable temperature ceiling",
    )

    replace_once(
        wasm,
        '''        .map_err(err)?;
        emit_progress(
            "Post-processing",
''',
        '''        .map_err(err)?;
        if !solution.converged {
            return Err(err(&format!(
                "thermal structural FEA did not converge ({} iterations, relative residual {:.3e})",
                solution.iterations,
                solution.rel_residual,
            )));
        }
        emit_progress(
            "Post-processing",
''',
        "structural convergence enforcement",
    )

    lifecycle_effect = f'''  useEffect(() => {{
    if (!s.model && thermalActive) {{
      window.dispatchEvent(new CustomEvent<boolean>("{EVENT}", {{ detail: false }}));
    }}
  }}, [s.model, thermalActive]);

'''
    replace_once(
        rail,
        '''  const buildsim = s.appMode === "buildsim";
''',
        lifecycle_effect + '''  const buildsim = s.appMode === "buildsim";
''',
        "StepRail missing-model Thermal exit",
    )
    replace_once(
        panel,
        '''      unitRev: s.unitRev,
    }))
  );
  const buildsim = s.appMode === "buildsim";
''',
        '''      unitRev: s.unitRev,
    }))
  );
''' + lifecycle_effect + '''  const buildsim = s.appMode === "buildsim";
''',
        "StepPanel missing-model Thermal exit",
    )

    replace_once(
        topbar,
        '''  const onLoad = async (f: File | undefined) => {
    if (f) {
''',
        f'''  const onLoad = async (f: File | undefined) => {{
    if (f) {{
      window.dispatchEvent(new CustomEvent<boolean>("{EVENT}", {{ detail: false }}));
''',
        "TopBar model-load Thermal exit",
    )

    marker = source_root / ".enderslicer-thermal-integrity-bugfix-round1"
    marker.write_text(MARKER + "\n", encoding="utf-8")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
