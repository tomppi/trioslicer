#!/usr/bin/env python3
"""Expose filaSim's live material library to Android thermal workspaces.

The material manager is the source of truth for every property it already owns.
Thermal Integrity and Anneal may complement conductivity, heat capacity and
process schedule data, but must not maintain competing copies of density,
elastic, strength, shrink or CTE values.
"""
from __future__ import annotations

import pathlib

from filasim_annealing_common import append_once

MARKER = "EnderSlicer filaSim material source v1"

BRIDGE = r'''

// EnderSlicer filaSim material source v1
export interface EnderSlicerFilaSimMaterialSnapshot {
  activeMaterialName: string;
  materials: Material[];
  print: {
    infillPct: number;
    perimeters: number;
    lineWidthMm: number;
    topBottomLayers: number;
    layerHeightMm: number;
  };
}

declare global {
  interface Window {
    EnderSlicerFilaSimMaterialSource?: Readonly<{
      getSnapshot: () => EnderSlicerFilaSimMaterialSnapshot;
      setActiveMaterial: (name: string) => boolean;
    }>;
  }
}

function enderSlicerFilaSimMaterialSnapshot(): EnderSlicerFilaSimMaterialSnapshot {
  const state = useStore.getState();
  return {
    activeMaterialName: state.material.name,
    // Plain copies prevent an external Android-only consumer from mutating
    // Zustand state behind the material manager's validation/persistence path.
    materials: state.materials.map((material) => ({ ...material })),
    print: {
      infillPct: state.printInfill,
      perimeters: state.perimeters,
      lineWidthMm: state.lineWidth,
      topBottomLayers: state.topBottomLayers,
      layerHeightMm: state.layerHeight,
    },
  };
}

if (typeof window !== "undefined") {
  const publish = () => {
    window.dispatchEvent(
      new CustomEvent<EnderSlicerFilaSimMaterialSnapshot>(
        "enderslicer-filasim-materials-changed",
        { detail: enderSlicerFilaSimMaterialSnapshot() }
      )
    );
  };

  window.EnderSlicerFilaSimMaterialSource = Object.freeze({
    getSnapshot: enderSlicerFilaSimMaterialSnapshot,
    setActiveMaterial(name: string): boolean {
      const state = useStore.getState();
      const material = state.materials.find((entry) => entry.name === name);
      if (!material || material.process === "isotropic") return false;
      state.setMaterial(material);
      return true;
    },
  });

  useStore.subscribe((state, previous) => {
    if (
      state.material !== previous.material ||
      state.materials !== previous.materials ||
      state.printInfill !== previous.printInfill ||
      state.perimeters !== previous.perimeters ||
      state.lineWidth !== previous.lineWidth ||
      state.topBottomLayers !== previous.topBottomLayers ||
      state.layerHeight !== previous.layerHeight
    ) {
      publish();
    }
  });
  queueMicrotask(publish);
}
'''


def apply(source_root: pathlib.Path) -> None:
    source_root = source_root.resolve()
    store = source_root / "web/src/store.ts"
    if not store.is_file():
        raise RuntimeError(f"filaSim material source target is missing: {store}")

    append_once(store, "EnderSlicer filaSim material source v1", BRIDGE)
    verified = store.read_text(encoding="utf-8")
    for contract in (
        "EnderSlicerFilaSimMaterialSource",
        "enderslicer-filasim-materials-changed",
        "state.materials.map",
        "state.setMaterial(material)",
        "state.material !== previous.material",
    ):
        if contract not in verified:
            raise RuntimeError(f"filaSim material source contract is missing: {contract}")

    (source_root / ".enderslicer-filasim-material-source-v1").write_text(
        MARKER + "\n", encoding="utf-8"
    )


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
