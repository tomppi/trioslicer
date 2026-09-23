#!/usr/bin/env node
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const runtimePaths = process.argv.slice(2);
if (!runtimePaths.length) throw new Error("Enclosure runtime paths are required");
const window = {};
const source = runtimePaths.map((runtimePath) => fs.readFileSync(runtimePath, "utf8")).join("\n");
vm.runInContext(source, vm.createContext({ window, console }));
const api = window.EnderSlicerNearbyEnclosureTestApi;
assert.ok(api, "Enclosure test API must be exposed");
for (const mode of ["open", "engine_running", "engine_heat_soak", "ventilated_enclosure", "sealed_enclosure", "custom"]) {
  assert.ok(api.ENVIRONMENT_PRESETS[mode], `Missing environment preset ${mode}`);
}

const open = api.equivalentBoundaryForPart({
  partTemperatureC: 70,
  coverageFraction: 0,
  insideConvectionWm2K: 20,
  outsideConvectionWm2K: 8,
  partEmissivity: 0.9,
  wallEmissivity: 0.8,
  enclosureAirTemperatureC: 100,
  wallTemperatureC: 120,
  externalTemperatureC: 25,
});
assert.ok(Math.abs(open.ambientTemperatureC - 25) < 1e-6, "Open boundary must reduce to external ambient");
assert.ok(Math.abs(open.convectionWm2K - 8) < 1e-12);
assert.ok(Math.abs(open.emissivity - 0.9) < 1e-12);

const hotWall = api.equivalentBoundaryForPart({
  partTemperatureC: 60,
  coverageFraction: 1,
  insideConvectionWm2K: 8,
  outsideConvectionWm2K: 5,
  partEmissivity: 0.9,
  wallEmissivity: 0.85,
  enclosureAirTemperatureC: 65,
  wallTemperatureC: 120,
  externalTemperatureC: 25,
});
assert.ok(hotWall.ambientTemperatureC > 65, "Hot enclosure walls must raise the equivalent boundary above air temperature");

const stableAir = api.enclosureAirStep({
  oldAirTemperatureC: 40,
  partMeanTemperatureC: 40,
  wallTemperatureC: 40,
  externalTemperatureC: 40,
  dtSeconds: 30,
  volumeL: 20,
  coverageFraction: 1,
  exteriorAreaMm2: 10000,
  insideConvectionWm2K: 8,
  enclosureUaWPerK: 3,
  ventilationAch: 5,
  internalHeatW: 0,
});
assert.ok(Math.abs(stableAir.airTemperatureC - 40) < 1e-10, "Balanced enclosure air must remain unchanged");

const heatedAir = api.enclosureAirStep({
  oldAirTemperatureC: 25,
  partMeanTemperatureC: 25,
  wallTemperatureC: 100,
  externalTemperatureC: 25,
  dtSeconds: 60,
  volumeL: 20,
  coverageFraction: 1,
  exteriorAreaMm2: 10000,
  insideConvectionWm2K: 8,
  enclosureUaWPerK: 5,
  ventilationAch: 0,
  internalHeatW: 10,
});
assert.ok(heatedAir.airTemperatureC > 25, "Wall/internal heat must raise enclosure air temperature");

const rampOptions = {
  enclosureWallStartTemperatureC: 80,
  enclosureWallEndTemperatureC: 120,
  enclosureWallRampSeconds: 100,
};
assert.equal(api.wallTemperatureAt(rampOptions, 0), 80);
assert.equal(api.wallTemperatureAt(rampOptions, 50), 100);
assert.equal(api.wallTemperatureAt(rampOptions, 1000), 120);

const stages = api.planEnclosureStages(601, 10);
assert.ok(stages.length <= 24, "Environment coupling must remain Android-bounded");
assert.ok(Math.abs(stages.reduce((sum, value) => sum + value, 0) - 601) < 1e-9);
assert.ok(stages.every((value) => value > 0));

console.log("Nearby Hot Object engine-bay/enclosure lumped-boundary tests passed");
