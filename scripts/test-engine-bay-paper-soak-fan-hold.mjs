#!/usr/bin/env node
import fs from "node:fs";
import vm from "node:vm";

const sourcePath = process.argv[2];
if (!sourcePath) throw new Error("usage: node test-engine-bay-paper-soak-fan-hold.mjs <runtime.js>");
const source = fs.readFileSync(sourcePath, "utf8");

const basePlan = (durationSeconds, timeStepSeconds) => {
  const duration = Number(durationSeconds);
  const step = Number(timeStepSeconds);
  const totalSteps = Math.max(1, Math.ceil(duration / step));
  const stepsPerStage = Math.max(1, Math.ceil(totalSteps / 24));
  const stageSeconds = stepsPerStage * step;
  const stages = [];
  let remaining = duration;
  while (remaining > 1e-9) {
    const seconds = Math.min(stageSeconds, remaining);
    stages.push(seconds);
    remaining -= seconds;
  }
  return stages;
};

const context = {
  console,
  Object,
  Number,
  Math,
  window: {},
  ENGINE_BAY_HINT_REFERENCE: Object.freeze({
    id: "gao-2024-suv-key-off-soak",
    fanHoldSeconds: 30,
    earlyRadiationConvectionSeconds: 120,
  }),
  planEnclosureStages: basePlan,
  wallTemperatureAt(options, elapsedSeconds) {
    const start = Number(options.enclosureWallStartTemperatureC);
    const end = Number(options.enclosureWallEndTemperatureC);
    const ramp = Math.max(0, Number(options.enclosureWallRampSeconds));
    if (ramp <= 0) return end;
    const fraction = Math.min(1, Math.max(0, Number(elapsedSeconds) / ramp));
    return start + (end - start) * fraction;
  },
  enclosureAirStep(config) {
    return { ventilationAch: Number(config.ventilationAch) };
  },
  async runEnclosureScenario() {
    return { environment: {} };
  },
};
context.window = context;
vm.createContext(context);
vm.runInContext(source, context, { filename: sourcePath });

const api = context.EnderSlicerPaperSoakFanHoldTestApi;
if (!api) throw new Error("missing EnderSlicerPaperSoakFanHoldTestApi");
if (api.PAPER_SOAK_FORCED_VENTILATION_ACH !== 18) {
  throw new Error("paper fan hold must reuse the existing 18 ACH engine-running assumption");
}

const stages = api.paperSoakStagePlan("paper_suv_400s_soak", 400, 10, basePlan);
if (stages[0] !== 30) throw new Error(`expected an exact 30 s first stage, got ${stages[0]}`);
const total = stages.reduce((sum, value) => sum + value, 0);
if (Math.abs(total - 400) > 1e-9) throw new Error(`paper stage plan totals ${total}, expected 400`);
if (stages.length > 24) throw new Error(`paper stage plan exceeds Android stage budget: ${stages.length}`);

const normalStages = api.paperSoakStagePlan("generic_conservative", 400, 10, basePlan);
if (normalStages[0] === 30) throw new Error("non-paper profiles must retain the original planner");

if (api.paperSoakVentilationAchAt("paper_suv_400s_soak", 29.999, 3) !== 18) {
  throw new Error("forced ventilation must remain active before 30 s");
}
if (api.paperSoakVentilationAchAt("paper_suv_400s_soak", 30, 3) !== 3) {
  throw new Error("normal heat-soak ventilation must start at 30 s");
}
if (api.paperSoakVentilationAchAt("generic_conservative", 10, 3) !== 3) {
  throw new Error("forced ventilation must not affect generic profiles");
}

const wallOptions = {
  enclosureWallStartTemperatureC: 90,
  enclosureWallEndTemperatureC: 135,
  enclosureWallRampSeconds: 120,
};
const beforeFanStop = api.paperSoakWallInputs("paper_suv_400s_soak", wallOptions, 15);
if (beforeFanStop.elapsedSeconds !== 0 || beforeFanStop.options.enclosureWallRampSeconds !== 90) {
  throw new Error("wall ramp must remain held during the first 30 seconds and still finish at 120 seconds");
}
const afterFanStop = api.paperSoakWallInputs("paper_suv_400s_soak", wallOptions, 45);
if (afterFanStop.elapsedSeconds !== 15 || afterFanStop.options.enclosureWallRampSeconds !== 90) {
  throw new Error("wall ramp must start from zero elapsed time after fan stop");
}

console.log("engine-bay paper soak fan-hold contracts passed");
