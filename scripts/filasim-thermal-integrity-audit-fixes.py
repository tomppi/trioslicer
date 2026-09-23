#!/usr/bin/env python3
"""Small deterministic corrections applied after thermal voxel hardening."""

from __future__ import annotations

import pathlib


MARKER = "EnderSlicer thermal integrity audit fixes v1"


def replace_once(path: pathlib.Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    if old not in text:
        raise RuntimeError(f"Unable to locate {label} in {path}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def replace_remaining_once(path: pathlib.Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count == 0:
        return
    if count != 1:
        raise RuntimeError(f"Expected one remaining {label} in {path}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def apply(source_root: pathlib.Path) -> None:
    source_root = source_root.resolve()
    thermal = source_root / "crates/filasim-core/src/thermal.rs"
    wasm = source_root / "crates/filasim-wasm/src/lib.rs"
    if not thermal.is_file() or not wasm.is_file():
        raise RuntimeError("Apply the thermal base patch and voxel hardening first")

    # Avoid an overlapping mutable/immutable borrow in the generated Rust test.
    replace_once(
        thermal,
        '''        grid.scale[grid.cell_index(0, 0, 1)] = 0.0;
''',
        '''        let upper_left = grid.cell_index(0, 0, 1);
        grid.scale[upper_left] = 0.0;
''',
        "staircase test cell indexing",
    )

    # Keep the geometric occupancy factor physically continuous. Active cells
    # already have positive occupancy, so only a tiny numerical floor is needed;
    # a 1% floor would noticeably over-connect extremely small cut cells.
    replace_once(
        thermal,
        '''        face_area_fraction[ci] = occupancy.clamp(0.0, 1.0).powf(2.0 / 3.0).max(0.01);
''',
        '''        face_area_fraction[ci] = occupancy.clamp(0.0, 1.0).powf(2.0 / 3.0).max(1e-9);
''',
        "cut-cell area numerical floor",
    )
    replace_once(
        thermal,
        '''                        face_area_fraction[ci].min(face_area_fraction[cj]).max(0.01);
''',
        '''                        face_area_fraction[ci].min(face_area_fraction[cj]).max(1e-9);
''',
        "shared cut-cell area numerical floor",
    )

    # The hardening script has two identical cooled-boundary comparisons. Its
    # idempotent helper changes the linear-system occurrence first; explicitly
    # replace the one remaining energy-accounting occurrence as well.
    replace_remaining_once(
        thermal,
        '''        if boundary.face == options.cooled_face {
''',
        '''        if boundary.cooled {
''',
        "cooled-boundary energy-balance comparison",
    )

    # The voxel-hardening transform intentionally replaces the first matching
    # field pair, which is the thermal-eigenforce call. Restore that solve field:
    # equivalent nodal forces must remain occupancy-scaled because they are
    # assembled into the occupancy-scaled stiffness operator.
    replace_once(
        wasm,
        '''            filasim_core::thermal::thermal_eigen_forces(
                &grid,
                &material_stiffness,
                &thermal.temperatures_c,
''',
        '''            filasim_core::thermal::thermal_eigen_forces(
                &grid,
                &temperature_eps,
                &thermal.temperatures_c,
''',
        "occupancy-scaled thermal eigenforce field",
    )

    # Stress is a material quantity, so this call uses the occupancy-decoupled
    # stiffness field. The solve itself continues to use `temperature_eps`.
    replace_once(
        wasm,
        '''        let von_mises = filasim_core::thermal::thermal_von_mises(
            &grid,
            &solution.u,
            e0,
            nu,
            &temperature_eps,
            &thermal.temperatures_c,
''',
        '''        let von_mises = filasim_core::thermal::thermal_von_mises(
            &grid,
            &solution.u,
            e0,
            nu,
            &material_stiffness,
            &thermal.temperatures_c,
''',
        "occupancy-decoupled material stress field",
    )

    # `solve_with_eps_cached` accepts Vec<f32> through Into<EpsField>. Passing
    # `.into()` here leaves the generic target ambiguous under the WASM build;
    # pass the Vec directly so the function's bound performs the conversion.
    replace_remaining_once(
        wasm,
        '''            temperature_eps.clone().into(),
''',
        '''            temperature_eps.clone(),
''',
        "thermal stiffness field conversion",
    )

    marker = source_root / ".enderslicer-thermal-integrity-audit-fixes"
    marker.write_text(MARKER + "\n", encoding="utf-8")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
