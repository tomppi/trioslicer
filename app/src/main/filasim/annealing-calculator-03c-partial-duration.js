  // Fixed-duration annealing semantics. Whole-part readiness and soak are
  // diagnostics, not prerequisites for returning the final temperature field.
  function optionalFinite(value) {
    if (value === null || value === undefined) return null;
    const number = Number(value);
    return Number.isFinite(number) ? number : null;
  }

  function materialVolumeAtThreshold(result, target, cooling = false) {
    let total = 0;
    let reached = 0;
    for (let index = 0; index < result.temperatures.length; index += 1) {
      const fraction = Number(result.materialFraction[index]);
      if (!(fraction > 1e-7)) continue;
      total += fraction;
      const temperature = Number(result.temperatures[index]);
      if (cooling ? temperature <= target : temperature >= target) reached += fraction;
    }
    return total > 0 ? reached / total : 0;
  }

  const fixedDurationThermalOptionsBase = thermalOptions;
  thermalOptions = function fixedDurationThermalOptions(common, stage, initialField = null) {
    const options = fixedDurationThermalOptionsBase(common, stage);
    options.stopWhenReady = false;
    options.initialTemperatureFieldC = initialField
      ? Array.from(initialField, (temperature) => Number(temperature))
      : null;
    return options;
  };

  const fixedDurationCreateGroupBase = createGroup;
  createGroup = function createFixedDurationGroup() {
    const group = fixedDurationCreateGroupBase();
    const lead = group.querySelector(".ac-lead");
    if (lead) {
      lead.textContent =
        "Simulates the selected oven exposure on the actual voxelized printed part and always returns the end-of-time temperature field. Whole-part core readiness and soak completion are reported as diagnostics; partial heating is a valid result. Cooling starts from the actual nonuniform heating field.";
    }
    const heatingLabel = group.querySelector("#ac-maxHeatingHours")?.closest("label")?.querySelector("span");
    if (heatingLabel) heatingLabel.textContent = "Oven exposure duration";
    const coolingLabel = group.querySelector("#ac-maxCoolingHours")?.closest("label")?.querySelector("span");
    if (coolingLabel) coolingLabel.textContent = "Cooling simulation duration";
    const run = group.querySelector("#ac-run");
    if (run) run.textContent = "Simulate Oven Exposure & Cooling";
    return group;
  };

  async function runFixedDurationCycle() {
    if (runInFlight) return;
    const run = input("run");
    const cancel = input("cancel");
    const status = input("status");
    runInFlight = true;
    latest = null;
    window.dispatchEvent(new CustomEvent(THERMAL_CLEAR_EVENT));
    run.disabled = true;
    cancel.disabled = false;
    input("results").classList.remove("ready");
    status.className = "ac-status dim";
    status.textContent = "Capturing model identity and preparing the oven boundary…";
    beginElapsedClock();
    setProgress("Preflight", 0.01, "Validating inputs and current voxel grid");

    try {
      const common = collectCommon();
      saveDraft(common);
      let transform = null;
      try {
        const pose = await request("transformMatrix");
        if (Array.isArray(pose) && pose.length === 12 && pose.every(Number.isFinite)) transform = pose.slice();
      } catch (error) {
        console.error("Annealing pose capture failed", error);
      }

      const heatingOptions = thermalOptions(common, "heating");
      status.textContent = "Simulating the selected oven exposure from every oven-exposed surface…";
      const heatingRaw = await request("thermalIntegrity", { opts: heatingOptions }, (data) => progress("heating", data));
      const heating = validateResult(heatingRaw, "Heating solve");

      let cooling = null;
      let coolingOptions = null;
      if (common.simulateCooling) {
        setProgress("Preparing cooling", 0.58, "Starting from the exact nonuniform end-of-exposure temperature field");
        status.textContent = "Simulating cooling from the actual heating result…";
        coolingOptions = thermalOptions(common, "cooling", heating.temperatures);
        const coolingRaw = await request("thermalIntegrity", { opts: coolingOptions }, (data) => progress("cooling", data));
        cooling = validateResult(coolingRaw, "Cooling solve");
      }

      const sourceIdentity = {
        sourceName: String(android.sourceFileName?.() || "model.stl"),
        sourceSha256: String(android.sourceSha256?.() || "unknown"),
        upstreamCommit: String(android.upstreamCommit?.() || "unknown"),
      };
      latest = {
        common, heatingOptions, coolingOptions, heating, cooling, transform,
        sourceIdentity, completedAtEpochMillis: Date.now(),
      };
      try { localStorage.setItem(REPORT_KEY, JSON.stringify(buildReport())); } catch (_) { /* optional */ }
      renderResults();
      showHeating3d();
      setProgress("Complete", 1, `Fixed-duration thermal cycle calculated in ${formatDuration((performance.now() - runStartedAt) / 1000, false)}.`);
      const heatComplete = optionalFinite(heating.stats.readinessCompleteTimeSeconds);
      status.className = heatComplete === null ? "ac-status ac-warning" : "ac-status dim";
      status.textContent = heatComplete === null
        ? "Exposure simulated successfully. The requested whole-part soak did not finish within this duration; the partial temperature field is shown and remains a valid result."
        : "Exposure simulated successfully. The requested whole-part soak completed during the selected duration.";
    } catch (error) {
      latest = null;
      status.className = "ac-status ac-error";
      status.textContent = `Annealing calculation failed: ${error?.message || error}`;
      setProgress(/cancel/i.test(String(error)) ? "Cancelled" : "Failed", Number(input("progress-bar").value) / 100, String(error?.message || error));
      console.error("EnderSlicer annealing calculation failed", error);
    } finally {
      runInFlight = false;
      run.disabled = false;
      cancel.disabled = true;
      endElapsedClock();
    }
  }

  // Re-apply the voxel-aware Android workload preflight to the new core cycle.
  runCycle = async function runCycleWithVoxelBudgetAndPartialResults() {
    if (runInFlight || voxelBudgetPreflightInFlight) return;
    const run = input("run");
    const cancel = input("cancel");
    const status = input("status");
    voxelBudgetPreflightInFlight = true;
    run.disabled = true;
    cancel.disabled = true;
    status.className = "ac-status dim";
    status.textContent = "Reading the current voxel grid and planning an Android-safe transient workload…";
    try {
      const voxel = await request("voxelInfo");
      const solidCells = Number(voxel?.solid);
      if (!Number.isSafeInteger(solidCells) || solidCells <= 0) {
        throw new Error("filaSim returned an invalid solid-voxel count during annealing preflight.");
      }
      const budgetApi = window.EnderSlicerAnnealingStepBudget;
      if (!budgetApi?.applyForSolidCells) throw new Error("The annealing workload planner is unavailable.");
      const plan = budgetApi.applyForSolidCells(solidCells, true);
      if (!plan) throw new Error("Unable to plan the annealing transient workload.");
      const maximumWork = Math.max(plan.heatingCellSteps || 0, plan.coolingCellSteps || 0);
      if (maximumWork > plan.maxTransientCellSteps) {
        throw new Error(`Annealing preflight could not reduce the workload below ${plan.maxTransientCellSteps.toLocaleString()} solid-cell steps.`);
      }
      status.textContent =
        `Voxel preflight: ${solidCells.toLocaleString()} solid cells, ${plan.effectiveTimeStepSeconds} s timestep, ` +
        `${plan.heatingSteps.toLocaleString()} heating${plan.coolingSteps ? ` / ${plan.coolingSteps.toLocaleString()} cooling` : ""} steps.`;
      await runFixedDurationCycle();
    } catch (error) {
      latest = null;
      status.className = "ac-status ac-error";
      status.textContent = `Annealing calculation failed: ${error?.message || error}`;
      console.error("EnderSlicer annealing workload preflight failed", error);
    } finally {
      voxelBudgetPreflightInFlight = false;
      if (!runInFlight) {
        run.disabled = false;
        cancel.disabled = true;
      }
    }
  };

  renderResults = function renderFixedDurationResults() {
    if (!latest) return;
    const heatReached = optionalFinite(latest.heating.stats.readinessReachedTimeSeconds);
    const heatComplete = optionalFinite(latest.heating.stats.readinessCompleteTimeSeconds);
    const heatingEnd = Number(latest.heating.stats.finalTimeSeconds);
    const requestedSoak = latest.common.soakMinutes * 60;
    const achievedWholePartSoak = heatReached === null ? 0 : Math.max(0, heatingEnd - heatReached);
    const heatFraction = materialVolumeAtThreshold(latest.heating, latest.common.readinessTemperatureC, false);
    const coolingEnd = latest.cooling ? Number(latest.cooling.stats.finalTimeSeconds) : 0;
    const handlingReached = latest.cooling
      ? optionalFinite(latest.cooling.stats.readinessReachedTimeSeconds)
      : null;
    const handlingFraction = latest.cooling
      ? materialVolumeAtThreshold(latest.cooling, latest.common.handlingTemperatureC, true)
      : null;
    const total = heatingEnd + coolingEnd;

    input("results").classList.add("ready");
    input("kpis").innerHTML = [
      kpi("Oven exposure simulated", formatDuration(heatingEnd)),
      kpi("Whole-part core target first reached", heatReached === null ? "Not reached" : formatDuration(heatReached)),
      kpi("Whole-part soak achieved", `${formatDuration(Math.min(achievedWholePartSoak, requestedSoak))} / ${formatDuration(requestedSoak)}`),
      kpi("Requested whole-part soak", heatComplete === null ? "Incomplete" : `Complete at ${formatDuration(heatComplete)}`),
      kpi("Material volume at/above core target", `${formatNumber(heatFraction * 100, 1)}%`),
      kpi("Cooling duration simulated", latest.cooling ? formatDuration(coolingEnd) : "Not simulated"),
      kpi("Whole part first safe to handle", !latest.cooling ? "Not simulated" : handlingReached === null ? "Not reached" : formatDuration(handlingReached)),
      kpi("Material volume at/below handling target", handlingFraction === null ? "Not simulated" : `${formatNumber(handlingFraction * 100, 1)}%`),
      kpi("Complete simulated timeline", formatDuration(total)),
      kpi("Heating temperature spread", `${formatNumber(Number(latest.heating.stats.maximumTemperatureC) - Number(latest.heating.stats.minimumTemperatureC), 2)} °C`),
      kpi("Cold/mean/hot after oven exposure", `${formatNumber(latest.heating.stats.minimumTemperatureC, 1)} / ${formatNumber(latest.heating.stats.meanTemperatureC, 1)} / ${formatNumber(latest.heating.stats.maximumTemperatureC, 1)} °C`),
      kpi("Voxel grid", `${latest.heating.stats.nx}×${latest.heating.stats.ny}×${latest.heating.stats.nz} · ${formatNumber(latest.heating.stats.h, 2)} mm`),
    ].join("");
    drawHeatmap();

    const notes = [];
    if (heatReached === null) {
      notes.push(`The whole part did not reach ${formatNumber(latest.common.readinessTemperatureC, 1)} °C. ${formatNumber(heatFraction * 100, 1)}% of modeled material volume was at or above that threshold at the end of exposure.`);
    } else if (heatComplete === null) {
      notes.push(`The whole part reached the core target at ${formatDuration(heatReached)}, then accumulated ${formatDuration(achievedWholePartSoak)} of the requested ${formatDuration(requestedSoak)} soak before removal.`);
    } else {
      notes.push(`The requested whole-part soak completed at ${formatDuration(heatComplete)}; the simulation continued to the selected ${formatDuration(heatingEnd)} oven exposure.`);
    }
    notes.push(
      "A region being at or above the selected temperature is not a crystallinity prediction; polymer kinetics and formulation-specific annealing response are not modeled.",
      latest.heating.stats.densityAware
        ? "The printed material field used optimized Smart Infill density."
        : `The material field used ${latest.common.infillPct}% fallback infill plus configured skins.`,
      latest.cooling
        ? "Cooling starts from the exact nonuniform end-of-exposure cell-temperature field."
        : "Cooling was not simulated.",
      `Material profile: ${latest.common.materialName}. Validate the oven schedule and dimensional change with a spool-specific coupon.`,
      "Oven temperature accuracy and airflow dominate boundary uncertainty. Use an independent thermometer and avoid direct contact with heating elements.",
    );
    input("result-note").className = heatComplete === null ? "ac-status ac-warning" : "ac-status dim";
    input("result-note").textContent = notes.join("\n");
  };

  const fixedDurationBuildReportBase = buildReport;
  buildReport = function buildFixedDurationReport() {
    const report = fixedDurationBuildReportBase();
    if (!latest) return report;
    const heatReached = optionalFinite(latest.heating.stats.readinessReachedTimeSeconds);
    const heatComplete = optionalFinite(latest.heating.stats.readinessCompleteTimeSeconds);
    const heatingEnd = Number(latest.heating.stats.finalTimeSeconds);
    const coolingEnd = latest.cooling ? Number(latest.cooling.stats.finalTimeSeconds) : null;
    const handlingReached = latest.cooling
      ? optionalFinite(latest.cooling.stats.readinessReachedTimeSeconds)
      : null;
    report.schemaVersion = Math.max(3, Number(report.schemaVersion) || 0);
    report.analysisKind = "fdm-fixed-duration-geometry-aware-annealing";
    report.schedule = {
      ovenExposureSeconds: heatingEnd,
      wholePartCoreReachedSeconds: heatReached,
      requestedWholePartSoakSeconds: latest.common.soakMinutes * 60,
      achievedWholePartSoakSeconds: heatReached === null ? 0 : Math.max(0, heatingEnd - heatReached),
      wholePartSoakCompleted: heatComplete !== null,
      wholePartSoakCompletedSeconds: heatComplete,
      materialVolumeAtOrAboveTargetFraction: materialVolumeAtThreshold(latest.heating, latest.common.readinessTemperatureC, false),
      coolingSimulatedSeconds: coolingEnd,
      wholePartHandlingTargetReachedSeconds: handlingReached,
      materialVolumeAtOrBelowHandlingTargetFraction: latest.cooling
        ? materialVolumeAtThreshold(latest.cooling, latest.common.handlingTemperatureC, true)
        : null,
      totalSimulatedSeconds: heatingEnd + (coolingEnd || 0),
      handlingTargetC: latest.common.handlingTemperatureC,
    };
    report.assumptions = [
      ...report.assumptions.filter((entry) => !String(entry).includes("Cooling starts conservatively")),
      "Whole-part readiness and soak completion are reported diagnostics; an incomplete or spatially partial heating field is still a valid fixed-duration result.",
      "Cooling, when enabled, starts from the exact nonuniform end-of-exposure temperature field.",
      "Temperature-threshold volume is not a polymer crystallization or annealing-kinetics prediction.",
    ];
    return report;
  };
