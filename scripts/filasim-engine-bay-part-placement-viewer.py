#!/usr/bin/env python3
"""Add constrained world-space placement of the printed object to filaSim."""
from __future__ import annotations

import pathlib

MARKER = "EnderSlicer engine-bay plastic object placement viewer v1"
SOURCE_MARKER = ".enderslicer-engine-bay-part-placement-viewer-v1"


def replace_once(path: pathlib.Path, old: str, new: str, label: str) -> None:
    text = path.read_text(encoding="utf-8")
    if new in text:
        return
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one {label} in {path}, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


PLACEMENT_METHODS = r'''  private engineBayPartFootprintHalfWidth(normalizedY: number): number {
    const profile: Array<[number, number]> = [
      [-0.34, -0.50], [0.34, -0.50], [0.46, -0.38], [0.48, -0.12],
      [0.39, 0.02], [0.36, 0.22], [0.39, 0.42], [0.31, 0.50],
      [-0.31, 0.50], [-0.39, 0.42], [-0.36, 0.22], [-0.39, 0.02],
      [-0.48, -0.12], [-0.46, -0.38],
    ];
    const y = Math.min(0.5, Math.max(-0.5, normalizedY));
    const intersections: number[] = [];
    for (let index = 0; index < profile.length; index += 1) {
      const [x1, y1] = profile[index];
      const [x2, y2] = profile[(index + 1) % profile.length];
      if (Math.abs(y2 - y1) <= 1e-12) {
        if (Math.abs(y - y1) <= 1e-9) intersections.push(x1, x2);
        continue;
      }
      const minimum = Math.min(y1, y2);
      const maximum = Math.max(y1, y2);
      if (y < minimum - 1e-9 || y > maximum + 1e-9) continue;
      const fraction = (y - y1) / (y2 - y1);
      if (fraction >= -1e-9 && fraction <= 1 + 1e-9) {
        intersections.push(x1 + (x2 - x1) * fraction);
      }
    }
    return intersections.length
      ? Math.max(...intersections.map((x) => Math.abs(x)))
      : 0.31;
  }

  private clampEngineBayPartPlacement(requested: THREE.Vector3): {
    position: THREE.Vector3;
    clamped: boolean;
    fits: boolean;
  } {
    if (!this.partBbox || !this.engineBayPartEnvelope) {
      return { position: requested.clone(), clamped: false, fits: true };
    }
    const envelope = this.engineBayPartEnvelope;
    const width = Math.max(0.1, Number(envelope.widthMm));
    const depth = Math.max(0.1, Number(envelope.depthMm));
    const height = Math.max(0.1, Number(envelope.heightMm));
    const offset = new THREE.Vector3(
      Number(envelope.offsetXmm || 0),
      Number(envelope.offsetYmm || 0),
      Number(envelope.offsetZmm || 0),
    );
    const [lx, ly, lz, hx, hy, hz] = this.partBbox;
    const half = new THREE.Vector3((hx - lx) * 0.5, (hy - ly) * 0.5, (hz - lz) * 0.5);
    const local = requested.clone().sub(offset);
    let fits = true;

    const clampAxis = (number: number, halfSpan: number, label: string) => {
      const allowance = halfSpan;
      if (allowance < 0) {
        fits = false;
        return 0;
      }
      if (!Number.isFinite(number)) throw new Error(`${label} placement is not finite`);
      return Math.min(allowance, Math.max(-allowance, number));
    };

    if (envelope.shape !== "engine-bay") {
      local.x = clampAxis(local.x, width * 0.5 - half.x, "X");
      local.y = clampAxis(local.y, depth * 0.5 - half.y, "Y");
      local.z = clampAxis(local.z, height * 0.5 - half.z, "Z");
    } else {
      const yAllowance = depth * 0.5 - half.y;
      local.y = clampAxis(local.y, yAllowance, "Y");
      const allowedHalfWidth = (centerY: number) => {
        const samples = [centerY - half.y, centerY, centerY + half.y];
        return Math.min(...samples.map((sampleY) =>
          this.engineBayPartFootprintHalfWidth(sampleY / depth) * width - half.x));
      };
      let xAllowance = allowedHalfWidth(local.y);
      if (xAllowance < 0) {
        local.y = 0;
        xAllowance = allowedHalfWidth(local.y);
      }
      local.x = clampAxis(local.x, xAllowance, "X");

      const sampleX = [local.x - half.x, local.x, local.x + half.x];
      const sampleY = [local.y - half.y, local.y, local.y + half.y];
      let highestFloor = Number.NEGATIVE_INFINITY;
      let lowestHood = Number.POSITIVE_INFINITY;
      for (const x of sampleX) {
        for (const y of sampleY) {
          const normalizedX = x / Math.max(1e-9, width * 0.5);
          const normalizedY = y / Math.max(1e-9, depth * 0.5);
          const hood = height * 0.5
            - height * (0.055 * normalizedX * normalizedX
              + 0.025 * (normalizedY + 0.15) * (normalizedY + 0.15));
          const floor = -height * 0.5 + height * 0.025 * (y / depth + 0.5);
          highestFloor = Math.max(highestFloor, floor);
          lowestHood = Math.min(lowestHood, hood);
        }
      }
      const lowerCenter = highestFloor + half.z;
      const upperCenter = lowestHood - half.z;
      if (lowerCenter > upperCenter) {
        fits = false;
        local.z = (lowerCenter + upperCenter) * 0.5;
      } else {
        local.z = Math.min(upperCenter, Math.max(lowerCenter, local.z));
      }
    }

    const position = local.add(offset);
    return {
      position,
      clamped: position.distanceToSquared(requested) > 1e-10,
      fits,
    };
  }

  private applyEngineBayPartPlacementToMesh() {
    if (!this.mesh) return;
    if (this.engineBayPartTrackedMesh !== this.mesh || !this.engineBayPartBasePosition) {
      this.engineBayPartTrackedMesh = this.mesh;
      this.engineBayPartBasePosition = this.mesh.position.clone();
    }
    const basePosition = this.engineBayPartBasePosition ?? this.mesh.position.clone();
    this.engineBayPartBasePosition = basePosition;
    this.mesh.position.copy(basePosition).add(this.engineBayPartPlacement);
    this.mesh.updateMatrixWorld(true);
  }

  private notifyEngineBayPartPlacement(final: boolean, clamped: boolean, fits: boolean) {
    window.dispatchEvent(new CustomEvent("enderslicer-engine-bay-part-placement-changed", {
      detail: {
        positionMm: [
          this.engineBayPartPlacement.x,
          this.engineBayPartPlacement.y,
          this.engineBayPartPlacement.z,
        ],
        final,
        clamped,
        fits,
      },
    }));
  }

  setEngineBayPartPlacement(detail: {
    positionMm?: [number, number, number];
    dragMode?: "none" | "xy" | "z";
    envelope?: {
      widthMm: number;
      depthMm: number;
      heightMm: number;
      offsetXmm?: number;
      offsetYmm?: number;
      offsetZmm?: number;
      shape?: "box" | "engine-bay";
    } | null;
    notify?: boolean;
  } | null) {
    if (!detail) {
      this.engineBayPartPlacement.set(0, 0, 0);
      this.engineBayPartEnvelope = null;
      this.engineBayPartDragMode = "none";
      this.applyEngineBayPartPlacementToMesh();
      return;
    }
    this.engineBayPartEnvelope = detail.envelope ?? null;
    this.engineBayPartDragMode = detail.dragMode ?? "none";
    if (this.engineBayPartDragMode !== "none") this.setNearbyHotObjectDragMode(0);
    const requestedCoordinates = Array.isArray(detail.positionMm) && detail.positionMm.length === 3
      ? detail.positionMm.map(Number)
      : null;
    const requested = requestedCoordinates
      ? new THREE.Vector3(
          requestedCoordinates[0], requestedCoordinates[1], requestedCoordinates[2],
        )
      : this.engineBayPartPlacement.clone();
    const constrained = this.clampEngineBayPartPlacement(requested);
    this.engineBayPartPlacement.copy(constrained.position);
    this.applyEngineBayPartPlacementToMesh();
    if (this.canvas) {
      this.canvas.style.cursor = this.engineBayPartDragMode === "xy"
        ? "move"
        : this.engineBayPartDragMode === "z" ? "ns-resize" : "";
    }
    if (detail.notify) {
      this.notifyEngineBayPartPlacement(true, constrained.clamped, constrained.fits);
    }
  }

  private finishEngineBayPartDrag(final = false) {
    if (this.engineBayPartDragPointerId === null) return;
    this.engineBayPartDragPointerId = null;
    if (this.controls) this.controls.enabled = true;
    const constrained = this.clampEngineBayPartPlacement(this.engineBayPartPlacement);
    this.engineBayPartPlacement.copy(constrained.position);
    this.applyEngineBayPartPlacementToMesh();
    if (final) this.notifyEngineBayPartPlacement(true, constrained.clamped, constrained.fits);
    this.engineBayPartDragMode = "none";
    if (this.canvas) this.canvas.style.cursor = "";
  }

  private onEngineBayPartPointerDown = (ev: PointerEvent) => {
    if (!this.mesh || this.engineBayPartDragMode === "none" || ev.button !== 0) return;
    const hit = this.rayTri(ev);
    if (!hit) return;
    this.engineBayPartDragPointerId = ev.pointerId;
    this.engineBayPartDragStartPlacement.copy(this.engineBayPartPlacement);
    this.engineBayPartDragStartPoint.copy(hit.point);
    this.engineBayPartDragStartPointer = { x: ev.clientX, y: ev.clientY };
    this.engineBayPartDragPlane.setFromNormalAndCoplanarPoint(
      new THREE.Vector3(0, 0, 1),
      hit.point,
    );
    this.controls.enabled = false;
    this.canvas.style.cursor = this.engineBayPartDragMode === "z" ? "ns-resize" : "grabbing";
    ev.preventDefault();
    ev.stopImmediatePropagation();
  };

  private onEngineBayPartDragMove = (ev: PointerEvent) => {
    if (this.engineBayPartDragPointerId !== ev.pointerId) return;
    const requested = this.engineBayPartDragStartPlacement.clone();
    if (this.engineBayPartDragMode === "z") {
      const rect = this.renderer.domElement.getBoundingClientRect();
      const worldPerPixel = (this.camera.top - this.camera.bottom)
        / Math.max(1e-9, this.camera.zoom * rect.height);
      requested.z -= (ev.clientY - this.engineBayPartDragStartPointer.y) * worldPerPixel;
    } else {
      const rect = this.renderer.domElement.getBoundingClientRect();
      this.pointer.x = ((ev.clientX - rect.left) / rect.width) * 2 - 1;
      this.pointer.y = -((ev.clientY - rect.top) / rect.height) * 2 + 1;
      this.raycaster.setFromCamera(this.pointer, this.camera);
      const current = new THREE.Vector3();
      if (!this.raycaster.ray.intersectPlane(this.engineBayPartDragPlane, current)) return;
      requested.x += current.x - this.engineBayPartDragStartPoint.x;
      requested.y += current.y - this.engineBayPartDragStartPoint.y;
    }
    const constrained = this.clampEngineBayPartPlacement(requested);
    this.engineBayPartPlacement.copy(constrained.position);
    this.applyEngineBayPartPlacementToMesh();
    this.notifyEngineBayPartPlacement(false, constrained.clamped, constrained.fits);
    ev.preventDefault();
    ev.stopImmediatePropagation();
  };

  private onEngineBayPartDragEnd = (ev: PointerEvent) => {
    if (this.engineBayPartDragPointerId !== ev.pointerId) return;
    this.finishEngineBayPartDrag(true);
    ev.preventDefault();
    ev.stopImmediatePropagation();
  };

'''


def apply(source_root: pathlib.Path) -> None:
    source_root = source_root.resolve()
    scene = source_root / "web/src/viewer/SceneManager.ts"
    viewer = source_root / "web/src/viewer/Viewer.tsx"
    for path in (scene, viewer):
        if not path.is_file():
            raise RuntimeError(f"Part-placement viewer target is missing: {path}")

    replace_once(
        scene,
        """  private nearbyHotObjectEnclosure = new THREE.Group();
  private nearbyHotObjectEnclosureDisposables: (THREE.BufferGeometry | THREE.Material)[] = [];

""",
        """  private nearbyHotObjectEnclosure = new THREE.Group();
  private nearbyHotObjectEnclosureDisposables: (THREE.BufferGeometry | THREE.Material)[] = [];
  private engineBayPartPlacement = new THREE.Vector3();
  private engineBayPartEnvelope: any = null;
  private engineBayPartDragMode: \"none\" | \"xy\" | \"z\" = \"none\";
  private engineBayPartDragPointerId: number | null = null;
  private engineBayPartDragPlane = new THREE.Plane();
  private engineBayPartDragStartPlacement = new THREE.Vector3();
  private engineBayPartDragStartPoint = new THREE.Vector3();
  private engineBayPartDragStartPointer = { x: 0, y: 0 };
  private engineBayPartTrackedMesh: THREE.Mesh | null = null;
  private engineBayPartBasePosition: THREE.Vector3 | null = null;

""",
        "part-placement viewer state",
    )
    replace_once(
        scene,
        "  clearNearbyHotObjectState() {\n",
        PLACEMENT_METHODS + "  clearNearbyHotObjectState() {\n",
        "part-placement methods",
    )
    replace_once(
        scene,
        """    this.setNearbyHotObjectMarker(null);
    this.setNearbyHotObjectEnclosureBox(null);
  }
""",
        """    this.setNearbyHotObjectMarker(null);
    this.setNearbyHotObjectEnclosureBox(null);
    this.setEngineBayPartPlacement(null);
  }
""",
        "part-placement clear state",
    )
    replace_once(
        scene,
        """    canvas.addEventListener("pointerdown", this.onNearbyHotObjectPointerDown, true);
    document.addEventListener("pointermove", this.onNearbyHotObjectDragMove, true);
    document.addEventListener("pointerup", this.onNearbyHotObjectDragEnd, true);
    document.addEventListener("pointercancel", this.onNearbyHotObjectDragEnd, true);
""",
        """    canvas.addEventListener("pointerdown", this.onEngineBayPartPointerDown, true);
    document.addEventListener("pointermove", this.onEngineBayPartDragMove, true);
    document.addEventListener("pointerup", this.onEngineBayPartDragEnd, true);
    document.addEventListener("pointercancel", this.onEngineBayPartDragEnd, true);
    canvas.addEventListener("pointerdown", this.onNearbyHotObjectPointerDown, true);
    document.addEventListener("pointermove", this.onNearbyHotObjectDragMove, true);
    document.addEventListener("pointerup", this.onNearbyHotObjectDragEnd, true);
    document.addEventListener("pointercancel", this.onNearbyHotObjectDragEnd, true);
""",
        "part-placement listener registration",
    )
    replace_once(
        scene,
        """    this.canvas?.removeEventListener(
      "pointerdown", this.onNearbyHotObjectPointerDown, true
    );
    document.removeEventListener("pointermove", this.onNearbyHotObjectDragMove, true);
""",
        """    this.canvas?.removeEventListener(
      "pointerdown", this.onEngineBayPartPointerDown, true
    );
    document.removeEventListener("pointermove", this.onEngineBayPartDragMove, true);
    document.removeEventListener("pointerup", this.onEngineBayPartDragEnd, true);
    document.removeEventListener("pointercancel", this.onEngineBayPartDragEnd, true);
    this.canvas?.removeEventListener(
      "pointerdown", this.onNearbyHotObjectPointerDown, true
    );
    document.removeEventListener("pointermove", this.onNearbyHotObjectDragMove, true);
""",
        "part-placement listener disposal",
    )

    replace_once(
        viewer,
        """const NEARBY_ENCLOSURE_BOX_EVENT = "enderslicer-nearby-hot-object-enclosure-box";
const NEARBY_CLEAR_EVENT = "enderslicer-nearby-hot-object-clear";
""",
        """const NEARBY_ENCLOSURE_BOX_EVENT = "enderslicer-nearby-hot-object-enclosure-box";
const ENGINE_BAY_PART_PLACEMENT_EVENT = "enderslicer-engine-bay-part-placement";
const NEARBY_CLEAR_EVENT = "enderslicer-nearby-hot-object-clear";
""",
        "part-placement Viewer event constant",
    )
    replace_once(
        viewer,
        """    const onNearbyEnclosureBox = (event: Event) => {
      scene.setNearbyHotObjectEnclosureBox((event as CustomEvent).detail ?? null);
    };
    const onNearbyClear = () => {
""",
        """    const onNearbyEnclosureBox = (event: Event) => {
      scene.setNearbyHotObjectEnclosureBox((event as CustomEvent).detail ?? null);
    };
    const onEngineBayPartPlacement = (event: Event) => {
      scene.setEngineBayPartPlacement((event as CustomEvent).detail ?? null);
    };
    const onNearbyClear = () => {
""",
        "part-placement Viewer event handler",
    )
    replace_once(
        viewer,
        """    window.addEventListener(NEARBY_ENCLOSURE_BOX_EVENT, onNearbyEnclosureBox);
    window.addEventListener(NEARBY_CLEAR_EVENT, onNearbyClear);
""",
        """    window.addEventListener(NEARBY_ENCLOSURE_BOX_EVENT, onNearbyEnclosureBox);
    window.addEventListener(ENGINE_BAY_PART_PLACEMENT_EVENT, onEngineBayPartPlacement);
    window.addEventListener(NEARBY_CLEAR_EVENT, onNearbyClear);
""",
        "part-placement Viewer listener registration",
    )
    replace_once(
        viewer,
        """      window.removeEventListener(NEARBY_ENCLOSURE_BOX_EVENT, onNearbyEnclosureBox);
      window.removeEventListener(NEARBY_CLEAR_EVENT, onNearbyClear);
""",
        """      window.removeEventListener(NEARBY_ENCLOSURE_BOX_EVENT, onNearbyEnclosureBox);
      window.removeEventListener(ENGINE_BAY_PART_PLACEMENT_EVENT, onEngineBayPartPlacement);
      window.removeEventListener(NEARBY_CLEAR_EVENT, onNearbyClear);
""",
        "part-placement Viewer listener cleanup",
    )

    for path, contract in (
        (scene, "setEngineBayPartPlacement"),
        (scene, "clampEngineBayPartPlacement"),
        (scene, "engineBayPartFootprintHalfWidth"),
        (scene, "onEngineBayPartDragMove"),
        (scene, "lowestHood"),
        (viewer, "ENGINE_BAY_PART_PLACEMENT_EVENT"),
    ):
        if contract not in path.read_text(encoding="utf-8"):
            raise RuntimeError(f"Part-placement contract {contract!r} is missing from {path}")
    (source_root / SOURCE_MARKER).write_text(MARKER + "\n", encoding="utf-8")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
