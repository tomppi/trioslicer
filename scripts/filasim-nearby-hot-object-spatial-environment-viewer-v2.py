#!/usr/bin/env python3
"""Compatibility entry point for the spatial viewer transform.

The spatial implementation follows the marker-drag transform. This entry point
adapts anchors already changed by that earlier transform and applies one strict
TypeScript compatibility rewrite to the generated viewer.
"""
from __future__ import annotations

import importlib.util
import pathlib

BASE = pathlib.Path(__file__).with_name(
    "filasim-nearby-hot-object-spatial-environment-viewer.py"
)
if not BASE.is_file():
    raise RuntimeError(f"Spatial viewer transform is missing: {BASE}")

spec = importlib.util.spec_from_file_location("enderslicer_spatial_viewer_v1", BASE)
if spec is None or spec.loader is None:
    raise RuntimeError(f"Unable to load {BASE}")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

_original_replace_between = module.replace_between
_original_replace_once = module.replace_once


def replace_between_fixed(path, start, end, replacement, label):
    if label == "XY/Z constrained heat-source drag handlers":
        end = "  // ---------- axis gizmo ----------\n"
    return _original_replace_between(path, start, end, replacement, label)


def replace_once_fixed(path, old, new, label):
    if label == "spatial viewer disposal":
        old = """    this.callouts.dispose();
"""
        new = """    this.setNearbyHotObjectMarker(null);
    this.setNearbyHotObjectEnclosureBox(null);
    this.callouts.dispose();
"""
    return _original_replace_once(path, old, new, label)


module.replace_between = replace_between_fixed
module.replace_once = replace_once_fixed


def patch_strict_typescript(scene: pathlib.Path) -> None:
    text = scene.read_text(encoding="utf-8")
    old = """    let source: THREE.Object3D | null = null;
    this.nearbyHotObjectMarker.traverse((object) => {
      if (!source && object.userData?.nearbyMarkerType === "source"
          && Number(object.userData.nearbySourceId) === sourceId) source = object;
    });
    if (!source) return;
    const data = source.userData;
"""
    new = """    const sourceMatches: THREE.Object3D[] = [];
    this.nearbyHotObjectMarker.traverse((object: THREE.Object3D) => {
      if (object.userData?.nearbyMarkerType === "source"
          && Number(object.userData.nearbySourceId) === sourceId) {
        sourceMatches.push(object);
      }
    });
    const source = sourceMatches[0];
    if (!source) return;
    const data = source.userData;
"""
    if new not in text:
        if text.count(old) != 1:
            raise RuntimeError(
                f"Expected one strict TypeScript source-marker lookup in {scene}, "
                f"found {text.count(old)}"
            )
        scene.write_text(text.replace(old, new, 1), encoding="utf-8")


def apply(source_root: pathlib.Path) -> None:
    module.apply(source_root)
    patch_strict_typescript(
        source_root.resolve() / "web/src/viewer/SceneManager.ts"
    )


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
