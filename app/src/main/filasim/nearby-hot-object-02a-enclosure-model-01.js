  // Lumped engine-bay and enclosure boundary model. This intentionally avoids
  // pretending to be CFD: the printed part keeps filaSim's full voxel field,
  // while the surrounding air is one finite-capacity node and the enclosure
  // walls are one prescribed radiant-temperature node.
  const SIGMA_SB_W_M2_K4 = 5.670374419e-8;
  const AIR_CP_J_KG_K = 1006;
  const AIR_DENSITY_25C_KG_M3 = 1.184;
  const MAX_ENVIRONMENT_COUPLING_STAGES = 24;
  const MAX_STEADY_ENVIRONMENT_ITERS = 12;

  const ENVIRONMENT_PRESETS = Object.freeze({
    open: Object.freeze({ label: "Open air" }),
    engine_running: Object.freeze({
      label: "Engine bay — engine running",
      ambientTemperatureC: 25,
      initialTemperatureC: 25,
      convectionWm2K: 12,
      externalConvectionWm2K: 8,
      enclosureVolumeL: 80,
      enclosureInitialAirTemperatureC: 70,
      enclosureWallStartTemperatureC: 95,
      enclosureWallEndTemperatureC: 95,
      enclosureWallRampSeconds: 0,
      enclosureVentilationAch: 18,
      enclosureUaWPerK: 12,
      enclosureInternalHeatW: 80,
      enclosureCoveragePercent: 100,
      enclosureWallEmissivity: 0.85,
    }),
    engine_heat_soak: Object.freeze({
      label: "Engine bay — heat soak after shutdown",
      ambientTemperatureC: 25,
      initialTemperatureC: 60,
      convectionWm2K: 8,
      externalConvectionWm2K: 5,
      enclosureVolumeL: 80,
      enclosureInitialAirTemperatureC: 60,
      enclosureWallStartTemperatureC: 85,
      enclosureWallEndTemperatureC: 120,
      enclosureWallRampSeconds: 900,
      enclosureVentilationAch: 1.5,
      enclosureUaWPerK: 8,
      enclosureInternalHeatW: 0,
      enclosureCoveragePercent: 100,
      enclosureWallEmissivity: 0.85,
    }),
    ventilated_enclosure: Object.freeze({
      label: "Ventilated enclosure",
      ambientTemperatureC: 23,
      initialTemperatureC: 30,
      convectionWm2K: 8,
      externalConvectionWm2K: 5,
      enclosureVolumeL: 20,
      enclosureInitialAirTemperatureC: 35,
      enclosureWallStartTemperatureC: 40,
      enclosureWallEndTemperatureC: 40,
      enclosureWallRampSeconds: 0,
      enclosureVentilationAch: 10,
      enclosureUaWPerK: 4,
      enclosureInternalHeatW: 10,
      enclosureCoveragePercent: 100,
      enclosureWallEmissivity: 0.8,
    }),
    sealed_enclosure: Object.freeze({
      label: "Sealed enclosure",
      ambientTemperatureC: 23,
      initialTemperatureC: 23,
      convectionWm2K: 4,
      externalConvectionWm2K: 5,
      enclosureVolumeL: 20,
      enclosureInitialAirTemperatureC: 23,
      enclosureWallStartTemperatureC: 23,
      enclosureWallEndTemperatureC: 60,
      enclosureWallRampSeconds: 1800,
      enclosureVentilationAch: 0.1,
      enclosureUaWPerK: 2,
      enclosureInternalHeatW: 5,
      enclosureCoveragePercent: 100,
      enclosureWallEmissivity: 0.8,
    }),
    custom: Object.freeze({ label: "Custom enclosed space" }),
  });

  function clampNumber(number, minimum, maximum) {
    return Math.min(maximum, Math.max(minimum, Number(number)));
  }

  function kelvin(temperatureC) {
    return Math.max(1, Number(temperatureC) + 273.15);
  }

  function airDensityKgM3(temperatureC) {
    return AIR_DENSITY_25C_KG_M3 * 298.15 / kelvin(temperatureC);
  }

  function enclosureEffectiveEmissivity(partEmissivity, wallEmissivity) {
    const part = clampNumber(partEmissivity, 0, 1);
    const wall = clampNumber(wallEmissivity, 0, 1);
    if (part <= 0 || wall <= 0) return 0;
    return clampNumber(1 / (1 / part + 1 / wall - 1), 0, 1);
  }

  function radiationIntoPartWm2(emissivity, partTemperatureC, surroundingsTemperatureC) {
    return clampNumber(emissivity, 0, 1) * SIGMA_SB_W_M2_K4
      * (kelvin(surroundingsTemperatureC) ** 4 - kelvin(partTemperatureC) ** 4);
  }

  function equivalentBoundaryForPart(config) {
    const partTemperatureC = Number(config.partTemperatureC);
    const coverage = clampNumber(config.coverageFraction, 0, 1);
    const outsideFraction = 1 - coverage;
    const insideH = Math.max(0, Number(config.insideConvectionWm2K));
    const outsideH = Math.max(0, Number(config.outsideConvectionWm2K));
    const partEmissivity = clampNumber(config.partEmissivity, 0, 1);
    const wallEffective = enclosureEffectiveEmissivity(partEmissivity, config.wallEmissivity);
    const effectiveH = coverage * insideH + outsideFraction * outsideH;
    const effectiveEmissivity = coverage * wallEffective + outsideFraction * partEmissivity;
    const targetFluxWm2 = coverage * (
      insideH * (Number(config.enclosureAirTemperatureC) - partTemperatureC)
      + radiationIntoPartWm2(wallEffective, partTemperatureC, config.wallTemperatureC)
    ) + outsideFraction * (
      outsideH * (Number(config.externalTemperatureC) - partTemperatureC)
      + radiationIntoPartWm2(partEmissivity, partTemperatureC, config.externalTemperatureC)
    );

    const modeledFlux = (candidateC) => effectiveH * (candidateC - partTemperatureC)
      + radiationIntoPartWm2(effectiveEmissivity, partTemperatureC, candidateC);
    let low = -273.14;
    let high = 2000;
    for (let iteration = 0; iteration < 90; iteration += 1) {
      const middle = (low + high) * 0.5;
      if (modeledFlux(middle) < targetFluxWm2) low = middle;
      else high = middle;
    }
    return {
      ambientTemperatureC: (low + high) * 0.5,
      convectionWm2K: effectiveH,
      emissivity: effectiveEmissivity,
      targetHeatFluxWm2: targetFluxWm2,
      wallEffectiveEmissivity: wallEffective,
    };
  }

  function wallTemperatureAt(options, elapsedSeconds) {
    const start = Number(options.enclosureWallStartTemperatureC);
    const end = Number(options.enclosureWallEndTemperatureC);
    const ramp = Math.max(0, Number(options.enclosureWallRampSeconds));
    if (ramp <= 0) return end;
    const fraction = clampNumber(Number(elapsedSeconds) / ramp, 0, 1);
    return start + (end - start) * fraction;
  }

  function planEnclosureStages(durationSeconds, timeStepSeconds) {
    const duration = Number(durationSeconds);
    const thermalStep = Number(timeStepSeconds);
    const totalThermalSteps = Math.max(1, Math.ceil(duration / thermalStep));
    const thermalStepsPerStage = Math.max(1, Math.ceil(totalThermalSteps / MAX_ENVIRONMENT_COUPLING_STAGES));
    const nominalStageSeconds = thermalStepsPerStage * thermalStep;
    const stages = [];
    let remaining = duration;
    while (remaining > 1e-9) {
      const seconds = Math.min(remaining, nominalStageSeconds);
      stages.push(seconds);
      remaining -= seconds;
    }
    return stages;
  }

  function enclosureAirStep(config) {
    const oldAirC = Number(config.oldAirTemperatureC);
    const partC = Number(config.partMeanTemperatureC);
    const wallC = Number(config.wallTemperatureC);
    const externalC = Number(config.externalTemperatureC);
    const dt = Math.max(1e-9, Number(config.dtSeconds));
    const volumeM3 = Math.max(1e-6, Number(config.volumeL) / 1000);
    const density = airDensityKgM3(oldAirC);
    const airCapacityJk = density * AIR_CP_J_KG_K * volumeM3;
    const coverage = clampNumber(config.coverageFraction, 0, 1);
    const areaM2 = Math.max(0, Number(config.exteriorAreaMm2)) * 1e-6;
    const partConductanceWk = coverage * Math.max(0, Number(config.insideConvectionWm2K)) * areaM2;
    const wallConductanceWk = Math.max(0, Number(config.enclosureUaWPerK));
    const ventilationConductanceWk = density * AIR_CP_J_KG_K * volumeM3
      * Math.max(0, Number(config.ventilationAch)) / 3600;
    const storedConductanceWk = airCapacityJk / dt;
    const denominator = storedConductanceWk + partConductanceWk
      + wallConductanceWk + ventilationConductanceWk;
    if (!(denominator > 0) || !Number.isFinite(denominator)) {
      throw new Error("The enclosure air model has no finite thermal capacity or heat-transfer path.");
    }
    const newAirC = (
      storedConductanceWk * oldAirC
      + partConductanceWk * partC
      + wallConductanceWk * wallC
      + ventilationConductanceWk * externalC
      + Number(config.internalHeatW)
    ) / denominator;
    return {
      airTemperatureC: newAirC,
      airCapacityJk,
      partConductanceWk,
      wallConductanceWk,
      ventilationConductanceWk,
      partToAirW: partConductanceWk * (partC - newAirC),
      wallToAirW: wallConductanceWk * (wallC - newAirC),
      ventilationLossW: ventilationConductanceWk * (newAirC - externalC),
    };
  }

  function steadyEnclosureAirTemperature(config) {
    const volumeM3 = Math.max(1e-6, Number(config.volumeL) / 1000);
    const density = airDensityKgM3(config.previousAirTemperatureC);
    const coverage = clampNumber(config.coverageFraction, 0, 1);
    const areaM2 = Math.max(0, Number(config.exteriorAreaMm2)) * 1e-6;
    const partConductanceWk = coverage * Math.max(0, Number(config.insideConvectionWm2K)) * areaM2;
    const wallConductanceWk = Math.max(0, Number(config.enclosureUaWPerK));
    const ventilationConductanceWk = density * AIR_CP_J_KG_K * volumeM3
      * Math.max(0, Number(config.ventilationAch)) / 3600;
    const denominator = partConductanceWk + wallConductanceWk + ventilationConductanceWk;
    if (!(denominator > 0)) {
      throw new Error("Steady enclosed-space analysis requires ventilation, wall heat transfer, or part-to-air convection.");
    }
    return (
      partConductanceWk * Number(config.partMeanTemperatureC)
      + wallConductanceWk * Number(config.wallTemperatureC)
      + ventilationConductanceWk * Number(config.externalTemperatureC)
      + Number(config.internalHeatW)
    ) / denominator;
  }

  function environmentLabel(mode) {
    return ENVIRONMENT_PRESETS[mode]?.label || String(mode || "Custom enclosed space");
  }

  function applyEnvironmentPreset(mode) {
    const preset = ENVIRONMENT_PRESETS[mode];
    if (!preset) return;
    if (mode !== "open" && mode !== "custom") {
      Object.entries(preset).forEach(([key, presetValue]) => {
        if (key !== "label") setValue(key, presetValue);
      });
    }
    syncEnvironmentUi();
  }

  function syncEnvironmentUi() {
    const select = document.getElementById("ti-environmentMode");
    const fields = document.getElementById("ti-enclosure-fields");
    const note = document.getElementById("ti-enclosure-note");
    const mode = select?.value || "open";
    const enclosed = mode !== "open";
    fields?.classList.toggle("ti-hidden", !enclosed);
    if (note) {
      note.className = `ti-status ${enclosed ? "ti-warning" : "dim"}`;
      note.textContent = enclosed
        ? `${environmentLabel(mode)} uses a finite, well-mixed air node plus enclosure-wall radiation. Preset numbers are editable starting assumptions, not calibrated values for a specific vehicle or enclosure. Airflow distribution and local recirculation are not CFD-modeled.`
        : "Open air keeps the original fixed ambient convection and radiation boundary.";
    }
  }

  function environmentOptionsFromInputs(options) {
    if (options.environmentMode === "open") return null;
    return {
      mode: options.environmentMode,
      label: environmentLabel(options.environmentMode),
      externalTemperatureC: options.ambientTemperatureC,
      volumeL: options.enclosureVolumeL,
      initialAirTemperatureC: options.enclosureInitialAirTemperatureC,
      wallStartTemperatureC: options.enclosureWallStartTemperatureC,
      wallEndTemperatureC: options.enclosureWallEndTemperatureC,
      wallRampSeconds: options.enclosureWallRampSeconds,
      ventilationAch: options.enclosureVentilationAch,
      enclosureUaWPerK: options.enclosureUaWPerK,
      internalHeatW: options.enclosureInternalHeatW,
      coverageFraction: options.enclosureCoveragePercent / 100,
      wallEmissivity: options.enclosureWallEmissivity,
      insideConvectionWm2K: options.convectionWm2K,
      outsideConvectionWm2K: options.externalConvectionWm2K,
      partEmissivity: options.sourcePartEmissivity,
    };
  }

  function validateThermalData(data, label) {
    const stats = data?.stats;
    if (!stats || !(data?.temperatures instanceof Float32Array)
        || !(data?.history instanceof Float64Array)
        || !(data?.displacements instanceof Float32Array)
        || !(data?.materialFraction instanceof Float32Array)
        || !(data?.vertexTemperatures instanceof Float32Array)) {
      throw new Error(`${label} returned an incomplete filaSim result.`);
    }
    return data;
  }

  function appendOffsetHistory(target, history, offsetSeconds) {
    for (let index = 0; index + 2 < history.length; index += 3) {
      const time = Number(history[index]) + offsetSeconds;
      if (target.length && Math.abs(target[target.length - 3] - time) <= 1e-9) continue;
      target.push(time, Number(history[index + 1]), Number(history[index + 2]));
    }
  }

  function stageOptions(base, environment, boundary, durationSeconds, initialField, thermalOnly) {
    return {
      ...base,
      mode: "transient",
      durationSeconds,
      timeStepSeconds: Math.min(base.timeStepSeconds, durationSeconds),
      initialTemperatureFieldC: initialField
        ? Array.from(initialField, (temperature) => Number(temperature))
        : null,
      ambientTemperatureC: boundary.ambientTemperatureC,
      convectionWm2K: boundary.convectionWm2K,
      emissivity: boundary.emissivity,
      sourcePartEmissivity: environment.partEmissivity,
      thermalOnly,
    };
  }

