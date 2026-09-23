#!/usr/bin/env python3
"""Align legacy boundary regression with the physical contact-heater model."""
from __future__ import annotations
import pathlib

MARKER = "EnderSlicer thermal integrity physical contract fix v1"


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
        raise RuntimeError(f"Thermal core is missing: {thermal}")

    replace_once(
        thermal,
        '''    #[test]
    fn selected_face_uses_only_the_global_extreme_not_a_step_underside() {
        let mut grid = VoxelGrid::solid_box(2, 1, 2, 1.0);
        // Connected staircase: lower row [solid, solid], upper row [void, solid].
        let upper_left = grid.cell_index(0, 0, 1);
        grid.scale[upper_left] = 0.0;
        let material = grid.scale.clone();
        let mut options = base_options();
        options.heated_face = ThermalFace::XMin;
        options.cooled_face = ThermalFace::XMax;
        let system = build_system(&grid, &material, &options).unwrap();
        assert!((system.heated_area_mm2 - 1.0).abs() < 1e-12);
        assert!((system.cooled_area_mm2 - 2.0).abs() < 1e-12);
    }
''',
        '''    #[test]
    fn heater_orientation_uses_all_exterior_faces_while_sink_stays_global() {
        let mut grid = VoxelGrid::solid_box(2, 1, 2, 1.0);
        // Connected staircase: lower row [solid, solid], upper row [void, solid].
        let upper_left = grid.cell_index(0, 0, 1);
        grid.scale[upper_left] = 0.0;
        let material = grid.scale.clone();
        let mut options = base_options();
        options.heated_face = ThermalFace::XMin;
        options.cooled_face = ThermalFace::XMax;
        let system = build_system(&grid, &material, &options).unwrap();
        // The lower-left exterior and the exposed step face both receive the
        // contact-heater power. The fixed-temperature sink remains the two
        // exterior faces on the global X+ extreme plane.
        assert!((system.heated_area_mm2 - 2.0).abs() < 1e-12);
        assert!((system.cooled_area_mm2 - 2.0).abs() < 1e-12);
    }
''',
        "legacy global-extreme heater regression",
    )

    marker = source_root / ".enderslicer-thermal-integrity-physical-contract-fix-v1"
    marker.write_text(MARKER + "\n", encoding="utf-8")

    verified = thermal.read_text(encoding="utf-8")
    if "selected_face_uses_only_the_global_extreme_not_a_step_underside" in verified:
        raise RuntimeError("Obsolete global-extreme heater regression remains")
    if "heater_orientation_uses_all_exterior_faces_while_sink_stays_global" not in verified:
        raise RuntimeError("Physical boundary regression is missing")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
