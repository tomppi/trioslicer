#!/usr/bin/env python3
"""Align the 12-zone engine-bay map with vehicle width/depth axes."""
from __future__ import annotations

import pathlib

MARKER = "EnderSlicer engine-bay zone vehicle-axis mapping v1"
SOURCE_MARKER = ".enderslicer-engine-bay-zone-axis-fix-v1"


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
        raise RuntimeError(f"Engine-bay zone-axis target is missing: {thermal}")

    replace_once(
        thermal,
        """    /// Optional 3 x 2 x 2 local engine-bay environment. Zone order is
    /// longitudinal X (front/middle/rear) fastest, then lateral Y, then Z.
""",
        """    /// Optional 3 x 2 x 2 local engine-bay environment. Vehicle axes are
    /// X = left/right width, Y = front/rear depth, Z = height. Zone order is
    /// longitudinal Y (front/middle/rear) fastest, then lateral X, then Z.
""",
        "zoned ThermalOptions axis documentation",
    )
    replace_once(
        thermal,
        """    let longitudinal = (normalized[0] * 3.0).floor() as usize;
    let lateral = usize::from(normalized[1] >= 0.5);
    let vertical = usize::from(normalized[2] >= 0.5);
""",
        """    // Vehicle coordinates: X is left/right, Y is front/rear.
    let longitudinal = (normalized[1] * 3.0).floor() as usize;
    let lateral = usize::from(normalized[0] >= 0.5);
    let vertical = usize::from(normalized[2] >= 0.5);
""",
        "spatial environment vehicle-axis mapping",
    )

    text = thermal.read_text(encoding="utf-8")
    for contract in (
        "X = left/right width, Y = front/rear depth, Z = height",
        "longitudinal Y (front/middle/rear) fastest",
        "let longitudinal = (normalized[1] * 3.0).floor() as usize;",
        "let lateral = usize::from(normalized[0] >= 0.5);",
    ):
        if contract not in text:
            raise RuntimeError(f"Engine-bay vehicle-axis contract {contract!r} is missing")
    (source_root / SOURCE_MARKER).write_text(MARKER + "\n", encoding="utf-8")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
