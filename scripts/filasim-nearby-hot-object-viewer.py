#!/usr/bin/env python3
"""Add Nearby Hot Object picking, marker and replacement workflow labels."""
from __future__ import annotations

import pathlib

MARKER = "EnderSlicer nearby hot object viewer v1"
PICK_EVENT = "enderslicer-nearby-hot-object-picked"
PICK_MODE_EVENT = "enderslicer-nearby-hot-object-pick-mode"
MARKER_EVENT = "enderslicer-nearby-hot-object-marker"
CLEAR_EVENT = "enderslicer-nearby-hot-object-clear"


def replace_once(path: pathlib.Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one {label} in {path}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def insert_before_once(path: pathlib.Path, marker: str, insertion: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if insertion in text:
        return
    count = text.count(marker)
    if count != 1:
        raise RuntimeError(f"Expected one {label} marker in {path}, found {count}")
    path.write_text(text.replace(marker, insertion + marker, 1), encoding="utf-8")


def apply(source_root: pathlib.Path) -> None:
    source_root = source_root.resolve()
    viewer = source_root / "web/src/viewer/Viewer.tsx"
    scene = source_root / "web/src/viewer/SceneManager.ts"
    rail = source_root / "web/src/ui/StepRail.tsx"
    panel = source_root / "web/src/ui/StepPanel.tsx"
    for path in (viewer, scene, rail, panel):
        if not path.is_file():
            raise RuntimeError(f"Nearby-hot-object viewer target is missing: {path}")

    replace_once(
        scene,
        """  /** Viewer picked a new deformation autoscale (display exaggeration base). */
""",
        """  /** Pick the nearest point and outward normal for a nearby hot object. */
  onNearbyHotObjectPick?: (
    point: [number, number, number],
    normal: [number, number, number]
  ) => void;
  /** Viewer picked a new deformation autoscale (display exaggeration base). */
""",
        "SceneCallbacks nearby-source pick",
    )
    replace_once(
        scene,
        """  private pickArrowDisposables: (THREE.BufferGeometry | THREE.Material)[] = [];

""",
        """  private pickArrowDisposables: (THREE.BufferGeometry | THREE.Material)[] = [];
  private nearbyHotObjectPickMode = false;
  private nearbyHotObjectMarker = new THREE.Group();
  private nearbyHotObjectMarkerDisposables: (THREE.BufferGeometry | THREE.Material)[] = [];

""",
        "SceneManager nearby-source fields",
    )
    replace_once(
        scene,
        """    this.scene.add(this.bcMarkers);
""",
        """    this.scene.add(this.nearbyHotObjectMarker);
    this.scene.add(this.bcMarkers);
""",
        "nearby-source marker scene group",
    )
    replace_once(
        scene,
        """    for (const d of this.pickArrowDisposables) d.dispose();
""",
        """    for (const d of this.pickArrowDisposables) d.dispose();
    for (const d of this.nearbyHotObjectMarkerDisposables) d.dispose();
""",
        "nearby-source marker disposal",
    )

    marker_methods = r"""
  setNearbyHotObjectPickMode(on: boolean) {
    this.nearbyHotObjectPickMode = on;
    if (this.canvas) this.canvas.style.cursor = on ? "crosshair" : "";
  }

  setNearbyHotObjectMarker(detail: {
    target: [number, number, number];
    normal: [number, number, number];
    gapMm: number;
    diameterMm: number;
  } | null) {
    for (const child of [...this.nearbyHotObjectMarker.children]) {
      this.nearbyHotObjectMarker.remove(child);
    }
    for (const disposable of this.nearbyHotObjectMarkerDisposables) disposable.dispose();
    this.nearbyHotObjectMarkerDisposables = [];
    if (!detail) return;
    const target = new THREE.Vector3(...detail.target);
    const normal = new THREE.Vector3(...detail.normal);
    if (normal.lengthSq() < 1e-12) return;
    normal.normalize();
    const radius = Math.max(0.05, Number(detail.diameterMm) * 0.5);
    const gap = Math.max(0, Number(detail.gapMm));
    const center = target.clone().addScaledVector(normal, gap + radius);
    const pointRadius = Math.max(this.bboxDiag * 0.009, 0.35);
    const pointGeo = new THREE.SphereGeometry(pointRadius, 18, 12);
    const pointMat = new THREE.MeshBasicMaterial({ color: 0xffd166, depthTest: false });
    const point = new THREE.Mesh(pointGeo, pointMat);
    point.position.copy(target);
    point.renderOrder = 1000;
    const lineGeo = new THREE.BufferGeometry().setFromPoints([target, center]);
    const lineMat = new THREE.LineBasicMaterial({ color: 0xff7b54, depthTest: false });
    const line = new THREE.Line(lineGeo, lineMat);
    line.renderOrder = 999;
    const sourceGeo = new THREE.SphereGeometry(radius, 28, 18);
    const sourceMat = new THREE.MeshStandardMaterial({
      color: 0xff5a36,
      emissive: 0xff2d16,
      emissiveIntensity: 0.45,
      transparent: true,
      opacity: 0.38,
      wireframe: true,
      depthWrite: false,
    });
    const hotObject = new THREE.Mesh(sourceGeo, sourceMat);
    hotObject.position.copy(center);
    this.nearbyHotObjectMarker.add(point, line, hotObject);
    this.nearbyHotObjectMarkerDisposables.push(
      pointGeo, pointMat, lineGeo, lineMat, sourceGeo, sourceMat
    );
  }

  private onNearbyHotObjectPointerDown = (ev: PointerEvent) => {
    if (!this.nearbyHotObjectPickMode || !this.mesh || ev.button !== 0) return;
    const hit = this.rayTri(ev);
    const normal = hit && hit.faceIndex != null ? this.triNormalOf(hit.faceIndex) : null;
    if (!hit || !normal) return;
    this.nearbyHotObjectPickMode = false;
    this.canvas.style.cursor = "";
    ev.preventDefault();
    ev.stopImmediatePropagation();
    this.callbacks.onNearbyHotObjectPick?.(
      [hit.point.x, hit.point.y, hit.point.z],
      [normal.x, normal.y, normal.z]
    );
  };

"""
    insert_before_once(scene, "  // ---------- axis gizmo ----------\n", marker_methods, "nearby marker methods")

    insert_before_once(
        scene,
        """    canvas.addEventListener("pointerup", this.onPointerUp);
""",
        """    // Capture selection before OrbitControls and the normal viewer pointer
    // handler. Anchoring this insertion to pointerup keeps the transform
    // compatible with earlier features that may separate pointermove and pointerdown.
    canvas.addEventListener("pointerdown", this.onNearbyHotObjectPointerDown, true);
""",
        "nearby-source pointer capture registration",
    )
    replace_once(
        scene,
        """    this.callouts.dispose();
    this.canvas?.removeEventListener("wheel", this.onWheel);
""",
        """    this.callouts.dispose();
    this.canvas?.removeEventListener(
      "pointerdown", this.onNearbyHotObjectPointerDown, true
    );
    this.canvas?.removeEventListener("wheel", this.onWheel);
""",
        "nearby-source pointer capture disposal",
    )

    replace_once(
        viewer,
        """const THERMAL_CLEAR_EVENT = "enderslicer-thermal-clear-3d";

""",
        f"""const THERMAL_CLEAR_EVENT = "enderslicer-thermal-clear-3d";
const NEARBY_PICK_EVENT = "{PICK_EVENT}";
const NEARBY_PICK_MODE_EVENT = "{PICK_MODE_EVENT}";
const NEARBY_MARKER_EVENT = "{MARKER_EVENT}";
const NEARBY_CLEAR_EVENT = "{CLEAR_EVENT}";

""",
        "Viewer nearby-source events",
    )
    replace_once(
        viewer,
        """      onAutoScale: (autoScale) => {
""",
        f"""      onNearbyHotObjectPick: (point, normal) => {{
        window.dispatchEvent(new CustomEvent(NEARBY_PICK_EVENT, {{
          detail: {{ point, normal }},
        }}));
      }},
      onAutoScale: (autoScale) => {{
""",
        "Viewer nearby-source callback",
    )
    replace_once(
        viewer,
        """    window.addEventListener(THERMAL_RESULT_EVENT, onThermalResult3d);
    window.addEventListener(THERMAL_CLEAR_EVENT, onThermalClear3d);

""",
        """    const onNearbyPickMode = (event: Event) => {
      scene.setNearbyHotObjectPickMode(Boolean((event as CustomEvent).detail));
    };
    const onNearbyMarker = (event: Event) => {
      scene.setNearbyHotObjectMarker((event as CustomEvent).detail ?? null);
    };
    const onNearbyClear = () => {
      scene.setNearbyHotObjectPickMode(false);
      scene.setNearbyHotObjectMarker(null);
    };
    window.addEventListener(THERMAL_RESULT_EVENT, onThermalResult3d);
    window.addEventListener(THERMAL_CLEAR_EVENT, onThermalClear3d);
    window.addEventListener(NEARBY_PICK_MODE_EVENT, onNearbyPickMode);
    window.addEventListener(NEARBY_MARKER_EVENT, onNearbyMarker);
    window.addEventListener(NEARBY_CLEAR_EVENT, onNearbyClear);

""",
        "Viewer nearby-source listeners",
    )
    replace_once(
        viewer,
        """    sceneEvents.onModelLoaded = (m) => scene.setModel(m);
""",
        """    sceneEvents.onModelLoaded = (m) => {
      scene.setNearbyHotObjectMarker(null);
      window.dispatchEvent(new CustomEvent(NEARBY_CLEAR_EVENT));
      scene.setModel(m);
    };
""",
        "nearby-source clear on model load",
    )
    replace_once(
        viewer,
        """      window.removeEventListener(THERMAL_RESULT_EVENT, onThermalResult3d);
      window.removeEventListener(THERMAL_CLEAR_EVENT, onThermalClear3d);
""",
        """      window.removeEventListener(THERMAL_RESULT_EVENT, onThermalResult3d);
      window.removeEventListener(THERMAL_CLEAR_EVENT, onThermalClear3d);
      window.removeEventListener(NEARBY_PICK_MODE_EVENT, onNearbyPickMode);
      window.removeEventListener(NEARBY_MARKER_EVENT, onNearbyMarker);
      window.removeEventListener(NEARBY_CLEAR_EVENT, onNearbyClear);
""",
        "Viewer nearby-source cleanup",
    )

    for path, old, new, label in (
        (rail, 'title="Thermal Integrity — service-temperature heat flow and structural FEA"',
         'title="Nearby Hot Object — radiative and ambient heating of the printed part"',
         "rail title"),
        (rail, '<span className="st-name">Thermal</span>',
         '<span className="st-name">Hot object</span>', "rail label"),
        (panel, '<b>Thermal Integrity</b>', '<b>Nearby Hot Object</b>', "panel title"),
        (panel, '<span>Service-temperature heat flow and structural FEA.</span>',
         '<span>Temperature and deformation near a hot engine part, fire, or heater.</span>',
         "panel description"),
    ):
        replace_once(path, old, new, label)

    marker = source_root / ".enderslicer-nearby-hot-object-viewer-v1"
    marker.write_text(MARKER + "\n", encoding="utf-8")
    for path, contract in (
        (scene, "setNearbyHotObjectMarker"),
        (scene, "onNearbyHotObjectPointerDown"),
        (scene, 'addEventListener("pointerdown", this.onNearbyHotObjectPointerDown, true)'),
        (scene, 'removeEventListener(\n      "pointerdown", this.onNearbyHotObjectPointerDown, true'),
        (viewer, PICK_EVENT),
        (rail, "Hot object"),
        (panel, "Nearby Hot Object"),
    ):
        if contract not in path.read_text(encoding="utf-8"):
            raise RuntimeError(f"Nearby-hot-object viewer contract {contract!r} missing from {path}")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
