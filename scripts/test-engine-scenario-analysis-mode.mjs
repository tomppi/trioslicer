#!/usr/bin/env node
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const path = process.argv[2];
if (!path) throw new Error("Engine scenario analysis-mode path is required");
const source = fs.readFileSync(path, "utf8");
const boundary = source.indexOf("  const scenarioModeApplyBase");
assert.ok(boundary > 0);
const context = vm.createContext({});
vm.runInContext(source.slice(0, boundary) + "\nglobalThis.modes = ENGINE_SCENARIO_ANALYSIS;", context);
assert.equal(context.modes.petrol_normal.mode, "steady");
assert.equal(context.modes.petrol_sustained.mode, "transient");
assert.equal(context.modes.petrol_turbo_high.durationSeconds, 1200);
assert.equal(context.modes.shutdown_heat_soak.timeStepSeconds, 30);
assert.equal(context.modes.conservative_worst_case.mode, "steady");
assert.ok(source.includes('setValue("mode", analysis.mode)'));
console.log("Engine scenario analysis-mode test passed");
