#!/usr/bin/env node
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const runtimePath = process.argv[2];
if (!runtimePath) throw new Error("Dual-source runtime path is required");
const source = fs.readFileSync(runtimePath, "utf8");
const boundary = source.indexOf("  function sourceSelectOptions");
assert.ok(boundary > 0, "Unable to isolate dual-source preset model");
const context = vm.createContext({ console });
vm.runInContext(
  source.slice(0, boundary) + `\nglobalThis.api = { SOURCE_TYPE_PRESETS, ENGINE_SCENARIO_PRESETS, sourceTemperatureAt };`,
  context,
);
const api = context.api;
assert.ok(api.SOURCE_TYPE_PRESETS.turbo_high);
assert.equal(api.SOURCE_TYPE_PRESETS.turbo_high.temperatureC, 850);
assert.equal(api.SOURCE_TYPE_PRESETS.turbo_extreme.temperatureC, 950);
assert.ok(api.ENGINE_SCENARIO_PRESETS.petrol_normal.source2Enabled);
assert.equal(api.ENGINE_SCENARIO_PRESETS.petrol_turbo_high.source2Type, "turbo_high");
assert.equal(api.ENGINE_SCENARIO_PRESETS.shutdown_heat_soak.source2TemperatureC, 750);
assert.equal(api.ENGINE_SCENARIO_PRESETS.shutdown_heat_soak.source2EndTemperatureC, 250);
assert.equal(api.sourceTemperatureAt(750, 250, 1800, 0), 750);
assert.equal(api.sourceTemperatureAt(750, 250, 1800, 900), 500);
assert.equal(api.sourceTemperatureAt(750, 250, 1800, 3600), 250);
assert.equal(api.sourceTemperatureAt(600, 900, 0, 500), 600);
for (const contract of [
  "source2Enabled",
  "source2TargetMm",
  "source2AbsorbedW",
  "runOpenAirSourceTimeline",
  "event.stopImmediatePropagation()",
  "two-source-piecewise-temperature-stage-coupling-v1",
  "Each source uses its own picked location, visibility field",
]) {
  assert.ok(source.includes(contract), `Missing dual-source runtime contract ${contract}`);
}
console.log("Nearby Hot Object dual-source presets and ramp tests passed");
