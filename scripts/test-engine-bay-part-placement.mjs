#!/usr/bin/env node
import fs from "node:fs";
import vm from "node:vm";

const [runtimePath, transformPath] = process.argv.slice(2);
if (!runtimePath || !transformPath) {
  throw new Error("Expected part-placement runtime and viewer-transform paths");
}
const runtime = fs.readFileSync(runtimePath, "utf8");
const transform = fs.readFileSync(transformPath, "utf8");

for (const token of [
  "Move plastic object X/Y",
  "Move plastic object Z",
  "complete-object-bounding-box-inside-closed-envelope-v1",
  "fixed-voxel-grid-inverse-world-transform-v1",
  "thermalPointFromViewer",
  "solverEnvelopeOffsets",
  "partPlacementXMm",
  "viewerEnclosureOffsetXMm",
  "saveDraftWithViewerEnclosureOffsets",
  "EnderSlicerPartPlacementTestApi",
]) {
  if (!runtime.includes(token)) throw new Error(`Part-placement runtime is missing ${token}`);
}
for (const token of [
  "setEngineBayPartPlacement",
  "clampEngineBayPartPlacement",
  "engineBayPartFootprintHalfWidth",
  "highestFloor",
  "lowestHood",
  "onEngineBayPartDragMove",
  "ENGINE_BAY_PART_PLACEMENT_EVENT",
]) {
  if (!transform.includes(token)) throw new Error(`Part-placement viewer transform is missing ${token}`);
}

const values = {
  partPlacementXMm: "12",
  partPlacementYMm: "-8",
  partPlacementZMm: "25",
  environmentMode: "engine_heat_soak",
};
const listeners = new Map();
const context = {
  window: {
    addEventListener(name, callback) { listeners.set(name, callback); },
    dispatchEvent() {},
    requestAnimationFrame(callback) { callback(); },
  },
  document: { getElementById() { return null; } },
  console,
  CustomEvent: class CustomEvent { constructor(type, init = {}) { this.type = type; this.detail = init.detail; } },
  CLEAR_EVENT: "clear",
  createGroup() { return {}; },
  collectOptions() {
    return {
      sourceTargetMm: [120, 80, 30],
      source2Enabled: true,
      source2TargetMm: [-20, 40, 60],
      enclosureOffsetXMm: 15,
      enclosureOffsetYMm: 2,
      enclosureOffsetZMm: -5,
    };
  },
  saveDraft(options) { context.persistedOptions = options; },
  restoreDraft() {},
  syncEnvironmentUi() {},
  bind() {},
  installUi() { return true; },
  collectReport() { return {}; },
  beginHeatSourceDrag() {},
  renderCombinedHeatSourceMarkers() {},
  renderEnclosureBox() {},
  invalidate() {},
  input() { return null; },
  field() { return ""; },
  setValue(id, value) { values[id] = String(value); },
  value(id) { return values[id] ?? "0"; },
  checked() { return false; },
  finite(value) {
    const parsed = Number(value);
    if (!Number.isFinite(parsed)) throw new Error("not finite");
    return parsed;
  },
  enclosureBoxDimensions() {
    return {
      widthMm: 1090,
      depthMm: 660,
      heightMm: 542,
      offsetXmm: 15,
      offsetYmm: 2,
      offsetZmm: -5,
    };
  },
  isEngineBayEnvelopeMode() { return true; },
  latest: null,
  Number,
  Array,
  Object,
  Math,
};
vm.createContext(context);
vm.runInContext(runtime, context, { filename: runtimePath });

const api = context.window.EnderSlicerPartPlacementTestApi;
if (!api) throw new Error("Part-placement test API was not installed");
const translated = api.thermalPointFromViewer([120, 80, 30], [12, -8, 25]);
if (translated.join(",") !== "108,88,5") {
  throw new Error(`Unexpected translated source point ${translated}`);
}
const offsets = api.solverEnvelopeOffsets([15, 2, -5], [12, -8, 25]);
if (offsets.join(",") !== "3,10,-30") {
  throw new Error(`Unexpected solver-relative enclosure offsets ${offsets}`);
}

const options = context.collectOptions();
if (options.sourceTargetMm.join(",") !== "108,88,5") {
  throw new Error(`Primary source was not translated into fixed voxel coordinates: ${options.sourceTargetMm}`);
}
if (options.source2TargetMm.join(",") !== "-32,48,35") {
  throw new Error(`Secondary source was not translated into fixed voxel coordinates: ${options.source2TargetMm}`);
}
if ([options.enclosureOffsetXMm, options.enclosureOffsetYMm, options.enclosureOffsetZMm].join(",") !== "3,10,-30") {
  throw new Error("Engine-bay offset did not receive the inverse part-placement transform");
}
if (options.viewerEnclosureOffsetXMm !== 15 || options.partPlacementZMm !== 25) {
  throw new Error("Viewer-space placement metadata was not preserved");
}
context.saveDraft(options);
if (context.persistedOptions.enclosureOffsetXMm !== 15
    || context.persistedOptions.enclosureOffsetYMm !== 2
    || context.persistedOptions.enclosureOffsetZMm !== -5) {
  throw new Error("Draft persistence did not restore viewer-space enclosure offsets");
}

console.log("Plastic-object world placement, inverse solver coordinates, and closed-envelope viewer contracts verified.");
