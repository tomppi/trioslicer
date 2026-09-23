#!/usr/bin/env python3
"""Add draggable nearby-hot-object markers and gap-volume guides to filaSim."""
from __future__ import annotations

import pathlib

MARKER = "EnderSlicer nearby hot object marker drag viewer v1"
SOURCE_MARKER = ".enderslicer-nearby-hot-object-marker-drag-viewer-v1"


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
    scene = source_root / "web/src/viewer/SceneManager.ts"
    viewer = source_root / "web/src/viewer/Viewer.tsx"
    for path in (scene, viewer):
        if not path.is_file():
            raise RuntimeError(f"Nearby marker-drag target is missing: {path}")

    replace_once(
        scene,
        """  onNearbyHotObjectPick?: (
    point: [number, number, number],
    normal: [number, number, number]
  ) => void;
  /** Viewer picked a new deformation autoscale (display exaggeration base). */
""",
        """  onNearbyHotObjectPick?: (
    point: [number, number, number],
    normal: [number, number, number]
  ) => void;
  /** A heat-source sphere was dragged to a new world-space position. */
  onNearbyHotObjectDrag?: (
    sourceId: number,
    target: [number, number, number],
    normal: [number, number, number],
    gapMm: number
  ) => void;
  /** Viewer picked a new deformation autoscale (display exaggeration base). */
""",
        "SceneCallbacks heat-source drag",
    )
    replace_once(
        scene,
        """  private nearbyHotObjectPickMode = false;
  private nearbyHotObjectMarker = new THREE.Group();
  private nearbyHotObjectMarkerDisposables: (THREE.BufferGeometry | THREE.Material)[] = [];

""",
        """  private nearbyHotObjectPickMode = false;
  private nearbyHotObjectDragMode = 0;
  private nearbyHotObjectDragSourceId: number | null = null;
  private nearbyHotObjectDragPointerId: number | null = null;
  private nearbyHotObjectDragPlane = new THREE.Plane();
  private nearbyHotObjectDragRadiusMm = 0;
  private nearbyHotObjectMarker = new THREE.Group();
  private nearbyHotObjectMarkerDisposables: (THREE.BufferGeometry | THREE.Material)[] = [];

""",
        "SceneManager heat-source drag state",
    )

    old_marker = '''  setNearbyHotObjectMarker(detail: {
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
'''
    new_marker = '''  setNearbyHotObjectMarker(detail:
    | {
        target: [number, number, number];
        normal: [number, number, number];
        gapMm: number;
        diameterMm: number;
        sourceId?: number;
        label?: string;
      }
    | {
        markers: Array<{
          target: [number, number, number];
          normal: [number, number, number];
          gapMm: number;
          diameterMm: number;
          sourceId?: number;
          label?: string;
        }>;
      }
    | null
  ) {
    for (const child of [...this.nearbyHotObjectMarker.children]) {
      this.nearbyHotObjectMarker.remove(child);
    }
    for (const disposable of this.nearbyHotObjectMarkerDisposables) disposable.dispose();
    this.nearbyHotObjectMarkerDisposables = [];
    if (!detail) return;
    const markers = Array.isArray((detail as any).markers)
      ? (detail as any).markers
      : [detail as any];
    markers.forEach((entry: any, index: number) => {
      const target = new THREE.Vector3(...entry.target);
      const normal = new THREE.Vector3(...entry.normal);
      if (normal.lengthSq() < 1e-12) return;
      normal.normalize();
      const sourceId = Number(entry.sourceId ?? index + 1);
      const radius = Math.max(0.05, Number(entry.diameterMm) * 0.5);
      const gap = Math.max(0, Number(entry.gapMm));
      const sourceSurface = target.clone().addScaledVector(normal, gap);
      const center = target.clone().addScaledVector(normal, gap + radius);
      const primary = sourceId !== 2;
      const pointColor = primary ? 0xffd166 : 0xb5d8ff;
      const guideColor = primary ? 0xff8c5c : 0x64a8ff;
      const sourceColor = primary ? 0xff5a36 : 0x3978ef;
      const emissiveColor = primary ? 0xff2d16 : 0x173e9b;

      const pointRadius = Math.max(this.bboxDiag * 0.009, 0.35);
      const pointGeo = new THREE.SphereGeometry(pointRadius, 18, 12);
      const pointMat = new THREE.MeshBasicMaterial({ color: pointColor, depthTest: false });
      const point = new THREE.Mesh(pointGeo, pointMat);
      point.position.copy(target);
      point.renderOrder = 1002;

      const lineGeo = new THREE.BufferGeometry().setFromPoints([target, center]);
      const lineMat = new THREE.LineBasicMaterial({ color: guideColor, depthTest: false });
      const line = new THREE.Line(lineGeo, lineMat);
      line.renderOrder = 1001;

      const gapLength = Math.max(0.05, target.distanceTo(sourceSurface));
      const gapRadius = Math.max(pointRadius * 0.65, Math.min(radius * 0.18, this.bboxDiag * 0.025));
      const gapGeo = new THREE.CylinderGeometry(gapRadius, gapRadius, gapLength, 18, 1, true);
      const gapMat = new THREE.MeshBasicMaterial({
        color: guideColor,
        transparent: true,
        opacity: 0.2,
        depthWrite: false,
        side: THREE.DoubleSide,
      });
      const gapGuide = new THREE.Mesh(gapGeo, gapMat);
      gapGuide.position.copy(target).add(sourceSurface).multiplyScalar(0.5);
      gapGuide.quaternion.setFromUnitVectors(
        new THREE.Vector3(0, 1, 0),
        sourceSurface.clone().sub(target).normalize(),
      );
      gapGuide.renderOrder = 1000;

      const sourceGeo = new THREE.SphereGeometry(radius, 28, 18);
      const sourceMat = new THREE.MeshStandardMaterial({
        color: sourceColor,
        emissive: emissiveColor,
        emissiveIntensity: 0.5,
        transparent: true,
        opacity: 0.42,
        wireframe: true,
        depthWrite: false,
      });
      const sourceMesh = new THREE.Mesh(sourceGeo, sourceMat);
      sourceMesh.position.copy(center);
      sourceMesh.renderOrder = 1003;
      sourceMesh.userData = {
        nearbyMarkerType: "source",
        nearbySourceId: sourceId,
        nearbyTarget: entry.target,
        nearbyNormal: entry.normal,
        nearbyGapMm: gap,
        nearbyDiameterMm: Number(entry.diameterMm),
      };

      const group = new THREE.Group();
      group.add(point, line, gapGuide, sourceMesh);
      this.nearbyHotObjectMarker.add(group);
      this.nearbyHotObjectMarkerDisposables.push(
        pointGeo, pointMat, lineGeo, lineMat, gapGeo, gapMat, sourceGeo, sourceMat,
      );
    });
  }

  setNearbyHotObjectDragMode(sourceId: number) {
    this.nearbyHotObjectDragMode = Number.isFinite(sourceId)
      ? Math.max(0, Math.round(sourceId))
      : 0;
    if (this.nearbyHotObjectDragMode <= 0) this.finishNearbyHotObjectDrag();
    if (this.canvas) this.canvas.style.cursor = this.nearbyHotObjectDragMode > 0 ? "grab" : "";
  }

  private nearbyHotObjectMarkerHit(ev: PointerEvent): THREE.Intersection | null {
    const rect = this.renderer.domElement.getBoundingClientRect();
    this.pointer.x = ((ev.clientX - rect.left) / rect.width) * 2 - 1;
    this.pointer.y = -((ev.clientY - rect.top) / rect.height) * 2 + 1;
    this.raycaster.setFromCamera(this.pointer, this.camera);
    const hits = this.raycaster.intersectObject(this.nearbyHotObjectMarker, true);
    return hits.find((hit) => hit.object.userData?.nearbyMarkerType === "source") ?? null;
  }

  private finishNearbyHotObjectDrag() {
    this.nearbyHotObjectDragSourceId = null;
    this.nearbyHotObjectDragPointerId = null;
    if (this.controls) this.controls.enabled = true;
    if (this.canvas) this.canvas.style.cursor = this.nearbyHotObjectDragMode > 0 ? "grab" : "";
  }
'''
    replace_once(scene, old_marker, new_marker, "multi-source marker renderer")

    old_pointer = '''  private onNearbyHotObjectPointerDown = (ev: PointerEvent) => {
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

'''
    new_pointer = '''  private onNearbyHotObjectPointerDown = (ev: PointerEvent) => {
    if (!this.mesh || ev.button !== 0) return;
    if (this.nearbyHotObjectDragMode > 0) {
      const markerHit = this.nearbyHotObjectMarkerHit(ev);
      if (markerHit) {
        const data = markerHit.object.userData;
        const sourceId = Number(data.nearbySourceId);
        const target = data.nearbyTarget;
        const diameterMm = Number(data.nearbyDiameterMm);
        if (sourceId === this.nearbyHotObjectDragMode
            && Array.isArray(target) && target.length === 3
            && Number.isFinite(diameterMm) && diameterMm > 0) {
          this.nearbyHotObjectDragSourceId = sourceId;
          this.nearbyHotObjectDragPointerId = ev.pointerId;
          this.nearbyHotObjectDragRadiusMm = diameterMm * 0.5;
          const cameraDirection = new THREE.Vector3();
          this.camera.getWorldDirection(cameraDirection).normalize();
          this.nearbyHotObjectDragPlane.setFromNormalAndCoplanarPoint(
            cameraDirection,
            markerHit.point,
          );
          this.controls.enabled = false;
          this.canvas.style.cursor = "grabbing";
          ev.preventDefault();
          ev.stopImmediatePropagation();
          return;
        }
      }
    }
    if (!this.nearbyHotObjectPickMode) return;
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

  private onNearbyHotObjectDragMove = (ev: PointerEvent) => {
    if (this.nearbyHotObjectDragSourceId === null
        || this.nearbyHotObjectDragPointerId !== ev.pointerId) return;
    const rect = this.renderer.domElement.getBoundingClientRect();
    this.pointer.x = ((ev.clientX - rect.left) / rect.width) * 2 - 1;
    this.pointer.y = -((ev.clientY - rect.top) / rect.height) * 2 + 1;
    this.raycaster.setFromCamera(this.pointer, this.camera);
    const draggedCenter = new THREE.Vector3();
    if (!this.raycaster.ray.intersectPlane(this.nearbyHotObjectDragPlane, draggedCenter)) return;

    let sourceData: any = null;
    this.nearbyHotObjectMarker.traverse((object) => {
      if (sourceData || object.userData?.nearbyMarkerType !== "source") return;
      if (Number(object.userData.nearbySourceId) === this.nearbyHotObjectDragSourceId) {
        sourceData = object.userData;
      }
    });
    if (!sourceData || !Array.isArray(sourceData.nearbyTarget)) return;
    const target = new THREE.Vector3(...sourceData.nearbyTarget);
    const offset = draggedCenter.clone().sub(target);
    const fallbackNormal = new THREE.Vector3(...sourceData.nearbyNormal).normalize();
    const distance = Math.max(offset.length(), this.nearbyHotObjectDragRadiusMm + 0.1);
    const normal = offset.lengthSq() > 1e-12 ? offset.normalize() : fallbackNormal;
    const gapMm = Math.max(0, distance - this.nearbyHotObjectDragRadiusMm);
    this.callbacks.onNearbyHotObjectDrag?.(
      this.nearbyHotObjectDragSourceId,
      [target.x, target.y, target.z],
      [normal.x, normal.y, normal.z],
      gapMm,
    );
    ev.preventDefault();
    ev.stopImmediatePropagation();
  };

  private onNearbyHotObjectDragEnd = (ev: PointerEvent) => {
    if (this.nearbyHotObjectDragSourceId === null
        || this.nearbyHotObjectDragPointerId !== ev.pointerId) return;
    this.finishNearbyHotObjectDrag();
    ev.preventDefault();
    ev.stopImmediatePropagation();
  };

'''
    replace_once(scene, old_pointer, new_pointer, "heat-source drag pointer handlers")

    replace_once(
        scene,
        '''    canvas.addEventListener("pointerdown", this.onNearbyHotObjectPointerDown, true);
    canvas.addEventListener("pointerup", this.onPointerUp);
''',
        '''    canvas.addEventListener("pointerdown", this.onNearbyHotObjectPointerDown, true);
    document.addEventListener("pointermove", this.onNearbyHotObjectDragMove, true);
    document.addEventListener("pointerup", this.onNearbyHotObjectDragEnd, true);
    document.addEventListener("pointercancel", this.onNearbyHotObjectDragEnd, true);
    canvas.addEventListener("pointerup", this.onPointerUp);
''',
        "drag listener registration",
    )
    replace_once(
        scene,
        '''    this.canvas?.removeEventListener(
      "pointerdown", this.onNearbyHotObjectPointerDown, true
    );
    this.canvas?.removeEventListener("wheel", this.onWheel);
''',
        '''    this.canvas?.removeEventListener(
      "pointerdown", this.onNearbyHotObjectPointerDown, true
    );
    document.removeEventListener("pointermove", this.onNearbyHotObjectDragMove, true);
    document.removeEventListener("pointerup", this.onNearbyHotObjectDragEnd, true);
    document.removeEventListener("pointercancel", this.onNearbyHotObjectDragEnd, true);
    this.canvas?.removeEventListener("wheel", this.onWheel);
''',
        "drag listener disposal",
    )

    replace_once(
        viewer,
        '''const NEARBY_MARKER_EVENT = "enderslicer-nearby-hot-object-marker";
const NEARBY_CLEAR_EVENT = "enderslicer-nearby-hot-object-clear";

''',
        '''const NEARBY_MARKER_EVENT = "enderslicer-nearby-hot-object-marker";
const NEARBY_DRAG_MODE_EVENT = "enderslicer-nearby-hot-object-drag-mode";
const NEARBY_DRAG_EVENT = "enderslicer-nearby-hot-object-drag";
const NEARBY_CLEAR_EVENT = "enderslicer-nearby-hot-object-clear";

''',
        "Viewer drag event constants",
    )
    replace_once(
        viewer,
        '''      onNearbyHotObjectPick: (point, normal) => {
        window.dispatchEvent(new CustomEvent(NEARBY_PICK_EVENT, {
          detail: { point, normal },
        }));
      },
      onAutoScale: (autoScale) => {
''',
        '''      onNearbyHotObjectPick: (point, normal) => {
        window.dispatchEvent(new CustomEvent(NEARBY_PICK_EVENT, {
          detail: { point, normal },
        }));
      },
      onNearbyHotObjectDrag: (sourceId, target, normal, gapMm) => {
        window.dispatchEvent(new CustomEvent(NEARBY_DRAG_EVENT, {
          detail: { sourceId, target, normal, gapMm },
        }));
      },
      onAutoScale: (autoScale) => {
''',
        "Viewer heat-source drag callback",
    )
    replace_once(
        viewer,
        '''    const onNearbyMarker = (event: Event) => {
      scene.setNearbyHotObjectMarker((event as CustomEvent).detail ?? null);
    };
    const onNearbyClear = () => {
      scene.setNearbyHotObjectPickMode(false);
      scene.setNearbyHotObjectMarker(null);
    };
''',
        '''    const onNearbyMarker = (event: Event) => {
      scene.setNearbyHotObjectMarker((event as CustomEvent).detail ?? null);
    };
    const onNearbyDragMode = (event: Event) => {
      scene.setNearbyHotObjectDragMode(Number((event as CustomEvent).detail ?? 0));
    };
    const onNearbyClear = () => {
      scene.setNearbyHotObjectPickMode(false);
      scene.setNearbyHotObjectDragMode(0);
      scene.setNearbyHotObjectMarker(null);
    };
''',
        "Viewer drag event handlers",
    )
    replace_once(
        viewer,
        '''    window.addEventListener(NEARBY_PICK_MODE_EVENT, onNearbyPickMode);
    window.addEventListener(NEARBY_MARKER_EVENT, onNearbyMarker);
    window.addEventListener(NEARBY_CLEAR_EVENT, onNearbyClear);

''',
        '''    window.addEventListener(NEARBY_PICK_MODE_EVENT, onNearbyPickMode);
    window.addEventListener(NEARBY_MARKER_EVENT, onNearbyMarker);
    window.addEventListener(NEARBY_DRAG_MODE_EVENT, onNearbyDragMode);
    window.addEventListener(NEARBY_CLEAR_EVENT, onNearbyClear);

''',
        "Viewer drag listener registration",
    )
    replace_once(
        viewer,
        '''      window.removeEventListener(NEARBY_PICK_MODE_EVENT, onNearbyPickMode);
      window.removeEventListener(NEARBY_MARKER_EVENT, onNearbyMarker);
      window.removeEventListener(NEARBY_CLEAR_EVENT, onNearbyClear);
''',
        '''      window.removeEventListener(NEARBY_PICK_MODE_EVENT, onNearbyPickMode);
      window.removeEventListener(NEARBY_MARKER_EVENT, onNearbyMarker);
      window.removeEventListener(NEARBY_DRAG_MODE_EVENT, onNearbyDragMode);
      window.removeEventListener(NEARBY_CLEAR_EVENT, onNearbyClear);
''',
        "Viewer drag listener cleanup",
    )

    for path, contract in (
        (scene, "setNearbyHotObjectDragMode"),
        (scene, "onNearbyHotObjectDrag"),
        (scene, "gapGuide"),
        (scene, "nearbyHotObjectDragPlane"),
        (viewer, "NEARBY_DRAG_MODE_EVENT"),
        (viewer, "NEARBY_DRAG_EVENT"),
    ):
        if contract not in path.read_text(encoding="utf-8"):
            raise RuntimeError(f"Nearby marker-drag contract {contract!r} missing from {path}")
    (source_root / SOURCE_MARKER).write_text(MARKER + "\n", encoding="utf-8")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
