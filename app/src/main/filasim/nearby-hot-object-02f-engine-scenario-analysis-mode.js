  const ENGINE_SCENARIO_ANALYSIS = Object.freeze({
    petrol_normal: Object.freeze({ mode: "steady" }),
    petrol_sustained: Object.freeze({ mode: "transient", durationSeconds: 1800, timeStepSeconds: 30 }),
    petrol_turbo_high: Object.freeze({ mode: "transient", durationSeconds: 1200, timeStepSeconds: 20 }),
    diesel_normal: Object.freeze({ mode: "transient", durationSeconds: 1800, timeStepSeconds: 30 }),
    shutdown_heat_soak: Object.freeze({ mode: "transient", durationSeconds: 1800, timeStepSeconds: 30 }),
    conservative_worst_case: Object.freeze({ mode: "steady" }),
  });

  const scenarioModeApplyBase = applyEngineScenario;
  applyEngineScenario = function applyEngineScenarioWithAnalysisMode(name) {
    scenarioModeApplyBase(name);
    const analysis = ENGINE_SCENARIO_ANALYSIS[name];
    if (!analysis) return;
    setValue("mode", analysis.mode);
    if (analysis.durationSeconds != null) setValue("durationSeconds", analysis.durationSeconds);
    if (analysis.timeStepSeconds != null) setValue("timeStepSeconds", analysis.timeStepSeconds);
    syncEnvironmentUi();
  };
