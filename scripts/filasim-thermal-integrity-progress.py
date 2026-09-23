#!/usr/bin/env python3
"""Add phase and live residual progress to the generated Thermal Integrity solver."""

from __future__ import annotations

import pathlib


MARKER = "EnderSlicer thermal integrity progress v2"


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
    worker = source_root / "web/src/worker/engine.worker.ts"
    if not thermal.is_file() or not wasm.is_file() or not worker.is_file():
        raise RuntimeError("Apply the thermal solver transforms before progress instrumentation")

    # filaSim's existing shared progress sink remains writable while WASM has
    # blocked the worker message loop. Publish a bounded residual trace from
    # the custom thermal PCG so Android can prove the solve is still advancing.
    replace_once(
        thermal,
        '''use crate::cancel;
''',
        '''use crate::{cancel, progress};
''',
        "thermal progress module import",
    )
    replace_once(
        thermal,
        '''    let mut rel = dot(&r, &r, &system.active).sqrt() / rhs_norm;
    if !rel.is_finite() {
        return Err("thermal solver residual is non-finite".into());
    }
''',
        '''    let mut rel = dot(&r, &r, &system.active).sqrt() / rhs_norm;
    if !rel.is_finite() {
        return Err("thermal solver residual is non-finite".into());
    }
    // Publish the initial residual and then one sample every eight PCG
    // iterations. At 4,000 iterations this remains well below filaSim's 2,048
    // slot shared buffer while still updating several times per second.
    let mut residual_trace = vec![rel as f32];
    progress::publish(&residual_trace);
''',
        "thermal initial residual publication",
    )
    replace_once(
        thermal,
        '''        if !rel.is_finite() {
            return Err("thermal solver diverged to a non-finite residual".into());
        }
        if rel <= tolerance {
''',
        '''        if !rel.is_finite() {
            return Err("thermal solver diverged to a non-finite residual".into());
        }
        if iteration == 1 || iteration % 8 == 0 || rel <= tolerance {
            residual_trace.push(rel as f32);
            progress::publish(&residual_trace);
        }
        if rel <= tolerance {
''',
        "thermal iterative residual publication",
    )

    replace_once(
        wasm,
        '''    pub fn solve_thermal_integrity(&mut self, opts_json: &str) -> Result<js_sys::Array, JsValue> {
        let opts: ThermalIntegrityOpts = serde_json::from_str(opts_json).map_err(err)?;
        self.ensure_grid()?;
''',
        '''    pub fn solve_thermal_integrity(
        &mut self,
        opts_json: &str,
        progress: &js_sys::Function,
    ) -> Result<js_sys::Array, JsValue> {
        let opts: ThermalIntegrityOpts = serde_json::from_str(opts_json).map_err(err)?;
        let emit_progress = |phase: &str, value: f64, detail: &str| {
            let payload = serde_json::json!({
                "phase": phase,
                "progress": value.clamp(0.0, 1.0),
                "detail": detail,
            })
            .to_string();
            let _ = progress.call1(&JsValue::NULL, &JsValue::from(payload));
        };
        emit_progress("Preparing voxel model", 0.06, "Validating the active analysis grid");
        self.ensure_grid()?;
''',
        "thermal integrity WASM progress signature",
    )

    replace_once(
        wasm,
        '''        let thermal =
            filasim_core::thermal::solve_thermal(&grid, &material_fraction, &thermal_options)
                .map_err(err)?;

        let e0 = opts.youngs_modulus.clamp(1.0, 1.0e7);
''',
        '''        emit_progress(
            if opts.mode == "transient" { "Transient thermal conduction" } else { "Steady thermal conduction" },
            0.12,
            if opts.mode == "transient" { "Solving implicit heat-flow time steps" } else { "Solving nonlinear conduction, convection and radiation" },
        );
        let thermal =
            filasim_core::thermal::solve_thermal(&grid, &material_fraction, &thermal_options)
                .map_err(err)?;
        emit_progress(
            "Thermal field complete",
            0.68,
            &format!("{} active cells · {} thermal iterations", material_fraction.iter().filter(|value| **value > 0.0).count(), thermal.iterations),
        );

        let e0 = opts.youngs_modulus.clamp(1.0, 1.0e7);
''',
        "thermal solve phase progress",
    )

    replace_once(
        wasm,
        '''        let mut assembled = assemble(
''',
        '''        emit_progress("Assembling structural FEA", 0.74, "Applying thermal strain, supports and mechanical loads");
        let mut assembled = assemble(
''',
        "structural assembly progress",
    )

    replace_once(
        wasm,
        '''        let (solution, _compliance) = filasim_core::simp::solve_with_eps_cached(
''',
        '''        emit_progress("Solving structural FEA", 0.82, "Computing thermally reduced stiffness and displacement");
        let (solution, _compliance) = filasim_core::simp::solve_with_eps_cached(
''',
        "structural solve progress",
    )

    replace_once(
        wasm,
        '''        let von_mises = filasim_core::thermal::thermal_von_mises(
''',
        '''        emit_progress(
            "Post-processing",
            0.94,
            &format!("Structural solve: {} iterations", solution.iterations),
        );
        let von_mises = filasim_core::thermal::thermal_von_mises(
''',
        "thermal post-processing progress",
    )

    replace_once(
        wasm,
        '''        let array = js_sys::Array::new();
''',
        '''        emit_progress("Packaging results", 0.98, "Preparing temperature, displacement and material fields");
        let array = js_sys::Array::new();
''',
        "result packaging progress",
    )

    replace_once(
        worker,
        '''        const array = requireModel().solve_thermal_integrity(JSON.stringify(msg.opts));
''',
        '''        const array = requireModel().solve_thermal_integrity(
          JSON.stringify(msg.opts),
          (payload: string) => {
            let data: unknown = payload;
            try {
              data = JSON.parse(payload);
            } catch (_) {
              // Keep the raw payload so the Android UI can still show progress.
            }
            self.postMessage({ id: msg.id, progress: true, data });
          }
        );
''',
        "thermal worker progress callback",
    )

    marker = source_root / ".enderslicer-thermal-integrity-progress-v2"
    marker.write_text(MARKER + "\n", encoding="utf-8")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
