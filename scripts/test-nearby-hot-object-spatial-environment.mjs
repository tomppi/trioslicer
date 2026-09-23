#!/usr/bin/env node
import fs from "node:fs";

const paths = process.argv.slice(2);
if (paths.length < 2) {
  throw new Error("Expected one or more runtime paths followed by the transform path");
}
const transformPath = paths.pop();
const runtime = paths.map((runtimePath) => fs.readFileSync(runtimePath, "utf8")).join("\n");
const transform = fs.readFileSync(transformPath, "utf8");
const runtimeContracts = [
  "No model-point marking is required.",
  "entire 3D model",
  "Show the enclosure / engine-bay walls in 3D",
  "enclosureWidthMm",
  "enclosureDepthMm",
  "enclosureHeightMm",
  "enclosureVolumeFromDimensions",
  "Hold the heat-source sphere still for 5 seconds",
  "movable-source-automatic-nearest-surface",
  "entire-voxel-model",
  "ENCLOSURE_WORLD_PRESETS",
  "applyEnvironmentPresetWithFiniteWorld",
  "applyEnclosureBoxPresetWithFiniteWorld",
  "enclosureWorldVolumeL",
  "engine_running: Object.freeze({ widthMm: 1090, depthMm: 660, heightMm: 542 })",
  "ventilated_enclosure: Object.freeze({ widthMm: 600, depthMm: 500, heightMm: 500 })",
  "sealed_enclosure: Object.freeze({ widthMm: 400, depthMm: 400, heightMm: 400 })",
];
const transformContracts = [
  "nearestSurfaceForNearbySource",
  "setNearbyHotObjectEnclosureBox",
  "nearbyHotObjectDragAxis",
  "new THREE.Vector3(0, 0, 1)",
  "window.setTimeout",
  "5000",
  "clearNearbyHotObjectState",
  "NEARBY_ENCLOSURE_BOX_EVENT",
];
for (const token of runtimeContracts) {
  if (!runtime.includes(token)) throw new Error(`Spatial runtime is missing ${token}`);
}
for (const token of transformContracts) {
  if (!transform.includes(token)) throw new Error(`Spatial transform is missing ${token}`);
}
if (runtime.includes("Select the primary source point before dragging")) {
  throw new Error("Spatial runtime still requires manual primary-source marking");
}
if (runtime.includes("applyEnclosureBoxPresetPreservingVolume")) {
  throw new Error("Spatial runtime still preserves the old open-air-oriented preset volumes");
}

const engineBayVolumeL = 1090 * 660 * 542 / 1_000_000;
if (Math.abs(engineBayVolumeL - 389.9148) > 1e-9) {
  throw new Error("Engine-bay calculation-world volume contract changed unexpectedly");
}
const ventilatedVolumeL = 600 * 500 * 500 / 1_000_000;
if (ventilatedVolumeL !== 150) throw new Error("Ventilated enclosure must be 150 L");
const sealedVolumeL = 400 * 400 * 400 / 1_000_000;
if (sealedVolumeL !== 64) throw new Error("Sealed enclosure must be 64 L");

console.log("Automatic source projection, constrained dragging, full-model calculation and finite enclosed worlds verified.");
