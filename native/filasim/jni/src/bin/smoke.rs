// SPDX-License-Identifier: AGPL-3.0-only
// Host verification for the native filaSim session: load an STL, constrain it,
// solve it and optimize it without Android or a browser. This is the tool that
// proves the native pipeline produces a real Smart Infill package.

use std::path::PathBuf;

use filasim_core::zip::read_zip;
use filasim_jni::session::Session;

fn main() {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 2 {
        eprintln!("usage: filasim-smoke <model.stl> [out-dir] [budget-pct] [goal] [mode]");
        std::process::exit(2);
    }
    let model = PathBuf::from(&args[1]);
    let out_dir = PathBuf::from(args.get(2).cloned().unwrap_or_else(|| "/tmp".into()));
    let budget: f64 = args.get(3).and_then(|v| v.parse().ok()).unwrap_or(25.0);
    // "budget", "match" or "strength": the goal is a UI choice now, so it has to
    // be exercisable here without a phone.
    let goal = args.get(4).cloned().unwrap_or_else(|| "budget".into());
    // graded (default), binary (hollow and solid regions) or solid (Part Topo
    // body): each takes a different binning and export path, so each needs a run.
    let mode = args.get(5).cloned().unwrap_or_else(|| "graded".into());
    let binary = mode == "binary";
    let solid = mode == "solid";
    let bytes = std::fs::read(&model).expect("read model");
    let started = std::time::Instant::now();

    let mut session = Session::new_from_bytes(&bytes, "smoke.stl").expect("import");
    println!(
        "imported {} triangles -> working {} ({} original), bodies {}",
        session.original_triangle_count(),
        session.working_triangle_count(),
        session.original_triangle_count(),
        session.bodies()
    );
    println!("patches: {}", session.patch_count());

    // A crude but real setup: fix the bottom band, push the top band down.
    let pos = session.original_positions();
    let (mut lo, mut hi) = (f32::MAX, f32::MIN);
    for t in pos.chunks(9) {
        for v in 0..3 {
            let z = t[3 * v + 2];
            lo = lo.min(z);
            hi = hi.max(z);
        }
    }
    let band = (hi - lo) * 0.08;
    let (mut bottom, mut top) = (Vec::new(), Vec::new());
    for (i, t) in pos.chunks(9).enumerate() {
        let cz = (t[2] + t[5] + t[8]) / 3.0;
        if cz <= lo + band {
            bottom.push(i as u32);
        } else if cz >= hi - band {
            top.push(i as u32);
        }
    }
    println!("support triangles: {}, load triangles: {}", bottom.len(), top.len());
    session
        .add_bc_json(&serde_json::json!({ "kind": "fixed", "tris": bottom }).to_string())
        .expect("fixed");
    session
        .add_bc_json(
            &serde_json::json!({ "kind": "force", "tris": top, "vector": [0.0, 0.0, -400.0] })
                .to_string(),
        )
        .expect("force");

    let info = session.voxel_info().expect("voxel");
    println!("grid: {info}");

    let check = session.check().expect("check");
    println!("check: {check}");

    let solve = session.solve().expect("solve");
    println!("solve: {solve}");

    // Keep the smoke run quick: a coarse grid, few smoothing passes.
    session.set_resolution(Some(120_000), None, Some(0.9), None);
    let opts = serde_json::json!({
        "budget_pct": budget,
        "perimeters": 2,
        "line_width": 0.45,
        "top_bottom_layers": 3,
        "layer_height": 0.2,
        "n_bins": 3,
        "smooth_iters": 1,
        "goal": goal,
        "sf_target": 2.0,
        "sf_measure": "both",
        "binary": binary,
        "solid": solid,
    })
    .to_string();
    let summary = session.optimize(&opts).expect("optimize");
    println!("optimize: {summary}");
    println!("progress: {}", session.progress_json());

    let regions = session.region_count();
    println!("regions: {regions}");
    for i in 0..regions {
        println!(
            "  region {i}: density {:.3}, {} vertices, {} triangles",
            session.region_density(i),
            session.region_positions(i).len() / 3,
            session.region_indices(i).len() / 3
        );
    }

    // The result view tints the part by the density under each surface triangle;
    // print what that lookup produced so a regression is visible here.
    let bins = session.surface_bins();
    if !bins.is_empty() {
        let resolved = bins.iter().filter(|&&b| b >= 0).count();
        let mut counts = std::collections::BTreeMap::new();
        for &b in &bins {
            *counts.entry(b).or_insert(0usize) += 1;
        }
        println!("surface bins: {resolved}/{} triangles resolved, {counts:?}", bins.len());
    }

    std::fs::create_dir_all(&out_dir).expect("out dir");
    let summary_path = out_dir.join("smoke-summary.json");
    std::fs::write(&summary_path, session.result_summary()).expect("write summary");
    println!("wrote {}", summary_path.display());

    // ---- the artifact the app hands to the slice pipeline -------------------
    // Structural checks only: the Kotlin side writes the v2 metadata and the app
    // validates the pair, so what has to hold here is the archive itself.
    if solid {
        assert!(session.is_solid_mode(), "solid mode should report itself");
        let stl = session.export_solid_stl().expect("solid export");
        let count = stl_triangle_count(&stl);
        assert!(count > 0, "the Part Topo body has no triangles");
        let stl_path = out_dir.join("smoke-solid.stl");
        std::fs::write(&stl_path, &stl).expect("write solid stl");
        println!("wrote {} ({} bytes)", stl_path.display(), stl.len());
        println!("verify: solid ok ({count} triangles)");
    } else {
        assert!(!session.is_solid_mode(), "graded/binary is not solid mode");
        let zip = session.export_modifier_zip().expect("modifier zip");
        let entries = read_zip(&zip).expect("the modifier archive is readable");
        assert!(!entries.is_empty(), "the archive has no modifiers");
        assert!(
            entries.len() <= MAX_MODIFIERS,
            "more modifiers than the app store accepts"
        );
        assert_eq!(entries.len(), regions as usize, "one entry per region");
        let base_pct = summary_base_pct(&session);
        let mut previous = base_pct;
        for (index, (name, bytes)) in entries.iter().enumerate() {
            let density_pct = (session.region_density(index as u32) * 100.0).round() as u32;
            assert_eq!(name, &format!("modifier_{density_pct}pct.stl"), "entry {index} name");
            assert!(
                density_pct > previous,
                "densities must rise above each other and above the base"
            );
            previous = density_pct;
            assert!(stl_triangle_count(bytes) > 0, "entry {index} has no triangles");
        }
        let zip_path = out_dir.join("smoke-modifiers.zip");
        std::fs::write(&zip_path, &zip).expect("write zip");
        println!("wrote {} ({} bytes)", zip_path.display(), zip.len());
        println!(
            "verify: modifiers ok ({mode}, {} entries, base {base_pct}% up to {previous}%)",
            entries.len(),
        );
    }

    println!("total {:.1}s", started.elapsed().as_secs_f64());
}

/// The app store refuses more modifiers than this (SmartInfillPackageStore).
const MAX_MODIFIERS: usize = 16;

/// Binary STL triangle count, asserting the header agrees with the byte length.
fn stl_triangle_count(bytes: &[u8]) -> usize {
    assert!(bytes.len() >= 84, "STL is shorter than its header");
    let count = u32::from_le_bytes([bytes[80], bytes[81], bytes[82], bytes[83]]) as usize;
    assert_eq!(
        bytes.len(),
        84 + count * 50,
        "STL length does not match its header"
    );
    count
}

/// The base infill density the summary reported, as a percentage.
fn summary_base_pct(session: &Session) -> u32 {
    let summary: serde_json::Value =
        serde_json::from_str(&session.result_summary()).expect("summary is JSON");
    (summary["baseDensity"].as_f64().unwrap_or(0.0) * 100.0).round() as u32
}
