  async function runTransientEnclosure(options, preflight, status, requestFn) {
    const environment = environmentOptionsFromInputs(options);
    const stages = planEnclosureStages(options.durationSeconds, options.timeStepSeconds);
    const exteriorAreaMm2 = Number(preflight.totalExteriorAreaMm2);
    if (!(exteriorAreaMm2 > 0)) throw new Error("filaSim did not return a valid exterior area for the enclosure model.");
    let airTemperatureC = environment.initialAirTemperatureC;
    let peakAirTemperatureC = airTemperatureC;
    let elapsed = 0;
    let previousMeanC = options.initialTemperatureC;
    let initialField = null;
    let finalData = null;
    let totalTimeSteps = 0;
    let totalIterations = 0;
    let peakPartTemperatureC = options.initialTemperatureC;
    let peakPartTimeSeconds = 0;
    const combinedHistory = [];
    const energy = {
      selectedHotObjectJ: 0,
      enclosureWallRadiationIntoPartJ: 0,
      enclosureAirConvectionIntoPartJ: 0,
      ventilationLossJ: 0,
      wallToAirJ: 0,
      internalAirHeatingJ: 0,
    };

    for (let index = 0; index < stages.length; index += 1) {
      const dt = stages[index];
      const wallC = wallTemperatureAt(options, elapsed + dt * 0.5);
      const boundary = equivalentBoundaryForPart({
        partTemperatureC: previousMeanC,
        coverageFraction: environment.coverageFraction,
        insideConvectionWm2K: environment.insideConvectionWm2K,
        outsideConvectionWm2K: environment.outsideConvectionWm2K,
        partEmissivity: environment.partEmissivity,
        wallEmissivity: environment.wallEmissivity,
        enclosureAirTemperatureC: airTemperatureC,
        wallTemperatureC: wallC,
        externalTemperatureC: environment.externalTemperatureC,
      });
      status.textContent = `Calculating ${environment.label}: environment stage ${index + 1}/${stages.length} · air ${format(airTemperatureC, 1)} °C · walls ${format(wallC, 1)} °C…`;
      const data = validateThermalData(await requestFn("thermalIntegrity", {
        opts: stageOptions(options, environment, boundary, dt, initialField, index + 1 < stages.length),
      }), `Enclosure stage ${index + 1}`);
      const partMeanC = Number(data.stats.meanTemperatureC);
      const airStep = enclosureAirStep({
        oldAirTemperatureC: airTemperatureC,
        partMeanTemperatureC: partMeanC,
        wallTemperatureC: wallC,
        externalTemperatureC: environment.externalTemperatureC,
        dtSeconds: dt,
        volumeL: environment.volumeL,
        coverageFraction: environment.coverageFraction,
        exteriorAreaMm2,
        insideConvectionWm2K: environment.insideConvectionWm2K,
        enclosureUaWPerK: environment.enclosureUaWPerK,
        ventilationAch: environment.ventilationAch,
        internalHeatW: environment.internalHeatW,
      });
      const areaM2 = exteriorAreaMm2 * 1e-6 * environment.coverageFraction;
      const wallRadiationIntoPartW = areaM2 * radiationIntoPartWm2(
        boundary.wallEffectiveEmissivity,
        partMeanC,
        wallC,
      );
      const airConvectionIntoPartW = -airStep.partToAirW;
      energy.selectedHotObjectJ += Number(data.stats.sourceAbsorbedW ?? data.stats.heatInputW ?? 0) * dt;
      energy.enclosureWallRadiationIntoPartJ += wallRadiationIntoPartW * dt;
      energy.enclosureAirConvectionIntoPartJ += airConvectionIntoPartW * dt;
      energy.ventilationLossJ += airStep.ventilationLossW * dt;
      energy.wallToAirJ += airStep.wallToAirW * dt;
      energy.internalAirHeatingJ += environment.internalHeatW * dt;
      appendOffsetHistory(combinedHistory, data.history, elapsed);
      const stagePeak = Number(data.stats.peakTemperatureC ?? data.stats.maximumTemperatureC);
      const stagePeakTime = Number(data.stats.peakTimeSeconds ?? dt);
      if (stagePeak > peakPartTemperatureC) {
        peakPartTemperatureC = stagePeak;
        peakPartTimeSeconds = elapsed + stagePeakTime;
      }
      totalTimeSteps += Number(data.stats.timeSteps || 0);
      totalIterations += Number(data.stats.iterations || 0);
      elapsed += dt;
      airTemperatureC = airStep.airTemperatureC;
      if (!Number.isFinite(airTemperatureC) || airTemperatureC <= -273.15 || airTemperatureC > 1500) {
        throw new Error("The enclosure air temperature left filaSim's supported range; reduce the heat input or increase enclosure volume/ventilation.");
      }
      peakAirTemperatureC = Math.max(peakAirTemperatureC, airTemperatureC);
      previousMeanC = partMeanC;
      initialField = data.temperatures;
      finalData = data;
    }

    const finalWallC = wallTemperatureAt(options, options.durationSeconds);
    finalData.history = new Float64Array(combinedHistory);
    finalData.stats = {
      ...finalData.stats,
      finalTimeSeconds: options.durationSeconds,
      timeSteps: totalTimeSteps,
      iterations: totalIterations,
      peakTemperatureC: peakPartTemperatureC,
      peakTimeSeconds: peakPartTimeSeconds,
      peakEnclosureAirTemperatureC: peakAirTemperatureC,
      finalEnclosureAirTemperatureC: airTemperatureC,
      finalEnclosureWallTemperatureC: finalWallC,
      totalExteriorAreaMm2: exteriorAreaMm2,
      enclosureMode: environment.mode,
      enclosureLabel: environment.label,
    };
    finalData.environment = {
      ...environment,
      model: "finite-well-mixed-air-plus-radiant-wall-stage-coupling-v1",
      couplingStages: stages.length,
      finalAirTemperatureC: airTemperatureC,
      peakAirTemperatureC,
      finalWallTemperatureC: finalWallC,
      exteriorAreaMm2,
      energy,
      limitation: "The voxel part is solved in 3D. Enclosure air and walls are lumped nodes; local airflow and CFD recirculation are not resolved.",
    };
    return finalData;
  }

  async function runSteadyEnclosure(options, preflight, status, requestFn) {
    const environment = environmentOptionsFromInputs(options);
    const exteriorAreaMm2 = Number(preflight.totalExteriorAreaMm2);
    if (!(exteriorAreaMm2 > 0)) throw new Error("filaSim did not return a valid exterior area for the enclosure model.");
    const wallC = wallTemperatureAt(options, Number.POSITIVE_INFINITY);
    let airTemperatureC = environment.initialAirTemperatureC;
    let previousPartMeanC = options.initialTemperatureC;
    let converged = false;
    let iterations = 0;
    for (let iteration = 0; iteration < MAX_STEADY_ENVIRONMENT_ITERS; iteration += 1) {
      iterations = iteration + 1;
      const boundary = equivalentBoundaryForPart({
        partTemperatureC: previousPartMeanC,
        coverageFraction: environment.coverageFraction,
        insideConvectionWm2K: environment.insideConvectionWm2K,
        outsideConvectionWm2K: environment.outsideConvectionWm2K,
        partEmissivity: environment.partEmissivity,
        wallEmissivity: environment.wallEmissivity,
        enclosureAirTemperatureC: airTemperatureC,
        wallTemperatureC: wallC,
        externalTemperatureC: environment.externalTemperatureC,
      });
      status.textContent = `Calculating ${environment.label}: steady enclosure iteration ${iteration + 1}/${MAX_STEADY_ENVIRONMENT_ITERS}…`;
      const data = validateThermalData(await requestFn("thermalIntegrity", { opts: {
        ...options,
        ambientTemperatureC: boundary.ambientTemperatureC,
        convectionWm2K: boundary.convectionWm2K,
        emissivity: boundary.emissivity,
        sourcePartEmissivity: environment.partEmissivity,
        thermalOnly: true,
      }}), `Steady enclosure iteration ${iteration + 1}`);
      const partMeanC = Number(data.stats.meanTemperatureC);
      const nextAirC = steadyEnclosureAirTemperature({
        previousAirTemperatureC: airTemperatureC,
        partMeanTemperatureC: partMeanC,
        wallTemperatureC: wallC,
        externalTemperatureC: environment.externalTemperatureC,
        volumeL: environment.volumeL,
        coverageFraction: environment.coverageFraction,
        exteriorAreaMm2,
        insideConvectionWm2K: environment.insideConvectionWm2K,
        enclosureUaWPerK: environment.enclosureUaWPerK,
        ventilationAch: environment.ventilationAch,
        internalHeatW: environment.internalHeatW,
      });
      if (Math.abs(nextAirC - airTemperatureC) <= 0.05
          && Math.abs(partMeanC - previousPartMeanC) <= 0.05) {
        airTemperatureC = nextAirC;
        previousPartMeanC = partMeanC;
        converged = true;
        break;
      }
      airTemperatureC = 0.5 * airTemperatureC + 0.5 * nextAirC;
      previousPartMeanC = partMeanC;
    }
    if (!converged) throw new Error("The steady enclosure air/part coupling did not converge; use transient mode or adjust airflow and heat-loss inputs.");

    const finalBoundary = equivalentBoundaryForPart({
      partTemperatureC: previousPartMeanC,
      coverageFraction: environment.coverageFraction,
      insideConvectionWm2K: environment.insideConvectionWm2K,
      outsideConvectionWm2K: environment.outsideConvectionWm2K,
      partEmissivity: environment.partEmissivity,
      wallEmissivity: environment.wallEmissivity,
      enclosureAirTemperatureC: airTemperatureC,
      wallTemperatureC: wallC,
      externalTemperatureC: environment.externalTemperatureC,
    });
    const finalData = validateThermalData(await requestFn("thermalIntegrity", { opts: {
      ...options,
      ambientTemperatureC: finalBoundary.ambientTemperatureC,
      convectionWm2K: finalBoundary.convectionWm2K,
      emissivity: finalBoundary.emissivity,
      sourcePartEmissivity: environment.partEmissivity,
      thermalOnly: false,
    }}), "Final steady enclosure solve");
    const partMeanC = Number(finalData.stats.meanTemperatureC);
    const areaM2 = exteriorAreaMm2 * 1e-6 * environment.coverageFraction;
    finalData.stats = {
      ...finalData.stats,
      finalEnclosureAirTemperatureC: airTemperatureC,
      peakEnclosureAirTemperatureC: airTemperatureC,
      finalEnclosureWallTemperatureC: wallC,
      totalExteriorAreaMm2: exteriorAreaMm2,
      enclosureMode: environment.mode,
      enclosureLabel: environment.label,
    };
    finalData.environment = {
      ...environment,
      model: "steady-well-mixed-air-plus-radiant-wall-fixed-point-v1",
      couplingIterations: iterations,
      finalAirTemperatureC: airTemperatureC,
      peakAirTemperatureC: airTemperatureC,
      finalWallTemperatureC: wallC,
      exteriorAreaMm2,
      energyRatesW: {
        selectedHotObject: Number(finalData.stats.sourceAbsorbedW ?? finalData.stats.heatInputW ?? 0),
        enclosureWallRadiationIntoPart: areaM2 * radiationIntoPartWm2(
          finalBoundary.wallEffectiveEmissivity,
          partMeanC,
          wallC,
        ),
        enclosureAirConvectionIntoPart: areaM2 * environment.insideConvectionWm2K
          * (airTemperatureC - partMeanC),
      },
      limitation: "The voxel part is solved in 3D. Enclosure air and walls are lumped nodes; local airflow and CFD recirculation are not resolved.",
    };
    return finalData;
  }

  async function runEnclosureScenario(options, preflight, status, requestFn) {
    return options.mode === "steady"
      ? runSteadyEnclosure(options, preflight, status, requestFn)
      : runTransientEnclosure(options, preflight, status, requestFn);
  }

  window.EnderSlicerNearbyEnclosureTestApi = Object.freeze({
    ENVIRONMENT_PRESETS,
    enclosureEffectiveEmissivity,
    radiationIntoPartWm2,
    equivalentBoundaryForPart,
    wallTemperatureAt,
    planEnclosureStages,
    enclosureAirStep,
    steadyEnclosureAirTemperature,
  });
