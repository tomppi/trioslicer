  // High-accuracy reduced-order engine-bay model. The part still uses filaSim's
  // full 3D voxel conduction; its exterior faces receive one of 12 local
  // air/wall boundary states (3 longitudinal x 2 lateral x 2 vertical).
  const ENGINE_BAY_ZONE_COUNT = 12;
  const ENGINE_BAY_ZONE_LAYOUT = "3x2x2-local-air-wall-network-v1";
  const ENGINE_BAY_CORRECTION_PASSES = 2;
  const MAX_ZONED_STEADY_ITERS = 8;

  function zoneCoordinates(zone) {
    const index = Math.max(0, Math.min(11, Number(zone) | 0));
    return {
      longitudinal: index % 3,
      lateral: Math.floor(index / 3) % 2,
      vertical: Math.floor(index / 6),
    };
  }

  function zoneArray(value) {
    if (Array.isArray(value) && value.length === ENGINE_BAY_ZONE_COUNT) {
      return value.map(Number);
    }
    return Array(ENGINE_BAY_ZONE_COUNT).fill(Number(value));
  }

  function meanNumbers(values) {
    return values.reduce((sum, current) => sum + Number(current), 0)
      / Math.max(1, values.length);
  }

  function initialEngineBayZoneAirTemperatures(options, environment) {
    const base = Number(environment.initialAirTemperatureC);
    const running = options.environmentMode === "engine_running";
    return Array.from({ length: ENGINE_BAY_ZONE_COUNT }, (_, zone) => {
      const { longitudinal, lateral, vertical } = zoneCoordinates(zone);
      const longitudinalBias = running ? [-5, 4, 2][longitudinal] : [-3, 2, 4][longitudinal];
      const lateralBias = lateral === 1 && options.source2Enabled ? 5 : 0;
      const verticalBias = vertical === 1 ? (running ? 11 : 13) : -4;
      return base + longitudinalBias + lateralBias + verticalBias;
    });
  }

  function engineBayWallZoneTemperatures(options, baseWallC) {
    const running = options.environmentMode === "engine_running";
    return Array.from({ length: ENGINE_BAY_ZONE_COUNT }, (_, zone) => {
      const { longitudinal, lateral, vertical } = zoneCoordinates(zone);
      const longitudinalBias = running ? [-7, 4, 6][longitudinal] : [-4, 3, 7][longitudinal];
      const lateralBias = lateral === 1 && options.source2Enabled ? 7 : 0;
      const verticalBias = vertical === 1 ? 9 : -5;
      return Number(baseWallC) + longitudinalBias + lateralBias + verticalBias;
    });
  }

  function engineBayZoneConvection(options, environment) {
    const running = options.environmentMode === "engine_running";
    const base = Number(environment.insideConvectionWm2K);
    return Array.from({ length: ENGINE_BAY_ZONE_COUNT }, (_, zone) => {
      const { longitudinal, vertical } = zoneCoordinates(zone);
      const frontFactor = longitudinal === 0 ? (running ? 1.35 : 0.9) : 1;
      const heightFactor = vertical === 1 ? (running ? 1.15 : 1.25) : 0.8;
      return Math.max(0.1, base * frontFactor * heightFactor);
    });
  }

  function partZoneMeanTemperatures(data, fallbackC) {
    const temperatures = data?.temperatures;
    const material = data?.materialFraction;
    const nx = Number(data?.stats?.nx) | 0;
    const ny = Number(data?.stats?.ny) | 0;
    const nz = Number(data?.stats?.nz) | 0;
    if (!(temperatures instanceof Float32Array)
        || !(material instanceof Float32Array)
        || nx <= 0 || ny <= 0 || nz <= 0
        || temperatures.length !== material.length
        || temperatures.length !== nx * ny * nz) {
      return zoneArray(fallbackC);
    }
    let minX = nx; let minY = ny; let minZ = nz;
    let maxX = -1; let maxY = -1; let maxZ = -1;
    for (let index = 0; index < material.length; index += 1) {
      if (!(material[index] > 1e-7)) continue;
      const x = index % nx;
      const y = Math.floor(index / nx) % ny;
      const z = Math.floor(index / (nx * ny));
      minX = Math.min(minX, x); maxX = Math.max(maxX, x);
      minY = Math.min(minY, y); maxY = Math.max(maxY, y);
      minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
    }
    if (maxX < minX || maxY < minY || maxZ < minZ) return zoneArray(fallbackC);
    const sums = Array(ENGINE_BAY_ZONE_COUNT).fill(0);
    const weights = Array(ENGINE_BAY_ZONE_COUNT).fill(0);
    for (let index = 0; index < material.length; index += 1) {
      const weight = Number(material[index]);
      const temperature = Number(temperatures[index]);
      if (!(weight > 1e-7) || !Number.isFinite(temperature)) continue;
      const x = index % nx;
      const y = Math.floor(index / nx) % ny;
      const z = Math.floor(index / (nx * ny));
      const fx = (x - minX + 0.5) / Math.max(1, maxX - minX + 1);
      const fy = (y - minY + 0.5) / Math.max(1, maxY - minY + 1);
      const fz = (z - minZ + 0.5) / Math.max(1, maxZ - minZ + 1);
      const longitudinal = Math.min(2, Math.floor(fx * 3));
      const lateral = fy >= 0.5 ? 1 : 0;
      const vertical = fz >= 0.5 ? 1 : 0;
      const zone = longitudinal + 3 * lateral + 6 * vertical;
      sums[zone] += temperature * weight;
      weights[zone] += weight;
    }
    const global = Number(data?.stats?.meanTemperatureC ?? fallbackC);
    return sums.map((sum, zone) => weights[zone] > 0 ? sum / weights[zone] : global);
  }

  function zoneNeighborPairs() {
    const pairs = [];
    for (let zone = 0; zone < ENGINE_BAY_ZONE_COUNT; zone += 1) {
      const { longitudinal, lateral, vertical } = zoneCoordinates(zone);
      if (longitudinal < 2) pairs.push([zone, zone + 1, "longitudinal"]);
      if (lateral < 1) pairs.push([zone, zone + 3, "lateral"]);
      if (vertical < 1) pairs.push([zone, zone + 6, "vertical"]);
    }
    return pairs;
  }
  const ENGINE_BAY_ZONE_NEIGHBORS = Object.freeze(zoneNeighborPairs());

  function normalizedZoneWeights(options, kind) {
    const weights = Array.from({ length: ENGINE_BAY_ZONE_COUNT }, (_, zone) => {
      const { longitudinal, lateral, vertical } = zoneCoordinates(zone);
      if (kind === "ventilation") {
        if (options.environmentMode === "engine_running") {
          return (longitudinal === 0 ? 2.2 : 0.7) * (vertical === 0 ? 1.25 : 0.8);
        }
        return (vertical === 1 ? 1.35 : 0.75) * (longitudinal === 2 ? 1.15 : 1);
      }
      let weight = longitudinal === 1 ? 2.0 : 0.8;
      if (vertical === 0) weight *= 1.35;
      if (lateral === 1 && options.source2Enabled) weight *= 1.45;
      return weight;
    });
    const total = weights.reduce((sum, weight) => sum + weight, 0);
    return weights.map((weight) => weight / Math.max(1e-12, total));
  }

  function zonedAirNetworkStep(config) {
    const oldAir = zoneArray(config.oldAirTemperaturesC);
    const part = zoneArray(config.partTemperaturesC);
    const wall = zoneArray(config.wallTemperaturesC);
    const h = zoneArray(config.convectionWm2K);
    const areaMm2 = zoneArray(config.zoneExteriorAreaMm2);
    const dt = Math.max(1e-6, Number(config.dtSeconds));
    const totalVolumeM3 = Math.max(1e-6, Number(config.volumeL) / 1000);
    const zoneVolumeM3 = totalVolumeM3 / ENGINE_BAY_ZONE_COUNT;
    const ventilationWeights = normalizedZoneWeights(config.options, "ventilation");
    const heatWeights = normalizedZoneWeights(config.options, "heat");
    const density = airDensityKgM3(meanNumbers(oldAir));
    const capacityJk = density * AIR_CP_J_KG_K * zoneVolumeM3;
    const totalVentilationG = density * AIR_CP_J_KG_K * totalVolumeM3
      * Math.max(0, Number(config.ventilationAch)) / 3600;
    const wallG = Math.max(0, Number(config.enclosureUaWPerK)) / ENGINE_BAY_ZONE_COUNT;
    const baseMixG = config.options.environmentMode === "engine_running" ? 1.8 : 0.9;
    const partG = h.map((coefficient, zone) => Math.max(0, coefficient)
      * Math.max(0, areaMm2[zone]) * 1e-6);
    const neighborConductance = Array.from({ length: ENGINE_BAY_ZONE_COUNT }, () => []);
    for (const [a, b, axis] of ENGINE_BAY_ZONE_NEIGHBORS) {
      let conductance = baseMixG * (axis === "longitudinal" ? 1.15 : axis === "lateral" ? 0.8 : 1);
      if (axis === "vertical" && oldAir[a] > oldAir[b]) {
        conductance += (config.options.environmentMode === "engine_running" ? 2.5 : 3.2);
      }
      neighborConductance[a].push([b, conductance]);
      neighborConductance[b].push([a, conductance]);
    }
    let guess = [...oldAir];
    for (let iteration = 0; iteration < 5; iteration += 1) {
      const next = Array(ENGINE_BAY_ZONE_COUNT).fill(0);
      for (let zone = 0; zone < ENGINE_BAY_ZONE_COUNT; zone += 1) {
        const storageG = capacityJk / dt;
        const ventilationG = totalVentilationG * ventilationWeights[zone];
        const mixingG = neighborConductance[zone]
          .reduce((sum, [, conductance]) => sum + conductance, 0);
        const denominator = storageG + partG[zone] + wallG + ventilationG + mixingG;
        const neighborInput = neighborConductance[zone]
          .reduce((sum, [other, conductance]) => sum + conductance * guess[other], 0);
        next[zone] = (
          storageG * oldAir[zone]
          + partG[zone] * part[zone]
          + wallG * wall[zone]
          + ventilationG * Number(config.externalTemperatureC)
          + neighborInput
          + Number(config.internalHeatW) * heatWeights[zone]
        ) / Math.max(1e-12, denominator);
      }
      guess = next;
    }
    return guess;
  }

  function steadyZonedAirNetworkStep(config) {
    const previous = zoneArray(config.previousAirTemperaturesC);
    const part = zoneArray(config.partTemperaturesC);
    const wall = zoneArray(config.wallTemperaturesC);
    const h = zoneArray(config.convectionWm2K);
    const areaMm2 = zoneArray(config.zoneExteriorAreaMm2);
    const totalVolumeM3 = Math.max(1e-6, Number(config.volumeL) / 1000);
    const ventilationWeights = normalizedZoneWeights(config.options, "ventilation");
    const heatWeights = normalizedZoneWeights(config.options, "heat");
    const density = airDensityKgM3(meanNumbers(previous));
    const totalVentilationG = density * AIR_CP_J_KG_K * totalVolumeM3
      * Math.max(0, Number(config.ventilationAch)) / 3600;
    const wallG = Math.max(0, Number(config.enclosureUaWPerK)) / ENGINE_BAY_ZONE_COUNT;
    const mixG = config.options.environmentMode === "engine_running" ? 1.8 : 0.9;
    const partG = h.map((coefficient, zone) => Math.max(0, coefficient)
      * Math.max(0, areaMm2[zone]) * 1e-6);
    let guess = [...previous];
    for (let iteration = 0; iteration < 12; iteration += 1) {
      const next = Array(ENGINE_BAY_ZONE_COUNT).fill(0);
      for (let zone = 0; zone < ENGINE_BAY_ZONE_COUNT; zone += 1) {
        const neighbors = ENGINE_BAY_ZONE_NEIGHBORS
          .filter(([a, b]) => a === zone || b === zone)
          .map(([a, b, axis]) => [a === zone ? b : a, mixG * (axis === "longitudinal" ? 1.15 : axis === "lateral" ? 0.8 : 1)]);
        const ventilationG = totalVentilationG * ventilationWeights[zone];
        const mixingTotal = neighbors.reduce((sum, [, conductance]) => sum + conductance, 0);
        const denominator = partG[zone] + wallG + ventilationG + mixingTotal;
        const neighborInput = neighbors.reduce((sum, [other, conductance]) => sum + conductance * guess[other], 0);
        next[zone] = (
          partG[zone] * part[zone]
          + wallG * wall[zone]
          + ventilationG * Number(config.externalTemperatureC)
          + neighborInput
          + Number(config.internalHeatW) * heatWeights[zone]
        ) / Math.max(1e-12, denominator);
      }
      guess = next;
    }
    return guess;
  }

  function spatialStageOptions(stage, options, environment, airC, wallC, convection, thermalOnly) {
    return {
      ...stage,
      thermalOnly,
      ambientTemperatureC: meanNumbers(airC),
      convectionWm2K: meanNumbers(convection),
      emissivity: Number(environment.partEmissivity),
      spatialEnvironmentEnabled: true,
      environmentWidthMm: Number(options.enclosureWidthMm),
      environmentDepthMm: Number(options.enclosureDepthMm),
      environmentHeightMm: Number(options.enclosureHeightMm),
      environmentOffsetXMm: Number(options.enclosureOffsetXmm),
      environmentOffsetYMm: Number(options.enclosureOffsetYmm),
      environmentOffsetZMm: Number(options.enclosureOffsetZmm),
      environmentAirTemperaturesC: zoneArray(airC),
      environmentWallTemperaturesC: zoneArray(wallC),
      environmentConvectionWm2K: zoneArray(convection),
      environmentWallEmissivities: zoneArray(environment.wallEmissivity),
    };
  }

  function validateZoneAreas(preflight) {
    const areas = preflight?.spatialZoneExteriorAreaMm2;
    if (!Array.isArray(areas) || areas.length !== ENGINE_BAY_ZONE_COUNT
        || areas.some((area) => !Number.isFinite(Number(area)) || Number(area) < 0)
        || areas.reduce((sum, area) => sum + Number(area), 0) <= 0) {
      throw new Error("filaSim did not return a valid 12-zone exterior-area map.");
    }
    return areas.map(Number);
  }

  async function runTransientZonedEngineBay(options, preflight, status, requestFn) {
    const environment = environmentOptionsFromInputs(options);
    const stages = planEnclosureStages(options.durationSeconds, options.timeStepSeconds);
    const zoneAreas = validateZoneAreas(preflight);
    let airC = initialEngineBayZoneAirTemperatures(options, environment);
    let peakAirC = Math.max(...airC);
    let elapsed = 0;
    let initialField = null;
    let finalData = null;
    let peakPartC = Number(options.initialTemperatureC);
    let peakPartTime = 0;
    let physicalTimeSteps = 0;
    let solverIterations = 0;
    const combinedHistory = [];
    const zoneHistory = [{ timeSeconds: 0, airTemperaturesC: [...airC] }];
    sourceRampElapsedSeconds = 0;

    for (let index = 0; index < stages.length; index += 1) {
      const dt = Number(stages[index]);
      const midpoint = elapsed + dt * 0.5;
      const baseWallC = wallTemperatureAt(options, midpoint);
      const wallC = engineBayWallZoneTemperatures(options, baseWallC);
      const convection = engineBayZoneConvection(options, environment);
      const averageBoundary = equivalentBoundaryForPart({
        partTemperatureC: Number(finalData?.stats?.meanTemperatureC ?? options.initialTemperatureC),
        coverageFraction: environment.coverageFraction,
        insideConvectionWm2K: meanNumbers(convection),
        outsideConvectionWm2K: environment.outsideConvectionWm2K,
        partEmissivity: environment.partEmissivity,
        wallEmissivity: environment.wallEmissivity,
        enclosureAirTemperatureC: meanNumbers(airC),
        wallTemperatureC: meanNumbers(wallC),
        externalTemperatureC: environment.externalTemperatureC,
      });
      const baseStage = stageOptions(options, environment, averageBoundary, dt, initialField, true);
      status.textContent = `High accuracy engine bay ${index + 1}/${stages.length} · predictor pass · ${ENGINE_BAY_ZONE_COUNT} zones…`;
      const predictor = validateThermalData(await requestFn("thermalIntegrity", {
        opts: spatialStageOptions(baseStage, options, environment, airC, wallC, convection, true),
      }), `Zoned predictor stage ${index + 1}`);
      const predictorPartC = partZoneMeanTemperatures(predictor, predictor.stats.meanTemperatureC);
      const ventilationAch = paperSoakVentilationAchAt(
        options.thermalHintProfile,
        midpoint,
        environment.ventilationAch,
      );
      const correctedAirC = zonedAirNetworkStep({
        oldAirTemperaturesC: airC,
        partTemperaturesC: predictorPartC,
        wallTemperaturesC: wallC,
        convectionWm2K: convection,
        zoneExteriorAreaMm2: zoneAreas,
        dtSeconds: dt,
        volumeL: environment.volumeL,
        ventilationAch,
        enclosureUaWPerK: environment.enclosureUaWPerK,
        internalHeatW: environment.internalHeatW,
        externalTemperatureC: environment.externalTemperatureC,
        options,
      });
      status.textContent = `High accuracy engine bay ${index + 1}/${stages.length} · correction pass · air ${format(meanNumbers(correctedAirC), 1)} °C…`;
      const corrected = validateThermalData(await requestFn("thermalIntegrity", {
        opts: spatialStageOptions(
          baseStage,
          options,
          environment,
          correctedAirC,
          wallC,
          convection,
          index + 1 < stages.length,
        ),
      }), `Zoned correction stage ${index + 1}`);
      const correctedPartC = partZoneMeanTemperatures(corrected, corrected.stats.meanTemperatureC);
      airC = zonedAirNetworkStep({
        oldAirTemperaturesC: airC,
        partTemperaturesC: correctedPartC,
        wallTemperaturesC: wallC,
        convectionWm2K: convection,
        zoneExteriorAreaMm2: zoneAreas,
        dtSeconds: dt,
        volumeL: environment.volumeL,
        ventilationAch,
        enclosureUaWPerK: environment.enclosureUaWPerK,
        internalHeatW: environment.internalHeatW,
        externalTemperatureC: environment.externalTemperatureC,
        options,
      });
      if (airC.some((temperature) => !Number.isFinite(temperature)
          || temperature <= -273.15 || temperature > 1500)) {
        throw new Error("A local engine-bay air zone left the supported temperature range.");
      }
      appendOffsetHistory(combinedHistory, corrected.history, elapsed);
      const stagePeak = Number(corrected.stats.peakTemperatureC ?? corrected.stats.maximumTemperatureC);
      if (stagePeak > peakPartC) {
        peakPartC = stagePeak;
        peakPartTime = elapsed + Number(corrected.stats.peakTimeSeconds ?? dt);
      }
      physicalTimeSteps += Number(corrected.stats.timeSteps || 0);
      solverIterations += Number(predictor.stats.thermalIterations ?? predictor.stats.iterations ?? 0)
        + Number(corrected.stats.thermalIterations ?? corrected.stats.iterations ?? 0);
      elapsed += dt;
      peakAirC = Math.max(peakAirC, ...airC);
      zoneHistory.push({ timeSeconds: elapsed, airTemperaturesC: [...airC] });
      initialField = corrected.temperatures;
      finalData = corrected;
    }

    const finalWallC = engineBayWallZoneTemperatures(
      options,
      wallTemperatureAt(options, options.durationSeconds),
    );
    finalData.history = new Float64Array(combinedHistory);
    finalData.stats = {
      ...finalData.stats,
      finalTimeSeconds: Number(options.durationSeconds),
      timeSteps: physicalTimeSteps,
      thermalIterations: solverIterations,
      peakTemperatureC: peakPartC,
      peakTimeSeconds: peakPartTime,
      finalEnclosureAirTemperatureC: meanNumbers(airC),
      minimumEnclosureAirTemperatureC: Math.min(...airC),
      maximumEnclosureAirTemperatureC: Math.max(...airC),
      peakEnclosureAirTemperatureC: peakAirC,
      finalEnclosureWallTemperatureC: meanNumbers(finalWallC),
      enclosureMode: environment.mode,
      enclosureLabel: environment.label,
      zonedEnvironmentSolverPasses: stages.length * ENGINE_BAY_CORRECTION_PASSES,
    };
    finalData.environment = {
      ...environment,
      model: ENGINE_BAY_ZONE_LAYOUT,
      accuracyMode: "zoned_high",
      couplingStages: stages.length,
      correctionPassesPerStage: ENGINE_BAY_CORRECTION_PASSES,
      solverPasses: stages.length * ENGINE_BAY_CORRECTION_PASSES,
      zoneExteriorAreaMm2: zoneAreas,
      finalAirTemperaturesC: [...airC],
      finalWallTemperaturesC: [...finalWallC],
      peakAirTemperatureC: peakAirC,
      zoneHistory,
      assumptions: {
        zones: "3 longitudinal x 2 lateral x 2 vertical",
        interzoneMixing: "implicit conservative conductance with stronger upward buoyancy exchange",
        localBoundary: "each exterior voxel face uses its zone air temperature, wall temperature, convection and wall emissivity",
      },
      limitation: "Reduced-order 12-zone heat-transfer model. It resolves local thermal gradients but not velocity, pressure, turbulence, fan jets or CFD recirculation.",
    };
    return finalData;
  }

  async function runSteadyZonedEngineBay(options, preflight, status, requestFn) {
    const environment = environmentOptionsFromInputs(options);
    const zoneAreas = validateZoneAreas(preflight);
    const wallC = engineBayWallZoneTemperatures(options, wallTemperatureAt(options, Number.POSITIVE_INFINITY));
    const convection = engineBayZoneConvection(options, environment);
    let airC = initialEngineBayZoneAirTemperatures(options, environment);
    let previousPartC = zoneArray(options.initialTemperatureC);
    let iterations = 0;
    for (let iteration = 0; iteration < MAX_ZONED_STEADY_ITERS; iteration += 1) {
      iterations = iteration + 1;
      status.textContent = `High accuracy steady engine bay · zone iteration ${iterations}/${MAX_ZONED_STEADY_ITERS}…`;
      const data = validateThermalData(await requestFn("thermalIntegrity", { opts: spatialStageOptions(
        { ...options, thermalOnly: true }, options, environment, airC, wallC, convection, true,
      ) }), `Steady zoned iteration ${iterations}`);
      const partC = partZoneMeanTemperatures(data, data.stats.meanTemperatureC);
      const nextAir = steadyZonedAirNetworkStep({
        previousAirTemperaturesC: airC,
        partTemperaturesC: partC,
        wallTemperaturesC: wallC,
        convectionWm2K: convection,
        zoneExteriorAreaMm2: zoneAreas,
        volumeL: environment.volumeL,
        ventilationAch: environment.ventilationAch,
        enclosureUaWPerK: environment.enclosureUaWPerK,
        internalHeatW: environment.internalHeatW,
        externalTemperatureC: environment.externalTemperatureC,
        options,
      });
      const difference = Math.max(...nextAir.map((temperature, zone) => Math.abs(temperature - airC[zone])));
      const partDifference = Math.max(...partC.map((temperature, zone) => Math.abs(temperature - previousPartC[zone])));
      airC = airC.map((temperature, zone) => 0.5 * temperature + 0.5 * nextAir[zone]);
      previousPartC = partC;
      if (difference <= 0.08 && partDifference <= 0.08) break;
    }
    const finalData = validateThermalData(await requestFn("thermalIntegrity", { opts: spatialStageOptions(
      { ...options, thermalOnly: false }, options, environment, airC, wallC, convection, false,
    ) }), "Final steady zoned engine-bay solve");
    finalData.stats = {
      ...finalData.stats,
      finalEnclosureAirTemperatureC: meanNumbers(airC),
      minimumEnclosureAirTemperatureC: Math.min(...airC),
      maximumEnclosureAirTemperatureC: Math.max(...airC),
      peakEnclosureAirTemperatureC: Math.max(...airC),
      finalEnclosureWallTemperatureC: meanNumbers(wallC),
      zonedEnvironmentSolverPasses: iterations + 1,
    };
    finalData.environment = {
      ...environment,
      model: ENGINE_BAY_ZONE_LAYOUT,
      accuracyMode: "zoned_high",
      couplingIterations: iterations,
      solverPasses: iterations + 1,
      zoneExteriorAreaMm2: zoneAreas,
      finalAirTemperaturesC: [...airC],
      finalWallTemperaturesC: [...wallC],
      limitation: "Reduced-order 12-zone steady heat-transfer model; airflow momentum and CFD recirculation are not resolved.",
    };
    return finalData;
  }

  const zonedCreateGroupBase = createGroup;
  createGroup = function createGroupWithZonedEngineBayAccuracy() {
    const group = zonedCreateGroupBase();
    const enclosureFields = group.querySelector("#ti-enclosure-fields");
    enclosureFields?.insertAdjacentHTML("afterbegin", `
      <label class="ti-select"><span>Engine-bay thermal accuracy</span>
        <select id="ti-environmentAccuracy">
          <option value="zoned_high" selected>High — 12 local zones, two correction passes</option>
          <option value="lumped_fast">Fast — one uniform air and wall temperature</option>
        </select>
      </label>
      <div id="ti-zoned-environment-note" class="ti-status dim">High accuracy maps every exposed voxel face to a local 3 × 2 × 2 air/wall zone and performs a predictor plus correction solve for every time stage. It is intentionally slower and can take several minutes on detailed models.</div>
    `);
    return group;
  };

  const zonedCollectOptionsBase = collectOptions;
  collectOptions = function collectOptionsWithZonedEngineBayAccuracy() {
    const options = zonedCollectOptionsBase();
    options.environmentAccuracy = value("environmentAccuracy") || "zoned_high";
    const high = options.environmentAccuracy === "zoned_high"
      && isEngineBayEnvelopeMode(options.environmentMode);
    const environment = environmentOptionsFromInputs(options);
    const initialAir = environment
      ? initialEngineBayZoneAirTemperatures(options, environment)
      : zoneArray(options.ambientTemperatureC);
    const wall = environment
      ? engineBayWallZoneTemperatures(options, options.enclosureWallStartTemperatureC)
      : zoneArray(options.ambientTemperatureC);
    const convection = environment
      ? engineBayZoneConvection(options, environment)
      : zoneArray(options.convectionWm2K);
    options.spatialEnvironmentEnabled = high;
    options.environmentAirTemperaturesC = initialAir;
    options.environmentWallTemperaturesC = wall;
    options.environmentConvectionWm2K = convection;
    options.environmentWallEmissivities = zoneArray(environment?.wallEmissivity ?? options.emissivity);
    return options;
  };

  const zonedSyncEnvironmentUiBase = syncEnvironmentUi;
  syncEnvironmentUi = function syncEnvironmentUiWithZonedAccuracy() {
    zonedSyncEnvironmentUiBase();
    const highControls = document.getElementById("ti-zoned-environment-note");
    highControls?.classList.toggle("ti-hidden", !isEngineBayEnvelopeMode());
    document.getElementById("ti-environmentAccuracy")?.closest("label")
      ?.classList.toggle("ti-hidden", !isEngineBayEnvelopeMode());
  };

  const zonedBindBase = bind;
  bind = function bindWithZonedEngineBayAccuracy(group) {
    zonedBindBase(group);
    group.querySelector("#ti-environmentAccuracy")?.addEventListener("change", () => {
      invalidate("Engine-bay thermal accuracy changed; calculate again.");
    });
  };

  const zonedTransientBase = runTransientEnclosure;
  runTransientEnclosure = async function runTransientEnclosureWithZonedAccuracy(
    options, preflight, status, requestFn,
  ) {
    if (options.environmentAccuracy !== "zoned_high"
        || !isEngineBayEnvelopeMode(options.environmentMode)) {
      return zonedTransientBase(options, preflight, status, requestFn);
    }
    return runTransientZonedEngineBay(options, preflight, status, requestFn);
  };

  const zonedSteadyBase = runSteadyEnclosure;
  runSteadyEnclosure = async function runSteadyEnclosureWithZonedAccuracy(
    options, preflight, status, requestFn,
  ) {
    if (options.environmentAccuracy !== "zoned_high"
        || !isEngineBayEnvelopeMode(options.environmentMode)) {
      return zonedSteadyBase(options, preflight, status, requestFn);
    }
    return runSteadyZonedEngineBay(options, preflight, status, requestFn);
  };

  const zonedRenderResultsBase = renderResults;
  renderResults = function renderResultsWithZonedEngineBayAccuracy() {
    zonedRenderResultsBase();
    if (!latest?.environment || latest.environment.accuracyMode !== "zoned_high") return;
    input("kpis").insertAdjacentHTML("beforeend", [
      kpi("Environment accuracy", "12 local zones"),
      kpi("Environment solver passes", String(latest.environment.solverPasses)),
      kpi("Final local air range", `${format(Math.min(...latest.environment.finalAirTemperaturesC), 1)}–${format(Math.max(...latest.environment.finalAirTemperaturesC), 1)} °C`),
    ].join(""));
  };

  const zonedCollectReportBase = collectReport;
  collectReport = function collectReportWithZonedEngineBayAccuracy() {
    const report = zonedCollectReportBase();
    if (latest?.environment?.accuracyMode === "zoned_high") {
      report.environmentAccuracy = {
        mode: "zoned_high",
        model: ENGINE_BAY_ZONE_LAYOUT,
        correctionPassesPerStage: ENGINE_BAY_CORRECTION_PASSES,
        solverPasses: Number(latest.environment.solverPasses),
        finalAirTemperaturesC: latest.environment.finalAirTemperaturesC,
        finalWallTemperaturesC: latest.environment.finalWallTemperaturesC,
        zoneExteriorAreaMm2: latest.environment.zoneExteriorAreaMm2,
      };
    }
    return report;
  };

  window.EnderSlicerZonedEnvironmentTestApi = Object.freeze({
    ENGINE_BAY_ZONE_COUNT,
    ENGINE_BAY_ZONE_LAYOUT,
    ENGINE_BAY_CORRECTION_PASSES,
    zoneCoordinates,
    initialEngineBayZoneAirTemperatures,
    engineBayWallZoneTemperatures,
    engineBayZoneConvection,
    zonedAirNetworkStep,
    steadyZonedAirNetworkStep,
  });
