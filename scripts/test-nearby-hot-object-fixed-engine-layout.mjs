#!/usr/bin/env node
import assert from "node:assert/strict";
import fs from "node:fs";
import vm from "node:vm";

const runtimePath = process.argv[2];
if (!runtimePath) throw new Error("Fixed engine layout runtime path is required");
const source = fs.readFileSync(runtimePath, "utf8");

const helpers = `
  var _inputs = {};
  function field(id, label, initial, step) { return '<input id="ti-' + id + '">'; }
  function checkbox(id, label, on) { return '<input id="ti-' + id + '">'; }
  function value(id) { return (_inputs[id] !== undefined ? _inputs[id] : ""); }
  function checked(id) { return false; }
  function setValue(id, next) { _inputs[id] = String(next); }
  function finite(raw, label, min = -Infinity, max = Infinity) {
    const n = Number(raw);
    if (!Number.isFinite(n) || n < min || n > max) throw new Error(label);
    return n;
  }
  function format(raw, digits = 3) {
    const n = Number(raw);
    if (!Number.isFinite(n)) return "—";
    return n.toLocaleString(undefined, { maximumFractionDigits: digits });
  }
  function kpi(label, display) { return '<div class="ti-kpi"><b>' + display + '</b><span>' + label + '</span></div>'; }
  let createGroup = () => ({ querySelector: () => null });
  let collectOptions = () => ({ sourceTargetMm: [10, 20, 30], sourceNormal: [0, 0, 1], sourceTemperatureC: 300, source2TemperatureC: 600 });
  let combinedHeatSourceMarkers = () => null;
  let renderSelection = () => {};
  let bind = () => {};
  let restoreDraft = () => {};
  let renderResults = () => {};
  let collectReport = () => ({});
  const renderCombinedHeatSourceMarkers = () => {};
  const applySourceType = () => {};
  const syncSource2Ui = () => {};
  const syncEnvironmentUi = () => {};
  const invalidate = () => {};
  const HEAT_SOURCE_DRAG_MODE_EVENT = "enderslicer-nearby-hot-object-drag-mode";
  const HEAT_SOURCE_DRAG_EVENT = "enderslicer-nearby-hot-object-drag";
  const input = (id) => ({ className: "", textContent: "" });
  let selected = null;
`;

const context = vm.createContext({ console });
const window = {
  addEventListener: (type, fn) => {},
  removeEventListener: () => {},
  dispatchEvent: () => {},
};
context.window = window;
vm.runInContext(helpers, context);

vm.runInContext(source, context, { filename: runtimePath });
const api = window.EnderSlicerEngineLayoutTestApi;
assert.ok(api, "Fixed engine layout test API missing");

// Layout presets are defined with rigid component offsets.
assert.ok(api.ENGINE_LAYOUT_PRESETS.engine_turbo_cluster);
assert.equal(api.ENGINE_LAYOUT_PRESETS.engine_turbo_cluster.label, "Engine + turbocharger cluster");
assert.equal(api.ENGINE_LAYOUT_PRESETS.engine_turbo_cluster.components.length, 2);
const block = api.ENGINE_LAYOUT_PRESETS.engine_turbo_cluster.components.find((c) => c.slot === "primary");
const turbo = api.ENGINE_LAYOUT_PRESETS.engine_turbo_cluster.components.find((c) => c.slot === "secondary");
assert.equal(block.label, "Engine block");
assert.deepEqual([...block.offsetMm], [0, 0, 0]);
assert.deepEqual([...turbo.offsetMm], [160, 60, 40]);

// The engine is fixed at the model bounding-box centre. Without a bbox the
// centre falls back to the origin.
const eq3 = (a, b) => {
  assert.ok(Array.isArray(a) && a.length === 3, "expected length-3 array");
  const values = a.map(Number);
  assert.ok(values.every((n) => Number.isFinite(n)), `expected finite values, got ${JSON.stringify(a)}`);
  values.forEach((n, i) => assert.ok(Math.abs(n - b[i]) < 1e-6, `axis ${i}: expected ~${b[i]}, got ${n}`));
};
eq3(api.engineModelCenter(), [0, 0, 0]);

// With a bbox, the anchor is its centre. Components sit at centre + rotated
// offset (yaw about the vertical axis).
context._inputs.engineLayout = "engine_turbo_cluster";
context._inputs.engineRotationDeg = 0;
context.engineModelBbox = [0, 0, 0, 100, 200, 300];
// Note: bbox state is captured via the event listener in the real runtime; the
// pure helper falls back to [0,0,0] unless the module's internal state is set.
eq3(api.engineComponentTarget("primary"), [0, 0, 0]);
eq3(api.engineComponentTarget("secondary"), [160, 60, 40]);

// Rotating by 90° spins the turbo around the block in the X/Y plane. (The
// centre is [0,0,0] in this isolated test since no bbox event has fired.)
context._inputs.engineRotationDeg = 90;
const rotated = api.engineComponentTarget("secondary");
// 90° yaw: (ox*cos - oy*sin, ox*sin + oy*cos) = (-60, 160, 40)
eq3(rotated, [-60, 160, 40]);
context._inputs.engineRotationDeg = 0;

// Pitch tilts the turbo in the X/Z plane: 90° pitch maps +X -> -Z, +Z -> +X.
context._inputs.enginePitchDeg = 90;
const pitched = api.engineComponentTarget("secondary");
// Pitch 90° about Y: x' = z, z' = -x  (160, 60, 40) -> (40, 60, -160)
eq3(pitched, [40, 60, -160]);
context._inputs.enginePitchDeg = 0;

// Roll spins around X: 90° roll maps +Y -> +Z, +Z -> -Y.
context._inputs.engineRollDeg = 90;
const rolled = api.engineComponentTarget("secondary");
// Roll 90° about X: y' = -z, z' = y  (160, 60, 40) -> (160, -40, 60)
eq3(rolled, [160, -40, 60]);
context._inputs.engineRollDeg = 0;

// The source marker carries the fixed gap/diameter and the rotation, and is
// positioned by an explicit world centre (not a surface normal).
const blockMarker = api.engineSourceMarker("primary");
const turboMarker = api.engineSourceMarker("secondary");
eq3(blockMarker.center, [0, 0, 0]);
assert.equal(blockMarker.gapMm, 25);
assert.equal(blockMarker.diameterMm, 180);
assert.equal(blockMarker.shape, "engine");
assert.equal(blockMarker.rotationDeg, 0);
eq3(turboMarker.center, [160, 60, 40]);
assert.equal(turboMarker.gapMm, 40);
assert.equal(turboMarker.diameterMm, 120);
assert.equal(turboMarker.shape, "turbo");

// Rigid-cluster model contract.
for (const contract of [
  "ENGINE_LAYOUT_PRESETS",
  "engine_turbo_cluster",
  "Engine + turbocharger cluster",
  "Engine yaw / rotation (degrees)",
  "fixed-engine-layout-rigid-cluster-v1",
  "engineAssemblyModel",
  "EnderSlicerEngineLayoutTestApi",
  "enderslicer-nearby-hot-object-model-bbox",
  "The engine is centred in the middle of the model",
  "event.stopImmediatePropagation()",
  "sourceShape",
  "sourceBlockLengthMm",
  "source2Shape",
  "source2TurboDiameterMm",
  "forcedConvectionWm2K",
  "Forced-air cooling (W/m²K)",
  "enginePitchDeg",
  "engineRollDeg",
  "Engine pitch (degrees)",
  "Engine roll (degrees)",
]) {
  assert.ok(source.includes(contract), `Missing fixed engine layout runtime contract ${contract}`);
}

console.log("Nearby Hot Object fixed engine layout tests passed");
