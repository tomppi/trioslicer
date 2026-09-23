#!/usr/bin/env python3
"""Thermal Integrity physical-boundary, validity, and 3D result correction.

Applied after the nonlinear and linear-fast-path transforms. The selected
heater direction now means every exterior face with that outward voxel normal,
not only the tiny global extreme. The heater is a contact input, so it does not
also reject its own power directly by convection/radiation. The transform also
adds an exact boundary preflight, suppresses structural post-processing outside
the material property range, maps cell temperatures to the real STL soup, and
reuses filaSim's normal scalar/deformed 3D result viewer.
"""

from __future__ import annotations

import pathlib

MARKER = "EnderSlicer thermal integrity physical model v1"
THERMAL_RESULT_EVENT = "enderslicer-thermal-result-3d"
THERMAL_CLEAR_EVENT = "enderslicer-thermal-clear-3d"


def replace_once(path: pathlib.Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one {label} in {path}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def append_once(path: pathlib.Path, marker: str, text: str) -> None:
    current = path.read_text(encoding="utf-8")
    if marker in current:
        return
    path.write_text(current.rstrip() + "\n\n" + text.strip() + "\n", encoding="utf-8")


def apply(source_root: pathlib.Path) -> None:
    source_root = source_root.resolve()
    thermal = source_root / "crates/filasim-core/src/thermal.rs"
    wasm = source_root / "crates/filasim-wasm/src/lib.rs"
    client = source_root / "web/src/engine/EngineClient.ts"
    protocol = source_root / "web/src/engine/EngineProtocol.ts"
    worker = source_root / "web/src/worker/engine.worker.ts"
    viewer = source_root / "web/src/viewer/Viewer.tsx"
    for path in (thermal, wasm, client, protocol, worker, viewer):
        if not path.is_file():
            raise RuntimeError(f"Thermal physical-model target is missing: {path}")

    replace_once(
        thermal,
        "#[derive(Clone, Debug)]\npub struct ThermalOptions {\n",
        """#[derive(Clone, Debug)]
pub struct ThermalBoundarySummary {
    pub heated_area_mm2: f64,
    pub cooled_area_mm2: f64,
    pub effective_heat_flux_w_m2: f64,
}

#[derive(Clone, Debug)]
pub struct ThermalOptions {
""",
        "thermal boundary summary type",
    )

    replace_once(
        thermal,
        """pub fn solve_thermal(
    grid: &VoxelGrid,
    material_fraction: &[f32],
    options: &ThermalOptions,
) -> Result<ThermalResult, String> {
""",
        """pub fn thermal_boundary_summary(
    grid: &VoxelGrid,
    material_fraction: &[f32],
    options: &ThermalOptions,
) -> Result<ThermalBoundarySummary, String> {
    validate(grid, material_fraction, options)?;
    let system = build_system(grid, material_fraction, options)?;
    let effective_heat_flux_w_m2 = if system.heated_area_mm2 > 0.0 {
        options.heat_power_w / (system.heated_area_mm2 * 1e-6)
    } else {
        0.0
    };
    Ok(ThermalBoundarySummary {
        heated_area_mm2: system.heated_area_mm2,
        cooled_area_mm2: system.cooled_area_mm2,
        effective_heat_flux_w_m2,
    })
}

pub fn solve_thermal(
    grid: &VoxelGrid,
    material_fraction: &[f32],
    options: &ThermalOptions,
) -> Result<ThermalResult, String> {
""",
        "thermal boundary preflight function",
    )

    replace_once(
        thermal,
        """                    let heated = face == options.heated_face && at_global_extreme;
                    let cooled = face == options.cooled_face && at_global_extreme;
""",
        """                    // A heater orientation selects every exterior face carrying that
                    // outward voxel normal. Restricting it to the single global
                    // extreme concentrated all power onto tiny features such as
                    // Benchy's chimney top. The fixed-temperature sink remains a
                    // global plane because it models a mounting/contact datum.
                    let heated = face == options.heated_face;
                    let cooled = face == options.cooled_face && at_global_extreme;
""",
        "all-exterior heater orientation",
    )

    replace_once(
        thermal,
        """        if boundary.cooled {
            let g =
                2.0 * boundary.conductivity_w_mm_k * boundary.area_mm2 / system.h_mm;
            diagonal[ci] += g;
            rhs[ci] += g * options.cooled_temperature_c;
        } else {
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
""",
        """        if boundary.cooled {
            let g =
                2.0 * boundary.conductivity_w_mm_k * boundary.area_mm2 / system.h_mm;
            diagonal[ci] += g;
            rhs[ci] += g * options.cooled_temperature_c;
        } else if boundary.heated {
            // Contact-heater power enters through source_w. Applying ambient
            // convection/radiation to the same face would let a tiny patch dump
            // the input locally instead of conducting it into the part.
        } else {
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
""",
        "contact-heater linear boundary",
    )

    replace_once(
        thermal,
        """        if boundary.cooled {
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
""",
        """        if boundary.cooled {
            let g = 2.0 * boundary.conductivity_w_mm_k * boundary.area_mm2 / system.h_mm;
            residual[ci] += g * (surface_c - options.cooled_temperature_c);
        } else if boundary.heated {
            // Contact-heater source is already included in source_w.
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
""",
        "contact-heater nonlinear residual",
    )

    replace_once(
        thermal,
        """        if boundary.cooled {
            let g =
                2.0 * boundary.conductivity_w_mm_k * boundary.area_mm2 / system.h_mm;
            out += g * (t - options.cooled_temperature_c);
        } else {
            out += options.convection_w_m2_k
                * boundary.area_mm2
                * 1e-6
                * (t - options.ambient_temperature_c);
            let tk = (t + 273.15).max(1.0);
            let tak = (options.ambient_temperature_c + 273.15).max(1.0);
            out += options.emissivity
                * SIGMA_SB_W_M2_K4
                * boundary.area_mm2
                * 1e-6
                * (tk.powi(4) - tak.powi(4));
        }
""",
        """        if boundary.cooled {
            let g =
                2.0 * boundary.conductivity_w_mm_k * boundary.area_mm2 / system.h_mm;
            out += g * (t - options.cooled_temperature_c);
        } else if boundary.heated {
            // The contact heater is an input boundary, not an ambient-loss face.
        } else {
            out += options.convection_w_m2_k
                * boundary.area_mm2
                * 1e-6
                * (t - options.ambient_temperature_c);
            let tk = (t + 273.15).max(1.0);
            let tak = (options.ambient_temperature_c + 273.15).max(1.0);
            out += options.emissivity
                * SIGMA_SB_W_M2_K4
                * boundary.area_mm2
                * 1e-6
                * (tk.powi(4) - tak.powi(4));
        }
""",
        "contact-heater energy accounting",
    )

    append_once(
        thermal,
        "physical_model_all_exterior_heater_area",
        r'''
#[cfg(test)]
mod physical_model_v1_tests {
    use super::*;

    fn options() -> ThermalOptions {
        ThermalOptions {
            transient: false,
            conductivity_w_m_k: [0.2, 0.2, 0.2],
            density_kg_m3: 1_240.0,
            specific_heat_j_kg_k: 1_800.0,
            conductivity_exponent: 1.0,
            ambient_temperature_c: 20.0,
            initial_temperature_c: 20.0,
            cooled_temperature_c: 20.0,
            convection_w_m2_k: 8.0,
            emissivity: 0.9,
            heated_face: ThermalFace::ZMax,
            cooled_face: ThermalFace::ZMin,
            heat_power_w: 5.0,
            volumetric_power_w: 0.0,
            duration_seconds: 10.0,
            time_step_seconds: 1.0,
            tolerance: 1e-8,
        }
    }

    #[test]
    fn physical_model_all_exterior_heater_area() {
        let mut grid = VoxelGrid::solid_box(2, 1, 2, 1.0);
        let upper_left = grid.cell_index(0, 0, 1);
        grid.scale[upper_left] = 0.0;
        let material = grid.scale.clone();
        let summary = thermal_boundary_summary(&grid, &material, &options()).unwrap();
        assert!((summary.heated_area_mm2 - 2.0).abs() < 1e-9, "{:?}", summary);
        assert!((summary.effective_heat_flux_w_m2 - 2_500_000.0).abs() < 1e-6);
    }

    #[test]
    fn contact_heater_does_not_reject_power_directly_to_ambient() {
        let grid = VoxelGrid::solid_box(8, 1, 1, 1.0);
        let material = vec![1.0; grid.cell_count()];
        let mut opts = options();
        opts.heated_face = ThermalFace::XMin;
        opts.cooled_face = ThermalFace::XMax;
        opts.heat_power_w = 1.0;
        let result = solve_thermal(&grid, &material, &opts).unwrap();
        assert!((result.heat_rejected_w - 1.0).abs() < 1e-4);
        assert!(result.energy_balance_relative < 1e-4);
        assert!(result.maximum_temperature_c > result.minimum_temperature_c);
    }
}
''',
    )

    replace_once(
        wasm,
        "/// Sample a solution's displacement onto each soup vertex (9 floats/triangle) —\n",
        r'''/// Sample the cell temperature field onto every corner of the real STL soup.
fn map_cell_temperature_to_mesh(
    mesh: &TriMesh,
    grid: &VoxelGrid,
    temperatures: &[f32],
    material_fraction: &[f32],
) -> Vec<f32> {
    fn sample(
        p: [f64; 3],
        grid: &VoxelGrid,
        temperatures: &[f32],
        material_fraction: &[f32],
    ) -> f32 {
        let base = [
            ((p[0] - grid.origin[0]) / grid.h - 0.5).round() as isize,
            ((p[1] - grid.origin[1]) / grid.h - 0.5).round() as isize,
            ((p[2] - grid.origin[2]) / grid.h - 0.5).round() as isize,
        ];
        let mut best = None::<(f64, f32)>;
        for radius in 0..=2isize {
            for dz in -radius..=radius {
                for dy in -radius..=radius {
                    for dx in -radius..=radius {
                        if radius > 0 && dx.abs().max(dy.abs()).max(dz.abs()) != radius {
                            continue;
                        }
                        let x = base[0] + dx;
                        let y = base[1] + dy;
                        let z = base[2] + dz;
                        if x < 0 || y < 0 || z < 0
                            || x >= grid.nx as isize || y >= grid.ny as isize || z >= grid.nz as isize
                        {
                            continue;
                        }
                        let ci = grid.cell_index(x as usize, y as usize, z as usize);
                        if material_fraction.get(ci).copied().unwrap_or(0.0) <= 1e-7 {
                            continue;
                        }
                        let center = [
                            grid.origin[0] + (x as f64 + 0.5) * grid.h,
                            grid.origin[1] + (y as f64 + 0.5) * grid.h,
                            grid.origin[2] + (z as f64 + 0.5) * grid.h,
                        ];
                        let distance = (center[0] - p[0]).powi(2)
                            + (center[1] - p[1]).powi(2)
                            + (center[2] - p[2]).powi(2);
                        let value = temperatures.get(ci).copied().unwrap_or(f32::NAN);
                        if value.is_finite() && best.map_or(true, |(d, _)| distance < d) {
                            best = Some((distance, value));
                        }
                    }
                }
            }
            if best.is_some() { break; }
        }
        best.map_or(f32::NAN, |(_, value)| value)
    }

    let mut out = Vec::with_capacity(mesh.tris.len() * 3);
    for triangle in &mesh.tris {
        for vertex in 0..3 {
            out.push(sample(
                [
                    triangle[3 * vertex] as f64,
                    triangle[3 * vertex + 1] as f64,
                    triangle[3 * vertex + 2] as f64,
                ],
                grid,
                temperatures,
                material_fraction,
            ));
        }
    }
    out
}

/// Sample a solution's displacement onto each soup vertex (9 floats/triangle) —
''',
        "mesh temperature mapping helper",
    )

    replace_once(
        wasm,
        "    /// EnderSlicer thermal integrity extension v1.\n",
        r'''    /** Exact heater/sink area and effective flux for the current voxel model. */
    pub fn thermal_integrity_preflight(&mut self, opts_json: &str) -> Result<String, JsValue> {
        let opts: ThermalIntegrityOpts = serde_json::from_str(opts_json).map_err(err)?;
        self.ensure_grid()?;
        let grid = self.grid.as_ref().unwrap().0.clone();
        let heated_face = filasim_core::thermal::ThermalFace::parse(&opts.heated_face)
            .ok_or_else(|| err("unsupported heated face"))?;
        let cooled_face = filasim_core::thermal::ThermalFace::parse(&opts.cooled_face)
            .ok_or_else(|| err("unsupported cooled face"))?;
        let (_, wall_mm) = resolve_wall(opts.perimeters, opts.line_width);
        let tb_mm = (opts.top_bottom_layers.min(20) as f64
            * opts.layer_height.clamp(0.04, 0.6)).min(5.0);
        let split = filasim_core::simp::classify_cells(
            &grid, wall_mm, tb_mm, tb_mm, self.composite_skin,
        );
        let fallback_density = (opts.infill_pct / 100.0).clamp(0.01, 1.0);
        let use_optimized = opts.density_aware && self.opt.is_some();
        let x: Vec<f64> = split.design.iter().map(|cell| {
            if use_optimized {
                self.opt.as_ref().and_then(|opt| opt.cell_density.get(cell)).copied()
                    .unwrap_or(fallback_density)
            } else { fallback_density }.clamp(0.01, 1.0)
        }).collect();
        let material_fraction = filasim_core::simp::build_vfrac(
            &grid, &split.design, &split.skin_frac, &x,
        );
        let thermal_options = filasim_core::thermal::ThermalOptions {
            transient: opts.mode == "transient",
            conductivity_w_m_k: [opts.conductivity_x, opts.conductivity_y, opts.conductivity_z],
            density_kg_m3: opts.density_kg_m3,
            specific_heat_j_kg_k: opts.specific_heat,
            conductivity_exponent: opts.conductivity_exponent,
            ambient_temperature_c: opts.ambient_temperature_c,
            initial_temperature_c: opts.initial_temperature_c,
            cooled_temperature_c: opts.cooled_temperature_c,
            convection_w_m2_k: opts.convection,
            emissivity: opts.emissivity,
            heated_face,
            cooled_face,
            heat_power_w: opts.heat_power_w,
            volumetric_power_w: opts.volumetric_power_w,
            duration_seconds: opts.duration_seconds,
            time_step_seconds: opts.time_step_seconds,
            tolerance: 1e-7,
        };
        let summary = filasim_core::thermal::thermal_boundary_summary(
            &grid, &material_fraction, &thermal_options,
        ).map_err(err)?;
        Ok(serde_json::json!({
            "heatedAreaMm2": summary.heated_area_mm2,
            "cooledAreaMm2": summary.cooled_area_mm2,
            "effectiveHeatFluxWm2": summary.effective_heat_flux_w_m2,
            "heatPowerW": opts.heat_power_w,
            "heaterBoundaryModel": "contact-all-exterior-faces-with-selected-normal",
            "sinkBoundaryModel": "fixed-temperature-global-extreme-plane",
            "densityAware": use_optimized,
        }).to_string())
    }

    /// EnderSlicer thermal integrity extension v1.
''',
        "WASM boundary preflight method",
    )

    replace_once(
        wasm,
        "        let e0 = opts.youngs_modulus.clamp(1.0, 1.0e7);\n",
        r'''        let vertex_temperatures = map_cell_temperature_to_mesh(
            &self.mesh, &grid, &thermal.temperatures_c, &material_fraction,
        );
        let lower_property_limit = opts.reference_temperature_c - 50.0;
        let structural_valid = thermal.maximum_temperature_c <= opts.service_limit_c
            && thermal.minimum_temperature_c >= lower_property_limit;
        if !structural_valid {
            emit_progress(
                "Thermal failure", 0.96,
                "Temperature field is outside the material model; structural FEA was skipped",
            );
            let active_cells = material_fraction.iter().filter(|value| **value > 0.0).count();
            let effective_flux = if thermal.exposed_heated_area_mm2 > 0.0 {
                opts.heat_power_w / (thermal.exposed_heated_area_mm2 * 1e-6)
            } else { 0.0 };
            let reason = if thermal.maximum_temperature_c > opts.service_limit_c {
                format!(
                    "maximum temperature {:.3} °C exceeds the material service limit {:.3} °C",
                    thermal.maximum_temperature_c, opts.service_limit_c,
                )
            } else {
                format!(
                    "minimum temperature {:.3} °C is below the supported property range {:.3} °C",
                    thermal.minimum_temperature_c, lower_property_limit,
                )
            };
            let stats = serde_json::json!({
                "materialName": opts.material_name,
                "mode": if opts.mode == "transient" { "transient" } else { "steady" },
                "minimumTemperatureC": thermal.minimum_temperature_c,
                "meanTemperatureC": thermal.mean_temperature_c,
                "maximumTemperatureC": thermal.maximum_temperature_c,
                "hotspotMm": thermal.hotspot_mm,
                "heatInputW": thermal.heat_input_w,
                "heatRejectedW": thermal.heat_rejected_w,
                "storageRateW": thermal.storage_rate_w,
                "energyBalanceRelative": thermal.energy_balance_relative,
                "thermalIterations": thermal.iterations,
                "thermalResidual": thermal.relative_residual,
                "timeSteps": thermal.time_steps,
                "finalTimeSeconds": thermal.final_time_seconds,
                "peakTemperatureC": thermal.peak_temperature_c,
                "peakTimeSeconds": thermal.peak_time_seconds,
                "heatedAreaMm2": thermal.exposed_heated_area_mm2,
                "cooledAreaMm2": thermal.exposed_cooled_area_mm2,
                "effectiveHeatFluxWm2": effective_flux,
                "heaterBoundaryModel": "contact-all-exterior-faces-with-selected-normal",
                "sinkBoundaryModel": "fixed-temperature-global-extreme-plane",
                "maxDisplacementMm": Option::<f64>::None,
                "maxVonMisesMpa": Option::<f64>::None,
                "minimumModulusRetention": Option::<f64>::None,
                "minimumStrengthRetention": Option::<f64>::None,
                "conservativeSafetyFactor": Option::<f64>::None,
                "temperatureMarginC": opts.service_limit_c - thermal.maximum_temperature_c,
                "propertyExtrapolated": true,
                "structuralValid": false,
                "materialValidity": "outside-model",
                "materialValidityReason": reason,
                "densityAware": use_optimized,
                "activeCells": active_cells,
                "nx": grid.nx, "ny": grid.ny, "nz": grid.nz, "h": grid.h,
                "structuralIterations": 0,
                "structuralResidual": Option::<f64>::None,
                "structuralConverged": false,
            }).to_string();
            let zero_displacements = vec![0.0f32; self.mesh.tris.len() * 9];
            let array = js_sys::Array::new();
            array.push(&JsValue::from(stats));
            array.push(&js_sys::Float32Array::from(thermal.temperatures_c.as_slice()));
            array.push(&js_sys::Float64Array::from(thermal.history.as_slice()));
            array.push(&js_sys::Float32Array::from(zero_displacements.as_slice()));
            array.push(&js_sys::Float32Array::from(material_fraction.as_slice()));
            array.push(&js_sys::Float32Array::from(vertex_temperatures.as_slice()));
            return Ok(array);
        }

        let e0 = opts.youngs_modulus.clamp(1.0, 1.0e7);
''',
        "material validity structural gate",
    )

    replace_once(
        wasm,
        """            "cooledAreaMm2": thermal.exposed_cooled_area_mm2,
            "maxDisplacementMm": max_displacement,
""",
        """            "cooledAreaMm2": thermal.exposed_cooled_area_mm2,
            "effectiveHeatFluxWm2": if thermal.exposed_heated_area_mm2 > 0.0 {
                opts.heat_power_w / (thermal.exposed_heated_area_mm2 * 1e-6)
            } else { 0.0 },
            "heaterBoundaryModel": "contact-all-exterior-faces-with-selected-normal",
            "sinkBoundaryModel": "fixed-temperature-global-extreme-plane",
            "maxDisplacementMm": max_displacement,
""",
        "valid-result boundary diagnostics",
    )
    replace_once(
        wasm,
        """            "propertyExtrapolated": property_extrapolated,
            "densityAware": use_optimized,
""",
        """            "propertyExtrapolated": property_extrapolated,
            "structuralValid": true,
            "materialValidity": "valid",
            "materialValidityReason": Option::<String>::None,
            "densityAware": use_optimized,
""",
        "valid-result material status",
    )
    replace_once(
        wasm,
        """        array.push(&js_sys::Float32Array::from(material_fraction.as_slice()));
        Ok(array)
""",
        """        array.push(&js_sys::Float32Array::from(material_fraction.as_slice()));
        array.push(&js_sys::Float32Array::from(vertex_temperatures.as_slice()));
        Ok(array)
""",
        "vertex temperature result array",
    )

    replace_once(
        client,
        "export interface ThermalIntegrityStats {\n",
        """export interface ThermalIntegrityPreflight {
  heatedAreaMm2: number;
  cooledAreaMm2: number;
  effectiveHeatFluxWm2: number;
  heatPowerW: number;
  heaterBoundaryModel: "contact-all-exterior-faces-with-selected-normal";
  sinkBoundaryModel: "fixed-temperature-global-extreme-plane";
  densityAware: boolean;
}

export interface ThermalIntegrityStats {
""",
        "preflight EngineClient type",
    )
    for field in (
        "maxDisplacementMm", "maxVonMisesMpa", "minimumModulusRetention",
        "minimumStrengthRetention", "conservativeSafetyFactor", "structuralResidual",
    ):
        replace_once(client, f"  {field}: number;\n", f"  {field}: number | null;\n", f"nullable {field}")
    replace_once(
        client,
        """  cooledAreaMm2: number;
  maxDisplacementMm: number | null;
""",
        """  cooledAreaMm2: number;
  effectiveHeatFluxWm2: number;
  heaterBoundaryModel: "contact-all-exterior-faces-with-selected-normal";
  sinkBoundaryModel: "fixed-temperature-global-extreme-plane";
  maxDisplacementMm: number | null;
""",
        "boundary diagnostics type",
    )
    replace_once(
        client,
        """  propertyExtrapolated: boolean;
  densityAware: boolean;
""",
        """  propertyExtrapolated: boolean;
  structuralValid: boolean;
  materialValidity: "valid" | "outside-model";
  materialValidityReason: string | null;
  densityAware: boolean;
""",
        "material validity type",
    )
    replace_once(
        client,
        """  /** Service-temperature thermal integrity solve with exact cell temperatures. */
  thermalIntegrity(
""",
        """  /** Exact heater area and effective flux before the long solve. */
  thermalIntegrityPreflight(opts: ThermalIntegrityOptions): Promise<ThermalIntegrityPreflight> {
    return this.call({ op: "thermalIntegrityPreflight", opts });
  }

  /** Service-temperature thermal integrity solve with exact cell temperatures. */
  thermalIntegrity(
""",
        "EngineClient preflight method",
    )
    replace_once(
        client,
        """    materialFraction: Float32Array;
  }> {
""",
        """    materialFraction: Float32Array;
    vertexTemperatures: Float32Array;
  }> {
""",
        "EngineClient vertex temperatures",
    )

    replace_once(
        protocol,
        """  ThermalIntegrityOptions,
  ThermalIntegrityStats,
""",
        """  ThermalIntegrityOptions,
  ThermalIntegrityPreflight,
  ThermalIntegrityStats,
""",
        "protocol preflight import",
    )
    replace_once(
        protocol,
        """  /** EnderSlicer service-temperature thermal integrity solve. */
  thermalIntegrity: { opts: ThermalIntegrityOptions };
""",
        """  /** Exact Thermal Integrity boundary preflight. */
  thermalIntegrityPreflight: { opts: ThermalIntegrityOptions };
  /** EnderSlicer service-temperature thermal integrity solve. */
  thermalIntegrity: { opts: ThermalIntegrityOptions };
""",
        "protocol preflight request",
    )
    replace_once(
        protocol,
        """  thermalIntegrity: {
    stats: ThermalIntegrityStats;
""",
        """  thermalIntegrityPreflight: ThermalIntegrityPreflight;
  thermalIntegrity: {
    stats: ThermalIntegrityStats;
""",
        "protocol preflight response",
    )
    replace_once(
        protocol,
        """    materialFraction: Float32Array;
  };
""",
        """    materialFraction: Float32Array;
    vertexTemperatures: Float32Array;
  };
""",
        "protocol vertex temperatures",
    )

    replace_once(
        worker,
        '      case "thermalIntegrity": {\n',
        r'''      case "thermalIntegrityPreflight": {
        const stats = JSON.parse(
          requireModel().thermal_integrity_preflight(JSON.stringify(msg.opts))
        );
        reply(msg, stats);
        return;
      }
      case "thermalIntegrity": {
''',
        "worker preflight operation",
    )
    replace_once(
        worker,
        """        const materialFraction = array[4] as Float32Array;
        stats.seconds = (performance.now() - t0) / 1000;
""",
        """        const materialFraction = array[4] as Float32Array;
        const vertexTemperatures = array[5] as Float32Array;
        stats.seconds = (performance.now() - t0) / 1000;
""",
        "worker vertex extraction",
    )
    replace_once(
        worker,
        "          { stats, temperatures, history, displacements, materialFraction },\n",
        "          { stats, temperatures, history, displacements, materialFraction, vertexTemperatures },\n",
        "worker vertex payload",
    )
    replace_once(
        worker,
        """            materialFraction.buffer,
          ]
""",
        """            materialFraction.buffer,
            vertexTemperatures.buffer,
          ]
""",
        "worker vertex transfer",
    )

    replace_once(
        viewer,
        'import { KIND_DOT } from "../ui/bcmeta";\n\n',
        f'''import {{ KIND_DOT }} from "../ui/bcmeta";

type ThermalResult3dDetail = {{
  vertexTemperatures: Float32Array;
  displacements: Float32Array;
  minimumTemperatureC: number;
  maximumTemperatureC: number;
  structuralValid: boolean;
  maxDisplacementMm: number | null;
}};
const THERMAL_RESULT_EVENT = "{THERMAL_RESULT_EVENT}";
const THERMAL_CLEAR_EVENT = "{THERMAL_CLEAR_EVENT}";

''',
        "viewer thermal event type",
    )
    replace_once(
        viewer,
        "  const [fps, setFps] = useState(0);\n\n",
        """  const [fps, setFps] = useState(0);
  const [thermalLegend, setThermalLegend] = useState<{
    min: number;
    max: number;
    structuralValid: boolean;
  } | null>(null);

""",
        "viewer thermal legend state",
    )
    replace_once(
        viewer,
        "    sceneEvents.onModelLoaded = (m) => scene.setModel(m);\n",
        f'''    const onThermalResult3d = (event: Event) => {{
      const detail = (event as CustomEvent<ThermalResult3dDetail>).detail;
      if (!detail || !(detail.vertexTemperatures instanceof Float32Array)
          || !(detail.displacements instanceof Float32Array)
          || !Number.isFinite(detail.minimumTemperatureC)
          || !Number.isFinite(detail.maximumTemperatureC)) return;
      scene.setViewState("deformed", 1);
      scene.setDisplacements(
        detail.structuralValid ? detail.displacements : null,
        detail.structuralValid && Number.isFinite(detail.maxDisplacementMm)
          ? {{ maxDisplacement: Number(detail.maxDisplacementMm) }} : null
      );
      scene.setScalarField(
        detail.vertexTemperatures, false, false,
        {{ min: detail.minimumTemperatureC, max: detail.maximumTemperatureC }}
      );
      scene.setProbeFormatter((value) => `${{value.toFixed(2)}} °C`);
      setThermalLegend({{
        min: detail.minimumTemperatureC,
        max: detail.maximumTemperatureC,
        structuralValid: detail.structuralValid,
      }});
    }};
    const onThermalClear3d = () => {{
      scene.setScalarField(null);
      scene.setDisplacements(null, null);
      scene.setProbeFormatter(null);
      scene.setViewState("setup", 1);
      setThermalLegend(null);
    }};
    window.addEventListener(THERMAL_RESULT_EVENT, onThermalResult3d);
    window.addEventListener(THERMAL_CLEAR_EVENT, onThermalClear3d);

    sceneEvents.onModelLoaded = (m) => scene.setModel(m);
''',
        "viewer thermal listeners",
    )
    replace_once(
        viewer,
        """    return () => {
      obs.disconnect();
      scene.dispose();
    };
""",
        """    return () => {
      obs.disconnect();
      window.removeEventListener(THERMAL_RESULT_EVENT, onThermalResult3d);
      window.removeEventListener(THERMAL_CLEAR_EVENT, onThermalClear3d);
      scene.dispose();
    };
""",
        "viewer thermal cleanup",
    )
    replace_once(
        viewer,
        """      <Legend />
      <div className="fpscounter" title="Render frame rate">
""",
        """      <Legend />
      {thermalLegend && (
        <div style={{
          position: "absolute", right: 12, top: 12, width: 190,
          padding: "9px 10px", borderRadius: 7,
          background: "rgba(22,24,27,.88)", color: "white",
          fontSize: 11, pointerEvents: "none",
          boxShadow: "0 2px 12px rgba(0,0,0,.28)",
        }}>
          <div style={{ fontWeight: 700, marginBottom: 6 }}>Temperature · 3D result</div>
          <div style={{
            height: 9, borderRadius: 4, background: cssGradient(jet), marginBottom: 5,
          }} />
          <div style={{ display: "flex", justifyContent: "space-between" }}>
            <span>{thermalLegend.min.toFixed(1)} °C</span>
            <span>{thermalLegend.max.toFixed(1)} °C</span>
          </div>
          {!thermalLegend.structuralValid && (
            <div style={{ color: "#ff9b90", marginTop: 6, fontWeight: 700 }}>
              THERMAL FAILURE · structural FEA not calculated
            </div>
          )}
        </div>
      )}
      <div className="fpscounter" title="Render frame rate">
""",
        "viewer thermal legend",
    )

    marker = source_root / ".enderslicer-thermal-integrity-physical-model-v1"
    marker.write_text(MARKER + "\n", encoding="utf-8")

    required = (
        (thermal, "thermal_boundary_summary"),
        (thermal, "let heated = face == options.heated_face;"),
        (thermal, "physical_model_all_exterior_heater_area"),
        (wasm, "thermal_integrity_preflight"),
        (wasm, "map_cell_temperature_to_mesh"),
        (wasm, '"structuralValid": false'),
        (worker, "vertexTemperatures"),
        (protocol, "thermalIntegrityPreflight"),
        (viewer, THERMAL_RESULT_EVENT),
        (viewer, "Temperature · 3D result"),
    )
    for path, contract in required:
        if contract not in path.read_text(encoding="utf-8"):
            raise RuntimeError(f"Thermal physical-model contract {contract!r} is missing from {path}")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
