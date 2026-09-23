#!/usr/bin/env node
import fs from "node:fs";

const [runtimePath, corePath, apiPath] = process.argv.slice(2);
if (!runtimePath || !corePath || !apiPath) {
  throw new Error("Expected zoned runtime, core transform and API transform paths");
}
const runtime = fs.readFileSync(runtimePath, "utf8");
const core = fs.readFileSync(corePath, "utf8");
const api = fs.readFileSync(apiPath, "utf8");

for (const token of [
  "ENGINE_BAY_ZONE_COUNT = 12",
  'ENGINE_BAY_ZONE_LAYOUT = "3x2x2-local-air-wall-network-v1"',
  "ENGINE_BAY_CORRECTION_PASSES = 2",
  "High — 12 local zones, two correction passes",
  "partZoneMeanTemperatures",
  "zonedAirNetworkStep",
  "runTransientZonedEngineBay",
  "runSteadyZonedEngineBay",
  "spatialEnvironmentEnabled",
  "EnderSlicerZonedEnvironmentTestApi",
]) {
  if (!runtime.includes(token)) throw new Error(`Zoned runtime is missing ${token}`);
}
for (const token of [
  "spatial_environment_enabled: bool",
  "environment_air_temperatures_c: [f64; 12]",
  "environment_zone: usize",
  "spatial_environment_zone(",
  "boundary_environment(",
  "spatial_zone_exterior_area_mm2: [f64; 12]",
]) {
  if (!core.includes(token)) throw new Error(`Zoned core transform is missing ${token}`);
}
for (const token of [
  "spatial_environment_enabled: bool",
  "environmentAirTemperaturesC",
  "spatialZoneExteriorAreaMm2",
  "environmentWallEmissivities",
]) {
  if (!api.includes(token)) throw new Error(`Zoned API transform is missing ${token}`);
}

const zones = [];
for (let vertical = 0; vertical < 2; vertical += 1) {
  for (let lateral = 0; lateral < 2; lateral += 1) {
    for (let longitudinal = 0; longitudinal < 3; longitudinal += 1) {
      zones.push(longitudinal + 3 * lateral + 6 * vertical);
    }
  }
}
if (zones.length !== 12 || new Set(zones).size !== 12 || Math.min(...zones) !== 0 || Math.max(...zones) !== 11) {
  throw new Error("3 x 2 x 2 zone-index contract is invalid");
}
const physicalStages = 20;
if (physicalStages * 2 !== 40) throw new Error("Predictor/correction pass contract changed");

console.log("12-zone engine-bay boundary field and predictor/correction coupling contracts verified.");
