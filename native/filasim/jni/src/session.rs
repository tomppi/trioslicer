// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 TrioSlicer contributors.
//
//! Native filaSim session: the structural Smart Infill pipeline without a
//! browser.
//!
//! This mirrors the pinned upstream 'filasim-wasm' session semantics — the
//! option resolution, the printable-geometry clamps, the gram conversion and
//! the goal handling — on top of the unmodified 'filasim-core' engine, so a
//! package produced here is the package the WebView produced. The differences
//! are only in the boundary: JSON strings and plain arrays instead of
//! 'JsValue', and the core's own cancellation/progress hooks instead of a
//! SharedArrayBuffer.

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use filasim_core::attach::{assemble, check_problem, BcKind, BcSpec, BodyLoad};
use filasim_core::bins::RegionMesh;
use filasim_core::mesh::TriMesh;
use filasim_core::pipeline::{run_optimization, PipelineCfg, PipelinePhase};
use filasim_core::segment::{body_count, segment, Segmentation};
use filasim_core::simp::OptimizeParams;
use filasim_core::solve::{
    active_nodes, pad_for_levels, solve_nodes_cached, SolveSettings, Solution, SolverCache,
};
use filasim_core::threemf::{export_stl_zip, import_3mf};
use filasim_core::voxel::VoxelGrid;

/// Printable-geometry clamp bands — kept identical to the upstream adapter so
/// the two agree on what a given perimeters/line-width pair means.
const PERIMETERS_RANGE: (u32, u32) = (1, 8);
const LINE_WIDTH_MM: (f64, f64) = (0.1, 1.5);
const WALL_MM: (f64, f64) = (0.2, 5.0);

fn resolve_wall(perimeters: u32, line_width: f64) -> (u32, f64) {
    let p = perimeters.clamp(PERIMETERS_RANGE.0, PERIMETERS_RANGE.1);
    let lw = line_width.clamp(LINE_WIDTH_MM.0, LINE_WIDTH_MM.1);
    (p, (p as f64 * lw).clamp(WALL_MM.0, WALL_MM.1))
}

/// Patch ids computed on the original mesh, carried onto the subdivided
/// working mesh (each child inherits its parent triangle's patch).
fn remap_segmentation(orig: &Segmentation, parents: &[u32]) -> Segmentation {
    Segmentation {
        patch_of_tri: parents.iter().map(|&p| orig.patch_of_tri[p as usize]).collect(),
        patch_count: orig.patch_count,
    }
}

fn import_any(bytes: &[u8]) -> Result<(TriMesh, usize), String> {
    if bytes.len() >= 2 && &bytes[..2] == b"PK" {
        let (mesh, objects) = import_3mf(bytes).map_err(|e| e.to_string())?;
        return Ok((mesh, objects));
    }
    Ok((TriMesh::from_stl(bytes).map_err(|e| e.to_string())?, 1))
}

/// One finished optimization, in the shape the UI and the exporter need.
pub struct OptOutput {
    /// Smoothed regions (display + modifier export).
    pub regions: Vec<RegionMesh>,
    /// Raw marching-tets regions before smoothing.
    pub regions_raw: Vec<RegionMesh>,
    pub base_density: f64,
    /// Final per-design-cell binned density.
    pub cell_density: HashMap<u32, f64>,
    pub perimeters: u32,
    pub top_bottom_layers: u32,
    pub solid_pattern: Option<String>,
    pub summary: String,
    pub solid: bool,
    pub centers: Vec<f64>,
    pub bins: Vec<u8>,
    pub x_binned: Vec<f32>,
    pub wall_mm: f64,
    pub tb_mm: f64,
    pub eval_exp: f64,
    pub eval_coeff: f64,
    pub smooth_iters: u32,
    pub binary: bool,
}

/// Live telemetry of a running optimize/solve, polled by the UI thread.
#[derive(Clone, Debug, Default)]
pub struct ProgressState {
    pub phase: String,
    pub iteration: u32,
    pub max_iter: u32,
    pub pass: u32,
    pub passes: u32,
    pub budget: f64,
    pub compliance: f64,
    pub mass_frac: f64,
    pub mean_infill: f64,
    pub change: f64,
    pub inner_iters: u32,
    pub inner_residual: f64,
    pub running: bool,
}

impl ProgressState {
    pub fn to_json(&self) -> String {
        serde_json::json!({
            "phase": self.phase,
            "iteration": self.iteration,
            "maxIter": self.max_iter,
            "pass": self.pass,
            "passes": self.passes,
            "budget": self.budget,
            "compliance": self.compliance,
            "massFrac": self.mass_frac,
            "meanInfill": self.mean_infill,
            "change": self.change,
            "innerIters": self.inner_iters,
            "innerResidual": self.inner_residual,
            "running": self.running,
        })
        .to_string()
    }
}

/// Vertex-sharing adjacency over the ORIGINAL triangle soup, built on the
/// first bounded pick and reused: the mesh never changes after import, and
/// rebuilding it per tap costs tens of milliseconds on a dense model.
struct PickIndex {
    /// Welded vertex id per original triangle corner.
    corners: Vec<[u32; 3]>,
    /// Welded vertex id -> incident original triangles.
    incident: HashMap<u32, Vec<u32>>,
}

/// The structural Smart Infill session: mesh, boundary conditions, solve and
/// the optimized design. One instance belongs to one loaded model.
pub struct Session {
    /// Working mesh: segmentation, BC attachment, voxelization.
    mesh: TriMesh,
    /// Original tessellation as imported — modifiers and exports come from here.
    mesh_orig: TriMesh,
    /// Original-triangle index per working triangle.
    parents: Vec<u32>,
    name: String,
    bodies: usize,
    seg: Segmentation,
    /// Lazily built adjacency for a bounded tap pick (a whole smooth hull is
    /// one crease patch, so a tap may not use the patch as its selection).
    pick_index: Option<PickIndex>,
    bcs: Vec<BcSpec>,
    settings: SolveSettings,
    /// tonne/mm3
    density: f64,
    /// Tensile strength (MPa) of the solid material.
    strength: f64,
    /// Layer-adhesion strength (MPa), perpendicular to the layers.
    strength_z: f64,
    /// Measured interlayer shear strength (MPa); None = 0.6 * strength_z.
    shear_strength_z: Option<f64>,
    layer_shear_on: bool,
    accel: [f64; 3],
    target_cells: u32,
    fixed_h: Option<f64>,
    snap_wall: f64,
    composite_skin: bool,
    grid: Option<(VoxelGrid, usize)>,
    solver_cache: Option<SolverCache>,
    solution: Option<Solution>,
    solution_eps: Option<Vec<f32>>,
    opt: Option<OptOutput>,
    cancel: Arc<AtomicBool>,
    progress: Arc<Mutex<ProgressState>>,
}

impl Session {
    /// Import an STL (binary or ASCII) or 3MF and build the analysis session.
    pub fn new_from_bytes(bytes: &[u8], name: &str) -> Result<Session, String> {
        let (mesh_orig, objects) = import_any(bytes)?;
        Ok(Self::from_import(mesh_orig, objects, name))
    }

    fn from_import(mesh_orig: TriMesh, mesh_objects: usize, name: &str) -> Session {
        // Refine the display/analysis tessellation: edges capped at ~1/60 of
        // the diagonal, with the upstream 160k-triangle budget.
        let (mesh, parents) = match mesh_orig.bounds() {
            Some((lo, hi)) => {
                let diag = ((hi[0] - lo[0]).powi(2)
                    + (hi[1] - lo[1]).powi(2)
                    + (hi[2] - lo[2]).powi(2))
                .sqrt();
                mesh_orig.subdivided_with_parents(diag / 60.0, 160_000)
            }
            None => (mesh_orig.clone(), (0..mesh_orig.len() as u32).collect()),
        };
        let seg = remap_segmentation(&segment(&mesh_orig, 10.0), &parents);
        let bodies = body_count(&mesh_orig);
        let _ = mesh_objects;
        Session {
            mesh,
            mesh_orig,
            parents,
            name: name.to_string(),
            bodies,
            seg,
            pick_index: None,
            bcs: Vec::new(),
            settings: SolveSettings::default(),
            density: 1.24e-9,
            strength: 50.0,
            strength_z: 35.0,
            shear_strength_z: None,
            layer_shear_on: true,
            accel: [0.0; 3],
            target_cells: 300_000,
            fixed_h: None,
            snap_wall: 0.0,
            composite_skin: false,
            grid: None,
            solver_cache: None,
            solution: None,
            solution_eps: None,
            opt: None,
            cancel: Arc::new(AtomicBool::new(false)),
            progress: Arc::new(Mutex::new(ProgressState::default())),
        }
    }

    // ---- identity and geometry the UI needs for picking ----

    pub fn name(&self) -> &str {
        &self.name
    }

    pub fn bodies(&self) -> usize {
        self.bodies
    }

    pub fn working_triangle_count(&self) -> usize {
        self.mesh.len()
    }

    pub fn original_triangle_count(&self) -> usize {
        self.mesh_orig.len()
    }

    /// Flattened xyz per ORIGINAL triangle vertex (9 f32 per triangle) — the
    /// picking and centroid source, matching the STL the app displays.
    pub fn original_positions(&self) -> Vec<f32> {
        let mut out = Vec::with_capacity(self.mesh_orig.tris.len() * 9);
        for t in &self.mesh_orig.tris {
            out.extend_from_slice(t);
        }
        out
    }

    pub fn patch_count(&self) -> u32 {
        self.seg.patch_count as u32
    }

    /// Patch id per ORIGINAL triangle — the picker maps a hit to its patch.
    pub fn patch_of_original_triangle(&self) -> Vec<u32> {
        let mut of_orig = vec![0u32; self.mesh_orig.len()];
        for (tri, &parent) in self.parents.iter().enumerate() {
            let p = parent as usize;
            if p < of_orig.len() {
                of_orig[p] = self.seg.patch_of_tri[tri];
            }
        }
        of_orig
    }

    /// Every ORIGINAL triangle carrying the given patch (what the UI feeds a
    /// boundary condition when the user picks a surface).
    pub fn original_triangles_of_patch(&self, patch: u32) -> Vec<u32> {
        self.patch_of_original_triangle()
            .iter()
            .enumerate()
            .filter(|(_, &p)| p == patch)
            .map(|(i, _)| i as u32)
            .collect()
    }

    /// The connected part of [original]'s surface patch within `radius_mm` of
    /// that triangle's centre — what a tap on the model selects.
    ///
    /// The crease segmentation is a face finder, not a selection: an organic
    /// surface is ONE patch (a 3DBenchy hull is 70% of its triangles), so a tap
    /// that took the patch would select most of the model. The UI therefore
    /// bounds the tap by a radius the user can resize, and offers the whole
    /// patch as an explicit second step ([`original_triangles_of_patch`]).
    pub fn region_around(&mut self, original: u32, radius_mm: f64) -> Vec<u32> {
        let count = self.mesh_orig.len();
        if original as usize >= count {
            return Vec::new();
        }
        let patch = self.patch_of_original_triangle();
        let target = patch[original as usize];
        let radius = if radius_mm.is_finite() { radius_mm.max(0.0) } else { 0.0 };
        let seed = tri_centre(&self.mesh_orig.tris[original as usize]);
        self.ensure_pick_index();
        let index = self.pick_index.as_ref().expect("pick index is built");
        let mut seen = vec![false; count];
        let mut selected = Vec::new();
        let mut stack = vec![original];
        seen[original as usize] = true;
        while let Some(tri) = stack.pop() {
            // The radius bounds the selection; a triangle outside it is not
            // entered, so the walk never spills past the tap.
            if !within(&tri_centre(&self.mesh_orig.tris[tri as usize]), &seed, radius) {
                continue;
            }
            selected.push(tri);
            for corner in index.corners[tri as usize] {
                let Some(neighbours) = index.incident.get(&corner) else {
                    continue;
                };
                for &other in neighbours {
                    if !seen[other as usize] && patch[other as usize] == target {
                        seen[other as usize] = true;
                        stack.push(other);
                    }
                }
            }
        }
        selected.sort_unstable();
        selected
    }

    /// The density bin of the material just inside every ORIGINAL model triangle
    /// — what the result view tints the part with. -1 where no design cell was
    /// reached (or before an optimization).
    ///
    /// The binned field lives on the design cells, which exclude the printed
    /// skin, so a surface triangle is never inside one. The lookup marches from
    /// the triangle centroid along its normal until it meets a design cell — the
    /// infill that surface covers. A fixed neighbourhood cannot do this: it
    /// cannot know how thick the skin is, and on a coarse grid a whole side of
    /// the part then stays uncoloured.
    ///
    /// Both directions are walked and the nearer hit wins: a mesh's winding is
    /// not guaranteed (the CI beam is wound inwards), and a thin wall can have
    /// infill on either side.
    pub fn surface_bins(&self) -> Vec<i32> {
        let (Some(opt), Some((grid, _))) = (self.opt.as_ref(), self.grid.as_ref()) else {
            return Vec::new();
        };
        // Density per grid cell (0 = not a design cell), so the march indexes an
        // array instead of hashing once per sample.
        let mut field = vec![0.0f32; grid.cell_count()];
        for (&cell, &density) in &opt.cell_density {
            if let Some(slot) = field.get_mut(cell as usize) {
                *slot = density as f32;
            }
        }
        let centers: Vec<f32> = opt.centers.iter().map(|&c| c as f32).collect();
        let bin_of = |density: f32| -> i32 {
            centers
                .iter()
                .position(|&c| (c - density).abs() <= 1e-6)
                .map_or(-1, |index| index as i32)
        };
        // A point on the far face of the last cell is still in that cell, but
        // floor() puts it one past the grid.
        let cell_index = |value: f64, origin: f64, count: usize| -> Option<usize> {
            let raw = ((value - origin) / grid.h).floor();
            if raw < 0.0 {
                return None;
            }
            if raw <= count as f64 - 1.0 {
                Some(raw as usize)
            } else if raw <= count as f64 + 1e-9 {
                Some(count - 1)
            } else {
                None
            }
        };
        // Walk inwards past any sane skin, bounded so a degenerate normal cannot
        // scan the whole grid.
        // Far enough to cross a thick skin plus a coarse cell or two: a 3 mm
        // reach left the under-side of an organic part (3DBenchy: 17% of its
        // triangles) uncoloured, while the first hit along the way still wins.
        let reach = (8.0 / grid.h).ceil().clamp(4.0, 400.0) as i64;
        let mut out = Vec::with_capacity(self.mesh_orig.len());
        for t in &self.mesh_orig.tris {
            let centre = tri_centre(t);
            let normal = tri_normal(t);
            let mut bin = -1;
            for step in 0..=reach {
                let distance = grid.h * step as f64;
                let mut hit = None;
                for direction in [-1.0f64, 1.0f64] {
                    let point = [
                        centre[0] + direction * normal[0] * distance,
                        centre[1] + direction * normal[1] * distance,
                        centre[2] + direction * normal[2] * distance,
                    ];
                    let (Some(cx), Some(cy), Some(cz)) = (
                        cell_index(point[0], grid.origin[0], grid.nx),
                        cell_index(point[1], grid.origin[1], grid.ny),
                        cell_index(point[2], grid.origin[2], grid.nz),
                    ) else {
                        continue;
                    };
                    let density = field[grid.cell_index(cx, cy, cz)];
                    if density > 0.0 {
                        hit = Some(density);
                        break;
                    }
                }
                if let Some(density) = hit {
                    bin = bin_of(density);
                    break;
                }
            }
            out.push(bin);
        }
        out
    }
    /// Welds the original soup once, with the segmentation's own quantization,
    /// so two coincident corners of adjacent triangles share a vertex id.
    fn ensure_pick_index(&mut self) {
        if self.pick_index.is_some() {
            return;
        }
        let q = match self.mesh_orig.bounds() {
            Some((lo, hi)) => {
                let diag = ((hi[0] - lo[0]).powi(2)
                    + (hi[1] - lo[1]).powi(2)
                    + (hi[2] - lo[2]).powi(2))
                .sqrt();
                (diag * 1e-6).max(1e-9)
            }
            None => 1e-9,
        };
        let mut ids: HashMap<(i64, i64, i64), u32> = HashMap::new();
        let mut corners: Vec<[u32; 3]> = Vec::with_capacity(self.mesh_orig.len());
        let mut incident: HashMap<u32, Vec<u32>> = HashMap::new();
        for (tri, t) in self.mesh_orig.tris.iter().enumerate() {
            let mut welded = [0u32; 3];
            for v in 0..3 {
                let key = (
                    (t[3 * v] as f64 / q).round() as i64,
                    (t[3 * v + 1] as f64 / q).round() as i64,
                    (t[3 * v + 2] as f64 / q).round() as i64,
                );
                let next = ids.len() as u32;
                welded[v] = *ids.entry(key).or_insert(next);
            }
            for v in 0..3 {
                incident.entry(welded[v]).or_default().push(tri as u32);
            }
            corners.push(welded);
        }
        self.pick_index = Some(PickIndex { corners, incident });
    }

    /// Expand ORIGINAL triangle indices to the WORKING triangle indices the
    /// solver attaches boundary conditions to (subdivision children inherit
    /// their parent).
    pub fn working_triangles_for_original(&self, original: &[u32]) -> Vec<u32> {
        let wanted: std::collections::HashSet<u32> = original.iter().copied().collect();
        self.parents
            .iter()
            .enumerate()
            .filter(|(_, p)| wanted.contains(p))
            .map(|(i, _)| i as u32)
            .collect()
    }

    // ---- settings ----

    /// Material properties: E (MPa), Poisson ratio, density (tonne/mm3) and
    /// the strength allowables the safety factor is scored against.
    #[allow(clippy::too_many_arguments)]
    pub fn set_material(
        &mut self,
        youngs_modulus_mpa: Option<f64>,
        poisson: Option<f64>,
        density_tonne_mm3: Option<f64>,
        strength_mpa: Option<f64>,
        layer_strength_mpa: Option<f64>,
        shear_strength_mpa: Option<f64>,
        layer_shear_on: Option<bool>,
    ) {
        if let Some(e) = youngs_modulus_mpa {
            if e.is_finite() && e > 0.0 {
                self.settings.e0 = e;
            }
        }
        if let Some(nu) = poisson {
            if nu.is_finite() && (-0.99..0.49).contains(&nu) {
                self.settings.nu = nu;
            }
        }
        if let Some(d) = density_tonne_mm3 {
            if d.is_finite() && d > 0.0 {
                self.density = d;
            }
        }
        if let Some(s) = strength_mpa {
            if s.is_finite() && s > 0.0 {
                self.strength = s;
            }
        }
        if let Some(s) = layer_strength_mpa {
            if s.is_finite() && s > 0.0 {
                self.strength_z = s;
            }
        }
        if let Some(s) = shear_strength_mpa {
            self.shear_strength_z = if s.is_finite() && s > 0.0 { Some(s) } else { None };
        }
        if let Some(on) = layer_shear_on {
            self.layer_shear_on = on;
        }
    }

    /// Analysis resolution: target solid-cell count, an optional fixed cell
    /// size, the wall-snapping thickness and the composite-skin switch.
    /// Any change invalidates the grid and everything solved on it.
    pub fn set_resolution(
        &mut self,
        target_cells: Option<u32>,
        fixed_h_mm: Option<f64>,
        snap_wall_mm: Option<f64>,
        composite_skin: Option<bool>,
    ) {
        let mut changed = false;
        if let Some(c) = target_cells {
            let c = c.clamp(20_000, 4_000_000);
            changed |= c != self.target_cells;
            self.target_cells = c;
        }
        if let Some(h) = fixed_h_mm {
            let h = if h.is_finite() && h > 0.0 { Some(h) } else { None };
            changed |= h != self.fixed_h;
            self.fixed_h = h;
        }
        if let Some(w) = snap_wall_mm {
            let w = if w.is_finite() && w > 0.0 { w } else { 0.0 };
            changed |= w != self.snap_wall;
            self.snap_wall = w;
        }
        if let Some(on) = composite_skin {
            changed |= on != self.composite_skin;
            self.composite_skin = on;
        }
        if changed {
            self.invalidate_grid();
        }
    }

    /// Active world acceleration (mm/s2) for self-weight and remote masses.
    pub fn set_accel(&mut self, accel: [f64; 3]) {
        if accel != self.accel {
            self.accel = accel;
            self.solution = None;
            self.solution_eps = None;
        }
    }

    /// Solver tolerance / iteration cap / multigrid depth.
    pub fn set_solver_limits(
        &mut self,
        tol: Option<f64>,
        max_iter: Option<u32>,
        max_levels: Option<u32>,
    ) {
        if let Some(t) = tol {
            if t.is_finite() && t > 0.0 {
                self.settings.tol = t;
            }
        }
        if let Some(i) = max_iter {
            self.settings.max_iter = (i as usize).clamp(50, 20_000);
        }
        if let Some(l) = max_levels {
            let l = (l as usize).clamp(1, 8);
            if l != self.settings.max_levels {
                self.settings.max_levels = l;
                self.invalidate_grid();
            }
        }
    }

    fn invalidate_grid(&mut self) {
        self.grid = None;
        self.solver_cache = None;
        self.solution = None;
        self.solution_eps = None;
        self.opt = None;
    }

    // ---- boundary conditions ----

    pub fn clear_bcs(&mut self) {
        self.bcs.clear();
        self.solution = None;
        self.solution_eps = None;
        self.opt = None;
    }

    pub fn bc_count(&self) -> usize {
        self.bcs.len()
    }

    /// The whole settings payload in one call — what the Kotlin panel sends:
    /// material, resolution, acceleration and solver limits, every key optional so
    /// a panel that has only some of them still sends a partial update.
    ///
    /// Mirrors [`Session::add_bc_json`]: the parsing lives here rather than in the
    /// JNI shim, so the exact payload the app writes is testable without a JNI
    /// environment.
    pub fn configure_json(&mut self, json: &str) -> Result<(), String> {
        let value: serde_json::Value = serde_json::from_str(json)
            .map_err(|e| format!("configuration is not valid JSON: {e}"))?;
        let f = |key: &str| value.get(key).and_then(|v| v.as_f64());
        let b = |key: &str| value.get(key).and_then(|v| v.as_bool());
        self.set_material(
            f("youngsModulusMpa"),
            f("poisson"),
            f("densityTonneMm3"),
            f("strengthMpa"),
            f("layerStrengthMpa"),
            f("shearStrengthMpa"),
            b("layerShearOn"),
        );
        self.set_resolution(
            f("targetCells").map(|v| v as u32),
            value.get("fixedHMm").and_then(|v| v.as_f64()),
            f("snapWallMm"),
            b("compositeSkin"),
        );
        if let Some(accel) = value.get("accel").and_then(|v| v.as_array()) {
            if accel.len() == 3 {
                self.set_accel([
                    accel[0].as_f64().unwrap_or(0.0),
                    accel[1].as_f64().unwrap_or(0.0),
                    accel[2].as_f64().unwrap_or(0.0),
                ]);
            }
        }
        self.set_solver_limits(
            f("tolerance"),
            f("maxIterations").map(|v| v as u32),
            f("maxLevels").map(|v| v as u32),
        );
        Ok(())
    }

    /// Add one boundary condition from a JSON descriptor. 'tris' are ORIGINAL
    /// mesh triangle indices (what the picker and the brush produce).
    ///
    /// kind: fixed | frictionless | elastic | force | pressure | bearing |
    ///       moment | displacement | cylindrical | mass
    pub fn add_bc_json(&mut self, json: &str) -> Result<(), String> {
        let value: serde_json::Value = serde_json::from_str(json)
            .map_err(|e| format!("boundary condition is not valid JSON: {e}"))?;
        let kind_name = value
            .get("kind")
            .and_then(|k| k.as_str())
            .ok_or("boundary condition has no kind")?;
        let tris: Vec<u32> = value
            .get("tris")
            .and_then(|t| t.as_array())
            .ok_or("boundary condition has no triangles")?
            .iter()
            .map(|v| v.as_u64().map(|n| n as u32).ok_or("triangle index is not a number"))
            .collect::<Result<Vec<u32>, &str>>()?;
        if tris.is_empty() {
            return Err("boundary condition selects no triangles".into());
        }
        let working = self.working_triangles_for_original(&tris);
        if working.is_empty() {
            return Err("boundary condition selects no mesh triangles".into());
        }
        let f = |name: &str, default: f64| -> f64 {
            value.get(name).and_then(|v| v.as_f64()).unwrap_or(default)
        };
        let vec3 = |name: &str| -> [f64; 3] {
            match value.get(name).and_then(|v| v.as_array()) {
                Some(a) if a.len() == 3 => [
                    a[0].as_f64().unwrap_or(0.0),
                    a[1].as_f64().unwrap_or(0.0),
                    a[2].as_f64().unwrap_or(0.0),
                ],
                _ => [0.0; 3],
            }
        };
        let kind = match kind_name {
            "fixed" => BcKind::Fixed,
            "frictionless" => BcKind::Frictionless,
            "elastic" => BcKind::Elastic(f("k", 10.0).clamp(1e-4, 1e7)),
            "force" => BcKind::Force(vec3("vector")),
            "pressure" => BcKind::Pressure(f("mpa", 0.0)),
            "bearing" => BcKind::Bearing(vec3("vector")),
            "moment" => BcKind::Moment(vec3("vector")),
            "displacement" => {
                let axes = value.get("axes").and_then(|v| v.as_array());
                let mut flags = [false; 3];
                if let Some(a) = axes {
                    for (i, slot) in flags.iter_mut().enumerate() {
                        *slot = a.get(i).and_then(|v| v.as_bool()).unwrap_or(false);
                    }
                }
                BcKind::Displacement(flags, vec3("vector"))
            }
            "cylindrical" => {
                let d = value.get("dofs").and_then(|v| v.as_array());
                let mut flags = [false; 3];
                for (i, slot) in flags.iter_mut().enumerate() {
                    *slot = d.and_then(|a| a.get(i)).and_then(|v| v.as_bool()).unwrap_or(false);
                }
                BcKind::Cylindrical(flags)
            }
            "mass" => {
                let rigid = value.get("rigid").and_then(|v| v.as_bool()).unwrap_or(false);
                BcKind::Mass { point: vec3("point"), mass: f("mass", 0.0), rigid }
            }
            other => return Err(format!("unsupported boundary condition kind: {other}")),
        };
        self.bcs.push(BcSpec { kind, tris: working });
        self.solution = None;
        self.solution_eps = None;
        self.opt = None;
        Ok(())
    }

    // ---- grid and solve ----

    fn body_arg<'a>(&self, vfrac: &'a [f32]) -> Option<BodyLoad<'a>> {
        if self.accel != [0.0; 3] {
            Some(BodyLoad { accel: self.accel, density: self.density, vfrac })
        } else {
            None
        }
    }

    fn ensure_grid(&mut self) -> Result<(), String> {
        if self.grid.is_some() {
            return Ok(());
        }
        let h = self.analysis_h()?;
        let grid = VoxelGrid::voxelize(&self.mesh_orig, h);
        if grid.solid_count() == 0 {
            return Err(
                "voxelization produced no solid cells — model too thin for this resolution".into(),
            );
        }
        let (padded, levels) = pad_for_levels(&grid, self.settings.max_levels);
        self.grid = Some((padded, levels));
        Ok(())
    }

    /// Analysis cell size (mm) for the current resolution setting — upstream
    /// semantics: sized from the part's actual volume, floored at 2% of the
    /// bounding box for degenerate meshes.
    fn analysis_h(&self) -> Result<f64, String> {
        let (lo, hi) = self.mesh.bounds().ok_or("empty mesh")?;
        let bbox_vol =
            (hi[0] - lo[0]).max(1e-6) * (hi[1] - lo[1]).max(1e-6) * (hi[2] - lo[2]).max(1e-6);
        let h = if let Some(fh) = self.fixed_h {
            let h = if self.snap_wall > 0.0 {
                let k = (self.snap_wall / fh).round().max(1.0);
                (self.snap_wall / k).max(1e-3)
            } else {
                fh
            };
            h.max((bbox_vol / 4_000_000.0).cbrt())
        } else {
            let part_vol = self.mesh.volume().abs();
            let fill_vol = if part_vol.is_finite() && part_vol > 1e-9 {
                part_vol.clamp(bbox_vol * 0.02, bbox_vol)
            } else {
                bbox_vol
            };
            filasim_core::voxel::pick_voxel_size(
                fill_vol,
                bbox_vol,
                self.target_cells as f64,
                self.snap_wall,
            )
        };
        Ok(h)
    }

    /// The settings the next run will use, as JSON.
    ///
    /// This is the UI's editable starting point and the single source of truth
    /// for the defaults: the panel fills its fields from here instead of keeping
    /// a second copy of them in Kotlin. `shearStrengthMpa` is the material's
    /// shear allowable even when layer shear scoring is off, so toggling it back
    /// on restores the same number.
    pub fn effective_config(&self) -> String {
        serde_json::json!({
            "youngsModulusMpa": self.settings.e0,
            "poisson": self.settings.nu,
            "densityTonneMm3": self.density,
            "strengthMpa": self.strength,
            "layerStrengthMpa": self.strength_z,
            "shearStrengthMpa": self.shear_strength_z.unwrap_or(0.6 * self.strength_z),
            "layerShearOn": self.layer_shear_on,
            "accel": self.accel,
            "targetCells": self.target_cells,
            "fixedHMm": self.fixed_h,
            "snapWallMm": self.snap_wall,
            "compositeSkin": self.composite_skin,
            "tolerance": self.settings.tol,
            "maxIterations": self.settings.max_iter,
            "maxLevels": self.settings.max_levels,
        })
        .to_string()
    }

    /// JSON: { nx, ny, nz, h, cells, solid, levels, patches, bodies, ... }
    pub fn voxel_info(&mut self) -> Result<String, String> {
        self.ensure_grid()?;
        let (g, levels) = self.grid.as_ref().unwrap();
        Ok(serde_json::json!({
            "nx": g.nx, "ny": g.ny, "nz": g.nz, "h": g.h,
            "cells": g.cell_count(), "solid": g.solid_count(),
            "levels": levels,
            "patches": self.seg.patch_count,
            "bodies": self.bodies,
            "triangles": self.mesh.len(),
            "originalTriangles": self.mesh_orig.len(),
        })
        .to_string())
    }

    /// Island + rigid-body-mode check. JSON CheckReport.
    pub fn check(&mut self) -> Result<String, String> {
        self.ensure_grid()?;
        let (grid, _) = self.grid.as_ref().unwrap();
        let asm = assemble(&self.mesh, grid, &self.bcs, self.body_arg(&grid.scale), &self.settings).map_err(|e| e.to_string())?;
        let report = check_problem(grid, &asm);
        let comps: Vec<serde_json::Value> = report
            .components
            .iter()
            .map(|c| {
                serde_json::json!({
                    "cells": c.cells,
                    "constrained": c.constrained,
                    "lambdaRatio": c.lambda_ratio,
                    "hasLoads": c.has_loads,
                    "mode": c.mode.as_ref().map(|m| serde_json::json!({
                        "t": m.t, "r": m.r, "center": m.center,
                    })),
                })
            })
            .collect();
        Ok(serde_json::json!({
            "ok": report.ok,
            "islandCount": report.island_count,
            "components": comps,
        })
        .to_string())
    }

    /// Static solve of the as-configured part.
    /// JSON: { iterations, relResidual, converged, maxDisplacement, tol }
    pub fn solve(&mut self) -> Result<String, String> {
        self.ensure_grid()?;
        self.reset_cancel();
        self.set_progress(|p| {
            *p = ProgressState { phase: "solve".into(), running: true, ..Default::default() };
        });
        let progress = Arc::clone(&self.progress);
        let this = &mut *self;
        let (grid, levels) = this.grid.as_ref().unwrap();
        let levels = *levels;
        let asm = match assemble(&this.mesh, grid, &this.bcs, this.body_arg(&grid.scale), &this.settings) {
            Ok(asm) => asm,
            Err(error) => {
                mark_finished(&progress, "failed");
                return Err(error.to_string());
            }
        };
        let report = check_problem(grid, &asm);
        if !report.ok {
            mark_finished(&progress, "failed");
            return Err("model is under-constrained — run check for details".into());
        }
        let settings = this.settings;
        let cancel = Arc::clone(&this.cancel);
        let sol = match Session::with_telemetry(&cancel, &progress, || {
            solve_nodes_cached(&mut this.solver_cache, grid, levels, &asm.problem, &settings)
        }) {
            Ok(sol) => sol,
            Err(error) => {
                mark_finished(&progress, "failed");
                return Err(error.to_string());
            }
        };
        let out = serde_json::json!({
            "iterations": sol.iterations,
            "relResidual": sol.rel_residual,
            "converged": sol.converged,
            "maxDisplacement": sol.max_displacement(),
            "tol": this.settings.tol,
            "residuals": sol.residuals.clone(),
        })
        .to_string();
        this.solution = Some(sol);
        this.solution_eps = None;
        this.set_progress(|p| {
            p.phase = "done".into();
            p.running = false;
        });
        Ok(out)
    }

    /// Run 'body' with the cooperative cancellation checker and the progress
    /// sink installed on this thread, then clear both. An associated function
    /// rather than a method so the caller can keep its mutable borrow of the
    /// solver cache while the UI thread holds the control handle.
    fn with_telemetry<T>(
        cancel_flag: &Arc<AtomicBool>,
        progress_flag: &Arc<Mutex<ProgressState>>,
        body: impl FnOnce() -> T,
    ) -> T {
        let cancel = Arc::clone(cancel_flag);
        filasim_core::cancel::set_checker(Some(Box::new(move || cancel.load(Ordering::Relaxed))));
        let progress = Arc::clone(progress_flag);
        filasim_core::progress::set_sink(Some(Box::new(move |trace: &[f32]| {
            if let Ok(mut state) = progress.lock() {
                state.inner_residual = trace.last().copied().unwrap_or(0.0) as f64;
                state.inner_iters = trace.len().saturating_sub(1) as u32;
            }
        })));
        let out = body();
        filasim_core::progress::set_sink(None);
        filasim_core::cancel::set_checker(None);
        out
    }

    /// Cancellation flag shared with the UI thread's control handle.
    pub fn cancel_handle(&self) -> Arc<AtomicBool> {
        Arc::clone(&self.cancel)
    }

    /// Progress snapshot shared with the UI thread's control handle.
    pub fn progress_handle(&self) -> Arc<Mutex<ProgressState>> {
        Arc::clone(&self.progress)
    }

    /// Request cancellation of a running solve/optimize.
    pub fn cancel(&self) {
        self.cancel.store(true, Ordering::Relaxed);
    }

    pub fn progress_json(&self) -> String {
        self.progress.lock().map(|s| s.to_json()).unwrap_or_else(|_| "{}".into())
    }

    fn reset_cancel(&self) {
        self.cancel.store(false, Ordering::Relaxed);
    }

    fn set_progress(&self, f: impl FnOnce(&mut ProgressState)) {
        if let Ok(mut state) = self.progress.lock() {
            f(&mut state);
        }
    }

    // ---- optimization ----

    /// Optimize the infill (or, in solid mode, the topology) for the current
    /// boundary conditions and return the summary JSON.
    pub fn optimize(&mut self, opts_json: &str) -> Result<String, String> {
        let opts: OptimizeOpts = serde_json::from_str(opts_json)
            .map_err(|e| format!("optimization options are invalid: {e}"))?;
        self.ensure_grid()?;
        self.reset_cancel();
        self.set_progress(|p| {
            *p = ProgressState { phase: "assemble".into(), running: true, ..Default::default() };
        });
        let progress = Arc::clone(&self.progress);

        let solid = opts.solid;
        let (eval_exp, eval_coeff) = if solid {
            (1.0, 1.0)
        } else {
            (opts.exponent.clamp(1.0, 3.5), opts.coeff.clamp(0.05, 2.0))
        };
        let (opt_exp, opt_coeff) =
            if opts.binary || solid { (3.0, 1.0) } else { (eval_exp, eval_coeff) };
        let floor = if solid { 1e-3 } else { (opts.floor_pct / 100.0).clamp(0.01, 0.5) };
        let cap = if solid { 1.0 } else { (opts.cap_pct / 100.0).clamp(floor + 0.05, 1.0) };
        let budget_pct = opts.budget_pct;
        let (perimeters, wall_mm) = resolve_wall(opts.perimeters, opts.line_width);
        let smooth_iters = (opts.smooth_iters as usize).min(60);
        let n_bins = (opts.n_bins as usize).clamp(2, 4);
        let top_mm =
            (opts.top_bottom_layers.min(20) as f64 * opts.layer_height.clamp(0.04, 0.6)).min(5.0);

        let this = &mut *self;
        let (grid, levels) = this.grid.as_ref().unwrap();
        let levels = *levels;
        // The optimizer realizes remote masses but recomputes self-weight itself
        // every iteration (upstream DESIGN section 16 decision 4).
        let mass_only = if this.accel != [0.0; 3] {
            Some(BodyLoad { accel: this.accel, density: this.density, vfrac: &[] })
        } else {
            None
        };
        let asm = match assemble(&this.mesh, grid, &this.bcs, mass_only, &this.settings) {
            Ok(asm) => asm,
            Err(error) => {
                mark_finished(&progress, "failed");
                return Err(error.to_string());
            }
        };
        let report = check_problem(grid, &asm);
        if !report.ok {
            mark_finished(&progress, "failed");
            return Err("model is under-constrained — fix the setup first".into());
        }
        let load_set = filasim_core::simp::LoadSet {
            primary_body: if this.accel != [0.0; 3] {
                Some(filasim_core::simp::BodyAccel { accel: this.accel, density: this.density })
            } else {
                None
            },
            ..Default::default()
        };
        let has_self_weight = load_set.has_self_weight();

        let bc_excl = if opts.goal == "strength" {
            match this.bc_exclusion(grid, &[&this.bcs]) {
                Ok(exclusion) => exclusion,
                Err(error) => {
                    mark_finished(&progress, "failed");
                    return Err(error);
                }
            }
        } else {
            Vec::new()
        };
        let bc_excl_cells = bc_excl.iter().filter(|&&e| e).count();

        let params = OptimizeParams {
            budget: (budget_pct / 100.0).clamp(0.01, 1.0),
            exponent: opt_exp,
            coeff: opt_coeff,
            floor,
            cap,
            wall_mm,
            top_mm,
            bottom_mm: top_mm,
            composite_skin: this.composite_skin,
            symmetry: opts.symmetry.as_ref().and_then(|v| {
                if v.len() == 4 && (v[0] * v[0] + v[1] * v[1] + v[2] * v[2]) > 1e-12 {
                    Some([v[0], v[1], v[2], v[3]])
                } else {
                    None
                }
            }),
            min_member_mm: opts.min_member_mm.clamp(0.0, 10.0),
            solid_mode: solid,
            retain_bc: opts.retain_bc,
            self_support: opts.self_support,
            overhang_deg: opts.overhang_deg.clamp(0.0, 90.0),
            max_iter: 80,
            ..Default::default()
        };

        let goal_match = opts.goal == "match" && !solid;
        let strength_goal = (opts.goal == "strength").then(|| filasim_core::pipeline::StrengthGoal {
            target: opts.sf_target.clamp(1.0, 9.5),
            spec: filasim_core::strength::StrengthSpec {
                measure: filasim_core::strength::SfMeasure::parse(&opts.sf_measure)
                    .unwrap_or(filasim_core::strength::SfMeasure::Both),
                strength: this.strength,
                strength_z: this.strength_z,
                shear_z: this.shear_strength_z_eff(),
            },
        });
        let ref_frac = (budget_pct / 100.0).clamp(params.floor, params.cap);
        let levels_clean: Option<Vec<f64>> = opts.levels_pct.as_ref().and_then(|user| {
            if user.is_empty() {
                None
            } else {
                let mut l: Vec<f64> = user.iter().map(|&p| (p / 100.0).clamp(0.01, 1.0)).collect();
                l.sort_by(|a, b| a.partial_cmp(b).unwrap());
                l.dedup_by(|a, b| (*a - *b).abs() < 0.005);
                Some(l)
            }
        });
        let cfg = PipelineCfg {
            eval: filasim_core::pipeline::EvalLaw { exp: eval_exp, coeff: eval_coeff },
            goal_match,
            strength: strength_goal,
            ref_frac,
            n_bins,
            levels_pct: levels_clean.as_deref(),
            smooth_iters,
            bc_excl: &bc_excl,
        };
        let max_iter = params.max_iter;
        let cancel = Arc::clone(&this.cancel);
        let oc = match Session::with_telemetry(&cancel, &progress, || {
                run_optimization(
                    &mut this.solver_cache,
                    grid,
                    levels,
                    &asm.problem,
                    &this.settings,
                    &params,
                    &cfg,
                    &load_set,
                    |upd, _x_phys, _design_cells| {
                        if let Ok(mut state) = progress.lock() {
                            state.iteration = upd.progress.iteration as u32;
                            state.max_iter = max_iter as u32;
                            state.pass = upd.pass as u32;
                            state.passes = upd.passes as u32;
                            state.budget = upd.budget;
                            state.compliance = upd.progress.compliance;
                            state.mass_frac = upd.progress.mass_frac;
                            state.mean_infill = upd.progress.mean_infill;
                            state.change = upd.progress.change;
                            state.inner_iters = upd.progress.inner_iters as u32;
                        }
                    },
                    |phase| {
                        if let Ok(mut state) = progress.lock() {
                            state.phase = match phase {
                                PipelinePhase::ReferenceSolve => "reference",
                                PipelinePhase::Preflight => "preflight",
                                PipelinePhase::SfEval => "sf_eval",
                                PipelinePhase::OptimizePass { .. } => "optimize_pass",
                                PipelinePhase::Binning => "binning",
                                PipelinePhase::VerifySolve => "verify",
                                PipelinePhase::UniformSolve => "uniform",
                                PipelinePhase::SolidSolve => "solid_ref",
                                PipelinePhase::StressRecovery => "stress",
                                PipelinePhase::Regions => "regions",
                                PipelinePhase::Smoothing => "smoothing",
                            }
                            .to_string();
                        }
                    },
                )
            }) {
            Ok(oc) => oc,
            Err(error) => {
                mark_finished(&progress, "failed");
                return Err(error.to_string());
            }
        };
        this.set_progress(|p| p.phase = "finalize".into());

        // ---- mass + summary (the gram conversion needs the material density) ----
        let cell_vol = grid.h * grid.h * grid.h;
        let grams =
            |infill_vol: f64| (oc.vol_skin + oc.sum_f + infill_vol) * cell_vol * this.density * 1e6;
        let mass_part = grams(oc.infill_vol_binned);
        let mass_solid = grams(oc.w_sum);
        let n_solid = oc.vol_skin + oc.sum_f + oc.w_sum;
        let mass_frac = (oc.vol_skin + oc.sum_f + oc.infill_vol_binned) / n_solid;
        let bin_counts: Vec<usize> = (0..oc.centers.len())
            .map(|c| oc.bins.iter().filter(|&&b| b as usize == c).count())
            .collect();
        let mut summary_v = serde_json::json!({
            "iterations": oc.total_iters,
            "converged": oc.design_converged,
            "bins": oc.centers.iter().zip(&bin_counts).map(|(&d, &n)| serde_json::json!({
                "density": d, "cells": n,
            })).collect::<Vec<_>>(),
            "baseDensity": oc.centers[0],
            "regionCount": oc.regions.len(),
            "massGrams": mass_part,
            "massSolidGrams": mass_solid,
            "massFrac": mass_frac,
            "meanInfill": oc.mean_binned,
            "targetInfill": oc.effective_budget,
            "stiffnessVsSolid": oc.c_solid / oc.c_binned,
            "gainVsUniform": oc.c_uniform / oc.c_binned - 1.0,
            "maxDisplacement": oc.max_disp,
            "uniformMaxDisp": oc.max_disp_uniform,
            "solidMaxDisp": oc.max_disp_solid,
            "hasBaselines": !solid,
            "selfWeight": has_self_weight,
            "binary": opts.binary,
            "solid": solid,
            "goal": if goal_match { "match" } else if strength_goal.is_some() { "strength" } else { "budget" },
            "passes": oc.pass_trace.len(),
        });
        if let Some(sg) = &strength_goal {
            let o = summary_v.as_object_mut().unwrap();
            o.insert("sfTarget".into(), serde_json::json!(sg.target));
            o.insert("sfAchieved".into(), serde_json::json!(oc.sf_crit));
            o.insert("sfBest".into(), serde_json::json!(oc.sf_crit_cap));
            o.insert("sfFeasible".into(), serde_json::json!(oc.sf_feasible));
            o.insert(
                "sfMeasure".into(),
                serde_json::json!(match sg.spec.measure {
                    filasim_core::strength::SfMeasure::Material => "material",
                    filasim_core::strength::SfMeasure::Layer => "layer",
                    filasim_core::strength::SfMeasure::Both => "both",
                }),
            );
            o.insert("sfPerStep".into(), serde_json::json!(oc.sf_per_step));
            o.insert("bindingCellCount".into(), serde_json::json!(oc.binding_cells.len()));
            o.insert("bindingSkinShare".into(), serde_json::json!(oc.binding_skin_share));
            o.insert("bcExcludedCells".into(), serde_json::json!(bc_excl_cells));
        }
        if goal_match {
            let o = summary_v.as_object_mut().unwrap();
            o.insert("refUniformPct".into(), serde_json::json!(ref_frac * 100.0));
            o.insert("targetCompliance".into(), serde_json::json!(oc.c_target));
            o.insert("achievedCompliance".into(), serde_json::json!(oc.c_binned));
            o.insert("matchDeviation".into(), serde_json::json!(oc.c_binned / oc.c_target - 1.0));
            o.insert(
                "massUniformRefGrams".into(),
                serde_json::json!(grams(ref_frac * oc.design_cells.len() as f64)),
            );
        }
        let summary = summary_v.to_string();

        // ---- deformed-view solution (kept for the region/density views) ----
        let (mx, my, mz) = (grid.nx + 1, grid.ny + 1, grid.nz + 1);
        let solution = Solution {
            u: oc.u_binned.iter().map(|&v| v as f32).collect(),
            mx,
            my,
            mz,
            h: grid.h,
            origin: grid.origin,
            active: active_nodes(grid),
            iterations: oc.design_iters,
            rel_residual: oc.verify_residual,
            converged: oc.verify_converged,
            residuals: Vec::new(),
        };
        this.solution_eps = Some(oc.solution_eps);
        this.solution = Some(solution);

        let mut field: HashMap<u32, f64> = HashMap::default();
        for (i, &c) in oc.design_cells.iter().enumerate() {
            field.insert(c, oc.x_binned[i]);
        }
        this.opt = Some(OptOutput {
            regions: oc.regions,
            regions_raw: oc.regions_raw,
            base_density: oc.centers[0],
            cell_density: field,
            perimeters,
            top_bottom_layers: opts.top_bottom_layers.min(20),
            solid_pattern: opts.solid_pattern.clone(),
            summary: summary.clone(),
            solid,
            centers: oc.centers,
            bins: oc.bins,
            x_binned: oc.x_binned.iter().map(|&x| x as f32).collect(),
            wall_mm,
            tb_mm: top_mm,
            eval_exp,
            eval_coeff,
            smooth_iters: smooth_iters as u32,
            binary: opts.binary,
        });
        this.set_progress(|p| {
            p.phase = "done".into();
            p.running = false;
        });
        Ok(summary)
    }

    fn shear_strength_z_eff(&self) -> f64 {
        if !self.layer_shear_on {
            return f64::INFINITY;
        }
        self.shear_strength_z.unwrap_or(0.6 * self.strength_z)
    }

    /// BC singularity exclusion: rigid constraint patches concentrate stress by
    /// construction, so the strength criterion does not score them. Only the
    /// strength goal reads this (upstream DESIGN section 20 decisions 5/7).
    fn bc_exclusion(&self, grid: &VoxelGrid, bc_sets: &[&[BcSpec]]) -> Result<Vec<bool>, String> {
        let mut patches: Vec<Vec<u32>> = Vec::new();
        for bcs in bc_sets {
            if bcs.is_empty() {
                continue;
            }
            let asm = assemble(&self.mesh, grid, bcs, None, &self.settings).map_err(|e| e.to_string())?;
            for (bc, nodes) in bcs.iter().zip(&asm.bc_nodes) {
                let rigid_iface = match &bc.kind {
                    BcKind::Fixed | BcKind::Frictionless | BcKind::Displacement(_, _) => true,
                    BcKind::Cylindrical(dofs) => dofs.iter().any(|&d| d),
                    BcKind::Mass { rigid, .. } => *rigid && nodes.len() >= 3,
                    _ => false,
                };
                if rigid_iface && !nodes.is_empty() {
                    patches.push(nodes.clone());
                }
            }
        }
        if patches.is_empty() {
            return Ok(Vec::new());
        }
        let refs: Vec<&[u32]> = patches.iter().map(|p| p.as_slice()).collect();
        Ok(filasim_core::strength::bc_exclusion(grid, &refs))
    }

    // ---- results ----

    pub fn has_result(&self) -> bool {
        self.opt.is_some()
    }

    pub fn region_count(&self) -> u32 {
        self.opt.as_ref().map_or(0, |o| o.regions.len() as u32)
    }

    pub fn region_density(&self, index: u32) -> f64 {
        self.opt
            .as_ref()
            .and_then(|o| o.regions.get(index as usize))
            .map_or(0.0, |r| r.density)
    }

    pub fn region_positions(&self, index: u32) -> Vec<f32> {
        self.opt
            .as_ref()
            .and_then(|o| o.regions.get(index as usize))
            .map_or(Vec::new(), |r| r.positions.clone())
    }

    pub fn region_indices(&self, index: u32) -> Vec<u32> {
        self.opt
            .as_ref()
            .and_then(|o| o.regions.get(index as usize))
            .map_or(Vec::new(), |r| r.indices.clone())
    }

    /// The densities and mass the optimizer settled on, plus the per-bin cell
    /// counts — the numbers the results panel shows.
    pub fn result_summary(&self) -> String {
        self.opt.as_ref().map_or_else(|| "{}".into(), |o| o.summary.clone())
    }

    /// One binary STL per modifier region, zipped — the transport the slice
    /// integration already consumes.
    pub fn export_modifier_zip(&self) -> Result<Vec<u8>, String> {
        let opt = self.opt.as_ref().ok_or("no optimization result — run optimize first")?;
        Ok(export_stl_zip(&opt.regions))
    }

    /// SOLID topology mode: the single optimized body as one binary STL.
    pub fn export_solid_stl(&self) -> Result<Vec<u8>, String> {
        let opt = self.opt.as_ref().ok_or("no optimization result — run optimize first")?;
        let r = opt.regions.first().ok_or("no optimized shape")?;
        let mut tris: Vec<[f32; 9]> = Vec::with_capacity(r.indices.len() / 3);
        for f in r.indices.chunks_exact(3) {
            let v = |i: u32| {
                let o = (i as usize) * 3;
                [r.positions[o], r.positions[o + 1], r.positions[o + 2]]
            };
            let (a, b, c) = (v(f[0]), v(f[1]), v(f[2]));
            tris.push([a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]]);
        }
        Ok(TriMesh::from_triangles(tris).to_stl_binary())
    }

    /// Solid mode's kept-material meshes, if any (one body).
    pub fn is_solid_mode(&self) -> bool {
        self.opt.as_ref().is_some_and(|o| o.solid)
    }
}

/// Optimization options — the same JSON the WebView's engine client sends, so
/// the Kotlin layer and any parity harness speak one language.
#[derive(Debug, Clone, serde::Deserialize)]
pub struct OptimizeOpts {
    #[serde(default = "d_budget")]
    pub budget_pct: f64,
    #[serde(default = "d_exponent")]
    pub exponent: f64,
    #[serde(default = "d_coeff")]
    pub coeff: f64,
    #[serde(default = "d_perimeters")]
    pub perimeters: u32,
    #[serde(default = "d_line_width")]
    pub line_width: f64,
    #[serde(default = "d_smooth")]
    pub smooth_iters: u32,
    #[serde(default = "d_bins")]
    pub n_bins: u32,
    #[serde(default = "d_floor")]
    pub floor_pct: f64,
    #[serde(default = "d_cap")]
    pub cap_pct: f64,
    #[serde(default)]
    pub levels_pct: Option<Vec<f64>>,
    #[serde(default)]
    pub binary: bool,
    #[serde(default)]
    pub solid: bool,
    #[serde(default = "d_true")]
    pub retain_bc: bool,
    #[serde(default)]
    pub self_support: bool,
    #[serde(default = "d_overhang")]
    pub overhang_deg: f64,
    #[serde(default)]
    pub solid_pattern: Option<String>,
    #[serde(default = "d_goal")]
    pub goal: String,
    #[serde(default = "d_sf_target")]
    pub sf_target: f64,
    #[serde(default = "d_sf_measure")]
    pub sf_measure: String,
    #[serde(default)]
    pub symmetry: Option<Vec<f64>>,
    #[serde(default = "d_tb_layers")]
    pub top_bottom_layers: u32,
    #[serde(default = "d_layer_height")]
    pub layer_height: f64,
    #[serde(default)]
    pub min_member_mm: f64,
}

fn d_budget() -> f64 { 25.0 }
fn d_exponent() -> f64 { 1.5 }
fn d_coeff() -> f64 { 1.0 }
fn d_perimeters() -> u32 { 2 }
fn d_line_width() -> f64 { 0.45 }
fn d_smooth() -> u32 { 2 }
fn d_bins() -> u32 { 3 }
fn d_floor() -> f64 { 10.0 }
fn d_cap() -> f64 { 70.0 }
fn d_true() -> bool { true }
fn d_overhang() -> f64 { 45.0 }
fn d_goal() -> String { "budget".into() }
fn d_sf_target() -> f64 { 2.0 }
fn d_sf_measure() -> String { "both".into() }
fn d_tb_layers() -> u32 { 3 }
fn d_layer_height() -> f64 { 0.2 }

/// Centre of a triangle in the 9-float soup.
fn tri_centre(t: &[f32; 9]) -> [f64; 3] {
    [
        (t[0] as f64 + t[3] as f64 + t[6] as f64) / 3.0,
        (t[1] as f64 + t[4] as f64 + t[7] as f64) / 3.0,
        (t[2] as f64 + t[5] as f64 + t[8] as f64) / 3.0,
    ]
}

/// Marks a run as over in the shared snapshot, whatever its outcome: the UI
/// polls this, and a cancelled or failed run must not look like it is still going.
fn mark_finished(progress: &Arc<Mutex<ProgressState>>, phase: &str) {
    if let Ok(mut state) = progress.lock() {
        state.phase = phase.to_string();
        state.running = false;
    }
}

/// Unit normal of a triangle in the 9-float soup (right-hand rule, which is the
/// outward direction on a closed STL).
fn tri_normal(t: &[f32; 9]) -> [f64; 3] {
    let e1 = [(t[3] - t[0]) as f64, (t[4] - t[1]) as f64, (t[5] - t[2]) as f64];
    let e2 = [(t[6] - t[0]) as f64, (t[7] - t[1]) as f64, (t[8] - t[2]) as f64];
    let n = [
        e1[1] * e2[2] - e1[2] * e2[1],
        e1[2] * e2[0] - e1[0] * e2[2],
        e1[0] * e2[1] - e1[1] * e2[0],
    ];
    let len = (n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).sqrt();
    if len > 0.0 {
        [n[0] / len, n[1] / len, n[2] / len]
    } else {
        [0.0, 0.0, 0.0]
    }
}
fn within(point: &[f64; 3], centre: &[f64; 3], radius: f64) -> bool {
    let dx = point[0] - centre[0];
    let dy = point[1] - centre[1];
    let dz = point[2] - centre[2];
    dx * dx + dy * dy + dz * dz <= radius * radius
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The exact payload app/src/main/java/com/tomppi/enderslicer/smartinfill/
    /// FilaSimEngine.kt writes for a default optimization.
    const KOTLIN_OPTIONS: &str = r#"{"budget_pct":25.0,"exponent":1.5,"coeff":1.0,"perimeters":2,
        "line_width":0.45,"top_bottom_layers":3,"layer_height":0.2,"min_member_mm":0.0,"n_bins":3,
        "smooth_iters":2,"floor_pct":10.0,"cap_pct":70.0,"binary":false,"solid":false,
        "solid_pattern":"rectilinear","goal":"budget","sf_target":2.0,"sf_measure":"both",
        "self_support":false,"overhang_deg":45.0,"retain_bc":true}"#;

    #[test]
    fn optimize_options_accept_the_kotlin_payload() {
        let opts: OptimizeOpts = serde_json::from_str(KOTLIN_OPTIONS).expect("kotlin payload parses");
        assert_eq!(opts.budget_pct, 25.0);
        assert_eq!(opts.perimeters, 2);
        assert_eq!(opts.top_bottom_layers, 3);
        assert_eq!(opts.goal, "budget");
        assert!(!opts.binary);
        assert!(!opts.solid);
        assert!(opts.levels_pct.is_none());
    }

    #[test]
    fn optimize_options_default_when_keys_are_absent() {
        let opts: OptimizeOpts = serde_json::from_str("{}").expect("empty payload parses");
        assert_eq!(opts.budget_pct, 25.0);
        assert_eq!(opts.exponent, 1.5);
        assert_eq!(opts.line_width, 0.45);
        assert_eq!(opts.layer_height, 0.2);
        assert_eq!(opts.top_bottom_layers, 3);
        assert_eq!(opts.floor_pct, 10.0);
        assert_eq!(opts.cap_pct, 70.0);
        assert_eq!(opts.sf_measure, "both");
        assert!(opts.retain_bc);
    }

    #[test]
    fn optimize_options_reject_malformed_payloads() {
        assert!(serde_json::from_str::<OptimizeOpts>("not json").is_err());
        assert!(serde_json::from_str::<OptimizeOpts>(r#"{"budget_pct":"high"}"#).is_err());
    }

    /// The printable-geometry clamps are the upstream adapter's, so a native
    /// run analyzes the wall the WebView run would have analyzed.
    #[test]
    fn wall_resolution_matches_the_upstream_clamps() {
        assert_eq!(resolve_wall(2, 0.45), (2, 0.9));
        assert_eq!(resolve_wall(0, 0.45), (1, 0.45));
        assert_eq!(resolve_wall(20, 5.0), (8, 5.0));
    }

    /// A one-triangle mesh is enough to own the settings the panel reads back.
    fn unit_session() -> Session {
        Session::from_import(
            TriMesh::from_triangles(vec![[0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, 0.0]]),
            1,
            "triangle",
        )
    }

    /// The panel fills its material and resolution fields from this payload, so
    /// it has to be the engine's real defaults - not a Kotlin copy of them.
    #[test]
    fn effective_config_reports_the_engine_defaults() {
        let json = unit_session().effective_config();
        let value: serde_json::Value = serde_json::from_str(&json).expect("payload parses");
        assert_eq!(value["youngsModulusMpa"], 2400.0);
        assert_eq!(value["poisson"], 0.35);
        assert_eq!(value["strengthMpa"], 50.0);
        assert_eq!(value["layerStrengthMpa"], 35.0);
        assert_eq!(value["shearStrengthMpa"], 21.0);
        assert_eq!(value["layerShearOn"], true);
        assert_eq!(value["targetCells"], 300_000);
        assert_eq!(value["tolerance"], 1e-5);
        assert_eq!(value["maxIterations"], 2000);
        assert_eq!(value["maxLevels"], 5);
        assert_eq!(value["fixedHMm"], serde_json::Value::Null);
        assert_eq!(value["accel"], serde_json::json!([0.0, 0.0, 0.0]));
        let density = value["densityTonneMm3"].as_f64().expect("density is a number");
        assert!((density - 1.24e-9).abs() < 1e-15, "density was {density}");
    }

    /// Reading back what a Kotlin payload wrote is what makes the panel's
    /// fields idempotent: edit a value, reopen the panel, see it again.
    #[test]
    fn effective_config_reflects_material_and_resolution_changes() {
        let mut session = unit_session();
        session.set_material(
            Some(3200.0),
            Some(0.30),
            Some(1.05e-9),
            Some(60.0),
            Some(40.0),
            None,
            Some(false),
        );
        session.set_resolution(Some(120_000), Some(0.8), Some(1.2), Some(true));
        session.set_solver_limits(Some(1e-4), Some(500), Some(3));
        let json = session.effective_config();
        let value: serde_json::Value = serde_json::from_str(&json).expect("payload parses");
        assert_eq!(value["youngsModulusMpa"], 3200.0);
        assert_eq!(value["poisson"], 0.30);
        assert_eq!(value["strengthMpa"], 60.0);
        assert_eq!(value["layerStrengthMpa"], 40.0);
        // The derived 0.6 * layer strength, even while layer shear is off.
        assert_eq!(value["shearStrengthMpa"], 24.0);
        assert_eq!(value["layerShearOn"], false);
        assert_eq!(value["targetCells"], 120_000);
        assert_eq!(value["fixedHMm"], 0.8);
        assert_eq!(value["snapWallMm"], 1.2);
        assert_eq!(value["compositeSkin"], true);
        assert_eq!(value["tolerance"], 1e-4);
        assert_eq!(value["maxIterations"], 500);
        assert_eq!(value["maxLevels"], 3);
    }

    /// A flat n x n grid (two triangles per cell). Every triangle is coplanar,
    /// so the crease segmentation puts ALL of them in one patch — the case a
    /// tap used to swallow whole.
    fn grid_session(n: usize, size_mm: f32) -> Session {
        let step = size_mm / n as f32;
        let mut tris = Vec::new();
        for i in 0..n {
            for j in 0..n {
                let x = i as f32 * step;
                let y = j as f32 * step;
                tris.push([x, y, 0.0, x + step, y, 0.0, x + step, y + step, 0.0]);
                tris.push([x, y, 0.0, x + step, y + step, 0.0, x, y + step, 0.0]);
            }
        }
        Session::from_import(TriMesh::from_triangles(tris), 1, "grid")
    }

    /// A closed box: each face is its own crease patch.
    fn cube_session(size: f32) -> Session {
        let s = size;
        let v = [
            [0.0, 0.0, 0.0],
            [s, 0.0, 0.0],
            [s, s, 0.0],
            [0.0, s, 0.0],
            [0.0, 0.0, s],
            [s, 0.0, s],
            [s, s, s],
            [0.0, s, s],
        ];
        let quads = [
            [0, 3, 2, 1],
            [4, 5, 6, 7],
            [0, 1, 5, 4],
            [1, 2, 6, 5],
            [2, 3, 7, 6],
            [3, 0, 4, 7],
        ];
        let mut tris = Vec::new();
        for q in quads {
            let (a, b, c, d) = (v[q[0]], v[q[1]], v[q[2]], v[q[3]]);
            tris.push([a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]]);
            tris.push([a[0], a[1], a[2], c[0], c[1], c[2], d[0], d[1], d[2]]);
        }
        Session::from_import(TriMesh::from_triangles(tris), 1, "cube")
    }

    /// The reported bug: tapping a smooth surface selected the whole model,
    /// because the tap took the crease patch. A bounded pick is a disc around
    /// the tap, and every triangle in it is inside the radius.
    #[test]
    fn a_bounded_pick_is_not_the_whole_patch() {
        let mut session = grid_session(20, 20.0);
        assert_eq!(session.patch_count(), 1, "a flat grid is a single patch");
        let seed = 2 * (10 * 20 + 10) as u32; // middle cell, first triangle
        let picked = session.region_around(seed, 3.0);
        assert!(picked.contains(&seed));
        assert!(
            picked.len() < session.original_triangle_count(),
            "a tap must not take a whole smooth surface"
        );
        let centre = tri_centre(&session.mesh_orig.tris[seed as usize]);
        for tri in &picked {
            assert!(within(
                &tri_centre(&session.mesh_orig.tris[*tri as usize]),
                &centre,
                3.0
            ));
        }
        // The radius is a real bound, not a decoration.
        assert!(session.region_around(seed, 6.0).len() > picked.len());
        // And a zero radius is exactly the tapped triangle.
        assert_eq!(session.region_around(seed, 0.0), vec![seed]);
    }

    /// Connectivity still rules: a radius that reaches across a crease stops at
    /// it, so a load on the top of a box never bleeds onto its walls.
    #[test]
    fn a_bounded_pick_stops_at_a_crease() {
        let mut session = cube_session(10.0);
        assert!(session.patch_count() >= 6, "each cube face is its own patch");
        assert_eq!(session.region_around(0, 1000.0).len(), 2, "one face only");
        assert!(session.region_around(999, 5.0).is_empty(), "no such triangle");
    }
    /// A session's optimizer output with a hand-built design field, so the
    /// result-view lookup is testable without running an optimization.
    fn test_opt(cell_density: HashMap<u32, f64>, centers: Vec<f64>) -> OptOutput {
        OptOutput {
            regions: Vec::new(),
            regions_raw: Vec::new(),
            base_density: centers.first().copied().unwrap_or(0.1),
            cell_density,
            perimeters: 2,
            top_bottom_layers: 3,
            solid_pattern: None,
            summary: "{}".into(),
            solid: false,
            centers,
            bins: Vec::new(),
            x_binned: Vec::new(),
            wall_mm: 0.9,
            tb_mm: 0.6,
            eval_exp: 1.5,
            eval_coeff: 1.0,
            smooth_iters: 2,
            binary: false,
        }
    }

    fn grid_10() -> VoxelGrid {
        VoxelGrid {
            nx: 10,
            ny: 10,
            nz: 10,
            h: 1.0,
            origin: [0.0; 3],
            scale: vec![1.0; 1000],
        }
    }

    /// A triangle in the z = 4.5 plane whose cell is (4, 4, 4).
    fn surface_session() -> Session {
        Session::from_import(
            TriMesh::from_triangles(vec![[4.0, 4.0, 4.5, 5.0, 4.0, 4.5, 4.0, 5.0, 4.5]]),
            1,
            "surface",
        )
    }

    /// The result view tints the part by the density under each surface triangle.
    /// The design field excludes the skin, so the surface cell itself is empty —
    /// the lookup has to find the material just below it.
    #[test]
    fn surface_bins_read_the_material_under_the_surface() {
        let mut session = surface_session();
        assert!(session.surface_bins().is_empty(), "no optimization, no bins");

        let grid = grid_10();
        let mut field = HashMap::new();
        field.insert(grid.cell_index(4, 4, 3) as u32, 0.42);
        session.grid = Some((grid, 1));
        session.opt = Some(test_opt(field, vec![0.1, 0.42]));

        assert_eq!(session.surface_bins(), vec![1], "the dense bin under the surface");
    }

    #[test]
    fn surface_bins_give_up_when_no_material_is_near() {
        let mut session = surface_session();
        let grid = grid_10();
        let mut field = HashMap::new();
        field.insert(grid.cell_index(0, 0, 0) as u32, 0.42);
        session.grid = Some((grid, 1));
        session.opt = Some(test_opt(field, vec![0.1, 0.42]));

        assert_eq!(session.surface_bins(), vec![-1], "nine cells away is not under it");
    }
    /// The exact payload FilaSimConfiguration.toJson() writes (its keys are pinned
    /// on the Kotlin side by FilaSimEngineContractTest), so a rename on either side
    /// fails a test instead of silently doing nothing on a phone.
    const KOTLIN_CONFIGURATION: &str = r#"{"youngsModulusMpa":2100.0,"poisson":0.37,
        "densityTonneMm3":1.27e-9,"strengthMpa":45.0,"layerStrengthMpa":34.0,
        "shearStrengthMpa":20.4,"layerShearOn":true,"targetCells":120000,"fixedHMm":0.3,
        "snapWallMm":0.9,"compositeSkin":true,"accel":[0.0,0.0,-9806.65],
        "tolerance":0.0001,"maxIterations":500,"maxLevels":3}"#;

    #[test]
    fn configure_json_takes_the_kotlin_payload() {
        let mut session = unit_session();
        session
            .configure_json(KOTLIN_CONFIGURATION)
            .expect("the Kotlin payload applies");
        let value: serde_json::Value =
            serde_json::from_str(&session.effective_config()).expect("payload parses");
        assert_eq!(value["youngsModulusMpa"], 2100.0);
        assert_eq!(value["poisson"], 0.37);
        assert_eq!(value["strengthMpa"], 45.0);
        assert_eq!(value["layerStrengthMpa"], 34.0);
        assert_eq!(value["shearStrengthMpa"], 20.4);
        assert_eq!(value["layerShearOn"], true);
        assert_eq!(value["targetCells"], 120_000);
        assert_eq!(value["fixedHMm"], 0.3);
        assert_eq!(value["snapWallMm"], 0.9);
        assert_eq!(value["compositeSkin"], true);
        assert_eq!(value["accel"], serde_json::json!([0.0, 0.0, -9806.65]));
        assert_eq!(value["tolerance"], 1e-4);
        assert_eq!(value["maxIterations"], 500);
        assert_eq!(value["maxLevels"], 3);
    }

    #[test]
    fn configure_json_refuses_a_payload_that_is_not_json() {
        let mut session = unit_session();
        let error = session.configure_json("not json").expect_err("malformed payload");
        assert!(error.contains("not valid JSON"), "{error}");
        // A partial payload is fine: every key is optional.
        session
            .configure_json(r#"{"poisson":0.3}"#)
            .expect("partial payload applies");
    }

    /// Every kind the Kotlin layer can write, in the shape it writes it
    /// (FilaSimBoundaryCondition.describe()). A key the parser does not know would
    /// otherwise leave a condition silently inert on the phone.
    #[test]
    fn every_kotlin_boundary_condition_payload_is_accepted() {
        let payloads = [
            r#"{"kind":"fixed","tris":[0,1]}"#,
            r#"{"kind":"frictionless","tris":[2]}"#,
            r#"{"kind":"elastic","k":50.0,"tris":[3]}"#,
            r#"{"kind":"force","vector":[0.0,0.0,-120.0],"tris":[4]}"#,
            r#"{"kind":"pressure","mpa":1.0,"tris":[5]}"#,
            r#"{"kind":"bearing","vector":[100.0,0.0,0.0],"tris":[6]}"#,
            r#"{"kind":"moment","vector":[0.0,0.0,-100.0],"tris":[7]}"#,
            r#"{"kind":"displacement","axes":[true,false,true],"vector":[0.0,0.0,-0.1],"tris":[8]}"#,
            r#"{"kind":"cylindrical","dofs":[true,true,true],"tris":[9]}"#,
            r#"{"kind":"mass","point":[1.0,2.0,3.0],"mass":0.5,"rigid":true,"tris":[10]}"#,
        ];
        let mut session = cube_session(10.0);
        for (index, payload) in payloads.iter().enumerate() {
            session
                .add_bc_json(payload)
                .unwrap_or_else(|e| panic!("payload {index} was refused: {e}"));
            assert_eq!(session.bc_count(), index + 1);
        }
        assert!(session.add_bc_json(r#"{"kind":"welded","tris":[11]}"#).is_err());
        assert!(session.add_bc_json(r#"{"kind":"fixed","tris":[]}"#).is_err());
        assert!(session.add_bc_json(r#"{"kind":"fixed"}"#).is_err());
    }

    /// A force that parses to a vector of zeros would pass the test above and do
    /// nothing here, so this one asks the assembled system whether it has a load.
    #[test]
    fn a_force_payload_arrives_as_a_load() {
        let mut session = cube_session(10.0);
        session
            .add_bc_json(r#"{"kind":"fixed","tris":[0,2]}"#)
            .expect("support");
        session
            .add_bc_json(r#"{"kind":"force","vector":[0.0,0.0,-120.0],"tris":[4,6]}"#)
            .expect("load");
        let report: serde_json::Value =
            serde_json::from_str(&session.check().expect("check runs")).expect("report parses");
        assert_eq!(report["ok"], true, "{report}");
        assert_eq!(report["components"][0]["hasLoads"], true, "{report}");
        assert_eq!(report["components"][0]["constrained"], true, "{report}");
    }
    /// The Stop button: the UI's control handle raises the shared flag while the
    /// worker sits inside the solver. The run has to give up quickly, report that
    /// it was stopped, and stop claiming it is still running — the panel polls
    /// that snapshot.
    #[test]
    fn a_cancelled_optimization_stops_early_and_clears_its_state() {
        let mut session = cube_session(30.0);
        session
            .add_bc_json(r#"{"kind":"fixed","tris":[0,1]}"#)
            .expect("support");
        session
            .add_bc_json(r#"{"kind":"force","vector":[0.0,0.0,-120.0],"tris":[8,9]}"#)
            .expect("load");

        // Raise the flag only once the run is under way: before it starts there is
        // nothing to cancel.
        let cancel = session.cancel_handle();
        let progress = session.progress_handle();
        let stopper = std::thread::spawn(move || {
            let deadline = std::time::Instant::now() + std::time::Duration::from_secs(60);
            while std::time::Instant::now() < deadline {
                let running = progress.lock().map(|state| state.running).unwrap_or(false);
                if running {
                    break;
                }
                std::thread::sleep(std::time::Duration::from_millis(2));
            }
            cancel.store(true, Ordering::Relaxed);
        });

        let started = std::time::Instant::now();
        let error = session
            .optimize(r#"{"budget_pct":25.0}"#)
            .expect_err("a stopped run reports it");
        let elapsed = started.elapsed();
        stopper.join().expect("the stopper thread");

        assert!(error.to_lowercase().contains("cancel"), "{error}");
        assert!(elapsed < std::time::Duration::from_secs(30), "stopping took {elapsed:?}");
        let snapshot = session.progress_json();
        assert!(
            snapshot.contains("\"running\":false"),
            "the snapshot still says a run is going: {snapshot}"
        );
        // The flag must not poison the next analysis.
        let report = session.check().expect("the session still checks");
        assert!(report.contains("\"ok\":true"), "{report}");
    }
}

