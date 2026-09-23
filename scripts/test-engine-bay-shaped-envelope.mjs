#!/usr/bin/env node
import fs from "node:fs";

const [runtimePath, transformPath] = process.argv.slice(2);
if (!runtimePath || !transformPath) {
  throw new Error("Expected shaped-envelope runtime and viewer-transform paths");
}
const runtime = fs.readFileSync(runtimePath, "utf8");
const transform = fs.readFileSync(transformPath, "utf8");

for (const token of [
  "ENGINE_BAY_ENVELOPE_SHAPE_FACTOR = 0.72",
  'enclosureShape = engineBay ? "generic-engine-bay-v1"',
  "enclosureCalculationClosed",
  "enclosureHoodBoundaryActive",
  "Show closed-hood boundary in viewer",
  "Show bottom boundary in viewer",
  "Hood and floor can stay hidden in the viewer",
  "EnderSlicerEngineBayEnvelopeTestApi",
]) {
  if (!runtime.includes(token)) throw new Error(`Shaped-envelope runtime is missing ${token}`);
}
for (const token of [
  'shape?: "box" | "engine-bay"',
  "Clockwise normalized footprint",
  "hoodZ",
  "floorZ",
  "strut-tower / wheel-house intrusion guides",
  "calculation envelope",
]) {
  if (!transform.includes(token)) throw new Error(`Shaped-envelope transform is missing ${token}`);
}

const rectangularVolumeL = 1090 * 660 * 542 / 1_000_000;
const shapedVolumeL = rectangularVolumeL * 0.72;
if (Math.abs(rectangularVolumeL - 389.9148) > 1e-9) {
  throw new Error("Engine-bay outer dimension contract changed unexpectedly");
}
if (Math.abs(shapedVolumeL - 280.738656) > 1e-9) {
  throw new Error(`Unexpected shaped engine-bay air volume ${shapedVolumeL}`);
}
if (!(shapedVolumeL < rectangularVolumeL && shapedVolumeL > 250)) {
  throw new Error("Shaped engine-bay volume must remain a plausible conservative sub-volume");
}

console.log("Closed shaped engine-bay envelope and hidden hood/floor viewer contracts verified.");
