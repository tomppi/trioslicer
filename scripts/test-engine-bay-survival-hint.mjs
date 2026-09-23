#!/usr/bin/env node
import fs from "node:fs";
import vm from "node:vm";

const sourcePath = process.argv[2];
if (!sourcePath) throw new Error("usage: node test-engine-bay-survival-hint.mjs <runtime.js>");
const source = fs.readFileSync(sourcePath, "utf8");

const elements = new Map();
const fakeElement = (value = "") => ({
  value,
  checked: false,
  textContent: "",
  classList: { contains: () => false },
  querySelector: () => null,
  querySelectorAll: () => [],
  insertAdjacentHTML: () => {},
  addEventListener: () => {},
});

const context = {
  console,
  ArrayBuffer,
  Object,
  Number,
  Math,
  Set,
  Map,
  document: {
    getElementById(id) {
      if (!elements.has(id)) elements.set(id, fakeElement());
      return elements.get(id);
    },
    querySelectorAll: () => [],
  },
  window: {},
  createGroup: () => ({ querySelector: () => null }),
  collectOptions: () => ({}),
  renderResults: () => {},
  collectReport: () => ({}),
  restoreDraft: () => {},
  bind: () => {},
  installUi: () => true,
  applyEnvironmentPreset: () => {},
  applyEnclosureBoxPreset: () => {},
  applySourceType: () => {},
  syncSource2Ui: () => {},
  syncEnvironmentUi: () => {},
  renderCombinedHeatSourceMarkers: () => {},
  invalidate: () => {},
  setValue: (id, value) => {
    const key = `ti-${id}`;
    if (!elements.has(key)) elements.set(key, fakeElement());
    elements.get(key).value = String(value);
  },
  value: (id) => elements.get(`ti-${id}`)?.value || "off",
  input: (id) => {
    const key = `ti-${id}`;
    if (!elements.has(key)) elements.set(key, fakeElement());
    return elements.get(key);
  },
  kpi: (label, value) => `${label}:${value}`,
  format: (value, digits) => Number(value).toFixed(digits),
  latest: {},
};
context.window = context;
vm.createContext(context);
vm.runInContext(source, context, { filename: sourcePath });

const api = context.EnderSlicerThermalHintTestApi;
if (!api) throw new Error("missing EnderSlicerThermalHintTestApi");

const paper = api.ENGINE_BAY_HINT_REFERENCE;
if (paper.durationSeconds !== 400) throw new Error("paper soak duration must be 400 s");
if (paper.fanHoldSeconds !== 30) throw new Error("paper fan hold must be 30 s");
if (paper.earlyRadiationConvectionSeconds !== 120) throw new Error("early heat-transfer period must be 120 s");
if (paper.publishedComponentAgreementC !== 10) throw new Error("published component agreement must be recorded as 10 °C");

const defaultsIndex = source.indexOf("applyEnvironmentPreset(profile.scenario.environmentMode)");
const specificIndex = source.indexOf("Object.entries(profile.scenario).forEach");
if (defaultsIndex < 0 || specificIndex < 0 || defaultsIndex > specificIndex) {
  throw new Error("generic environment defaults must be applied before paper-profile values");
}

const paperProfile = api.ENGINE_BAY_HINT_PROFILES.paper_suv_400s_soak;
if (paperProfile.uncertaintyC !== 20) throw new Error("paper profile must include additional reduced-order uncertainty");
if (paperProfile.scenario.durationSeconds !== 400) throw new Error("paper profile duration mismatch");
if (paperProfile.scenario.timeStepSeconds !== 10) throw new Error("paper profile timestep mismatch");
if (paperProfile.scenario.sourceType !== "engine_surface") throw new Error("paper profile must reuse engine source data");
if (paperProfile.scenario.source2Type !== "turbo_high") throw new Error("paper profile must reuse hot exhaust/turbo source data");

const pass = api.classifyThermalHint({
  peakTemperatureC: 45,
  serviceLimitC: 80,
  uncertaintyC: 20,
  safetyFactor: 2,
});
if (pass.verdict !== "LIKELY OK") throw new Error(`expected pass, got ${pass.verdict}`);

const caution = api.classifyThermalHint({
  peakTemperatureC: 52,
  serviceLimitC: 80,
  uncertaintyC: 20,
  safetyFactor: 1.2,
});
if (caution.verdict !== "CAUTION / TEST REQUIRED") throw new Error(`expected caution, got ${caution.verdict}`);

const failTemperature = api.classifyThermalHint({
  peakTemperatureC: 65,
  serviceLimitC: 80,
  uncertaintyC: 20,
  safetyFactor: 2,
});
if (failTemperature.verdict !== "UNLIKELY TO WORK") throw new Error(`expected thermal fail, got ${failTemperature.verdict}`);

const failStructure = api.classifyThermalHint({
  peakTemperatureC: 40,
  serviceLimitC: 80,
  uncertaintyC: 20,
  safetyFactor: 0.9,
});
if (failStructure.verdict !== "UNLIKELY TO WORK") throw new Error(`expected structural fail, got ${failStructure.verdict}`);

const invalid = api.classifyThermalHint({
  peakTemperatureC: 40,
  serviceLimitC: 80,
  uncertaintyC: 20,
  thermallyInvalid: true,
});
if (invalid.verdict !== "UNLIKELY TO WORK") throw new Error(`expected invalid-model fail, got ${invalid.verdict}`);

const unknown = api.classifyThermalHint({
  peakTemperatureC: null,
  serviceLimitC: 80,
  uncertaintyC: 20,
});
if (unknown.verdict !== "UNKNOWN") throw new Error(`expected unknown, got ${unknown.verdict}`);

console.log("engine-bay thermal survival hint contracts passed");
