#!/usr/bin/env python3
"""Thermal Integrity linear fast path and roundoff-safe convergence.

Applied after bugfix round 2. Problems with zero emissivity are exactly linear,
so they must use one PCG solve without Newton step limiting. Nonlinear residuals
also receive a tiny absolute floor to avoid rejecting converged transient steps
because of floating-point roundoff.
"""

from __future__ import annotations

import pathlib


MARKER = "EnderSlicer thermal integrity linear fast path v1"


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
        raise RuntimeError(f"Thermal linear-fast-path target is missing: {thermal}")

    replace_once(
        thermal,
        '''const NONLINEAR_ABS_TOL_C: f64 = 1e-4;
const NONLINEAR_REL_TOL: f64 = 1e-7;
''',
        '''const NONLINEAR_ABS_TOL_C: f64 = 1e-4;
const NONLINEAR_REL_TOL: f64 = 1e-7;
/// Absolute whole-system residual floor. 1e-7 W is negligible relative to
/// user-scale heat loads but above accumulated floating-point cancellation.
const NONLINEAR_ABS_RESIDUAL_W: f64 = 1e-7;
''',
        "nonlinear absolute residual floor",
    )

    replace_once(
        thermal,
        '''    let mut total_linear_iterations = 0usize;
    let mut last_linear_residual = f64::INFINITY;
    let initial_residual = nonlinear_residual_l2(system, options, &temperature, dt, old)?;
''',
        '''    // With zero emissivity, conduction, convection, prescribed
    // temperature and backward-Euler storage are all linear. Solving that
    // system once is exact and avoids incorrectly limiting a legitimate large
    // temperature rise to MAX_NEWTON_STEP_C per pass.
    if options.emissivity <= 0.0 {
        let (diagonal, rhs) = linear_system(system, options, &temperature, dt, old);
        let linear = pcg(system, &diagonal, &rhs, &temperature, options.tolerance)?;
        return Ok(NonlinearSolve {
            values: linear.values,
            linear_iterations: linear.iterations,
            linear_relative_residual: linear.relative_residual,
        });
    }

    let mut total_linear_iterations = 0usize;
    let mut last_linear_residual = f64::INFINITY;
    let initial_residual = nonlinear_residual_l2(system, options, &temperature, dt, old)?;
''',
        "exact linear thermal fast path",
    )

    replace_once(
        thermal,
        '''    let residual_target = (options.tolerance
        * initial_residual.max(system.heat_input_w.abs()).max(1e-6))
        .max(1e-10);
''',
        '''    let residual_target = (options.tolerance
        * initial_residual.max(system.heat_input_w.abs()).max(1e-6))
        .max(NONLINEAR_ABS_RESIDUAL_W);
''',
        "roundoff-safe nonlinear residual target",
    )

    marker = source_root / ".enderslicer-thermal-integrity-linear-fast-path-v1"
    marker.write_text(MARKER + "\n", encoding="utf-8")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
