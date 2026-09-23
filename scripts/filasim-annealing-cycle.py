#!/usr/bin/env python3
"""Apply the complete geometry-aware annealing extension after Thermal Integrity v12."""
from __future__ import annotations

import pathlib
import re

from filasim_annealing_common import ANNEALING_EVENT, MARKER, replace_once
from filasim_annealing_core import patch_thermal_core
from filasim_annealing_web import patch_client, patch_react_tabs, patch_viewer, patch_wasm


def apply(source_root: pathlib.Path) -> None:
    source_root = source_root.resolve()
    thermal = source_root / "crates/filasim-core/src/thermal.rs"
    wasm = source_root / "crates/filasim-wasm/src/lib.rs"
    client = source_root / "web/src/engine/EngineClient.ts"
    viewer = source_root / "web/src/viewer/Viewer.tsx"
    rail = source_root / "web/src/ui/StepRail.tsx"
    panel = source_root / "web/src/ui/StepPanel.tsx"
    topbar = source_root / "web/src/ui/TopBar.tsx"
    for path in (thermal, wasm, client, viewer, rail, panel, topbar):
        if not path.is_file():
            raise RuntimeError(f"Annealing transform target is missing: {path}")

    patch_thermal_core(thermal)

    # Thermal Integrity already emits a large serde_json::json! statistics
    # object. The two annealing readiness fields cross Rust's default macro
    # expansion depth, so make the required limit explicit and deterministic.
    recursion_limit = '#![recursion_limit = "256"]'
    wasm_text = wasm.read_text(encoding="utf-8")
    if recursion_limit not in wasm_text:
        wasm.write_text(f"{recursion_limit}\n{wasm_text}", encoding="utf-8")

    patch_wasm(wasm)
    patch_client(client)
    patch_viewer(viewer)

    # Fresh source builds receive the 3D result correction here so the normal
    # exact-source workflow compiles the corrected Viewer. A separate suffix
    # transform applies the same idempotent replacement to already-cached v5
    # source trees whose annealing marker is present.
    replace_once(
        viewer,
        '''      scene.setDisplacements(
        detail.structuralValid ? detail.displacements : null,
        detail.structuralValid && Number.isFinite(detail.maxDisplacementMm)
          ? { maxDisplacement: Number(detail.maxDisplacementMm) } : null
      );
''',
        '''      // filaSim's scalar-field renderer enters its result-colour path only
      // while a displacement buffer is installed. Thermal-only results must
      // therefore retain a correctly sized zero-displacement field instead of
      // passing null; the STL remains undeformed while temperature colours,
      // probing and the 3D legend become active.
      const displayDisplacements = detail.structuralValid
        ? detail.displacements
        : new Float32Array(detail.displacements.length);
      scene.setDisplacements(
        displayDisplacements,
        detail.structuralValid && Number.isFinite(detail.maxDisplacementMm)
          ? { maxDisplacement: Number(detail.maxDisplacementMm) }
          : { maxDisplacement: 0 }
      );
''',
        "annealing thermal-only 3D displacement bridge",
    )

    patch_react_tabs(rail, panel, topbar)

    marker = source_root / ".enderslicer-annealing-cycle-v1"
    marker.write_text(MARKER + "\n", encoding="utf-8")

    required = (
        (thermal, "fixed_surface_enabled"),
        (thermal, "readiness_complete_time_seconds"),
        (thermal, "coldest material voxel"),
        (thermal, "geometry_aware_annealing_thickness_test"),
        (wasm, recursion_limit),
        (wasm, "fixed_surface_enabled: opts.fixed_surface_enabled"),
        (wasm, '"readinessCompleteTimeSeconds"'),
        (client, "readinessCompleteTimeSeconds"),
        (viewer, "Annealing temperature · 3D result"),
        (viewer, "new Float32Array(detail.displacements.length)"),
        (viewer, "maxDisplacement: 0"),
        (rail, ANNEALING_EVENT),
        (rail, '<span className="st-name">Anneal</span>'),
        (panel, "enderslicer-annealing-calculator-mount"),
        (topbar, ANNEALING_EVENT),
    )
    for path, contract in required:
        if contract not in path.read_text(encoding="utf-8"):
            raise RuntimeError(f"Annealing contract {contract!r} is missing from {path}")

    core = thermal.read_text(encoding="utf-8")
    for match in re.finditer(r"ThermalOptions\s*\{", core):
        end = core.find("}\n", match.start())
        if end < 0 or "fixed_surface_enabled" not in core[match.start():end + 2]:
            raise RuntimeError("A ThermalOptions constructor is missing annealing fields")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
