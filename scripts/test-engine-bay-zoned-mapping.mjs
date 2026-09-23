#!/usr/bin/env node
import fs from "node:fs";
import vm from "node:vm";

const [runtimePath] = process.argv.slice(2);
if (!runtimePath) throw new Error("Expected zoned mapping runtime path");
const runtime = fs.readFileSync(runtimePath, "utf8");
for (const token of [
  "engineBayZoneForActiveCell",
  "partZoneMeanTemperaturesInEngineBayCoordinates",
  "activeZonedMappingOptions",
  "runTransientZonedEngineBayWithAlignedMapping",
  "runSteadyZonedEngineBayWithAlignedMapping",
  "Y/depth runs front -> rear",
  "EnderSlicerZonedMappingTestApi",
]) {
  if (!runtime.includes(token)) throw new Error(`Zoned mapping runtime is missing ${token}`);
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
const centered = {
  enclosureWidthMm: 1000,
  enclosureDepthMm: 600,
  enclosureHeightMm: 500,
  enclosureOffsetXmm: 0,
  enclosureOffsetYmm: 0,
  enclosureOffsetZmm: 0,
};
const centerZone = api.engineBayZoneForActiveCell(5, 2, 2, bounds, centered, 10);
if (centerZone !== 10) {
  throw new Error(`Expected centered upper-right-middle zone 10, received ${centerZone}`);
}
const leftShifted = { ...centered, enclosureOffsetXmm: 400 };
const leftZone = api.engineBayZoneForActiveCell(5, 2, 2, bounds, leftShifted, 10);
if (leftZone !== 7) {
  throw new Error(`Expected +X enclosure offset to move the cell left without changing front/rear: ${leftZone}`);
}
const frontShifted = { ...centered, enclosureOffsetYmm: 200 };
const frontZone = api.engineBayZoneForActiveCell(5, 2, 2, bounds, frontShifted, 10);
if (frontZone !== 9) {
  throw new Error(`Expected +Y enclosure offset to move the cell into the front zone: ${frontZone}`);
}
const hugeBay = { ...centered, enclosureWidthMm: 5000, enclosureDepthMm: 5000, enclosureHeightMm: 5000 };
const occupied = new Set();
for (let z = 0; z <= 3; z += 1) {
  for (let y = 0; y <= 3; y += 1) {
    for (let x = 0; x <= 9; x += 1) {
      occupied.add(api.engineBayZoneForActiveCell(x, y, z, bounds, hugeBay, 10));
    }
  }
}
if (occupied.size > 4 || [...occupied].some((zone) => zone % 3 !== 1)) {
  throw new Error(`A small centered part may straddle left/right and vertical midplanes but must remain in the middle front/rear column: ${[...occupied]}`);
}

console.log("Zoned part feedback uses Y/depth for front-rear and X/width for left-right, aligned with engine-bay offsets.");
