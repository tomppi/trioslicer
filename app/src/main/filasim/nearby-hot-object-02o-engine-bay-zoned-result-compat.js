  // Preserve the scalar environment summary expected by the existing result UI
  // while retaining all 12 local values in the high-accuracy report.
  function normalizeZonedEnvironmentResult(result) {
    const environment = result?.environment;
    if (!environment || environment.accuracyMode !== "zoned_high") return result;
    const air = zoneArray(environment.finalAirTemperaturesC);
    const walls = zoneArray(environment.finalWallTemperaturesC);
    const areas = zoneArray(environment.zoneExteriorAreaMm2);
    environment.finalAirTemperatureC = meanNumbers(air);
    environment.peakAirTemperatureC = Number.isFinite(Number(environment.peakAirTemperatureC))
      ? Number(environment.peakAirTemperatureC)
      : Math.max(...air);
    environment.finalWallTemperatureC = meanNumbers(walls);
    environment.exteriorAreaMm2 = areas.reduce((sum, area) => sum + Number(area), 0);
    if (result.stats) {
      result.stats.finalEnclosureAirTemperatureC = environment.finalAirTemperatureC;
      result.stats.peakEnclosureAirTemperatureC = environment.peakAirTemperatureC;
      result.stats.finalEnclosureWallTemperatureC = environment.finalWallTemperatureC;
      result.stats.totalExteriorAreaMm2 = environment.exteriorAreaMm2;
      if (Number.isFinite(Number(result.stats.thermalIterations))) {
        result.stats.iterations = Number(result.stats.thermalIterations);
      }
    }
    return result;
  }

  const zonedTransientResultCompatBase = runTransientZonedEngineBay;
  runTransientZonedEngineBay = async function runTransientZonedEngineBayWithResultCompatibility(
    ...args
  ) {
    return normalizeZonedEnvironmentResult(await zonedTransientResultCompatBase(...args));
  };

  const zonedSteadyResultCompatBase = runSteadyZonedEngineBay;
  runSteadyZonedEngineBay = async function runSteadyZonedEngineBayWithResultCompatibility(
    ...args
  ) {
    return normalizeZonedEnvironmentResult(await zonedSteadyResultCompatBase(...args));
  };

  const zonedSolverReportBase = collectReport;
  collectReport = function collectReportWithCorrectZonedSolverModel() {
    const report = zonedSolverReportBase();
    if (latest?.environment?.accuracyMode !== "zoned_high") return report;
    report.solverModel = "nearby-hot-object-plus-12-zone-engine-bay-v1";
    report.assumptions = [
      "The printed part uses filaSim's full 3D voxel conduction and thermo-mechanical result.",
      "Each exposed voxel face exchanges heat with one of 12 local engine-bay air/wall zones.",
      "Transient stages use a full predictor and correction part solve before advancing physical time.",
      "Inter-zone heat transport is a conservative reduced-order mixing and buoyancy network, not CFD.",
      "Velocity, pressure, turbulence, fan jets and detailed recirculation are not resolved.",
      "Built-in vehicle temperatures and heat-transfer coefficients are editable screening assumptions rather than vehicle certification data.",
    ];
    return report;
  };

  window.EnderSlicerZonedResultCompatTestApi = Object.freeze({
    normalizeZonedEnvironmentResult,
  });
