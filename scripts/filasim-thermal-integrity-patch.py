#!/usr/bin/env python3
# Apply EnderSlicerCura's deterministic thermal-integrity extension to pinned filaSim source.

from __future__ import annotations

import pathlib


MARKER = "EnderSlicer thermal integrity extension v1"


def replace_once(path: pathlib.Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise RuntimeError(f"Unable to locate {label} in {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


THERMAL_RS = r'''// SPDX-License-Identifier: AGPL-3.0-only
// EnderSlicer thermal integrity extension v1.
//
// Cell-centred finite-volume heat conduction on filaSim's existing voxel grid.
// Units are W, mm, K/°C, N and MPa. Conductivity and film coefficients enter
// in SI and are converted at the boundary of this module.

use crate::cancel;
use crate::fem::{NODE_OFFSETS, NODE_SIGNS};
use crate::voxel::VoxelGrid;

const SIGMA_SB_W_M2_K4: f64 = 5.670_374_419e-8;
const K_AIR_W_M_K: f64 = 0.026;
const MAX_LINEAR_ITERS: usize = 4_000;
const MAX_RADIATION_ITERS: usize = 12;
const MAX_TRANSIENT_STEPS: usize = 2_000;
const MIN_ABSOLUTE_TEMPERATURE_C: f64 = -273.15;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ThermalFace {
    XMin,
    XMax,
    YMin,
    YMax,
    ZMin,
    ZMax,
}

impl ThermalFace {
    pub fn parse(value: &str) -> Option<Self> {
        Some(match value {
            "xmin" => Self::XMin,
            "xmax" => Self::XMax,
            "ymin" => Self::YMin,
            "ymax" => Self::YMax,
            "zmin" => Self::ZMin,
            "zmax" => Self::ZMax,
            _ => return None,
        })
    }
}

#[derive(Clone, Debug)]
pub struct ThermalOptions {
    pub transient: bool,
    pub conductivity_w_m_k: [f64; 3],
    pub density_kg_m3: f64,
    pub specific_heat_j_kg_k: f64,
    pub conductivity_exponent: f64,
    pub ambient_temperature_c: f64,
    pub initial_temperature_c: f64,
    pub cooled_temperature_c: f64,
    pub convection_w_m2_k: f64,
    pub emissivity: f64,
    pub heated_face: ThermalFace,
    pub cooled_face: ThermalFace,
    pub heat_power_w: f64,
    pub volumetric_power_w: f64,
    pub duration_seconds: f64,
    pub time_step_seconds: f64,
    pub tolerance: f64,
}

#[derive(Clone, Debug)]
pub struct ThermalResult {
    pub temperatures_c: Vec<f32>,
    /// Flat triples: time seconds, maximum °C, material-volume-weighted mean °C.
    pub history: Vec<f64>,
    pub minimum_temperature_c: f64,
    pub mean_temperature_c: f64,
    pub maximum_temperature_c: f64,
    pub hotspot_mm: [f64; 3],
    pub heat_input_w: f64,
    pub heat_rejected_w: f64,
    pub storage_rate_w: f64,
    pub energy_balance_relative: f64,
    pub iterations: usize,
    pub relative_residual: f64,
    pub time_steps: usize,
    pub final_time_seconds: f64,
    pub peak_temperature_c: f64,
    pub peak_time_seconds: f64,
    pub exposed_heated_area_mm2: f64,
    pub exposed_cooled_area_mm2: f64,
}

#[derive(Clone, Copy)]
struct BoundaryFace {
    cell: usize,
    face: ThermalFace,
    area_mm2: f64,
    conductivity_w_mm_k: f64,
}

struct ThermalSystem {
    active: Vec<bool>,
    neighbors: Vec<[Option<usize>; 6]>,
    conductance: Vec<[f64; 6]>,
    conduction_diagonal: Vec<f64>,
    boundaries: Vec<BoundaryFace>,
    source_w: Vec<f64>,
    capacity_j_k: Vec<f64>,
    heat_input_w: f64,
    heated_area_mm2: f64,
    cooled_area_mm2: f64,
    h_mm: f64,
}

pub fn solve_thermal(
    grid: &VoxelGrid,
    material_fraction: &[f32],
    options: &ThermalOptions,
) -> Result<ThermalResult, String> {
    validate(grid, material_fraction, options)?;
    let system = build_system(grid, material_fraction, options)?;
    if options.transient {
        solve_transient(grid, material_fraction, options, &system)
    } else {
        solve_steady(grid, material_fraction, options, &system)
    }
}

fn validate(
    grid: &VoxelGrid,
    material_fraction: &[f32],
    options: &ThermalOptions,
) -> Result<(), String> {
    if grid.cell_count() == 0 || grid.solid_count() == 0 {
        return Err("thermal integrity requires a non-empty voxelized part".into());
    }
    if material_fraction.len() != grid.cell_count() {
        return Err("thermal material-fraction field does not match the voxel grid".into());
    }
    if material_fraction.iter().any(|v| !v.is_finite() || *v < 0.0 || *v > 1.000_1) {
        return Err("thermal material-fraction field contains an invalid value".into());
    }
    if options.conductivity_w_m_k.iter().any(|v| !v.is_finite() || *v < 0.005 || *v > 1_000.0) {
        return Err("thermal conductivity must be within 0.005..1000 W/(m·K)".into());
    }
    if !options.density_kg_m3.is_finite() || !(50.0..=30_000.0).contains(&options.density_kg_m3) {
        return Err("material density must be within 50..30000 kg/m³".into());
    }
    if !options.specific_heat_j_kg_k.is_finite()
        || !(50.0..=10_000.0).contains(&options.specific_heat_j_kg_k)
    {
        return Err("specific heat must be within 50..10000 J/(kg·K)".into());
    }
    if !options.conductivity_exponent.is_finite()
        || !(0.25..=4.0).contains(&options.conductivity_exponent)
    {
        return Err("conductivity density exponent must be within 0.25..4".into());
    }
    for (name, value) in [
        ("ambient temperature", options.ambient_temperature_c),
        ("initial temperature", options.initial_temperature_c),
        ("cooled temperature", options.cooled_temperature_c),
    ] {
        if !value.is_finite() || value <= MIN_ABSOLUTE_TEMPERATURE_C || value > 1_500.0 {
            return Err(format!("{name} is outside the supported physical range"));
        }
    }
    if !options.convection_w_m2_k.is_finite()
        || !(0.0..=100_000.0).contains(&options.convection_w_m2_k)
    {
        return Err("convection coefficient must be within 0..100000 W/(m²·K)".into());
    }
    if !options.emissivity.is_finite() || !(0.0..=1.0).contains(&options.emissivity) {
        return Err("emissivity must be within 0..1".into());
    }
    if !options.heat_power_w.is_finite() || !(0.0..=100_000.0).contains(&options.heat_power_w) {
        return Err("surface heat power must be within 0..100000 W".into());
    }
    if !options.volumetric_power_w.is_finite()
        || !(0.0..=100_000.0).contains(&options.volumetric_power_w)
    {
        return Err("volumetric heat power must be within 0..100000 W".into());
    }
    if options.heated_face == options.cooled_face && options.heat_power_w > 0.0 {
        return Err("the heated and fixed-temperature faces must be different".into());
    }
    if !options.tolerance.is_finite() || !(1e-10..=1e-3).contains(&options.tolerance) {
        return Err("thermal residual tolerance must be within 1e-10..1e-3".into());
    }
    if options.transient {
        if !options.duration_seconds.is_finite() || !(0.01..=31_536_000.0).contains(&options.duration_seconds) {
            return Err("transient duration must be within 0.01 seconds and one year".into());
        }
        if !options.time_step_seconds.is_finite()
            || !(1e-4..=86_400.0).contains(&options.time_step_seconds)
        {
            return Err("transient time step must be within 0.0001..86400 seconds".into());
        }
        let steps = (options.duration_seconds / options.time_step_seconds).ceil() as usize;
        if steps == 0 || steps > MAX_TRANSIENT_STEPS {
            return Err(format!(
                "transient simulation requires 1..{MAX_TRANSIENT_STEPS} time steps; increase the time step"
            ));
        }
    }
    Ok(())
}

fn build_system(
    grid: &VoxelGrid,
    material_fraction: &[f32],
    options: &ThermalOptions,
) -> Result<ThermalSystem, String> {
    let n = grid.cell_count();
    let mut active = vec![false; n];
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
    if !active.iter().any(|v| *v) {
        return Err("thermal material field contains no active cells".into());
    }

    let mut neighbors = vec![[None; 6]; n];
    let mut conductance = vec![[0.0; 6]; n];
    let mut conduction_diagonal = vec![0.0; n];
    for cz in 0..grid.nz {
        for cy in 0..grid.ny {
            for cx in 0..grid.nx {
                let ci = grid.cell_index(cx, cy, cz);
                if !active[ci] {
                    continue;
                }
                for (dir, axis, nx, ny, nz) in [
                    (1usize, 0usize, cx as isize + 1, cy as isize, cz as isize),
                    (3usize, 1usize, cx as isize, cy as isize + 1, cz as isize),
                    (5usize, 2usize, cx as isize, cy as isize, cz as isize + 1),
                ] {
                    if nx < 0
                        || ny < 0
                        || nz < 0
                        || nx >= grid.nx as isize
                        || ny >= grid.ny as isize
                        || nz >= grid.nz as isize
                    {
                        continue;
                    }
                    let cj = grid.cell_index(nx as usize, ny as usize, nz as usize);
                    if !active[cj] {
                        continue;
                    }
                    let ka = conductivity[ci][axis];
                    let kb = conductivity[cj][axis];
                    let harmonic = 2.0 * ka * kb / (ka + kb).max(1e-30);
                    let g = harmonic * grid.h;
                    neighbors[ci][dir] = Some(cj);
                    neighbors[cj][dir - 1] = Some(ci);
                    conductance[ci][dir] = g;
                    conductance[cj][dir - 1] = g;
                    conduction_diagonal[ci] += g;
                    conduction_diagonal[cj] += g;
                }
            }
        }
    }

    let mut boundaries = Vec::new();
    let mut heated_area_mm2 = 0.0;
    let mut cooled_area_mm2 = 0.0;
    for cz in 0..grid.nz {
        for cy in 0..grid.ny {
            for cx in 0..grid.nx {
                let ci = grid.cell_index(cx, cy, cz);
                if !active[ci] {
                    continue;
                }
                let area_fraction = (grid.scale[ci] as f64).clamp(0.0, 1.0).powf(2.0 / 3.0);
                let area = grid.h * grid.h * area_fraction.max(0.05);
                let candidates = [
                    (ThermalFace::XMin, 0usize, cx as isize - 1, cy as isize, cz as isize),
                    (ThermalFace::XMax, 0usize, cx as isize + 1, cy as isize, cz as isize),
                    (ThermalFace::YMin, 1usize, cx as isize, cy as isize - 1, cz as isize),
                    (ThermalFace::YMax, 1usize, cx as isize, cy as isize + 1, cz as isize),
                    (ThermalFace::ZMin, 2usize, cx as isize, cy as isize, cz as isize - 1),
                    (ThermalFace::ZMax, 2usize, cx as isize, cy as isize, cz as isize + 1),
                ];
                for (face, axis, nx, ny, nz) in candidates {
                    let exposed = nx < 0
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
                }
            }
        }
    }
    if options.heat_power_w > 0.0 && heated_area_mm2 <= 0.0 {
        return Err("the selected heated face has no exposed voxel surface".into());
    }
    if cooled_area_mm2 <= 0.0
        && options.convection_w_m2_k <= 0.0
        && options.emissivity <= 0.0
        && !options.transient
    {
        return Err("steady-state heat solve has no cooling boundary and is singular".into());
    }

    let mut source_w = vec![0.0; n];
    if options.heat_power_w > 0.0 {
        for boundary in &boundaries {
            if boundary.face == options.heated_face {
                source_w[boundary.cell] +=
                    options.heat_power_w * boundary.area_mm2 / heated_area_mm2;
            }
        }
    }
    let total_volume_fraction: f64 = material_fraction
        .iter()
        .enumerate()
        .filter(|(ci, _)| active[*ci])
        .map(|(_, value)| *value as f64)
        .sum();
    if options.volumetric_power_w > 0.0 {
        if total_volume_fraction <= 0.0 {
            return Err("volumetric heat source has no material volume".into());
        }
        for ci in 0..n {
            if active[ci] {
                source_w[ci] += options.volumetric_power_w
                    * material_fraction[ci] as f64
                    / total_volume_fraction;
            }
        }
    }

    Ok(ThermalSystem {
        active,
        neighbors,
        conductance,
        conduction_diagonal,
        boundaries,
        source_w,
        capacity_j_k,
        heat_input_w: options.heat_power_w + options.volumetric_power_w,
        heated_area_mm2,
        cooled_area_mm2,
        h_mm: grid.h,
    })
}

fn solve_steady(
    grid: &VoxelGrid,
    material_fraction: &[f32],
    options: &ThermalOptions,
    system: &ThermalSystem,
) -> Result<ThermalResult, String> {
    let mut temperature = vec![options.ambient_temperature_c; grid.cell_count()];
    let mut total_iterations = 0usize;
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

fn solve_transient(
    grid: &VoxelGrid,
    material_fraction: &[f32],
    options: &ThermalOptions,
    system: &ThermalSystem,
) -> Result<ThermalResult, String> {
    let steps = (options.duration_seconds / options.time_step_seconds).ceil() as usize;
    let mut temperature = vec![options.initial_temperature_c; grid.cell_count()];
    let mut history = Vec::with_capacity((steps + 1) * 3);
    let initial_stats = temperature_extremes(grid, material_fraction, &temperature, &system.active);
    history.extend_from_slice(&[0.0, initial_stats.2, initial_stats.1]);
    let mut peak_temperature = initial_stats.2;
    let mut peak_time = 0.0;
    let mut total_iterations = 0usize;
    let mut residual = f64::INFINITY;
    let mut last_old = temperature.clone();
    let mut final_dt = options.time_step_seconds;

    for step in 0..steps {
        if cancel::requested() {
            return Err("cancelled".into());
        }
        let elapsed_before = step as f64 * options.time_step_seconds;
        let dt = options
            .time_step_seconds
            .min(options.duration_seconds - elapsed_before)
            .max(1e-9);
        final_dt = dt;
        let old = temperature.clone();
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
        let time = (elapsed_before + dt).min(options.duration_seconds);
        let stats = temperature_extremes(grid, material_fraction, &temperature, &system.active);
        history.extend_from_slice(&[time, stats.2, stats.1]);
        if stats.2 > peak_temperature {
            peak_temperature = stats.2;
            peak_time = time;
        }
    }

    finish_result(
        grid,
        material_fraction,
        options,
        system,
        temperature,
        history,
        total_iterations,
        residual,
        steps,
        options.duration_seconds,
        Some((&last_old, final_dt, peak_temperature, peak_time)),
    )
}

fn linear_system(
    system: &ThermalSystem,
    options: &ThermalOptions,
    radiation_temperature: &[f64],
    dt: Option<f64>,
    previous_temperature: Option<&[f64]>,
) -> (Vec<f64>, Vec<f64>) {
    let n = system.active.len();
    let mut diagonal = vec![1.0; n];
    let mut rhs = vec![options.ambient_temperature_c; n];
    for ci in 0..n {
        if !system.active[ci] {
            continue;
        }
        diagonal[ci] = system.conduction_diagonal[ci];
        rhs[ci] = system.source_w[ci];
        if let (Some(step), Some(old)) = (dt, previous_temperature) {
            let mass = system.capacity_j_k[ci] / step;
            diagonal[ci] += mass;
            rhs[ci] += mass * old[ci];
        }
    }
    for boundary in &system.boundaries {
        let ci = boundary.cell;
        if boundary.face == options.cooled_face {
            let g =
                2.0 * boundary.conductivity_w_mm_k * boundary.area_mm2 / system.h_mm;
            diagonal[ci] += g;
            rhs[ci] += g * options.cooled_temperature_c;
        } else {
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
    }
    (diagonal, rhs)
}

fn linearized_radiation_w_m2_k(emissivity: f64, surface_c: f64, ambient_c: f64) -> f64 {
    if emissivity <= 0.0 {
        return 0.0;
    }
    let ts = (surface_c + 273.15).max(1.0);
    let ta = (ambient_c + 273.15).max(1.0);
    emissivity * SIGMA_SB_W_M2_K4 * (ts + ta) * (ts * ts + ta * ta)
}

struct LinearSolve {
    values: Vec<f64>,
    iterations: usize,
    relative_residual: f64,
}

fn pcg(
    system: &ThermalSystem,
    diagonal: &[f64],
    rhs: &[f64],
    initial: &[f64],
    tolerance: f64,
) -> Result<LinearSolve, String> {
    let n = rhs.len();
    let mut x = initial.to_vec();
    let mut ax = vec![0.0; n];
    apply(system, diagonal, &x, &mut ax);
    let mut r = vec![0.0; n];
    let mut z = vec![0.0; n];
    let mut p = vec![0.0; n];
    for i in 0..n {
        r[i] = rhs[i] - ax[i];
        z[i] = r[i] / diagonal[i].max(1e-30);
        p[i] = z[i];
    }
    let rhs_norm = dot(rhs, rhs, &system.active).sqrt().max(1e-30);
    let mut rz = dot(&r, &z, &system.active);
    let mut rel = dot(&r, &r, &system.active).sqrt() / rhs_norm;
    if !rel.is_finite() {
        return Err("thermal solver residual is non-finite".into());
    }
    if rel <= tolerance {
        return Ok(LinearSolve { values: x, iterations: 0, relative_residual: rel });
    }

    let mut ap = vec![0.0; n];
    for iteration in 1..=MAX_LINEAR_ITERS {
        if cancel::requested() {
            return Err("cancelled".into());
        }
        apply(system, diagonal, &p, &mut ap);
        let denom = dot(&p, &ap, &system.active);
        if !denom.is_finite() || denom <= 0.0 {
            return Err("thermal conduction operator is singular or not positive definite".into());
        }
        let alpha = rz / denom;
        for i in 0..n {
            if system.active[i] {
                x[i] += alpha * p[i];
                r[i] -= alpha * ap[i];
            }
        }
        rel = dot(&r, &r, &system.active).sqrt() / rhs_norm;
        if !rel.is_finite() {
            return Err("thermal solver diverged to a non-finite residual".into());
        }
        if rel <= tolerance {
            return Ok(LinearSolve { values: x, iterations: iteration, relative_residual: rel });
        }
        for i in 0..n {
            z[i] = if system.active[i] {
                r[i] / diagonal[i].max(1e-30)
            } else {
                0.0
            };
        }
        let next_rz = dot(&r, &z, &system.active);
        let beta = next_rz / rz.max(1e-300);
        for i in 0..n {
            p[i] = if system.active[i] { z[i] + beta * p[i] } else { 0.0 };
        }
        rz = next_rz;
    }
    Err(format!(
        "thermal solver did not converge within {MAX_LINEAR_ITERS} iterations (relative residual {rel:.3e})"
    ))
}

fn apply(system: &ThermalSystem, diagonal: &[f64], x: &[f64], out: &mut [f64]) {
    for i in 0..out.len() {
        if !system.active[i] {
            out[i] = x[i];
            continue;
        }
        let mut value = diagonal[i] * x[i];
        for dir in 0..6 {
            if let Some(j) = system.neighbors[i][dir] {
                value -= system.conductance[i][dir] * x[j];
            }
        }
        out[i] = value;
    }
}

fn dot(a: &[f64], b: &[f64], active: &[bool]) -> f64 {
    let mut sum = 0.0;
    let mut correction = 0.0;
    for i in 0..a.len() {
        if !active[i] {
            continue;
        }
        let product = a[i] * b[i];
        let y = product - correction;
        let t = sum + y;
        correction = (t - sum) - y;
        sum = t;
    }
    sum
}

fn finish_result(
    grid: &VoxelGrid,
    material_fraction: &[f32],
    options: &ThermalOptions,
    system: &ThermalSystem,
    temperature: Vec<f64>,
    history: Vec<f64>,
    iterations: usize,
    relative_residual: f64,
    time_steps: usize,
    final_time_seconds: f64,
    transient_tail: Option<(&[f64], f64, f64, f64)>,
) -> Result<ThermalResult, String> {
    if temperature
        .iter()
        .enumerate()
        .any(|(i, t)| system.active[i] && (!t.is_finite() || *t <= MIN_ABSOLUTE_TEMPERATURE_C))
    {
        return Err("thermal solution contains an invalid temperature".into());
    }
    let (minimum, mean, maximum, hotspot) =
        temperature_extremes_with_location(grid, material_fraction, &temperature, &system.active);
    let heat_rejected = heat_rejected_w(options, system, &temperature);
    let (storage_rate, peak_temperature, peak_time) = if let Some((old, dt, peak, time)) = transient_tail {
        let mut storage = 0.0;
        for ci in 0..temperature.len() {
            if system.active[ci] {
                storage += system.capacity_j_k[ci] * (temperature[ci] - old[ci]) / dt;
            }
        }
        (storage, peak, time)
    } else {
        (0.0, maximum, 0.0)
    };
    let imbalance = system.heat_input_w - heat_rejected - storage_rate;
    let denominator = system
        .heat_input_w
        .abs()
        .max(heat_rejected.abs())
        .max(storage_rate.abs())
        .max(1e-9);
    Ok(ThermalResult {
        temperatures_c: temperature.iter().map(|v| *v as f32).collect(),
        history,
        minimum_temperature_c: minimum,
        mean_temperature_c: mean,
        maximum_temperature_c: maximum,
        hotspot_mm: hotspot,
        heat_input_w: system.heat_input_w,
        heat_rejected_w: heat_rejected,
        storage_rate_w: storage_rate,
        energy_balance_relative: imbalance.abs() / denominator,
        iterations,
        relative_residual,
        time_steps,
        final_time_seconds,
        peak_temperature_c: peak_temperature,
        peak_time_seconds: peak_time,
        exposed_heated_area_mm2: system.heated_area_mm2,
        exposed_cooled_area_mm2: system.cooled_area_mm2,
    })
}

fn heat_rejected_w(options: &ThermalOptions, system: &ThermalSystem, temperature: &[f64]) -> f64 {
    let mut out = 0.0;
    for boundary in &system.boundaries {
        let t = temperature[boundary.cell];
        if boundary.face == options.cooled_face {
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
    }
    out
}

fn temperature_extremes(
    grid: &VoxelGrid,
    material_fraction: &[f32],
    temperature: &[f64],
    active: &[bool],
) -> (f64, f64, f64) {
    let (min, mean, max, _) =
        temperature_extremes_with_location(grid, material_fraction, temperature, active);
    (min, mean, max)
}

fn temperature_extremes_with_location(
    grid: &VoxelGrid,
    material_fraction: &[f32],
    temperature: &[f64],
    active: &[bool],
) -> (f64, f64, f64, [f64; 3]) {
    let mut minimum = f64::INFINITY;
    let mut maximum = f64::NEG_INFINITY;
    let mut hotspot = [0.0; 3];
    let mut weighted_sum = 0.0;
    let mut weight_sum = 0.0;
    for ci in 0..temperature.len() {
        if !active[ci] {
            continue;
        }
        let t = temperature[ci];
        minimum = minimum.min(t);
        if t > maximum {
            maximum = t;
            let cx = ci % grid.nx;
            let cy = (ci / grid.nx) % grid.ny;
            let cz = ci / (grid.nx * grid.ny);
            hotspot = [
                grid.origin[0] + (cx as f64 + 0.5) * grid.h,
                grid.origin[1] + (cy as f64 + 0.5) * grid.h,
                grid.origin[2] + (cz as f64 + 0.5) * grid.h,
            ];
        }
        let w = material_fraction[ci] as f64;
        weighted_sum += w * t;
        weight_sum += w;
    }
    (
        minimum,
        weighted_sum / weight_sum.max(1e-30),
        maximum,
        hotspot,
    )
}

fn max_difference(a: &[f64], b: &[f64], active: &[bool]) -> f64 {
    let mut maximum: f64 = 0.0;
    for i in 0..a.len() {
        if active[i] {
            maximum = maximum.max((a[i] - b[i]).abs());
        }
    }
    maximum
}

/// Assemble thermal expansion as an equivalent structural nodal force. The
/// local eigenstrain is `[alpha_xy*dT, alpha_xy*dT, alpha_z*dT]` per cell.
pub fn thermal_eigen_forces(
    grid: &VoxelGrid,
    stiffness_factor: &[f32],
    temperatures_c: &[f32],
    reference_temperature_c: f64,
    e0_mpa: f64,
    nu: f64,
    alpha_xy_per_k: f64,
    alpha_z_per_k: f64,
) -> Result<Vec<(u32, [f64; 3])>, String> {
    if stiffness_factor.len() != grid.cell_count() || temperatures_c.len() != grid.cell_count() {
        return Err("thermal structural fields do not match the voxel grid".into());
    }
    let (mx, my) = (grid.nx + 1, grid.ny + 1);
    let mut dense = vec![0.0; 3 * mx * my * (grid.nz + 1)];
    let coeff = grid.h * grid.h / 4.0;
    for cz in 0..grid.nz {
        for cy in 0..grid.ny {
            for cx in 0..grid.nx {
                let ci = grid.cell_index(cx, cy, cz);
                let eps = stiffness_factor[ci] as f64;
                if eps <= 0.0 {
                    continue;
                }
                let dt = temperatures_c[ci] as f64 - reference_temperature_c;
                let eigen = [alpha_xy_per_k * dt, alpha_xy_per_k * dt, alpha_z_per_k * dt];
                let lam = e0_mpa * nu / ((1.0 + nu) * (1.0 - 2.0 * nu));
                let mu = e0_mpa / (2.0 * (1.0 + nu));
                let tr = eigen[0] + eigen[1] + eigen[2];
                let stress = [
                    lam * tr + 2.0 * mu * eigen[0],
                    lam * tr + 2.0 * mu * eigen[1],
                    lam * tr + 2.0 * mu * eigen[2],
                ];
                let f0 = [
                    coeff * eps * stress[0],
                    coeff * eps * stress[1],
                    coeff * eps * stress[2],
                ];
                for l in 0..8 {
                    let [ox, oy, oz] = NODE_OFFSETS[l];
                    let [sx, sy, sz] = NODE_SIGNS[l];
                    let node = ((cz + oz) * my + cy + oy) * mx + cx + ox;
                    dense[3 * node] += f0[0] * sx;
                    dense[3 * node + 1] += f0[1] * sy;
                    dense[3 * node + 2] += f0[2] * sz;
                }
            }
        }
    }
    Ok(dense
        .chunks_exact(3)
        .enumerate()
        .filter(|(_, f)| f[0] != 0.0 || f[1] != 0.0 || f[2] != 0.0)
        .map(|(node, f)| (node as u32, [f[0], f[1], f[2]]))
        .collect())
}

/// Temperature-dependent modulus/strength retention. Linear degradation is a
/// deliberately conservative interpolation between a reference temperature
/// and the user/preset service limit; values above the limit stay at `floor`.
pub fn property_retention(
    temperature_c: f64,
    reference_temperature_c: f64,
    service_limit_c: f64,
    floor: f64,
) -> f64 {
    let floor = floor.clamp(0.001, 1.0);
    if temperature_c <= reference_temperature_c {
        return 1.0;
    }
    if service_limit_c <= reference_temperature_c + 1e-9 {
        return floor;
    }
    let fraction =
        ((temperature_c - reference_temperature_c) / (service_limit_c - reference_temperature_c))
            .clamp(0.0, 1.0);
    1.0 - (1.0 - floor) * fraction
}

/// Per-cell von Mises stress with a local thermal eigenstrain and the same
/// temperature-reduced stiffness field used by the structural solve.
pub fn thermal_von_mises(
    grid: &VoxelGrid,
    displacements: &[f32],
    e0_mpa: f64,
    nu: f64,
    stiffness_factor: &[f32],
    temperatures_c: &[f32],
    reference_temperature_c: f64,
    alpha_xy_per_k: f64,
    alpha_z_per_k: f64,
) -> Result<Vec<f32>, String> {
    let node_count = (grid.nx + 1) * (grid.ny + 1) * (grid.nz + 1);
    if displacements.len() != 3 * node_count
        || stiffness_factor.len() != grid.cell_count()
        || temperatures_c.len() != grid.cell_count()
    {
        return Err("thermal stress fields do not match the voxel grid".into());
    }
    let (mx, my) = (grid.nx + 1, grid.ny + 1);
    let inv4h = 1.0 / (4.0 * grid.h);
    let mut out = vec![0.0f32; grid.cell_count()];
    for cz in 0..grid.nz {
        for cy in 0..grid.ny {
            for cx in 0..grid.nx {
                let ci = grid.cell_index(cx, cy, cz);
                let eps = stiffness_factor[ci] as f64;
                if eps <= 0.0 {
                    continue;
                }
                let mut exx = 0.0;
                let mut eyy = 0.0;
                let mut ezz = 0.0;
                let mut gxy = 0.0;
                let mut gyz = 0.0;
                let mut gzx = 0.0;
                for l in 0..8 {
                    let [ox, oy, oz] = NODE_OFFSETS[l];
                    let [sx, sy, sz] = NODE_SIGNS[l];
                    let node = ((cz + oz) * my + cy + oy) * mx + cx + ox;
                    let ux = displacements[3 * node] as f64;
                    let uy = displacements[3 * node + 1] as f64;
                    let uz = displacements[3 * node + 2] as f64;
                    exx += sx * ux;
                    eyy += sy * uy;
                    ezz += sz * uz;
                    gxy += sy * ux + sx * uy;
                    gyz += sz * uy + sy * uz;
                    gzx += sx * uz + sz * ux;
                }
                exx *= inv4h;
                eyy *= inv4h;
                ezz *= inv4h;
                gxy *= inv4h;
                gyz *= inv4h;
                gzx *= inv4h;
                let dt = temperatures_c[ci] as f64 - reference_temperature_c;
                exx -= alpha_xy_per_k * dt;
                eyy -= alpha_xy_per_k * dt;
                ezz -= alpha_z_per_k * dt;
                let e = e0_mpa * eps;
                let lam = e * nu / ((1.0 + nu) * (1.0 - 2.0 * nu));
                let mu = e / (2.0 * (1.0 + nu));
                let tr = exx + eyy + ezz;
                let sxx = lam * tr + 2.0 * mu * exx;
                let syy = lam * tr + 2.0 * mu * eyy;
                let szz = lam * tr + 2.0 * mu * ezz;
                let sxy = mu * gxy;
                let syz = mu * gyz;
                let szx = mu * gzx;
                out[ci] = (0.5
                    * ((sxx - syy).powi(2) + (syy - szz).powi(2) + (szz - sxx).powi(2))
                    + 3.0 * (sxy * sxy + syz * syz + szx * szx))
                    .sqrt() as f32;
            }
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn base_options() -> ThermalOptions {
        ThermalOptions {
            transient: false,
            conductivity_w_m_k: [0.2, 0.2, 0.2],
            density_kg_m3: 1_240.0,
            specific_heat_j_kg_k: 1_800.0,
            conductivity_exponent: 1.0,
            ambient_temperature_c: 20.0,
            initial_temperature_c: 20.0,
            cooled_temperature_c: 20.0,
            convection_w_m2_k: 0.0,
            emissivity: 0.0,
            heated_face: ThermalFace::XMin,
            cooled_face: ThermalFace::XMax,
            heat_power_w: 1.0,
            volumetric_power_w: 0.0,
            duration_seconds: 10.0,
            time_step_seconds: 1.0,
            tolerance: 1e-8,
        }
    }

    #[test]
    fn steady_bar_is_hotter_at_the_heated_end_and_balances_power() {
        let grid = VoxelGrid::solid_box(12, 1, 1, 1.0);
        let result = solve_thermal(&grid, &vec![1.0; grid.cell_count()], &base_options()).unwrap();
        assert!(result.maximum_temperature_c > result.minimum_temperature_c);
        assert!(result.energy_balance_relative < 1e-5, "{:?}", result.energy_balance_relative);
        assert!((result.heat_rejected_w - 1.0).abs() < 1e-5);
    }

    #[test]
    fn transient_implicit_solution_warms_monotonically() {
        let grid = VoxelGrid::solid_box(6, 2, 2, 1.0);
        let mut options = base_options();
        options.transient = true;
        options.duration_seconds = 5.0;
        options.time_step_seconds = 0.5;
        options.convection_w_m2_k = 8.0;
        let result = solve_thermal(&grid, &vec![1.0; grid.cell_count()], &options).unwrap();
        let maxima: Vec<f64> = result.history.chunks_exact(3).map(|v| v[1]).collect();
        assert!(maxima.windows(2).all(|w| w[1] + 1e-8 >= w[0]));
        assert_eq!(result.time_steps, 10);
    }

    #[test]
    fn zero_delta_temperature_produces_no_eigen_force() {
        let grid = VoxelGrid::solid_box(2, 2, 2, 1.0);
        let forces = thermal_eigen_forces(
            &grid,
            &vec![1.0; grid.cell_count()],
            &vec![20.0; grid.cell_count()],
            20.0,
            2_400.0,
            0.35,
            90e-6,
            110e-6,
        )
        .unwrap();
        assert!(forces.is_empty());
    }
}
'''

WASM_OPTS = r'''
/// EnderSlicer thermal integrity extension v1: service-temperature conduction
/// and thermo-mechanical coupling, distinct from the print-build shrink model.
#[derive(serde::Deserialize)]
#[serde(rename_all = "camelCase", default)]
struct ThermalIntegrityOpts {
    mode: String,
    material_name: String,
    #[serde(rename = "conductivityXWmK")]
    conductivity_x: f64,
    #[serde(rename = "conductivityYWmK")]
    conductivity_y: f64,
    #[serde(rename = "conductivityZWmK")]
    conductivity_z: f64,
    #[serde(rename = "densityKgM3")]
    density_kg_m3: f64,
    #[serde(rename = "specificHeatJkgK")]
    specific_heat: f64,
    conductivity_exponent: f64,
    #[serde(rename = "alphaXyPerK")]
    alpha_xy: f64,
    #[serde(rename = "alphaZPerK")]
    alpha_z: f64,
    #[serde(rename = "youngsModulusMpa")]
    youngs_modulus: f64,
    poisson_ratio: f64,
    #[serde(rename = "referenceStrengthMpa")]
    reference_strength: f64,
    strength_density_exponent: f64,
    reference_temperature_c: f64,
    service_limit_c: f64,
    modulus_floor_fraction: f64,
    strength_floor_fraction: f64,
    ambient_temperature_c: f64,
    initial_temperature_c: f64,
    cooled_temperature_c: f64,
    #[serde(rename = "convectionWm2K")]
    convection: f64,
    emissivity: f64,
    heated_face: String,
    cooled_face: String,
    heat_power_w: f64,
    volumetric_power_w: f64,
    duration_seconds: f64,
    time_step_seconds: f64,
    free_expansion: bool,
    density_aware: bool,
    infill_pct: f64,
    exponent: f64,
    coeff: f64,
    perimeters: u32,
    line_width: f64,
    top_bottom_layers: u32,
    layer_height: f64,
}

impl Default for ThermalIntegrityOpts {
    fn default() -> Self {
        Self {
            mode: "steady".into(),
            material_name: "PLA".into(),
            conductivity_x: 0.18,
            conductivity_y: 0.18,
            conductivity_z: 0.13,
            density_kg_m3: 1_240.0,
            specific_heat: 1_800.0,
            conductivity_exponent: 1.0,
            alpha_xy: 96e-6,
            alpha_z: 110e-6,
            youngs_modulus: 2_400.0,
            poisson_ratio: 0.35,
            reference_strength: 45.0,
            strength_density_exponent: 1.5,
            reference_temperature_c: 23.0,
            service_limit_c: 50.0,
            modulus_floor_fraction: 0.05,
            strength_floor_fraction: 0.05,
            ambient_temperature_c: 23.0,
            initial_temperature_c: 23.0,
            cooled_temperature_c: 23.0,
            convection: 8.0,
            emissivity: 0.9,
            heated_face: "zmax".into(),
            cooled_face: "zmin".into(),
            heat_power_w: 5.0,
            volumetric_power_w: 0.0,
            duration_seconds: 600.0,
            time_step_seconds: 10.0,
            free_expansion: true,
            density_aware: true,
            infill_pct: 25.0,
            exponent: 1.5,
            coeff: 1.0,
            perimeters: 2,
            line_width: 0.45,
            top_bottom_layers: 5,
            layer_height: 0.2,
        }
    }
}
'''

WASM_METHOD = r'''
    /// EnderSlicer thermal integrity extension v1.
    ///
    /// Solves steady or implicit-transient anisotropic heat conduction on the
    /// current voxel grid, then couples the resulting local temperature field
    /// into the existing structural FEA through thermal eigenstrain and
    /// temperature-reduced stiffness. Current mechanical loads are retained.
    /// `freeExpansion` removes structural supports and applies the stress-free
    /// 3-2-1 rigid-body grounding; constrained mode uses the active supports.
    ///
    /// Return array: [stats JSON, cell temperatures f32, history f64,
    /// per-mesh-vertex structural displacements f32, material fraction f32].
    pub fn solve_thermal_integrity(&mut self, opts_json: &str) -> Result<js_sys::Array, JsValue> {
        let opts: ThermalIntegrityOpts = serde_json::from_str(opts_json).map_err(err)?;
        self.ensure_grid()?;
        let (grid, levels) = {
            let (grid, levels) = self.grid.as_ref().unwrap();
            (grid.clone(), *levels)
        };
        let heated_face =
            filasim_core::thermal::ThermalFace::parse(&opts.heated_face)
                .ok_or_else(|| err("unsupported heated face"))?;
        let cooled_face =
            filasim_core::thermal::ThermalFace::parse(&opts.cooled_face)
                .ok_or_else(|| err("unsupported cooled face"))?;

        let (_, wall_mm) = resolve_wall(opts.perimeters, opts.line_width);
        let tb_mm = (opts.top_bottom_layers.min(20) as f64
            * opts.layer_height.clamp(0.04, 0.6))
            .min(5.0);
        let split = filasim_core::simp::classify_cells(
            &grid,
            wall_mm,
            tb_mm,
            tb_mm,
            self.composite_skin,
        );
        let fallback_density = (opts.infill_pct / 100.0).clamp(0.01, 1.0);
        let use_optimized = opts.density_aware && self.opt.is_some();
        let x: Vec<f64> = split
            .design
            .iter()
            .map(|cell| {
                if use_optimized {
                    self.opt
                        .as_ref()
                        .and_then(|opt| opt.cell_density.get(cell))
                        .copied()
                        .unwrap_or(fallback_density)
                } else {
                    fallback_density
                }
                .clamp(0.01, 1.0)
            })
            .collect();
        let material_fraction =
            filasim_core::simp::build_vfrac(&grid, &split.design, &split.skin_frac, &x);
        let base_eps = filasim_core::simp::build_eps(
            &grid,
            &split.skin,
            &split.design,
            &split.skin_frac,
            &x,
            opts.exponent.clamp(1.0, 3.5),
            opts.coeff.clamp(0.05, 2.0),
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
        let thermal =
            filasim_core::thermal::solve_thermal(&grid, &material_fraction, &thermal_options)
                .map_err(err)?;

        let e0 = opts.youngs_modulus.clamp(1.0, 1.0e7);
        let nu = opts.poisson_ratio.clamp(-0.49, 0.49);
        let reference_temperature = opts.reference_temperature_c;
        let service_limit = opts.service_limit_c;
        let modulus_floor = opts.modulus_floor_fraction.clamp(0.001, 1.0);
        let strength_floor = opts.strength_floor_fraction.clamp(0.001, 1.0);
        let mut temperature_eps = base_eps.clone();
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

        let mut structural_bcs = self.bcs.clone();
        if opts.free_expansion {
            structural_bcs.retain(|bc| {
                matches!(
                    &bc.kind,
                    BcKind::Force(_)
                        | BcKind::Pressure(_)
                        | BcKind::Bearing(_)
                        | BcKind::Moment(_)
                        | BcKind::Mass { .. }
                )
            });
        }
        let solve_settings = SolveSettings {
            e0,
            nu,
            ti: None,
            ..self.settings
        };
        let mut assembled = assemble(
            &self.mesh,
            &grid,
            &structural_bcs,
            self.body_arg(&material_fraction),
            &solve_settings,
        )
        .map_err(err)?;
        if opts.free_expansion {
            filasim_core::buildsim::ground_rigid_body(&grid, &mut assembled.problem)
                .ok_or_else(|| err("free-expansion grounding could not find three independent nodes"))?;
        }
        if !opts.free_expansion {
            let report = check_problem(&grid, &assembled);
            if !report.ok {
                return Err(err(
                    "thermal structural model is under-constrained; add supports or use free expansion",
                ));
            }
        }
        assembled.problem.forces.extend(
            filasim_core::thermal::thermal_eigen_forces(
                &grid,
                &temperature_eps,
                &thermal.temperatures_c,
                reference_temperature,
                e0,
                nu,
                opts.alpha_xy,
                opts.alpha_z,
            )
            .map_err(err)?,
        );
        let (solution, _compliance) = filasim_core::simp::solve_with_eps_cached(
            &mut self.solver_cache,
            &grid,
            levels,
            &assembled.problem,
            &solve_settings,
            temperature_eps.clone().into(),
        )
        .map_err(err)?;
        let von_mises = filasim_core::thermal::thermal_von_mises(
            &grid,
            &solution.u,
            e0,
            nu,
            &temperature_eps,
            &thermal.temperatures_c,
            reference_temperature,
            opts.alpha_xy,
            opts.alpha_z,
        )
        .map_err(err)?;
        let strength_exponent = opts.strength_density_exponent.clamp(0.5, 4.0);
        let mut conservative_sf = f64::INFINITY;
        let mut max_von_mises: f64 = 0.0;
        for ci in 0..von_mises.len() {
            if grid.scale[ci] <= 0.0 || material_fraction[ci] <= 0.0 {
                continue;
            }
            let stress = von_mises[ci] as f64;
            max_von_mises = max_von_mises.max(stress);
            if stress <= 1e-9 {
                continue;
            }
            let retention = filasim_core::thermal::property_retention(
                thermal.temperatures_c[ci] as f64,
                reference_temperature,
                service_limit,
                strength_floor,
            );
            let density_strength = (material_fraction[ci] as f64)
                .powf(strength_exponent)
                .clamp(0.01, 1.0);
            let allowable = opts.reference_strength.max(0.1) * retention * density_strength;
            conservative_sf = conservative_sf.min(allowable / stress);
        }
        if !conservative_sf.is_finite() {
            conservative_sf = 10.0;
        }
        conservative_sf = conservative_sf.clamp(0.0, 10.0);
        let max_displacement = solution.max_displacement();
        let mesh_displacements = map_displacements(&self.mesh, &solution);
        let property_extrapolated = thermal.maximum_temperature_c > service_limit
            || thermal.minimum_temperature_c < reference_temperature - 50.0;
        let active_cells = material_fraction.iter().filter(|value| **value > 0.0).count();
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
            "maxDisplacementMm": max_displacement,
            "maxVonMisesMpa": max_von_mises,
            "minimumModulusRetention": minimum_modulus_retention,
            "minimumStrengthRetention": minimum_strength_retention,
            "conservativeSafetyFactor": conservative_sf,
            "temperatureMarginC": service_limit - thermal.maximum_temperature_c,
            "propertyExtrapolated": property_extrapolated,
            "densityAware": use_optimized,
            "activeCells": active_cells,
            "nx": grid.nx,
            "ny": grid.ny,
            "nz": grid.nz,
            "h": grid.h,
            "structuralIterations": solution.iterations,
            "structuralResidual": solution.rel_residual,
            "structuralConverged": solution.converged,
        })
        .to_string();
        let array = js_sys::Array::new();
        array.push(&JsValue::from(stats));
        array.push(&js_sys::Float32Array::from(thermal.temperatures_c.as_slice()));
        array.push(&js_sys::Float64Array::from(thermal.history.as_slice()));
        array.push(&js_sys::Float32Array::from(mesh_displacements.as_slice()));
        array.push(&js_sys::Float32Array::from(material_fraction.as_slice()));
        Ok(array)
    }

'''

ENGINE_TYPES = r'''
/** EnderSlicer service-temperature thermal integrity solve. */
export interface ThermalIntegrityOptions {
  mode: "steady" | "transient";
  materialName: string;
  conductivityXWmK: number;
  conductivityYWmK: number;
  conductivityZWmK: number;
  densityKgM3: number;
  specificHeatJkgK: number;
  conductivityExponent: number;
  alphaXyPerK: number;
  alphaZPerK: number;
  youngsModulusMpa: number;
  poissonRatio: number;
  referenceStrengthMpa: number;
  strengthDensityExponent: number;
  referenceTemperatureC: number;
  serviceLimitC: number;
  modulusFloorFraction: number;
  strengthFloorFraction: number;
  ambientTemperatureC: number;
  initialTemperatureC: number;
  cooledTemperatureC: number;
  convectionWm2K: number;
  emissivity: number;
  heatedFace: "xmin" | "xmax" | "ymin" | "ymax" | "zmin" | "zmax";
  cooledFace: "xmin" | "xmax" | "ymin" | "ymax" | "zmin" | "zmax";
  heatPowerW: number;
  volumetricPowerW: number;
  durationSeconds: number;
  timeStepSeconds: number;
  freeExpansion: boolean;
  densityAware: boolean;
  infillPct: number;
  exponent: number;
  coeff: number;
  perimeters: number;
  lineWidth: number;
  topBottomLayers: number;
  layerHeight: number;
}

export interface ThermalIntegrityStats {
  materialName: string;
  mode: "steady" | "transient";
  minimumTemperatureC: number;
  meanTemperatureC: number;
  maximumTemperatureC: number;
  hotspotMm: [number, number, number];
  heatInputW: number;
  heatRejectedW: number;
  storageRateW: number;
  energyBalanceRelative: number;
  thermalIterations: number;
  thermalResidual: number;
  timeSteps: number;
  finalTimeSeconds: number;
  peakTemperatureC: number;
  peakTimeSeconds: number;
  heatedAreaMm2: number;
  cooledAreaMm2: number;
  maxDisplacementMm: number;
  maxVonMisesMpa: number;
  minimumModulusRetention: number;
  minimumStrengthRetention: number;
  conservativeSafetyFactor: number;
  temperatureMarginC: number;
  propertyExtrapolated: boolean;
  densityAware: boolean;
  activeCells: number;
  nx: number;
  ny: number;
  nz: number;
  h: number;
  structuralIterations: number;
  structuralResidual: number;
  structuralConverged: boolean;
}

'''

ENGINE_METHOD = r'''
  /** Service-temperature thermal integrity solve with exact cell temperatures. */
  thermalIntegrity(
    opts: ThermalIntegrityOptions
  ): Promise<{
    stats: ThermalIntegrityStats;
    temperatures: Float32Array;
    history: Float64Array;
    displacements: Float32Array;
    materialFraction: Float32Array;
  }> {
    this.resetProgress();
    return this.call({ op: "thermalIntegrity", opts });
  }

'''

WORKER_CASE = r'''
      case "thermalIntegrity": {
        if (cancelArr) Atomics.store(cancelArr, 0, 0);
        const t0 = performance.now();
        const array = requireModel().solve_thermal_integrity(JSON.stringify(msg.opts));
        const stats = JSON.parse(array[0] as string);
        const temperatures = array[1] as Float32Array;
        const history = array[2] as Float64Array;
        const displacements = array[3] as Float32Array;
        const materialFraction = array[4] as Float32Array;
        stats.seconds = (performance.now() - t0) / 1000;
        reply(
          msg,
          { stats, temperatures, history, displacements, materialFraction },
          [
            temperatures.buffer,
            history.buffer,
            displacements.buffer,
            materialFraction.buffer,
          ]
        );
        return;
      }
'''

def apply(source_root: pathlib.Path) -> None:
    source_root = source_root.resolve()
    core_lib = source_root / "crates/filasim-core/src/lib.rs"
    core_thermal = source_root / "crates/filasim-core/src/thermal.rs"
    wasm_lib = source_root / "crates/filasim-wasm/src/lib.rs"
    protocol = source_root / "web/src/engine/EngineProtocol.ts"
    client = source_root / "web/src/engine/EngineClient.ts"
    worker = source_root / "web/src/worker/engine.worker.ts"
    for path in (core_lib, wasm_lib, protocol, client, worker):
        if not path.is_file():
            raise RuntimeError(f"Pinned filaSim thermal patch target is missing: {path}")

    core_thermal.write_text(THERMAL_RS, encoding="utf-8")
    replace_once(core_lib, "pub mod stress;\n", "pub mod stress;\npub mod thermal;\n", "core module list")

    replace_once(
        wasm_lib,
        "/// True when all 8 nodes of cell `ci` are active (the cell has been printed).\n",
        WASM_OPTS + "\n/// True when all 8 nodes of cell `ci` are active (the cell has been printed).\n",
        "thermal WASM options",
    )
    replace_once(
        wasm_lib,
        "    /// FDM build simulation (inherent strain, see `filasim_core::buildsim`): predict\n",
        WASM_METHOD
        + "    /// FDM build simulation (inherent strain, see `filasim_core::buildsim`): predict\n",
        "thermal WASM method",
    )

    replace_once(
        client,
        "/** Mirrors the wasm BuildSimOpts (serialized to JSON in the worker). */\n",
        ENGINE_TYPES + "/** Mirrors the wasm BuildSimOpts (serialized to JSON in the worker). */\n",
        "thermal EngineClient types",
    )
    replace_once(
        client,
        "  /** FDM build simulation (inherent strain): warping + bed peel. Leaves the\n",
        ENGINE_METHOD
        + "  /** FDM build simulation (inherent strain): warping + bed peel. Leaves the\n",
        "thermal EngineClient method",
    )

    replace_once(
        protocol,
        "  BuildSimOptions,\n  BuildSimStats,\n",
        "  BuildSimOptions,\n  BuildSimStats,\n  ThermalIntegrityOptions,\n  ThermalIntegrityStats,\n",
        "thermal protocol imports",
    )
    replace_once(
        protocol,
        "  /** BuildSimOpts object — serialized to JSON for the wasm API. */\n  buildSim: { opts: BuildSimOptions };\n",
        "  /** EnderSlicer service-temperature thermal integrity solve. */\n"
        "  thermalIntegrity: { opts: ThermalIntegrityOptions };\n"
        "  /** BuildSimOpts object — serialized to JSON for the wasm API. */\n"
        "  buildSim: { opts: BuildSimOptions };\n",
        "thermal protocol request",
    )
    replace_once(
        protocol,
        "  buildSim: SolveResult<BuildSimStats>;\n",
        "  thermalIntegrity: {\n"
        "    stats: ThermalIntegrityStats;\n"
        "    temperatures: Float32Array;\n"
        "    history: Float64Array;\n"
        "    displacements: Float32Array;\n"
        "    materialFraction: Float32Array;\n"
        "  };\n"
        "  buildSim: SolveResult<BuildSimStats>;\n",
        "thermal protocol response",
    )

    replace_once(
        worker,
        '      case "buildSim": {\n',
        WORKER_CASE + '      case "buildSim": {\n',
        "thermal worker operation",
    )

    marker = source_root / ".enderslicer-thermal-integrity"
    marker.write_text(MARKER + "\n", encoding="utf-8")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
