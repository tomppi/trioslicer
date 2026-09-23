  function createGroup() {
    const group = document.createElement("div");
    group.id = GROUP_ID;
    group.className = "group";
    group.innerHTML = `
      <div class="ac-lead">
        Calculates the complete oven cycle from the actual voxelized printed part. Heat-up ends only when the coldest material voxel is within the selected tolerance; soak starts then. Cooling ends only when the hottest voxel is below the handling temperature.
      </div>
      <div class="ac-grid">
        <label class="ac-select"><span>Material profile</span><select id="ac-preset">${Object.keys(PRESETS).map((key) => `<option>${key}</option>`).join("")}</select></label>
        <label class="ac-select"><span>Oven airflow</span><select id="ac-ovenMode"><option value="fan">Fan-assisted</option><option value="natural">Natural convection</option><option value="custom">Custom coefficient</option></select></label>
        ${field("ovenTemperatureC", "Oven set temperature", 75, 0.5, "°C")}
        ${field("targetToleranceC", "Core tolerance below setpoint", 2, 0.1, "°C")}
        ${field("soakMinutes", "Soak after the core is ready", 60, 1, "min")}
        ${field("initialTemperatureC", "Starting part temperature", 23, 0.5, "°C")}
        ${field("roomTemperatureC", "Cooling ambient", 23, 0.5, "°C")}
        ${field("handlingTemperatureC", "Safe handling target", 45, 0.5, "°C")}
        ${field("convectionWm2K", "Oven convection coefficient", 18, 0.5, "W/m²K")}
        ${field("coolingConvectionWm2K", "Cooling convection coefficient", 8, 0.5, "W/m²K")}
        ${field("emissivity", "Surface emissivity", 0.9, 0.01)}
        ${field("timeStepSeconds", "Simulation time step", 20, 1, "s")}
        ${field("maxHeatingHours", "Maximum heat/soak search", 12, 0.5, "h")}
        ${field("maxCoolingHours", "Maximum cooling search", 12, 0.5, "h")}
      </div>
      ${checkbox("densityAware", "Use optimized Smart Infill density when available", true)}
      ${checkbox("simulateCooling", "Calculate controlled cooling and safe handling time", true)}

      <details>
        <summary>Material and printed-structure inputs</summary>
        <div class="ac-grid">
          ${field("conductivityXWmK", "Conductivity X", 0.18, 0.001, "W/mK")}
          ${field("conductivityYWmK", "Conductivity Y", 0.18, 0.001, "W/mK")}
          ${field("conductivityZWmK", "Conductivity Z", 0.13, 0.001, "W/mK")}
          ${field("densityKgM3", "Polymer density", 1240, 1, "kg/m³")}
          ${field("specificHeatJkgK", "Specific heat", 1800, 1, "J/kgK")}
          ${field("conductivityExponent", "Infill conductivity exponent", 1, 0.05)}
          ${field("alphaXyPerK", "CTE XY", 0.000096, 0.000001, "1/K")}
          ${field("alphaZPerK", "CTE Z", 0.00011, 0.000001, "1/K")}
          ${field("youngsModulusMpa", "Young's modulus", 2400, 10, "MPa")}
          ${field("poissonRatio", "Poisson ratio", 0.35, 0.01)}
          ${field("referenceStrengthMpa", "Reference strength", 45, 0.5, "MPa")}
          ${field("serviceLimitC", "Structural model limit", 50, 0.5, "°C")}
          ${field("infillPct", "Fallback infill", 25, 1, "%")}
          ${field("perimeters", "Perimeters", 2, 1)}
          ${field("lineWidth", "Line width", 0.45, 0.01, "mm")}
          ${field("topBottomLayers", "Top/bottom layers", 5, 1)}
          ${field("layerHeight", "Layer height", 0.2, 0.01, "mm")}
        </div>
      </details>

      <div class="ac-actions">
        <button id="ac-run" class="primary">Calculate Complete Oven Cycle</button>
        <button id="ac-cancel" disabled>Cancel</button>
      </div>
      <div id="ac-progress" class="ac-progress" hidden>
        <div class="ac-progress-head"><b id="ac-progress-phase">Preparing</b><span id="ac-progress-time">0% · 0:00</span></div>
        <progress id="ac-progress-bar" max="100" value="0"></progress>
        <div id="ac-progress-detail" class="ac-status dim"></div>
      </div>
      <div id="ac-status" class="ac-status dim">Load a model, enter the actual oven temperature, then calculate.</div>

      <div id="ac-results" class="ac-results">
        <div id="ac-kpis" class="ac-kpis"></div>
        <canvas id="ac-heatmap" width="720" height="360"></canvas>
        <div id="ac-result-note" class="ac-status"></div>
        <div class="ac-report-actions">
          <button id="ac-show3d">Show Heating Result in 3D</button>
          <button id="ac-copy-report">Copy Cycle Report</button>
        </div>
      </div>

      <details>
        <summary>Spool-specific dimensional calibration</summary>
        <div class="ac-lead" style="margin-top:7px">Measure a printed coupon before and after annealing. The saved profile calculates the XYZ print scale required for the expected post-anneal dimension.</div>
        <div class="ac-grid">
          <label class="ac-field"><span>Profile name</span><input id="ac-calibrationName" type="text" value="My spool"></label>
          <label class="ac-select"><span>Saved calibration</span><select id="ac-calibrationProfile"><option value="">None</option></select></label>
          ${field("beforeX", "Before X", 100, 0.01, "mm")}
          ${field("afterX", "After X", 100, 0.01, "mm")}
          ${field("beforeY", "Before Y", 100, 0.01, "mm")}
          ${field("afterY", "After Y", 100, 0.01, "mm")}
          ${field("beforeZ", "Before Z", 100, 0.01, "mm")}
          ${field("afterZ", "After Z", 100, 0.01, "mm")}
        </div>
        <div class="ac-report-actions">
          <button id="ac-calculate-calibration">Calculate XYZ Compensation</button>
          <button id="ac-save-calibration">Save Calibration Profile</button>
        </div>
        <div id="ac-calibration-result" class="ac-cal-result dim">No calibration calculated.</div>
      </details>
    `;
    return group;
  }

  function applyPreset(name) {
    const preset = PRESETS[name];
    if (!preset) return;
    for (const [key, presetValue] of Object.entries(preset)) {
      if (key === "status") continue;
      setValue(key, presetValue);
    }
    const status = document.getElementById("ac-status");
    if (status) {
      status.className = `ac-status ${preset.status === "literature-seeded" ? "dim" : "ac-warning"}`;
      status.textContent = `${preset.materialName} profile: ${preset.status}. Confirm the filament manufacturer's temperature range and physically validate a coupon.`;
    }
  }

  function syncOvenMode() {
    const mode = value("ovenMode");
    const coefficient = input("convectionWm2K");
    if (mode === "fan") coefficient.value = "18";
    if (mode === "natural") coefficient.value = "8";
    coefficient.disabled = mode !== "custom";
  }

  function collectCommon() {
    const ovenTemperatureC = finite(value("ovenTemperatureC"), "oven temperature", -100, 300);
    const initialTemperatureC = finite(value("initialTemperatureC"), "starting temperature", -100, 250);
    const roomTemperatureC = finite(value("roomTemperatureC"), "cooling ambient", -100, 250);
    const targetToleranceC = finite(value("targetToleranceC"), "core tolerance", 0.1, 30);
    const readinessTemperatureC = ovenTemperatureC - targetToleranceC;
    const handlingTemperatureC = finite(value("handlingTemperatureC"), "handling target", -100, 250);
    const soakMinutes = finite(value("soakMinutes"), "soak time", 0, 24 * 60);
    const timeStepSeconds = finite(value("timeStepSeconds"), "time step", 0.1, 86400);
    const maxHeatingSeconds = finite(value("maxHeatingHours"), "maximum heating time", 0.01, 168) * 3600;
    const maxCoolingSeconds = finite(value("maxCoolingHours"), "maximum cooling time", 0.01, 168) * 3600;
    if (readinessTemperatureC <= initialTemperatureC) {
      throw new Error("The core target must be higher than the starting part temperature.");
    }
    if (handlingTemperatureC <= roomTemperatureC) {
      throw new Error("The handling target must be above the cooling ambient.");
    }
    if (handlingTemperatureC >= ovenTemperatureC) {
      throw new Error("The handling target must be below the oven temperature.");
    }
    const heatSteps = Math.ceil(maxHeatingSeconds / timeStepSeconds);
    const coolSteps = Math.ceil(maxCoolingSeconds / timeStepSeconds);
    if (heatSteps > 2000 || coolSteps > 2000) {
      throw new Error("Each stage is limited to 2000 implicit steps. Increase the time step or reduce the maximum search duration.");
    }
    return {
      materialName: value("preset"), ovenTemperatureC, initialTemperatureC,
      roomTemperatureC, targetToleranceC, readinessTemperatureC,
      handlingTemperatureC, soakMinutes, timeStepSeconds, maxHeatingSeconds,
      maxCoolingSeconds, simulateCooling: checked("simulateCooling"),
      convectionWm2K: finite(value("convectionWm2K"), "oven convection", 0, 100000),
      coolingConvectionWm2K: finite(value("coolingConvectionWm2K"), "cooling convection", 0, 100000),
      emissivity: finite(value("emissivity"), "emissivity", 0, 1),
      conductivityXWmK: finite(value("conductivityXWmK"), "X conductivity", 0.005, 1000),
      conductivityYWmK: finite(value("conductivityYWmK"), "Y conductivity", 0.005, 1000),
      conductivityZWmK: finite(value("conductivityZWmK"), "Z conductivity", 0.005, 1000),
      densityKgM3: finite(value("densityKgM3"), "density", 50, 30000),
      specificHeatJkgK: finite(value("specificHeatJkgK"), "specific heat", 50, 10000),
      conductivityExponent: finite(value("conductivityExponent"), "conductivity exponent", 0.25, 4),
      alphaXyPerK: finite(value("alphaXyPerK"), "XY CTE", 0, 0.01),
      alphaZPerK: finite(value("alphaZPerK"), "Z CTE", 0, 0.01),
      youngsModulusMpa: finite(value("youngsModulusMpa"), "Young's modulus", 1, 1e7),
      poissonRatio: finite(value("poissonRatio"), "Poisson ratio", -0.49, 0.49),
      referenceStrengthMpa: finite(value("referenceStrengthMpa"), "reference strength", 0.1, 1e6),
      serviceLimitC: finite(value("serviceLimitC"), "structural model limit", -50, 500),
      densityAware: checked("densityAware"),
      infillPct: finite(value("infillPct"), "fallback infill", 1, 100),
      perimeters: integer(value("perimeters"), "perimeters", 0, 20),
      lineWidth: finite(value("lineWidth"), "line width", 0.05, 5),
      topBottomLayers: integer(value("topBottomLayers"), "top/bottom layers", 0, 20),
      layerHeight: finite(value("layerHeight"), "layer height", 0.04, 0.6),
    };
  }

  function thermalOptions(common, stage) {
    const heating = stage === "heating";
    return {
      mode: "transient",
      materialName: common.materialName,
      conductivityXWmK: common.conductivityXWmK,
      conductivityYWmK: common.conductivityYWmK,
      conductivityZWmK: common.conductivityZWmK,
      densityKgM3: common.densityKgM3,
      specificHeatJkgK: common.specificHeatJkgK,
      conductivityExponent: common.conductivityExponent,
      alphaXyPerK: common.alphaXyPerK,
      alphaZPerK: common.alphaZPerK,
      youngsModulusMpa: common.youngsModulusMpa,
      poissonRatio: common.poissonRatio,
      referenceStrengthMpa: common.referenceStrengthMpa,
      strengthDensityExponent: 1.5,
      referenceTemperatureC: 23,
      serviceLimitC: common.serviceLimitC,
      modulusFloorFraction: 0.05,
      strengthFloorFraction: 0.05,
      ambientTemperatureC: heating ? common.ovenTemperatureC : common.roomTemperatureC,
      initialTemperatureC: heating ? common.initialTemperatureC : common.ovenTemperatureC,
      cooledTemperatureC: heating ? common.ovenTemperatureC : common.roomTemperatureC,
      convectionWm2K: heating ? common.convectionWm2K : common.coolingConvectionWm2K,
      emissivity: common.emissivity,
      heatedFace: "zmax",
      cooledFace: "zmin",
      heatPowerW: 0,
      volumetricPowerW: 0,
      durationSeconds: heating ? common.maxHeatingSeconds : common.maxCoolingSeconds,
      timeStepSeconds: common.timeStepSeconds,
      fixedSurfaceEnabled: false,
      readinessTemperatureC: heating ? common.readinessTemperatureC : common.handlingTemperatureC,
      readinessHoldSeconds: heating ? common.soakMinutes * 60 : 0,
      readinessCooling: !heating,
      stopWhenReady: true,
      freeExpansion: true,
      densityAware: common.densityAware,
      infillPct: common.infillPct,
      exponent: 1.5,
      coeff: 1,
      perimeters: common.perimeters,
      lineWidth: common.lineWidth,
      topBottomLayers: common.topBottomLayers,
      layerHeight: common.layerHeight,
    };
  }

  function saveDraft(common) {
    try {
      const draft = {};
      document.querySelectorAll(`#${GROUP_ID} input, #${GROUP_ID} select`).forEach((element) => {
        if (!element.id?.startsWith("ac-") || element.id.includes("calibration") || element.id.startsWith("ac-before") || element.id.startsWith("ac-after")) return;
        draft[element.id.slice(3)] = element.type === "checkbox" ? element.checked : element.value;
      });
      draft.lastValidated = common;
      localStorage.setItem(STORAGE_KEY, JSON.stringify(draft));
    } catch (_) { /* optional */ }
  }

  function restoreDraft() {
    let draft;
    try { draft = JSON.parse(localStorage.getItem(STORAGE_KEY) || "null"); } catch (_) { return; }
    if (!draft || typeof draft !== "object") return;
    for (const [key, draftValue] of Object.entries(draft)) {
      if (key === "lastValidated") continue;
      const element = document.getElementById(`ac-${key}`);
      if (!element) continue;
      if (element.type === "checkbox") element.checked = Boolean(draftValue);
      else element.value = String(draftValue);
    }
  }
