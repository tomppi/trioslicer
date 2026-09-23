#[derive(Clone, Debug)]
pub struct NearbyHotObjectOptions {
    /// Picked nearest point on the current, transformed model in world millimetres.
    pub target_mm: [f64; 3],
    /// Unit outward surface normal at the picked point, pointing toward the source.
    pub outward_normal: [f64; 3],
    /// Clear air gap from the picked part surface to the hot object's surface.
    pub gap_mm: f64,
    /// Effective diameter of the radiating hot region, represented as a sphere.
    pub diameter_mm: f64,
    pub source_temperature_c: f64,
    pub source_emissivity: f64,
    pub use_fixed_temperature_surface: bool,
}

#[derive(Clone, Debug)]
pub struct NearbyHotObjectPreflight {
    pub source_center_mm: [f64; 3],
    pub visible_area_mm2: f64,
    pub view_factor_area_mm2: f64,
    pub initial_absorbed_w: f64,
    pub effective_heat_flux_w_m2: f64,
}

pub fn nearby_hot_object_preflight(
    grid: &VoxelGrid,
    material_fraction: &[f32],
    options: &ThermalOptions,
    source: &NearbyHotObjectOptions,
) -> Result<NearbyHotObjectPreflight, String> {
    let system = build_nearby_hot_object_system(grid, material_fraction, options, source)?;
    let initial = if options.transient {
        options.initial_temperature_c
    } else {
        options.ambient_temperature_c
    };
    let temperature = vec![initial; grid.cell_count()];
    let absorbed = source_absorbed_w(&system, &temperature);
    let flux = if system.heated_area_mm2 > 0.0 {
        absorbed / (system.heated_area_mm2 * 1e-6)
    } else {
        0.0
    };
    Ok(NearbyHotObjectPreflight {
        source_center_mm: system.source_center_mm,
        visible_area_mm2: system.heated_area_mm2,
        view_factor_area_mm2: system.source_view_factor_area_mm2,
        initial_absorbed_w: absorbed,
        effective_heat_flux_w_m2: flux,
    })
}

pub fn solve_nearby_hot_object(
    grid: &VoxelGrid,
    material_fraction: &[f32],
    options: &ThermalOptions,
    source: &NearbyHotObjectOptions,
) -> Result<ThermalResult, String> {
    validate(grid, material_fraction, options)?;
    let system = build_nearby_hot_object_system(grid, material_fraction, options, source)?;
    if options.transient {
        solve_transient(grid, material_fraction, options, &system)
    } else {
        solve_steady(grid, material_fraction, options, &system)
    }
}

fn build_nearby_hot_object_system(
    grid: &VoxelGrid,
    material_fraction: &[f32],
    options: &ThermalOptions,
    source: &NearbyHotObjectOptions,
) -> Result<ThermalSystem, String> {
    for (label, value) in [
        ("source target X", source.target_mm[0]),
        ("source target Y", source.target_mm[1]),
        ("source target Z", source.target_mm[2]),
        ("source normal X", source.outward_normal[0]),
        ("source normal Y", source.outward_normal[1]),
        ("source normal Z", source.outward_normal[2]),
        ("source gap", source.gap_mm),
        ("source diameter", source.diameter_mm),
        ("source temperature", source.source_temperature_c),
        ("source emissivity", source.source_emissivity),
    ] {
        if !value.is_finite() {
            return Err(format!("nearby hot object {label} is not finite"));
        }
    }
    if !(0.0..=100_000.0).contains(&source.gap_mm) {
        return Err("nearby hot object gap must be within 0..100000 mm".into());
    }
    if !(0.1..=100_000.0).contains(&source.diameter_mm) {
        return Err("nearby hot object diameter must be within 0.1..100000 mm".into());
    }
    if source.source_temperature_c <= MIN_ABSOLUTE_TEMPERATURE_C
        || source.source_temperature_c > MAX_SUPPORTED_TEMPERATURE_C
    {
        return Err("nearby hot object temperature is outside the supported range".into());
    }
    if !(0.0..=1.0).contains(&source.source_emissivity) {
        return Err("nearby hot object emissivity must be within 0..1".into());
    }
    let normal_length = (source.outward_normal[0].powi(2)
        + source.outward_normal[1].powi(2)
        + source.outward_normal[2].powi(2))
        .sqrt();
    if normal_length <= 1e-9 {
        return Err("nearby hot object requires a valid picked surface normal".into());
    }
    let normal = [
        source.outward_normal[0] / normal_length,
        source.outward_normal[1] / normal_length,
        source.outward_normal[2] / normal_length,
    ];
    let radius = source.diameter_mm * 0.5;
    let center_distance = source.gap_mm + radius;
    let source_center = [
        source.target_mm[0] + normal[0] * center_distance,
        source.target_mm[1] + normal[1] * center_distance,
        source.target_mm[2] + normal[2] * center_distance,
    ];

    // Build the proven conduction/convection system with no contact or internal
    // heat. The selected cooled face is retained only when the optional mounting
    // sink is enabled. Contact-heater flags are always cleared so every exposed
    // face continues to exchange heat with ambient air and surroundings.
    let mut base = options.clone();
    base.heat_power_w = 0.0;
    base.volumetric_power_w = 0.0;
    let mut system = build_system(grid, material_fraction, &base)?;
    for boundary in &mut system.boundaries {
        boundary.heated = false;
        if !source.use_fixed_temperature_surface {
            boundary.cooled = false;
        }
    }
    if !source.use_fixed_temperature_surface {
        system.cooled_area_mm2 = 0.0;
    }
    system.heat_input_w = 0.0;
    system.heated_area_mm2 = 0.0;
    system.source_temperature_c = source.source_temperature_c;
    system.source_center_mm = source_center;
    system.source_effective_emissivity = effective_emissivity(
        options.emissivity,
        source.source_emissivity,
    );
    system.source_view_factor = vec![0.0; system.boundaries.len()];
    system.source_view_factor_area_mm2 = 0.0;

    let mut minimum_clearance = f64::INFINITY;
    for (index, boundary) in system.boundaries.iter().enumerate() {
        if boundary.cooled {
            continue;
        }
        let face_center = boundary_center_mm(grid, boundary);
        let to_source = [
            source_center[0] - face_center[0],
            source_center[1] - face_center[1],
            source_center[2] - face_center[2],
        ];
        let distance2 = to_source[0].powi(2) + to_source[1].powi(2) + to_source[2].powi(2);
        if distance2 <= 1e-18 {
            return Err("nearby hot object centre coincides with the model surface".into());
        }
        let distance = distance2.sqrt();
        minimum_clearance = minimum_clearance.min(distance - radius);
        let direction = [
            to_source[0] / distance,
            to_source[1] / distance,
            to_source[2] / distance,
        ];
        let face_normal = thermal_face_normal(boundary.face);
        let cosine = (face_normal[0] * direction[0]
            + face_normal[1] * direction[1]
            + face_normal[2] * direction[2])
            .max(0.0);
        if cosine <= 0.0 {
            continue;
        }
        // Differential-surface to diffuse-sphere approximation. For a face
        // aimed at the sphere centre this is exactly the projected solid-angle
        // factor R²/d²; cosine accounts for an oblique receiving face.
        let geometric = (cosine * radius.powi(2) / distance2).clamp(0.0, 1.0);
        if geometric <= 1e-12
            || ray_blocked_by_part(grid, &system.active, boundary.cell, face_center, source_center)
        {
            continue;
        }
        system.source_view_factor[index] = geometric;
        system.heated_area_mm2 += boundary.area_mm2;
        system.source_view_factor_area_mm2 += boundary.area_mm2 * geometric;
    }
    if minimum_clearance < -grid.h * 0.25 {
        return Err(
            "nearby hot object overlaps the model; select the true nearest point or increase the gap"
                .into(),
        );
    }
    if system.source_view_factor_area_mm2 <= 0.0 {
        return Err(
            "the nearby hot object is not visible from any exterior model surface; select another point"
                .into(),
        );
    }
    Ok(system)
}

fn effective_emissivity(part: f64, source: f64) -> f64 {
    if part <= 0.0 || source <= 0.0 {
        return 0.0;
    }
    (1.0 / (1.0 / part + 1.0 / source - 1.0)).clamp(0.0, 1.0)
}

fn thermal_face_normal(face: ThermalFace) -> [f64; 3] {
    match face {
        ThermalFace::XMin => [-1.0, 0.0, 0.0],
        ThermalFace::XMax => [1.0, 0.0, 0.0],
        ThermalFace::YMin => [0.0, -1.0, 0.0],
        ThermalFace::YMax => [0.0, 1.0, 0.0],
        ThermalFace::ZMin => [0.0, 0.0, -1.0],
        ThermalFace::ZMax => [0.0, 0.0, 1.0],
    }
}

fn boundary_center_mm(grid: &VoxelGrid, boundary: &BoundaryFace) -> [f64; 3] {
    let cx = boundary.cell % grid.nx;
    let cy = (boundary.cell / grid.nx) % grid.ny;
    let cz = boundary.cell / (grid.nx * grid.ny);
    let mut center = [
        grid.origin[0] + (cx as f64 + 0.5) * grid.h,
        grid.origin[1] + (cy as f64 + 0.5) * grid.h,
        grid.origin[2] + (cz as f64 + 0.5) * grid.h,
    ];
    let normal = thermal_face_normal(boundary.face);
    for axis in 0..3 {
        center[axis] += normal[axis] * grid.h * 0.5;
    }
    center
}

fn ray_blocked_by_part(
    grid: &VoxelGrid,
    active: &[bool],
    source_cell: usize,
    from: [f64; 3],
    to: [f64; 3],
) -> bool {
    let delta = [to[0] - from[0], to[1] - from[1], to[2] - from[2]];
    let distance = (delta[0].powi(2) + delta[1].powi(2) + delta[2].powi(2)).sqrt();
    if distance <= grid.h {
        return false;
    }
    let steps = (distance / (grid.h * 0.45).max(1e-9)).ceil() as usize;
    for step in 2..steps {
        let t = step as f64 / steps as f64;
        let point = [
            from[0] + delta[0] * t,
            from[1] + delta[1] * t,
            from[2] + delta[2] * t,
        ];
        let x = ((point[0] - grid.origin[0]) / grid.h).floor() as isize;
        let y = ((point[1] - grid.origin[1]) / grid.h).floor() as isize;
        let z = ((point[2] - grid.origin[2]) / grid.h).floor() as isize;
        if x < 0 || y < 0 || z < 0
            || x >= grid.nx as isize || y >= grid.ny as isize || z >= grid.nz as isize
        {
            continue;
        }
        let cell = grid.cell_index(x as usize, y as usize, z as usize);
        if cell != source_cell && active[cell] {
            return true;
        }
    }
    false
}

fn source_absorbed_w(system: &ThermalSystem, temperature: &[f64]) -> f64 {
    if system.source_effective_emissivity <= 0.0 {
        return 0.0;
    }
    let mut absorbed = 0.0;
    for (boundary, factor) in system.boundaries.iter().zip(&system.source_view_factor) {
        if *factor <= 0.0 || boundary.cooled {
            continue;
        }
        let outward = radiation_flux_w_m2(
            system.source_effective_emissivity,
            temperature[boundary.cell],
            system.source_temperature_c,
        ) * factor * boundary.area_mm2 * 1e-6;
        absorbed -= outward;
    }
    absorbed
}
