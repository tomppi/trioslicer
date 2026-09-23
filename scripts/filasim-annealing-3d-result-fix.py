#!/usr/bin/env python3
"""Keep thermal-only temperature results visible in filaSim's 3D result path."""

from __future__ import annotations

import pathlib


MARKER = "EnderSlicer annealing 3D result display fix v1"
SOURCE_MARKER = ".enderslicer-annealing-3d-result-fix-v1"


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
    viewer = source_root / "web/src/viewer/Viewer.tsx"
    if not viewer.is_file():
        raise RuntimeError(f"Annealing 3D-result target is missing: {viewer}")

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
        "thermal-only 3D displacement bridge",
    )

    verified = viewer.read_text(encoding="utf-8")
    for contract in (
        "const displayDisplacements = detail.structuralValid",
        "new Float32Array(detail.displacements.length)",
        ": { maxDisplacement: 0 }",
        "temperature colours",
    ):
        if contract not in verified:
            raise RuntimeError(f"Annealing 3D-result contract {contract!r} is missing")
    if "detail.structuralValid ? detail.displacements : null" in verified:
        raise RuntimeError("Thermal-only 3D results still discard the displacement buffer")

    (source_root / SOURCE_MARKER).write_text(MARKER + "\n", encoding="utf-8")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
