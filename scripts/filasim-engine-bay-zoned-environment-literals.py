#!/usr/bin/env python3
"""Add safe zoned-environment defaults to every generated ThermalOptions literal."""
from __future__ import annotations

import pathlib
import re

MARKER = "EnderSlicer zoned ThermalOptions literal defaults v1"
SOURCE_MARKER = ".enderslicer-engine-bay-zoned-environment-literals-v1"


def apply(source_root: pathlib.Path) -> None:
    source_root = source_root.resolve()
    thermal = source_root / "crates/filasim-core/src/thermal.rs"
    if not thermal.is_file():
        raise RuntimeError(f"Zoned literal target is missing: {thermal}")
    text = thermal.read_text(encoding="utf-8")
    if "spatial_environment_enabled: false," in text and text.count("spatial_environment_enabled: false,") >= 2:
        (source_root / SOURCE_MARKER).write_text(MARKER + "\n", encoding="utf-8")
        return
    pattern = re.compile(
        r"(?P<indent>\s+)emissivity: (?P<value>[^,\n]+),\n(?P=indent)heated_face:",
        re.MULTILINE,
    )

    def replacement(match: re.Match[str]) -> str:
        indent = match.group("indent")
        return (
            f"{indent}emissivity: {match.group('value')},\n"
            f"{indent}spatial_environment_enabled: false,\n"
            f"{indent}environment_size_mm: [800.0, 600.0, 500.0],\n"
            f"{indent}environment_offset_mm: [0.0; 3],\n"
            f"{indent}environment_air_temperatures_c: [23.0; 12],\n"
            f"{indent}environment_wall_temperatures_c: [23.0; 12],\n"
            f"{indent}environment_convection_w_m2_k: [8.0; 12],\n"
            f"{indent}environment_wall_emissivities: [0.85; 12],\n"
            f"{indent}heated_face:"
        )

    updated, count = pattern.subn(replacement, text)
    if count < 1:
        raise RuntimeError("No generated ThermalOptions test literals were found")
    thermal.write_text(updated, encoding="utf-8")
    if updated.count("spatial_environment_enabled: false,") < count:
        raise RuntimeError("Zoned defaults were not added to every ThermalOptions literal")
    (source_root / SOURCE_MARKER).write_text(MARKER + "\n", encoding="utf-8")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
