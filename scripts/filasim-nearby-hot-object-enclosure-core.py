#!/usr/bin/env python3
"""Add enclosure metadata needed by the Nearby Hot Object lumped-air runtime."""
from __future__ import annotations

import pathlib

MARKER = "EnderSlicer nearby hot object enclosure thermal core v1"
SOURCE_MARKER = ".enderslicer-nearby-hot-object-enclosure-core-v1"


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
        raise RuntimeError(f"Nearby enclosure core target is missing: {thermal}")

    replace_once(
        thermal,
        """    pub source_temperature_c: f64,
    pub source_emissivity: f64,
    pub use_fixed_temperature_surface: bool,
""",
        """    pub source_temperature_c: f64,
    pub source_emissivity: f64,
    /// Printed-surface emissivity used only for exchange with the selected hot
    /// object. The runtime may use a different effective emissivity for the
    /// enclosure-wall boundary without changing source absorption.
    pub source_part_emissivity: f64,
    pub use_fixed_temperature_surface: bool,
""",
        "independent source-side part emissivity",
    )
    replace_once(
        thermal,
        """    pub initial_absorbed_w: f64,
    pub effective_heat_flux_w_m2: f64,
}
""",
        """    pub initial_absorbed_w: f64,
    pub effective_heat_flux_w_m2: f64,
    /// Total exterior area exchanging with the selected environment, excluding
    /// an optional fixed-temperature mounting surface.
    pub total_exterior_area_mm2: f64,
}
""",
        "preflight total exterior area",
    )
    replace_once(
        thermal,
        """        initial_absorbed_w: absorbed,
        effective_heat_flux_w_m2: flux,
    })
""",
        """        initial_absorbed_w: absorbed,
        effective_heat_flux_w_m2: flux,
        total_exterior_area_mm2: system.boundaries.iter()
            .filter(|boundary| !boundary.cooled)
            .map(|boundary| boundary.area_mm2)
            .sum(),
    })
""",
        "preflight exterior-area result",
    )
    replace_once(
        thermal,
        """        ("source temperature", source.source_temperature_c),
        ("source emissivity", source.source_emissivity),
""",
        """        ("source temperature", source.source_temperature_c),
        ("source emissivity", source.source_emissivity),
        ("source-side part emissivity", source.source_part_emissivity),
""",
        "source-side emissivity finite validation",
    )
    replace_once(
        thermal,
        """    if !(0.0..=1.0).contains(&source.source_emissivity) {
        return Err("nearby hot object emissivity must be within 0..1".into());
    }
""",
        """    if !(0.0..=1.0).contains(&source.source_emissivity) {
        return Err("nearby hot object emissivity must be within 0..1".into());
    }
    if !(0.0..=1.0).contains(&source.source_part_emissivity) {
        return Err("nearby hot object source-side part emissivity must be within 0..1".into());
    }
""",
        "source-side emissivity range validation",
    )
    replace_once(
        thermal,
        """    system.source_effective_emissivity = effective_emissivity(
        options.emissivity,
        source.source_emissivity,
    );
""",
        """    system.source_effective_emissivity = effective_emissivity(
        source.source_part_emissivity,
        source.source_emissivity,
    );
""",
        "source-independent enclosure emissivity",
    )

    text = thermal.read_text(encoding="utf-8")
    test_marker = "source_side_emissivity_remains_independent_from_enclosure_boundary"
    if test_marker not in text:
        text = text.rstrip() + r'''

#[cfg(test)]
mod nearby_hot_object_enclosure_contract_tests {
    use super::*;

    #[test]
    fn source_side_emissivity_remains_independent_from_enclosure_boundary() {
        let source_exchange = effective_emissivity(0.9, 0.8);
        let enclosure_exchange = effective_emissivity(0.2, 0.8);
        assert!(source_exchange > enclosure_exchange);
        assert!((source_exchange - 0.7346938775510204).abs() < 1e-12);
    }
}
''' + "\n"
        thermal.write_text(text, encoding="utf-8")

    for contract in (
        "source_part_emissivity: f64",
        "total_exterior_area_mm2: f64",
        "source_side_emissivity_remains_independent_from_enclosure_boundary",
    ):
        if contract not in thermal.read_text(encoding="utf-8"):
            raise RuntimeError(f"Nearby enclosure core contract {contract!r} is missing")
    (source_root / SOURCE_MARKER).write_text(MARKER + "\n", encoding="utf-8")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
