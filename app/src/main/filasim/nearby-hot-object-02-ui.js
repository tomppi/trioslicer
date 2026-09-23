  function createGroup() {
    const group = document.createElement("div");
    group.id = GROUP_ID;
    group.className = "group";
    group.innerHTML = `
      <div class="ti-title"><strong>Nearby Hot Object</strong><span class="dim small">3D temperature & FEA</span></div>
      <div class="dim small">Select the model point nearest a hot engine part, fire, exhaust, or heater. The source is placed outward along that surface normal. The final model is coloured by its calculated temperature.</div>
      <div class="ti-grid" style="margin-top:8px">
        <label class="ti-select"><span>Analysis</span><select id="ti-mode"><option value="steady">Steady state</option><option value="transient">Temperature after a set time</option></select></label>
        <label class="ti-select"><span>Material preset</span><select id="ti-preset"><option>PLA</option><option>PETG</option><option>ABS</option></select></label>
      </div>
      <div class="ti-pick">
        <button id="ti-pick-source" type="button" class="primary">Select nearest point on model</button>
        <div id="ti-source-selection" class="ti-status dim">No source point selected.</div>
      </div>
      <div class="ti-grid">
        ${field("sourceTemperatureC", "Hot object temperature (°C)", 300, 1)}
        ${field("sourceGapMm", "Surface-to-surface distance (mm)", 25, 0.1)}
        ${field("sourceDiameterMm", "Effective hot object diameter (mm)", 50, 0.1)}
        ${field("sourceEmissivity", "Hot object emissivity", 0.9, 0.01)}
        ${field("sourcePartConductivityTSlopePerK", "Conductivity vs temperature (%/°C)", 0.4, 0.01)}
        ${field("sourcePartEmissivityTSlopePerK", "Emissivity vs temperature (%/°C)", 0.05, 0.01)}
        ${field("ambientTemperatureC", "Ambient air temperature (°C)", 23, 0.1)}
        ${field("convectionWm2K", "Air convection (W/m²K)", 8, 0.1)}
        ${field("emissivity", "Printed surface emissivity", 0.9, 0.01)}
        ${field("initialTemperatureC", "Initial part temperature (°C)", 23, 0.1)}
        ${field("durationSeconds", "Exposure time (s)", 600, 1)}
        ${field("timeStepSeconds", "Time step (s)", 10, 0.1)}
      </div>
      ${checkbox("useFixedTemperatureSurface", "Part is mounted to a fixed-temperature surface", false)}
      <div id="ti-fixed-fields" class="ti-grid ti-hidden">
        <label class="ti-select"><span>Mounted global plane</span><select id="ti-cooledFace">${faceOptions("zmin")}</select></label>
        ${field("cooledTemperatureC", "Mount temperature (°C)", 23, 0.1)}
      </div>
      <div id="ti-source-preflight" class="ti-status dim">Select a point to calculate source visibility and absorbed heat.</div>
      ${checkbox("densityAware", "Use optimized Smart Infill density when available", true)}
      ${checkbox("freeExpansion", "Free thermal expansion (3-2-1 grounding); turn off to use filaSim supports", true)}
      <details><summary>Material and print-property inputs</summary><div class="ti-grid" style="margin-top:7px">
        ${field("conductivityXWmK", "Conductivity X (W/mK)", 0.18, 0.001)}
        ${field("conductivityYWmK", "Conductivity Y (W/mK)", 0.18, 0.001)}
        ${field("conductivityZWmK", "Conductivity Z (W/mK)", 0.13, 0.001)}
        ${field("densityKgM3", "Density (kg/m³)", 1240, 1)}
        ${field("specificHeatJkgK", "Specific heat (J/kgK)", 1800, 1)}
        ${field("conductivityExponent", "Infill conductivity exponent", 1, 0.05)}
        ${field("alphaXyPerK", "CTE XY (1/K)", 0.000096, 0.000001)}
        ${field("alphaZPerK", "CTE Z (1/K)", 0.00011, 0.000001)}
        ${field("youngsModulusMpa", "Young's modulus at reference (MPa)", 2400, 10)}
        ${field("poissonRatio", "Poisson ratio", 0.35, 0.01)}
        ${field("referenceStrengthMpa", "Reference strength (MPa)", 45, 0.5)}
        ${field("strengthDensityExponent", "Infill strength exponent", 1.5, 0.05)}
        ${field("referenceTemperatureC", "Property reference (°C)", 23, 0.1)}
        ${field("serviceLimitC", "Material service limit (°C)", 50, 0.5)}
        ${field("modulusFloorFraction", "Modulus floor fraction", 0.05, 0.01)}
        ${field("strengthFloorFraction", "Strength floor fraction", 0.05, 0.01)}
        ${field("infillPct", "Fallback infill (%)", 25, 1)}
        ${field("exponent", "Stiffness density exponent", 1.5, 0.05)}
        ${field("coeff", "Stiffness density coefficient", 1, 0.05)}
        ${field("perimeters", "Perimeters", 2, 1)}
        ${field("lineWidth", "Line width (mm)", 0.45, 0.01)}
        ${field("topBottomLayers", "Top/bottom layers", 5, 1)}
        ${field("layerHeight", "Layer height (mm)", 0.2, 0.01)}
      </div></details>
      <div class="ti-actions"><button id="ti-run" class="primary">Calculate temperature</button><button id="ti-save" disabled>Save report</button></div>
      <div id="ti-status" class="ti-status dim">Select the nearest model point, set the hot object, then calculate.</div>
      <div id="ti-results" class="ti-results"><div id="ti-kpis" class="ti-kpis"></div><div id="ti-result-note" class="ti-status dim"></div></div>
    `;
    return group;
  }

  function applyPreset(name) {
    const preset = PRESETS[name];
    if (!preset) return;
    Object.entries(preset).forEach(([key, presetValue]) => { if (key !== "materialName") setValue(key, presetValue); });
    setValue("referenceTemperatureC", 23);
    invalidate("Material properties changed; calculate again.");
  }

  function sourceMarkerDetail() {
    if (!selected) return null;
    return { target: selected.point, normal: selected.normal,
      gapMm: finite(value("sourceGapMm"), "source distance", 0, 100000),
      diameterMm: finite(value("sourceDiameterMm"), "source diameter", 0.1, 100000) };
  }
  function updateMarker() {
    try {
      const detail = sourceMarkerDetail();
      window.dispatchEvent(new CustomEvent(MARKER_EVENT, { detail }));
    } catch (_) { /* validation is shown on run */ }
  }
  function renderSelection() {
    const box = document.getElementById("ti-source-selection");
    if (!box) return;
    if (!selected) { box.textContent = "No source point selected."; return; }
    const p = selected.point.map((v) => Number(v).toFixed(2)).join(", ");
    const n = selected.normal.map((v) => Number(v).toFixed(3)).join(", ");
    box.textContent = `Nearest point: ${p} mm · outward normal: ${n}`;
  }

  function collectOptions() {
    if (!selected || !Array.isArray(selected.point) || !Array.isArray(selected.normal)) {
      throw new Error("Select the model point nearest the hot object first.");
    }
    const presetName = value("preset");
    const preset = PRESETS[presetName];
    const mode = value("mode");
    const options = {
      mode,
      materialName: preset?.materialName || presetName,
      conductivityXWmK: finite(value("conductivityXWmK"), "X conductivity", 0.005, 1000),
      conductivityYWmK: finite(value("conductivityYWmK"), "Y conductivity", 0.005, 1000),
      conductivityZWmK: finite(value("conductivityZWmK"), "Z conductivity", 0.005, 1000),
      densityKgM3: finite(value("densityKgM3"), "density", 50, 30000),
      specificHeatJkgK: finite(value("specificHeatJkgK"), "specific heat", 50, 10000),
      conductivityExponent: finite(value("conductivityExponent"), "conductivity exponent", 0.25, 4),
      alphaXyPerK: finite(value("alphaXyPerK"), "XY CTE", 0, 0.01),
      alphaZPerK: finite(value("alphaZPerK"), "Z CTE", 0, 0.01),
      youngsModulusMpa: finite(value("youngsModulusMpa"), "Young's modulus", 1, 10_000_000),
      poissonRatio: finite(value("poissonRatio"), "Poisson ratio", -0.49, 0.49),
      referenceStrengthMpa: finite(value("referenceStrengthMpa"), "reference strength", 0.1, 10000),
      strengthDensityExponent: finite(value("strengthDensityExponent"), "strength exponent", 0.5, 4),
      referenceTemperatureC: finite(value("referenceTemperatureC"), "reference temperature", -200, 1000),
      serviceLimitC: finite(value("serviceLimitC"), "service limit", -100, 1000),
      modulusFloorFraction: finite(value("modulusFloorFraction"), "modulus floor", 0.001, 1),
      strengthFloorFraction: finite(value("strengthFloorFraction"), "strength floor", 0.001, 1),
      ambientTemperatureC: finite(value("ambientTemperatureC"), "ambient temperature", -200, 1000),
      initialTemperatureC: finite(value("initialTemperatureC"), "initial temperature", -200, 1000),
      cooledTemperatureC: finite(value("cooledTemperatureC"), "mount temperature", -200, 1000),
      convectionWm2K: finite(value("convectionWm2K"), "convection", 0, 100000),
      emissivity: finite(value("emissivity"), "printed surface emissivity", 0, 1),
      sourceTargetMm: selected.point.map(Number),
      sourceNormal: selected.normal.map(Number),
      sourceGapMm: finite(value("sourceGapMm"), "surface distance", 0, 100000),
      sourceDiameterMm: finite(value("sourceDiameterMm"), "hot object diameter", 0.1, 100000),
      sourceTemperatureC: finite(value("sourceTemperatureC"), "hot object temperature", -200, 2000),
      sourceEmissivity: finite(value("sourceEmissivity"), "hot object emissivity", 0, 1),
      sourcePartConductivityTSlopePerK: finite(value("sourcePartConductivityTSlopePerK"), "conductivity temp slope", -1, 1),
      sourcePartEmissivityTSlopePerK: finite(value("sourcePartEmissivityTSlopePerK"), "emissivity temp slope", -1, 1),
      useFixedTemperatureSurface: checked("useFixedTemperatureSurface"),
      heatedFace: "zmax",
      cooledFace: value("cooledFace"),
      heatPowerW: 0,
      volumetricPowerW: 0,
      durationSeconds: finite(value("durationSeconds"), "exposure time", 0.01, 31_536_000),
      timeStepSeconds: finite(value("timeStepSeconds"), "time step", 0.0001, 86400),
      freeExpansion: checked("freeExpansion"), densityAware: checked("densityAware"),
      infillPct: finite(value("infillPct"), "infill", 1, 100),
      exponent: finite(value("exponent"), "stiffness exponent", 1, 3.5),
      coeff: finite(value("coeff"), "stiffness coefficient", 0.05, 2),
      perimeters: integer(value("perimeters"), "perimeters", 0, 20),
      lineWidth: finite(value("lineWidth"), "line width", 0.05, 5),
      topBottomLayers: integer(value("topBottomLayers"), "top/bottom layers", 0, 20),
      layerHeight: finite(value("layerHeight"), "layer height", 0.04, 0.6),
    };
    if (options.serviceLimitC <= options.referenceTemperatureC) {
      throw new Error("The material service limit must exceed the property reference temperature.");
    }
    if (mode === "transient") {
      const steps = Math.ceil(options.durationSeconds / options.timeStepSeconds);
      if (steps > 2000) throw new Error("The transient run exceeds 2000 steps; increase the time step.");
    }
    return options;
  }

  function saveDraft(options) {
    try { localStorage.setItem(STORAGE_KEY, JSON.stringify({ ...options, selected })); } catch (_) { /* optional */ }
  }
  function restoreDraft() {
    let draft;
    try { draft = JSON.parse(localStorage.getItem(STORAGE_KEY) || "null"); } catch (_) { return; }
    if (!draft || typeof draft !== "object") return;
    if (draft.selected?.point?.length === 3 && draft.selected?.normal?.length === 3) selected = draft.selected;
    Object.entries(draft).forEach(([key, draftValue]) => {
      const element = document.getElementById(`ti-${key}`);
      if (!element || key === "selected") return;
      if (element.type === "checkbox") element.checked = Boolean(draftValue);
      else if (!Array.isArray(draftValue)) element.value = String(draftValue);
    });
    renderSelection(); updateMarker(); toggleFixedFields();
  }

  function invalidate(message) {
    analysisEpoch += 1;
    latest = null;
    window.dispatchEvent(new CustomEvent(THERMAL_CLEAR_EVENT));
    const results = document.getElementById("ti-results"); if (results) results.classList.remove("ready");
    const save = document.getElementById("ti-save"); if (save) save.disabled = true;
    const status = document.getElementById("ti-status");
    if (status && message) { status.className = "ti-status dim"; status.textContent = message; }
  }

