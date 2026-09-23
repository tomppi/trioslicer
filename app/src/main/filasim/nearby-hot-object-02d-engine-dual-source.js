  // Generic engine-bay operating presets and one independently positioned
  // secondary turbo/exhaust source. Values are editable engineering starting
  // assumptions, never claims about a specific vehicle.
  const SOURCE_TYPE_PRESETS = Object.freeze({
    custom: Object.freeze({ label: "Custom hot object" }),
    engine_surface: Object.freeze({ label: "Engine block / cylinder head", temperatureC: 105, endTemperatureC: 105, diameterMm: 180, emissivity: 0.82 }),
    exhaust_manifold_normal: Object.freeze({ label: "Exhaust manifold — normal load", temperatureC: 500, endTemperatureC: 500, diameterMm: 90, emissivity: 0.85 }),
    exhaust_manifold_high: Object.freeze({ label: "Exhaust manifold — high load", temperatureC: 700, endTemperatureC: 700, diameterMm: 100, emissivity: 0.88 }),
    turbo_moderate: Object.freeze({ label: "Turbo hot side — moderate load", temperatureC: 600, endTemperatureC: 600, diameterMm: 110, emissivity: 0.86 }),
    turbo_high: Object.freeze({ label: "Turbo hot side — high load", temperatureC: 850, endTemperatureC: 850, diameterMm: 120, emissivity: 0.88 }),
    turbo_extreme: Object.freeze({ label: "Turbo hot side — extreme", temperatureC: 950, endTemperatureC: 950, diameterMm: 125, emissivity: 0.9 }),
    turbo_compressor: Object.freeze({ label: "Turbo compressor housing", temperatureC: 150, endTemperatureC: 150, diameterMm: 120, emissivity: 0.75 }),
    catalyst: Object.freeze({ label: "Catalytic converter", temperatureC: 650, endTemperatureC: 650, diameterMm: 140, emissivity: 0.88 }),
  });

  const ENGINE_SCENARIO_PRESETS = Object.freeze({
    custom: Object.freeze({ label: "Custom" }),
    petrol_normal: Object.freeze({
      label: "Petrol engine — normal driving", environmentMode: "engine_running",
      ambientTemperatureC: 25, initialTemperatureC: 25, convectionWm2K: 12,
      enclosureInitialAirTemperatureC: 65, enclosureWallStartTemperatureC: 90,
      enclosureWallEndTemperatureC: 90, enclosureWallRampSeconds: 0,
      enclosureVentilationAch: 18, enclosureUaWPerK: 12, enclosureInternalHeatW: 60,
      sourceType: "engine_surface", sourceTemperatureC: 105, sourceEndTemperatureC: 105,
      sourceRampSeconds: 0, source2Enabled: true, source2Type: "exhaust_manifold_normal",
      source2TemperatureC: 500, source2EndTemperatureC: 500, source2RampSeconds: 0,
    }),
    petrol_sustained: Object.freeze({
      label: "Petrol engine — sustained load", environmentMode: "engine_running",
      ambientTemperatureC: 30, initialTemperatureC: 35, convectionWm2K: 16,
      enclosureInitialAirTemperatureC: 85, enclosureWallStartTemperatureC: 130,
      enclosureWallEndTemperatureC: 145, enclosureWallRampSeconds: 600,
      enclosureVentilationAch: 25, enclosureUaWPerK: 16, enclosureInternalHeatW: 120,
      sourceType: "engine_surface", sourceTemperatureC: 120, sourceEndTemperatureC: 125,
      sourceRampSeconds: 600, source2Enabled: true, source2Type: "exhaust_manifold_high",
      source2TemperatureC: 700, source2EndTemperatureC: 800, source2RampSeconds: 600,
    }),
    petrol_turbo_high: Object.freeze({
      label: "Petrol turbo — high load", environmentMode: "engine_running",
      ambientTemperatureC: 30, initialTemperatureC: 40, convectionWm2K: 18,
      enclosureInitialAirTemperatureC: 95, enclosureWallStartTemperatureC: 145,
      enclosureWallEndTemperatureC: 175, enclosureWallRampSeconds: 600,
      enclosureVentilationAch: 28, enclosureUaWPerK: 18, enclosureInternalHeatW: 160,
      sourceType: "engine_surface", sourceTemperatureC: 125, sourceEndTemperatureC: 130,
      sourceRampSeconds: 600, source2Enabled: true, source2Type: "turbo_high",
      source2TemperatureC: 850, source2EndTemperatureC: 950, source2RampSeconds: 600,
    }),
    diesel_normal: Object.freeze({
      label: "Diesel turbo — normal load", environmentMode: "engine_running",
      ambientTemperatureC: 25, initialTemperatureC: 30, convectionWm2K: 14,
      enclosureInitialAirTemperatureC: 70, enclosureWallStartTemperatureC: 105,
      enclosureWallEndTemperatureC: 115, enclosureWallRampSeconds: 600,
      enclosureVentilationAch: 20, enclosureUaWPerK: 14, enclosureInternalHeatW: 80,
      sourceType: "engine_surface", sourceTemperatureC: 105, sourceEndTemperatureC: 110,
      sourceRampSeconds: 600, source2Enabled: true, source2Type: "turbo_moderate",
      source2TemperatureC: 550, source2EndTemperatureC: 650, source2RampSeconds: 600,
    }),
    shutdown_heat_soak: Object.freeze({
      label: "Post-shutdown turbo heat soak", environmentMode: "engine_heat_soak",
      ambientTemperatureC: 25, initialTemperatureC: 60, convectionWm2K: 8,
      enclosureInitialAirTemperatureC: 60, enclosureWallStartTemperatureC: 85,
      enclosureWallEndTemperatureC: 135, enclosureWallRampSeconds: 900,
      enclosureVentilationAch: 1.5, enclosureUaWPerK: 8, enclosureInternalHeatW: 0,
      sourceType: "engine_surface", sourceTemperatureC: 115, sourceEndTemperatureC: 90,
      sourceRampSeconds: 1800, source2Enabled: true, source2Type: "turbo_high",
      source2TemperatureC: 750, source2EndTemperatureC: 250, source2RampSeconds: 1800,
      durationSeconds: 1800, timeStepSeconds: 30,
    }),
    conservative_worst_case: Object.freeze({
      label: "Conservative engine-bay worst case", environmentMode: "engine_running",
      ambientTemperatureC: 40, initialTemperatureC: 50, convectionWm2K: 20,
      enclosureInitialAirTemperatureC: 110, enclosureWallStartTemperatureC: 170,
      enclosureWallEndTemperatureC: 200, enclosureWallRampSeconds: 600,
      enclosureVentilationAch: 12, enclosureUaWPerK: 20, enclosureInternalHeatW: 220,
      sourceType: "engine_surface", sourceTemperatureC: 130, sourceEndTemperatureC: 130,
      sourceRampSeconds: 0, source2Enabled: true, source2Type: "turbo_extreme",
      source2TemperatureC: 950, source2EndTemperatureC: 950, source2RampSeconds: 0,
    }),
  });

  function sourceTemperatureAt(startC, endC, rampSeconds, elapsedSeconds) {
    const ramp = Math.max(0, Number(rampSeconds));
    if (ramp <= 0) return Number(startC);
    const fraction = Math.min(1, Math.max(0, Number(elapsedSeconds) / ramp));
    return Number(startC) + (Number(endC) - Number(startC)) * fraction;
  }

  function sourceSelectOptions() {
    return Object.entries(SOURCE_TYPE_PRESETS).map(([key, preset]) =>
      `<option value="${key}">${preset.label}</option>`).join("");
  }

  function engineScenarioOptions() {
    return Object.entries(ENGINE_SCENARIO_PRESETS).map(([key, preset]) =>
      `<option value="${key}">${preset.label}</option>`).join("");
  }

  function applySourceType(prefix, name) {
    const preset = SOURCE_TYPE_PRESETS[name];
    if (!preset || name === "custom") return;
    setValue(`${prefix}TemperatureC`, preset.temperatureC);
    setValue(`${prefix}EndTemperatureC`, preset.endTemperatureC);
    setValue(`${prefix}DiameterMm`, preset.diameterMm);
    setValue(`${prefix}Emissivity`, preset.emissivity);
    setValue(`${prefix}RampSeconds`, 0);
  }

  function applyEngineScenario(name) {
    const preset = ENGINE_SCENARIO_PRESETS[name];
    if (!preset || name === "custom") return;
    Object.entries(preset).forEach(([key, presetValue]) => {
      if (key !== "label") setValue(key, presetValue);
    });
    const enabled = document.getElementById("ti-source2Enabled");
    if (enabled) enabled.checked = Boolean(preset.source2Enabled);
    applyEnvironmentPreset(preset.environmentMode);
    syncSource2Ui();
    syncEnvironmentUi();
  }

  let secondarySelected = null;
  let pendingPickSource = 1;

  function secondaryMarkerDetail() {
    if (!secondarySelected) return null;
    return {
      target: secondarySelected.point,
      normal: secondarySelected.normal,
      gapMm: finite(value("source2GapMm"), "secondary source distance", 0, 100000),
      diameterMm: finite(value("source2DiameterMm"), "secondary source diameter", 0.1, 100000),
    };
  }

  function showSecondaryMarker() {
    try {
      window.dispatchEvent(new CustomEvent(MARKER_EVENT, { detail: secondaryMarkerDetail() }));
    } catch (_) { /* run validation reports input errors */ }
  }

  function renderSecondarySelection() {
    const box = document.getElementById("ti-source2-selection");
    if (!box) return;
    if (!secondarySelected) {
      box.textContent = "No turbo/exhaust source point selected.";
      return;
    }
    const p = secondarySelected.point.map((number) => Number(number).toFixed(2)).join(", ");
    const n = secondarySelected.normal.map((number) => Number(number).toFixed(3)).join(", ");
    box.textContent = `Second source point: ${p} mm · outward normal: ${n}`;
  }

  function syncSource2Ui() {
    const enabled = Boolean(document.getElementById("ti-source2Enabled")?.checked);
    document.getElementById("ti-source2-fields")?.classList.toggle("ti-hidden", !enabled);
  }

  const dualCreateGroupBase = createGroup;
  createGroup = function createGroupWithEnginePresetsAndSecondSource() {
    const group = dualCreateGroupBase();
    const firstGrid = group.querySelector(".ti-grid");
    firstGrid?.insertAdjacentHTML("beforeend", `
      <label class="ti-select"><span>Engine operating scenario</span><select id="ti-engineScenario">${engineScenarioOptions()}</select></label>
    `);
    const pick = group.querySelector(".ti-pick");
    pick?.insertAdjacentHTML("beforebegin", `
      <div class="ti-grid" style="margin-top:8px">
        <label class="ti-select"><span>Primary source type</span><select id="ti-sourceType">${sourceSelectOptions()}</select></label>
        ${field("sourceEndTemperatureC", "Primary final temperature (°C)", 300, 1)}
        ${field("sourceRampSeconds", "Primary temperature ramp time (s)", 0, 1)}
      </div>
    `);
    const fixed = group.querySelector("#ti-fixed-fields")?.previousElementSibling;
    fixed?.insertAdjacentHTML("beforebegin", `
      ${checkbox("source2Enabled", "Add turbo / exhaust heat source", false)}
      <div id="ti-source2-fields" class="ti-hidden">
        <div class="ti-pick">
          <label class="ti-select"><span>Second source type</span><select id="ti-source2Type">${sourceSelectOptions()}</select></label>
          <button id="ti-pick-source2" type="button" class="primary">Select nearest point for turbo / exhaust</button>
          <div id="ti-source2-selection" class="ti-status dim">No turbo/exhaust source point selected.</div>
        </div>
        <div class="ti-grid">
          ${field("source2TemperatureC", "Second source initial temperature (°C)", 600, 1)}
          ${field("source2EndTemperatureC", "Second source final temperature (°C)", 600, 1)}
          ${field("source2RampSeconds", "Second source temperature ramp time (s)", 0, 1)}
          ${field("source2GapMm", "Second source surface distance (mm)", 40, 0.1)}
          ${field("source2DiameterMm", "Second source effective diameter (mm)", 110, 0.1)}
          ${field("source2Emissivity", "Second source emissivity", 0.86, 0.01)}
        </div>
      </div>
    `);
    const primaryTemp = group.querySelector("#ti-sourceTemperatureC")?.closest("label")?.querySelector("span");
    if (primaryTemp) primaryTemp.textContent = "Primary initial temperature (°C)";
    return group;
  };

  const dualCollectOptionsBase = collectOptions;
  collectOptions = function collectOptionsWithSecondSource() {
    const options = dualCollectOptionsBase();
    options.engineScenario = value("engineScenario");
    options.sourceType = value("sourceType");
    options.sourceEndTemperatureC = finite(value("sourceEndTemperatureC"), "primary final temperature", -200, 2000);
    options.sourceRampSeconds = finite(value("sourceRampSeconds"), "primary temperature ramp time", 0, 31_536_000);
    options.source2Enabled = checked("source2Enabled");
    options.source2Type = value("source2Type");
    options.source2TemperatureC = finite(value("source2TemperatureC"), "second source initial temperature", -200, 2000);
    options.source2EndTemperatureC = finite(value("source2EndTemperatureC"), "second source final temperature", -200, 2000);
    options.source2RampSeconds = finite(value("source2RampSeconds"), "second source temperature ramp time", 0, 31_536_000);
    options.source2GapMm = finite(value("source2GapMm"), "second source distance", 0, 100000);
    options.source2DiameterMm = finite(value("source2DiameterMm"), "second source diameter", 0.1, 100000);
    options.source2Emissivity = finite(value("source2Emissivity"), "second source emissivity", 0, 1);
    options.source2PartEmissivity = options.sourcePartEmissivity;
    if (options.source2Enabled) {
      if (!secondarySelected?.point || !secondarySelected?.normal) {
        throw new Error("Select the model point nearest the turbo/exhaust source.");
      }
      options.source2TargetMm = secondarySelected.point.map(Number);
      options.source2Normal = secondarySelected.normal.map(Number);
    } else {
      options.source2TargetMm = [0, 0, 0];
      options.source2Normal = [0, 0, 1];
    }
    return options;
  };

  const dualSaveDraftBase = saveDraft;
  saveDraft = function saveDraftWithSecondSource(options) {
    dualSaveDraftBase(options);
    try {
      const draft = JSON.parse(localStorage.getItem(STORAGE_KEY) || "{}");
      draft.secondarySelected = secondarySelected;
      localStorage.setItem(STORAGE_KEY, JSON.stringify(draft));
    } catch (_) { /* optional */ }
  };

  const dualRestoreDraftBase = restoreDraft;
  restoreDraft = function restoreDraftWithSecondSource() {
    dualRestoreDraftBase();
    try {
      const draft = JSON.parse(localStorage.getItem(STORAGE_KEY) || "null");
      if (draft?.secondarySelected?.point?.length === 3 && draft?.secondarySelected?.normal?.length === 3) {
        secondarySelected = draft.secondarySelected;
      }
    } catch (_) { /* optional */ }
    renderSecondarySelection();
    syncSource2Ui();
  };

  const dualBindBase = bind;
  bind = function bindWithEnginePresetsAndSecondSource(group) {
    dualBindBase(group);
    group.querySelector("#ti-pick-source")?.addEventListener("click", () => { pendingPickSource = 1; });
    group.querySelector("#ti-pick-source2")?.addEventListener("click", () => {
      pendingPickSource = 2;
      window.dispatchEvent(new CustomEvent(PICK_MODE_EVENT, { detail: true }));
      const status = input("status");
      status.className = "ti-status dim";
      status.textContent = "Tap the model point nearest the turbo or exhaust source.";
    });
    group.querySelector("#ti-source2Enabled")?.addEventListener("change", () => {
      syncSource2Ui();
      invalidate("Second heat source changed; calculate again.");
    });
    group.querySelector("#ti-engineScenario")?.addEventListener("change", (event) => {
      applyEngineScenario(event.target.value);
      invalidate("Engine operating scenario changed; calculate again.");
    });
    group.querySelector("#ti-sourceType")?.addEventListener("change", (event) => {
      applySourceType("source", event.target.value);
      invalidate("Primary source preset changed; calculate again.");
    });
    group.querySelector("#ti-source2Type")?.addEventListener("change", (event) => {
      applySourceType("source2", event.target.value);
      showSecondaryMarker();
      invalidate("Second source preset changed; calculate again.");
    });
    for (const id of ["source2GapMm", "source2DiameterMm"]) {
      group.querySelector(`#ti-${id}`)?.addEventListener("change", showSecondaryMarker);
    }
  };

  window.addEventListener(PICK_EVENT, (event) => {
    if (pendingPickSource !== 2) return;
    const point = event?.detail?.point;
    const normal = event?.detail?.normal;
    if (!Array.isArray(point) || point.length !== 3 || !Array.isArray(normal) || normal.length !== 3) return;
    event.stopImmediatePropagation();
    secondarySelected = { point: point.map(Number), normal: normal.map(Number) };
    pendingPickSource = 1;
    renderSecondarySelection();
    showSecondaryMarker();
    invalidate("Turbo/exhaust source position selected; calculate the result.");
  });
  window.addEventListener(CLEAR_EVENT, () => {
    secondarySelected = null;
    renderSecondarySelection();
  });

  let sourceRampElapsedSeconds = 0;
  const dualStageOptionsBase = stageOptions;
  stageOptions = function stageOptionsWithSourceRamps(base, environment, boundary, durationSeconds, initialField, thermalOnly) {
    const options = dualStageOptionsBase(base, environment, boundary, durationSeconds, initialField, thermalOnly);
    const midpoint = sourceRampElapsedSeconds + durationSeconds * 0.5;
    sourceRampElapsedSeconds += durationSeconds;
    options.sourceTemperatureC = sourceTemperatureAt(base.sourceTemperatureC, base.sourceEndTemperatureC, base.sourceRampSeconds, midpoint);
    options.source2TemperatureC = sourceTemperatureAt(base.source2TemperatureC, base.source2EndTemperatureC, base.source2RampSeconds, midpoint);
    return options;
  };

  const dualTransientEnclosureBase = runTransientEnclosure;
  runTransientEnclosure = async function runTransientEnclosureWithSourceRamps(...args) {
    sourceRampElapsedSeconds = 0;
    return dualTransientEnclosureBase(...args);
  };

  function sourceTimelineEnabled(options) {
    return options.mode === "transient" && (
      Math.abs(options.sourceEndTemperatureC - options.sourceTemperatureC) > 1e-9
      || (options.source2Enabled && Math.abs(options.source2EndTemperatureC - options.source2TemperatureC) > 1e-9)
    );
  }

  async function runOpenAirSourceTimeline(options, status, requestFn) {
    const stages = planEnclosureStages(options.durationSeconds, options.timeStepSeconds);
    let elapsed = 0;
    let initialField = null;
    let finalData = null;
    let totalTimeSteps = 0;
    let totalIterations = 0;
    let peakTemperatureC = options.initialTemperatureC;
    let peakTimeSeconds = 0;
    const combinedHistory = [];
    const sourceEnergy = { source1J: 0, source2J: 0 };
    for (let index = 0; index < stages.length; index += 1) {
      const duration = stages[index];
      const midpoint = elapsed + duration * 0.5;
      const stage = {
        ...options,
        mode: "transient",
        durationSeconds: duration,
        timeStepSeconds: Math.min(options.timeStepSeconds, duration),
        initialTemperatureFieldC: initialField ? Array.from(initialField, Number) : null,
        sourceTemperatureC: sourceTemperatureAt(options.sourceTemperatureC, options.sourceEndTemperatureC, options.sourceRampSeconds, midpoint),
        source2TemperatureC: sourceTemperatureAt(options.source2TemperatureC, options.source2EndTemperatureC, options.source2RampSeconds, midpoint),
        thermalOnly: index + 1 < stages.length,
      };
      status.textContent = `Calculating source-temperature stage ${index + 1}/${stages.length} · source 1 ${format(stage.sourceTemperatureC, 0)} °C${stage.source2Enabled ? ` · source 2 ${format(stage.source2TemperatureC, 0)} °C` : ""}…`;
      const data = validateThermalData(await requestFn("thermalIntegrity", { opts: stage }), `Source stage ${index + 1}`);
      appendOffsetHistory(combinedHistory, data.history, elapsed);
      sourceEnergy.source1J += Number(data.stats.source1AbsorbedW || 0) * duration;
      sourceEnergy.source2J += Number(data.stats.source2AbsorbedW || 0) * duration;
      const stagePeak = Number(data.stats.peakTemperatureC ?? data.stats.maximumTemperatureC);
      if (stagePeak > peakTemperatureC) {
        peakTemperatureC = stagePeak;
        peakTimeSeconds = elapsed + Number(data.stats.peakTimeSeconds ?? duration);
      }
      totalTimeSteps += Number(data.stats.timeSteps || 0);
      totalIterations += Number(data.stats.iterations || 0);
      elapsed += duration;
      initialField = data.temperatures;
      finalData = data;
    }
    finalData.history = new Float64Array(combinedHistory);
    finalData.stats = {
      ...finalData.stats,
      finalTimeSeconds: options.durationSeconds,
      timeSteps: totalTimeSteps,
      iterations: totalIterations,
      peakTemperatureC,
      peakTimeSeconds,
    };
    finalData.sourceTimeline = {
      model: "two-source-piecewise-temperature-stage-coupling-v1",
      stages: stages.length,
      finalSource1TemperatureC: sourceTemperatureAt(options.sourceTemperatureC, options.sourceEndTemperatureC, options.sourceRampSeconds, options.durationSeconds),
      finalSource2TemperatureC: sourceTemperatureAt(options.source2TemperatureC, options.source2EndTemperatureC, options.source2RampSeconds, options.durationSeconds),
      energy: sourceEnergy,
    };
    return finalData;
  }

  let activeSourceTimeline = null;
  const dualRunAnalysisBase = runAnalysis;
  runAnalysis = async function runAnalysisWithSourceTimeline() {
    const nativeRequest = request;
    activeSourceTimeline = null;
    request = async function dualSourceAwareRequest(op, payload = {}) {
      if (op === "thermalIntegrity" && payload?.opts?.environmentMode === "open"
          && sourceTimelineEnabled(payload.opts)) {
        const result = await runOpenAirSourceTimeline(
          payload.opts,
          document.getElementById("ti-status"),
          nativeRequest,
        );
        activeSourceTimeline = result.sourceTimeline;
        return result;
      }
      return nativeRequest(op, payload);
    };
    try {
      await dualRunAnalysisBase();
    } finally {
      request = nativeRequest;
    }
  };

  const dualRenderResultsBase = renderResults;
  renderResults = function renderResultsWithSourceAttribution() {
    dualRenderResultsBase();
    if (!latest) return;
    if (activeSourceTimeline) latest.sourceTimeline = activeSourceTimeline;
    const stats = latest.stats;
    input("kpis").insertAdjacentHTML("beforeend", [
      kpi("Primary source absorbed heat", `${format(stats.source1AbsorbedW ?? stats.sourceAbsorbedW, 4)} W`),
      kpi("Turbo/exhaust absorbed heat", latest.options.source2Enabled ? `${format(stats.source2AbsorbedW, 4)} W` : "Disabled"),
      kpi("Primary source type", SOURCE_TYPE_PRESETS[latest.options.sourceType]?.label || "Custom"),
      kpi("Second source type", latest.options.source2Enabled ? (SOURCE_TYPE_PRESETS[latest.options.source2Type]?.label || "Custom") : "Disabled"),
    ].join(""));
    const note = input("result-note");
    const extra = [
      "Engine and turbo temperature presets are editable generic starting assumptions, not vehicle-specific measured values.",
      "Each source uses its own picked location, visibility field, distance, diameter, temperature and emissivity; their radiative boundary fluxes are summed per voxel face.",
    ];
    if (latest.sourceTimeline) {
      extra.push(`Temperature ramps used ${latest.sourceTimeline.stages} bounded coupling stages.`);
    }
    note.textContent += `\n${extra.join("\n")}`;
  };

  const dualCollectReportBase = collectReport;
  collectReport = function collectReportWithDualSources() {
    const report = dualCollectReportBase();
    report.schemaVersion = Math.max(3, Number(report.schemaVersion) || 0);
    report.sources = [
      {
        role: "primary", type: latest.options.sourceType,
        initialTemperatureC: latest.options.sourceTemperatureC,
        finalTemperatureC: latest.options.sourceEndTemperatureC,
        rampSeconds: latest.options.sourceRampSeconds,
        targetMm: latest.options.sourceTargetMm,
        normal: latest.options.sourceNormal,
        gapMm: latest.options.sourceGapMm,
        diameterMm: latest.options.sourceDiameterMm,
        emissivity: latest.options.sourceEmissivity,
        finalAbsorbedW: latest.stats.source1AbsorbedW,
      },
      latest.options.source2Enabled ? {
        role: "secondary", type: latest.options.source2Type,
        initialTemperatureC: latest.options.source2TemperatureC,
        finalTemperatureC: latest.options.source2EndTemperatureC,
        rampSeconds: latest.options.source2RampSeconds,
        targetMm: latest.options.source2TargetMm,
        normal: latest.options.source2Normal,
        gapMm: latest.options.source2GapMm,
        diameterMm: latest.options.source2DiameterMm,
        emissivity: latest.options.source2Emissivity,
        finalAbsorbedW: latest.stats.source2AbsorbedW,
      } : null,
    ].filter(Boolean);
    if (latest.sourceTimeline) report.sourceTimeline = latest.sourceTimeline;
    return report;
  };

  window.EnderSlicerDualSourceTestApi = Object.freeze({
    SOURCE_TYPE_PRESETS,
    ENGINE_SCENARIO_PRESETS,
    sourceTemperatureAt,
  });
