#!/usr/bin/env python3
"""Replace the rectangular enclosure viewer with a closed engine-bay envelope.

The visible side shell follows a generic passenger-car engine bay footprint.
Hood and floor surfaces remain part of the closed calculation envelope but can
be hidden in the viewer so heat-source placement stays unobstructed.
"""
from __future__ import annotations

import pathlib

MARKER = "EnderSlicer shaped engine-bay envelope viewer v1"
SOURCE_MARKER = ".enderslicer-engine-bay-shaped-envelope-viewer-v1"


def replace_between(
    path: pathlib.Path,
    start: str,
    end: str,
    replacement: str,
    label: str,
) -> None:
    text = path.read_text(encoding="utf-8")
    if replacement in text:
        return
    start_index = text.find(start)
    end_index = text.find(end, start_index + len(start)) if start_index >= 0 else -1
    if start_index < 0 or end_index < 0:
        raise RuntimeError(f"Unable to locate {label} in {path}")
    path.write_text(text[:start_index] + replacement + text[end_index:], encoding="utf-8")


SHAPED_METHOD = r'''  setNearbyHotObjectEnclosureBox(detail: {
    visible?: boolean;
    widthMm: number;
    depthMm: number;
    heightMm: number;
    offsetXmm?: number;
    offsetYmm?: number;
    offsetZmm?: number;
    shape?: "box" | "engine-bay";
    hoodVisible?: boolean;
    floorVisible?: boolean;
    wallOpacity?: number;
  } | null) {
    for (const child of [...this.nearbyHotObjectEnclosure.children]) {
      this.nearbyHotObjectEnclosure.remove(child);
    }
    for (const disposable of this.nearbyHotObjectEnclosureDisposables) disposable.dispose();
    this.nearbyHotObjectEnclosureDisposables = [];
    if (!detail || detail.visible === false || !this.partBbox) return;
    const width = Math.max(0.1, Number(detail.widthMm));
    const depth = Math.max(0.1, Number(detail.depthMm));
    const height = Math.max(0.1, Number(detail.heightMm));
    if (![width, depth, height].every(Number.isFinite)) return;
    const [lx, ly, lz, hx, hy, hz] = this.partBbox;
    const center = new THREE.Vector3(
      (lx + hx) * 0.5 + Number(detail.offsetXmm || 0),
      (ly + hy) * 0.5 + Number(detail.offsetYmm || 0),
      (lz + hz) * 0.5 + Number(detail.offsetZmm || 0),
    );

    const wallOpacity = Math.min(0.3, Math.max(0.015, Number(detail.wallOpacity ?? 0.075)));
    const wallMat = new THREE.MeshBasicMaterial({
      color: 0x74a7d8,
      transparent: true,
      opacity: wallOpacity,
      depthWrite: false,
      side: THREE.DoubleSide,
    });
    const edgeMat = new THREE.LineBasicMaterial({
      color: 0x9ac7ef,
      transparent: true,
      opacity: 0.82,
      depthTest: false,
    });

    if (detail.shape !== "engine-bay") {
      const boxGeo = new THREE.BoxGeometry(width, depth, height);
      const walls = new THREE.Mesh(boxGeo, wallMat);
      walls.position.copy(center);
      walls.renderOrder = 50;
      const edgeGeo = new THREE.EdgesGeometry(boxGeo);
      const edges = new THREE.LineSegments(edgeGeo, edgeMat);
      edges.position.copy(center);
      edges.renderOrder = 51;
      this.nearbyHotObjectEnclosure.add(walls, edges);
      this.nearbyHotObjectEnclosureDisposables.push(boxGeo, wallMat, edgeGeo, edgeMat);
    } else {
      // Clockwise normalized footprint. The side indentations approximate the
      // wheel-house/strut-tower intrusions while the narrower rear represents
      // the firewall/cowl end of a generic transverse or longitudinal bay.
      const profile: Array<[number, number]> = [
        [-0.34, -0.50], [0.34, -0.50], [0.46, -0.38], [0.48, -0.12],
        [0.39, 0.02], [0.36, 0.22], [0.39, 0.42], [0.31, 0.50],
        [-0.31, 0.50], [-0.39, 0.42], [-0.36, 0.22], [-0.39, 0.02],
        [-0.48, -0.12], [-0.46, -0.38],
      ];
      const lowerZ = center.z - height * 0.5;
      const hoodZ = (x: number, y: number) => {
        const nx = x / Math.max(1e-9, width * 0.5);
        const ny = y / Math.max(1e-9, depth * 0.5);
        return center.z + height * 0.5
          - height * (0.055 * nx * nx + 0.025 * (ny + 0.15) * (ny + 0.15));
      };
      const floorZ = (_x: number, y: number) =>
        lowerZ + height * 0.025 * ((y / Math.max(1e-9, depth)) + 0.5);
      const sidePositions: number[] = [];
      const hoodPositions: number[] = [];
      const floorPositions: number[] = [];
      const upperRing: THREE.Vector3[] = [];
      const lowerRing: THREE.Vector3[] = [];
      const topCenter = new THREE.Vector3(center.x, center.y, center.z + height * 0.48);
      const floorCenter = new THREE.Vector3(center.x, center.y, lowerZ + height * 0.0125);

      for (const [px, py] of profile) {
        const x = center.x + px * width;
        const y = center.y + py * depth;
        upperRing.push(new THREE.Vector3(x, y, hoodZ(x - center.x, y - center.y)));
        lowerRing.push(new THREE.Vector3(x, y, floorZ(x - center.x, y - center.y)));
      }
      for (let i = 0; i < profile.length; i += 1) {
        const j = (i + 1) % profile.length;
        const a = lowerRing[i];
        const b = lowerRing[j];
        const c = upperRing[j];
        const d = upperRing[i];
        sidePositions.push(
          a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z,
          a.x, a.y, a.z, c.x, c.y, c.z, d.x, d.y, d.z,
        );
        const ta = upperRing[i];
        const tb = upperRing[j];
        hoodPositions.push(
          topCenter.x, topCenter.y, topCenter.z,
          ta.x, ta.y, ta.z,
          tb.x, tb.y, tb.z,
        );
        const fa = lowerRing[i];
        const fb = lowerRing[j];
        floorPositions.push(
          floorCenter.x, floorCenter.y, floorCenter.z,
          fb.x, fb.y, fb.z,
          fa.x, fa.y, fa.z,
        );
      }

      const makeGeometry = (positions: number[]) => {
        const geometry = new THREE.BufferGeometry();
        geometry.setAttribute("position", new THREE.Float32BufferAttribute(positions, 3));
        geometry.computeVertexNormals();
        return geometry;
      };
      const sideGeo = makeGeometry(sidePositions);
      const sideMesh = new THREE.Mesh(sideGeo, wallMat);
      sideMesh.renderOrder = 50;
      const sideEdgeGeo = new THREE.EdgesGeometry(sideGeo, 18);
      const sideEdges = new THREE.LineSegments(sideEdgeGeo, edgeMat);
      sideEdges.renderOrder = 51;
      this.nearbyHotObjectEnclosure.add(sideMesh, sideEdges);
      this.nearbyHotObjectEnclosureDisposables.push(sideGeo, sideEdgeGeo);

      const cap = (
        positions: number[],
        visible: boolean,
        opacity: number,
        edgeOpacity: number,
      ) => {
        const geometry = makeGeometry(positions);
        const material = new THREE.MeshBasicMaterial({
          color: 0x74a7d8,
          transparent: true,
          opacity: visible ? opacity : 0.0,
          depthWrite: false,
          side: THREE.DoubleSide,
        });
        const mesh = new THREE.Mesh(geometry, material);
        mesh.renderOrder = 48;
        const capEdgeGeo = new THREE.EdgesGeometry(geometry, 20);
        const capEdgeMat = new THREE.LineBasicMaterial({
          color: 0x9ac7ef,
          transparent: true,
          opacity: visible ? edgeOpacity : 0.14,
          depthTest: false,
        });
        const edges = new THREE.LineSegments(capEdgeGeo, capEdgeMat);
        edges.renderOrder = 49;
        this.nearbyHotObjectEnclosure.add(mesh, edges);
        this.nearbyHotObjectEnclosureDisposables.push(
          geometry, material, capEdgeGeo, capEdgeMat,
        );
      };
      cap(hoodPositions, Boolean(detail.hoodVisible), wallOpacity * 0.75, 0.58);
      cap(floorPositions, Boolean(detail.floorVisible), wallOpacity * 0.55, 0.42);

      // Two translucent strut-tower / wheel-house intrusion guides. They are
      // visualization geometry for the generic envelope and make the bay shape
      // readable while preserving a clear central placement cavity.
      for (const side of [-1, 1]) {
        const radius = Math.min(width * 0.105, depth * 0.17);
        const towerGeo = new THREE.CylinderGeometry(radius * 0.78, radius, height * 0.56, 28, 1, true);
        towerGeo.rotateX(Math.PI * 0.5);
        const towerMat = new THREE.MeshBasicMaterial({
          color: 0x74a7d8,
          transparent: true,
          opacity: wallOpacity * 0.72,
          depthWrite: false,
          side: THREE.DoubleSide,
        });
        const tower = new THREE.Mesh(towerGeo, towerMat);
        tower.position.set(
          center.x + side * width * 0.285,
          center.y + depth * 0.17,
          center.z - height * 0.08,
        );
        tower.renderOrder = 52;
        const towerEdgeGeo = new THREE.EdgesGeometry(towerGeo, 18);
        const towerEdges = new THREE.LineSegments(towerEdgeGeo, edgeMat);
        towerEdges.position.copy(tower.position);
        towerEdges.renderOrder = 53;
        this.nearbyHotObjectEnclosure.add(tower, towerEdges);
        this.nearbyHotObjectEnclosureDisposables.push(
          towerGeo, towerMat, towerEdgeGeo,
        );
      }
      this.nearbyHotObjectEnclosureDisposables.push(wallMat, edgeMat);
    }
    if (!this.nearbyHotObjectEnclosure.parent) this.scene.add(this.nearbyHotObjectEnclosure);
  }

'''


def apply(source_root: pathlib.Path) -> None:
    source_root = source_root.resolve()
    scene = source_root / "web/src/viewer/SceneManager.ts"
    if not scene.is_file():
        raise RuntimeError(f"Shaped engine-bay viewer target is missing: {scene}")
    replace_between(
        scene,
        "  setNearbyHotObjectEnclosureBox(detail:",
        "  clearNearbyHotObjectState() {",
        SHAPED_METHOD,
        "enclosure renderer",
    )
    text = scene.read_text(encoding="utf-8")
    for contract in (
        'shape?: "box" | "engine-bay"',
        "Clockwise normalized footprint",
        "hoodZ",
        "floorZ",
        "strut-tower / wheel-house intrusion guides",
    ):
        if contract not in text:
            raise RuntimeError(f"Shaped envelope contract {contract!r} is missing")
    (source_root / SOURCE_MARKER).write_text(MARKER + "\n", encoding="utf-8")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("source_root", type=pathlib.Path)
    args = parser.parse_args()
    apply(args.source_root)
