  // Apply broad environment/source defaults first, then the selected scenario's
  // more specific values. Reversing this order silently replaced sustained-load
  // and turbo-high assumptions with the generic engine-running preset.
  applyEngineScenario = function applyEngineScenario(name) {
    const preset = ENGINE_SCENARIO_PRESETS[name];
    if (!preset || name === "custom") return;
    applyEnvironmentPreset(preset.environmentMode);
    applySourceType("source", preset.sourceType);
    if (preset.source2Enabled) applySourceType("source2", preset.source2Type);
    Object.entries(preset).forEach(([key, presetValue]) => {
      if (key !== "label") setValue(key, presetValue);
    });
    const enabled = document.getElementById("ti-source2Enabled");
    if (enabled) enabled.checked = Boolean(preset.source2Enabled);
    syncSource2Ui();
    syncEnvironmentUi();
  };
