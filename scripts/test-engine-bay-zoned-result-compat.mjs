#!/usr/bin/env node
import fs from "node:fs";
import vm from "node:vm";

const [runtimePath] = process.argv.slice(2);
if (!runtimePath) throw new Error("Expected zoned result-compat runtime path");
const runtime = fs.readFileSync(runtimePath, "utf8");
for (const token of [
  "normalizeZonedEnvironmentResult",
  "finalAirTemperatureC",
  "finalWallTemperatureC",
  "exteriorAreaMm2",
  "nearby-hot-object-plus-12-zone-engine-bay-v1",
  "EnderSlicerZonedResultCompatTestApi",
]) {
  if (!runtime.includes(token)) throw new Error(`Zoned result compatibility is missing ${token}`);
}
const context = {
  window: {},
  latest: null,
  async runTransientZonedEngineBay() {},
  async runSteadyZonedEngineBay() {},
  collectReport() { return {}; },
  zoneArray(value) {
    if (Array.isArray(value) && value.length === 12) return value.map(Number);
    return Array(12).fill(Number(value));
  },
  meanNumbers(values) { return values.reduce((a, b) => a + Number(b), 0) / values.length; },
  Number,
  Math,
  Array,
  Object,
};
vm.createContext(context);
vm.runInContext(runtime, context, { filename: runtimePath });
const api = context.window.EnderSlicerZonedResultCompatTestApi;
if (!api) throw new Error("Zoned result compatibility test API missing");
const result = {
  stats: { thermalIterations: 123 },
  environment: {
    accuracyMode: "zoned_high",
    finalAirTemperaturesC: Array.from({ length: 12 }, (_, i) => 50 + i),
    finalWallTemperaturesC: Array.from({ length: 12 }, (_, i) => 80 + i),
    zoneExteriorAreaMm2: Array(12).fill(100),
    peakAirTemperatureC: 72,
  },
};
api.normalizeZonedEnvironmentResult(result);
if (Math.abs(result.environment.finalAirTemperatureC - 55.5) > 1e-12) {
  throw new Error("Final zoned air mean was not preserved for the existing UI");
}
if (Math.abs(result.environment.finalWallTemperatureC - 85.5) > 1e-12) {
  throw new Error("Final zoned wall mean was not preserved for the existing UI");
}
if (result.environment.exteriorAreaMm2 !== 1200) {
  throw new Error("Zoned exterior areas were not summed for the existing UI");
}
if (result.stats.iterations !== 123) {
  throw new Error("Aggregated zoned solver iterations were not exposed");
}
console.log("Zoned environment arrays and scalar result/report compatibility verified.");
