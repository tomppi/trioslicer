#!/usr/bin/env python3
"""Support fixed-duration and spatially partial annealing results.

The whole-part readiness/soak threshold is diagnostic, not a prerequisite for
returning the final transient field. Cooling can be seeded from the exact
nonuniform end-of-exposure cell temperatures.
"""

from __future__ import annotations

import pathlib
import re


MARKER = "EnderSlicer fixed-duration partial annealing v1"
SOURCE_MARKER = ".enderslicer-annealing-partial-duration-v1"


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
    client = source_root / "web/src/engine/EngineClient.ts"
    for path in (thermal, wasm, client):
        if not path.is_file():
            raise RuntimeError(f"Partial-annealing target is missing: {path}")

    replace_once(
        thermal,
        """    pub ambient_temperature_c: f64,
    pub initial_temperature_c: f64,
    pub cooled_temperature_c: f64,
""",
        """    pub ambient_temperature_c: f64,
    pub initial_temperature_c: f64,
    /// Optional exact cell field for a chained transient stage. When present,
    /// this overrides the scalar initial temperature and must match the voxel
    /// grid. It lets cooling start from the real partial-heating result.
    pub initial_temperature_field_c: Option<Vec<f64>>,
    pub cooled_temperature_c: f64,
""",
        "thermal initial field option",
    )

    replace_once(
        thermal,
        """    if !options.convection_w_m2_k.is_finite()
""",
        """    if let Some(field) = &options.initial_temperature_field_c {
        if !options.transient {
            return Err("an initial temperature field requires a transient solve".into());
        }
        if field.len() != grid.cell_count() {
            return Err("initial temperature field does not match the voxel grid".into());
        }
        if field.iter().any(|value| {
            !value.is_finite() || *value <= MIN_ABSOLUTE_TEMPERATURE_C || *value > 1_500.0
        }) {
            return Err("initial temperature field contains an unsupported value".into());
        }
    }
    if !options.convection_w_m2_k.is_finite()
""",
        "initial field validation",
    )

    replace_once(
        thermal,
        """    let steps = (options.duration_seconds / options.time_step_seconds).ceil() as usize;
    let mut temperature = vec![options.initial_temperature_c; grid.cell_count()];
""",
        """    let steps = (options.duration_seconds / options.time_step_seconds).ceil() as usize;
    let mut temperature = options.initial_temperature_field_c.clone().unwrap_or_else(|| {
        vec![options.initial_temperature_c; grid.cell_count()]
    });
""",
        "transient initial field selection",
    )

    replace_once(
        thermal,
        """                if time - started + 1e-9 >= options.readiness_hold_seconds {
                    readiness_complete_time_seconds = Some(time);
                    if options.stop_when_ready {
                        break;
                    }
                }
""",
        """                if readiness_complete_time_seconds.is_none()
                    && time - started + 1e-9 >= options.readiness_hold_seconds
                {
                    // Preserve the first completion instant even when a
                    // fixed-duration run continues beyond readiness.
                    readiness_complete_time_seconds = Some(time);
                    if options.stop_when_ready {
                        break;
                    }
                }
""",
        "first readiness completion time",
    )

    text = thermal.read_text(encoding="utf-8")
    text, count = re.subn(
        r"(?m)^(\s*)initial_temperature_c:\s*([^\n]+),\s*\n(\s*)cooled_temperature_c:",
        r"\1initial_temperature_c: \2,\n\1initial_temperature_field_c: None,\n\3cooled_temperature_c:",
        text,
    )
    if count < 2:
        raise RuntimeError(f"Expected ThermalOptions constructors, patched {count}")
    thermal.write_text(text, encoding="utf-8")

    # Deterministic chained-stage regressions.
    test_marker = "partial_duration_nonuniform_initial_field"
    thermal_text = thermal.read_text(encoding="utf-8")
    if test_marker not in thermal_text:
        thermal.write_text(
            thermal_text.rstrip()
            + r'''

#[cfg(test)]
mod partial_duration_v1_tests {
    use super::*;

    fn options(cell_count: usize) -> ThermalOptions {
        ThermalOptions {
            transient: true,
            conductivity_w_m_k: [0.18, 0.18, 0.13],
            density_kg_m3: 1_240.0,
            specific_heat_j_kg_k: 1_800.0,
            conductivity_exponent: 1.0,
            ambient_temperature_c: 23.0,
            initial_temperature_c: 23.0,
            initial_temperature_field_c: Some(
                (0..cell_count).map(|index| if index == 0 { 75.0 } else { 25.0 }).collect()
            ),
            cooled_temperature_c: 23.0,
            convection_w_m2_k: 8.0,
            emissivity: 0.9,
            heated_face: ThermalFace::ZMax,
            cooled_face: ThermalFace::ZMin,
            heat_power_w: 0.0,
            volumetric_power_w: 0.0,
            duration_seconds: 2.0,
            time_step_seconds: 1.0,
            fixed_surface_enabled: false,
            readiness_temperature_c: Some(45.0),
            readiness_hold_seconds: 0.0,
            readiness_cooling: true,
            stop_when_ready: false,
            tolerance: 1e-7,
        }
    }

    #[test]
    fn partial_duration_nonuniform_initial_field() {
        let grid = VoxelGrid::solid_box(2, 1, 1, 1.0);
        let material = vec![1.0; grid.cell_count()];
        let result = solve_thermal(&grid, &material, &options(grid.cell_count())).unwrap();
        assert_eq!(result.final_time_seconds, 2.0);
        assert!(result.maximum_temperature_c < 75.0);
        assert!(result.minimum_temperature_c > 23.0);
    }

    #[test]
    fn partial_duration_rejects_wrong_initial_field_size() {
        let grid = VoxelGrid::solid_box(2, 1, 1, 1.0);
        let material = vec![1.0; grid.cell_count()];
        let error = solve_thermal(&grid, &material, &options(1)).unwrap_err();
        assert!(error.contains("initial temperature field does not match"));
    }

    #[test]
    fn fixed_duration_preserves_first_readiness_completion_time() {
        let grid = VoxelGrid::solid_box(1, 1, 1, 1.0);
        let material = vec![1.0; grid.cell_count()];
        let mut opts = options(grid.cell_count());
        opts.initial_temperature_field_c = Some(vec![75.0]);
        opts.ambient_temperature_c = 75.0;
        opts.readiness_temperature_c = Some(70.0);
        opts.readiness_cooling = false;
        opts.readiness_hold_seconds = 2.0;
        opts.duration_seconds = 5.0;
        let result = solve_thermal(&grid, &material, &opts).unwrap();
        assert_eq!(result.final_time_seconds, 5.0);
        assert_eq!(result.readiness_complete_time_seconds, Some(3.0));
    }
}
'''
            + "\n",
            encoding="utf-8",
        )

    replace_once(
        wasm,
        """    ambient_temperature_c: f64,
    initial_temperature_c: f64,
    cooled_temperature_c: f64,
""",
        """    ambient_temperature_c: f64,
    initial_temperature_c: f64,
    initial_temperature_field_c: Option<Vec<f64>>,
    cooled_temperature_c: f64,
""",
        "WASM initial field option",
    )
    replace_once(
        wasm,
        """            ambient_temperature_c: 23.0,
            initial_temperature_c: 23.0,
            cooled_temperature_c: 23.0,
""",
        """            ambient_temperature_c: 23.0,
            initial_temperature_c: 23.0,
            initial_temperature_field_c: None,
            cooled_temperature_c: 23.0,
""",
        "WASM initial field default",
    )
    wasm_text = wasm.read_text(encoding="utf-8")
    wasm_text, wasm_count = re.subn(
        r"(?m)^(\s*)initial_temperature_c:\s*opts\.initial_temperature_c,\s*$",
        r"\1initial_temperature_c: opts.initial_temperature_c,\n"
        r"\1initial_temperature_field_c: opts.initial_temperature_field_c.clone(),",
        wasm_text,
    )
    if wasm_count != 2:
        raise RuntimeError(f"Expected two WASM ThermalOptions constructors, patched {wasm_count}")
    wasm.write_text(wasm_text, encoding="utf-8")

    replace_once(
        client,
        """  ambientTemperatureC: number;
  initialTemperatureC: number;
  cooledTemperatureC: number;
""",
        """  ambientTemperatureC: number;
  initialTemperatureC: number;
  /** Optional exact cell temperatures for a chained transient stage. */
  initialTemperatureFieldC?: number[] | null;
  cooledTemperatureC: number;
""",
        "TypeScript initial field option",
    )

    for path, contract in (
        (thermal, "initial_temperature_field_c: Option<Vec<f64>>"),
        (thermal, "fixed_duration_preserves_first_readiness_completion_time"),
        (thermal, test_marker),
        (wasm, "initial_temperature_field_c: opts.initial_temperature_field_c.clone()"),
        (client, "initialTemperatureFieldC?: number[] | null"),
    ):
        if contract not in path.read_text(encoding="utf-8"):
            raise RuntimeError(f"Partial-annealing contract {contract!r} is missing from {path}")

    (source_root / SOURCE_MARKER).write_text(MARKER + "\n", encoding="utf-8")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
