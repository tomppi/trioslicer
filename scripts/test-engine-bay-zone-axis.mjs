#!/usr/bin/env node
import fs from "node:fs";
import vm from "node:vm";

const [runtimePath, rustTransformPath] = process.argv.slice(2);
if (!runtimePath || !rustTransformPath) {
  throw new Error("Expected zoned mapping runtime and Rust axis-transform paths");
}
const runtime = fs.readFileSync(runtimePath, "utf8");
const rustTransform = fs.readFileSync(rustTransformPath, "utf8");
for (const token of [
  "Y/depth runs front -> rear",
  "normalized[1] * 3",
  "normalized[0] >= 0.5",
  "EnderSlicerZonedMappingTestApi",
]) {
  if (!runtime.includes(token)) throw new Error(`Vehicle-axis runtime is missing ${token}`);
}
for (const token of [
  "longitudinal Y (front/middle/rear) fastest",
  "normalized[1] * 3.0",
  "normalized[0] >= 0.5",
]) {
  if (!rustTransform.includes(token)) throw new Error(`Rust vehicle-axis transform is missing ${token}`);
}

const context = {
  window: {},
  console,
  partZoneMeanTemperatures() {},
  async runTransientZonedEngineBay() {},
  async runSteadyZonedEngineBay() {},
  ENGINE_BAY_ZONE_COUNT: 12,
  zoneArray(value) { return Array(12).fill(Number(value)); },
  Float32Array,
  Number,
  Math,
  Array,
  Object,
};
vm.createContext(context);
vm.runInContext(runtime, context, { filename: runtimePath });
const api = context.window.EnderSlicerZonedMappingTestApi;
if (!api) throw new Error("Zoned mapping test API was not installed");

const bounds = { minX: 0, maxX: 9, minY: 0, maxY: 3, minZ: 0, maxZ: 3 };
const base = {
  enclosureWidthMm: 1000,
  enclosureDepthMm: 600,
  enclosureHeightMm: 500,
  enclosureOffsetXmm: 0,
  enclosureOffsetYmm: 0,
  enclosureOffsetZmm: 0,
};

// A positive Y enclosure offset places the centered part toward the front of
// the bay. X remains on the right side and Z remains upper.
const front = api.engineBayZoneForActiveCell(
  5, 2, 2, bounds, { ...base, enclosureOffsetYmm: 200 }, 10,
);
if (front !== 9) {
  throw new Error(`Expected upper-right-front zone 9 after +Y bay offset, received ${front}`);
}

// A positive X enclosure offset moves the same part toward the left side; it
// must not change front/rear classification.
const leftMiddle = api.engineBayZoneForActiveCell(
  5, 2, 2, bounds, { ...base, enclosureOffsetXmm: 400 }, 10,
);
if (leftMiddle !== 7) {
  throw new Error(`Expected upper-left-middle zone 7 after +X bay offset, received ${leftMiddle}`);
}

const hugeBay = {
  ...base,
  enclosureWidthMm: 5000,
  enclosureDepthMm: 5000,
  enclosureHeightMm: 5000,
};
const occupied = new Set();
for (let z = 0; z <= 3; z += 1) {
  for (let y = 0; y <= 3; y += 1) {
    for (let x = 0; x <= 9; x += 1) {
      occupied.add(api.engineBayZoneForActiveCell(x, y, z, bounds, hugeBay, 10));
    }
  }
}
if (occupied.size > 4 || [...occupied].some((zone) => zone % 3 !== 1)) {
  throw new Error(`A small centered part must remain in the middle front/rear column: ${[...occupied]}`);
}

console.log("Engine-bay zones use Y/depth for front-rear, X/width for left-right, and Z for height.");
