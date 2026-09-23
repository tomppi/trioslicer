  // The base Nearby Hot Object runtime is intentionally kept intact. These
  // wrappers add the enclosure UI and intercept only its final thermal request;
  // open-air behavior still follows the original single-solve path byte-for-byte.
  const enclosureCreateGroupBase = createGroup;
  createGroup = function createGroupWithEnvironment() {
    const group = enclosureCreateGroupBase();
    const firstGrid = group.querySelector(".ti-grid");
    firstGrid?.insertAdjacentHTML("beforeend", `
      <label class="ti-select"><span>Environment</span><select id="ti-environmentMode">
        <option value="open">Open air</option>
        <option value="engine_running">Engine bay — engine running</option>
        <option value="engine_heat_soak">Engine bay — heat soak after shutdown</option>
        <option value="ventilated_enclosure">Ventilated enclosure</option>
        <option value="sealed_enclosure">Sealed enclosure</option>
        <option value="custom">Custom enclosed space</option>
      </select></label>
    `);
    const pick = group.querySelector(".ti-pick");
    pick?.insertAdjacentHTML("beforebegin", `
      <div id="ti-enclosure-note" class="ti-status dim">Open air keeps the original fixed ambient convection and radiation boundary.</div>
      <div id="ti-enclosure-fields" class="ti-hidden">
        <div class="ti-grid">
          ${field("enclosureVolumeL", "Free enclosed air volume (L)", 20, 0.1)}
          ${field("enclosureInitialAirTemperatureC", "Initial enclosed air temperature (°C)", 23, 0.1)}
          ${field("enclosureWallStartTemperatureC", "Initial wall / engine-bay radiant temperature (°C)", 23, 0.1)}
          ${field("enclosureWallEndTemperatureC", "Final wall / engine-bay radiant temperature (°C)", 23, 0.1)}
          ${field("enclosureWallRampSeconds", "Wall-temperature ramp time (s)", 0, 1)}
          ${field("enclosureVentilationAch", "Ventilation (air changes/hour)", 0.1, 0.1)}
          ${field("enclosureUaWPerK", "Air-to-wall heat transfer UA (W/K)", 2, 0.1)}
          ${field("enclosureInternalHeatW", "Heat released directly into enclosed air (W)", 0, 0.1)}
          ${field("enclosureCoveragePercent", "Part surface inside enclosure (%)", 100, 1)}
          ${field("enclosureWallEmissivity", "Enclosure wall emissivity", 0.8, 0.01)}
          ${field("externalConvectionWm2K", "Open/external convection (W/m²K)", 5, 0.1)}
        </div>
      </div>
    `);
    const ambientLabel = group.querySelector("#ti-ambientTemperatureC")?.closest("label")?.querySelector("span");
    if (ambientLabel) ambientLabel.textContent = "Outside / ventilation intake temperature (°C)";
    const convectionLabel = group.querySelector("#ti-convectionWm2K")?.closest("label")?.querySelector("span");
    if (convectionLabel) convectionLabel.textContent = "Part-to-enclosed-air convection (W/m²K)";
    return group;
  };

  const enclosureCollectOptionsBase = collectOptions;
  collectOptions = function collectOptionsWithEnvironment() {
    const options = enclosureCollectOptionsBase();
    options.environmentMode = value("environmentMode");
    options.sourcePartEmissivity = options.emissivity;
    options.enclosureVolumeL = finite(value("enclosureVolumeL"), "enclosed air volume", 0.01, 1_000_000);
    options.enclosureInitialAirTemperatureC = finite(value("enclosureInitialAirTemperatureC"), "initial enclosed air temperature", -200, 1000);
    options.enclosureWallStartTemperatureC = finite(value("enclosureWallStartTemperatureC"), "initial enclosure wall temperature", -200, 1500);
    options.enclosureWallEndTemperatureC = finite(value("enclosureWallEndTemperatureC"), "final enclosure wall temperature", -200, 1500);
    options.enclosureWallRampSeconds = finite(value("enclosureWallRampSeconds"), "wall-temperature ramp time", 0, 31_536_000);
    options.enclosureVentilationAch = finite(value("enclosureVentilationAch"), "ventilation rate", 0, 100_000);
    options.enclosureUaWPerK = finite(value("enclosureUaWPerK"), "enclosure air-to-wall UA", 0, 1_000_000);
    options.enclosureInternalHeatW = finite(value("enclosureInternalHeatW"), "enclosure internal air heating", -100_000, 100_000);
    options.enclosureCoveragePercent = finite(value("enclosureCoveragePercent"), "enclosure surface coverage", 0, 100);
    options.enclosureWallEmissivity = finite(value("enclosureWallEmissivity"), "enclosure wall emissivity", 0, 1);
    options.externalConvectionWm2K = finite(value("externalConvectionWm2K"), "external convection", 0, 100_000);
    if (options.environmentMode !== "open" && options.enclosureCoveragePercent <= 0) {
      throw new Error("An enclosed-space mode requires more than 0% of the part surface inside the enclosure.");
    }
    return options;
  };

  const enclosureRestoreDraftBase = restoreDraft;
  restoreDraft = function restoreDraftWithEnvironment() {
    enclosureRestoreDraftBase();
    syncEnvironmentUi();
  };

  let activeEnvironmentResult = null;
  let activeEnvironmentPreflight = null;
  const enclosureRunAnalysisBase = runAnalysis;
  runAnalysis = async function runAnalysisWithEnvironment() {
    const nativeRequest = request;
    activeEnvironmentResult = null;
    activeEnvironmentPreflight = null;
    request = async function enclosureAwareRequest(op, payload = {}) {
      if (op === "thermalIntegrity" && payload?.opts?.environmentMode
          && payload.opts.environmentMode !== "open") {
        if (!activeEnvironmentPreflight) throw new Error("Enclosure preflight was not captured.");
        const result = await runEnclosureScenario(
          payload.opts,
          activeEnvironmentPreflight,
          document.getElementById("ti-status"),
          nativeRequest,
        );
        activeEnvironmentResult = result.environment || null;
        return result;
      }
      const result = await nativeRequest(op, payload);
      if (op === "thermalIntegrityPreflight") activeEnvironmentPreflight = result;
      return result;
    };
    try {
      await enclosureRunAnalysisBase();
      const preflightBox = document.getElementById("ti-source-preflight");
      if (preflightBox && Number(activeEnvironmentPreflight?.totalExteriorAreaMm2) > 0) {
        preflightBox.textContent += ` · total environment-exposed area: ${format(activeEnvironmentPreflight.totalExteriorAreaMm2, 2)} mm².`;
      }
      if (activeEnvironmentResult) {
        const status = document.getElementById("ti-status");
        if (status && !status.classList.contains("ti-error")) {
          status.textContent += ` Enclosure air ended at ${format(activeEnvironmentResult.finalAirTemperatureC, 1)} °C (peak ${format(activeEnvironmentResult.peakAirTemperatureC, 1)} °C).`;
        }
      }
    } finally {
      request = nativeRequest;
    }
  };

  const enclosureRenderResultsBase = renderResults;
  renderResults = function renderResultsWithEnvironment() {
    if (latest && activeEnvironmentResult) latest.environment = activeEnvironmentResult;
    enclosureRenderResultsBase();
    if (!latest?.environment) return;
    const environment = latest.environment;
    const kpis = input("kpis");
    kpis.insertAdjacentHTML("beforeend", [
      kpi("Environment", environment.label),
      kpi("Final enclosure air", `${format(environment.finalAirTemperatureC, 2)} °C`),
      kpi("Peak enclosure air", `${format(environment.peakAirTemperatureC, 2)} °C`),
      kpi("Final enclosure walls", `${format(environment.finalWallTemperatureC, 2)} °C`),
      kpi("Environment-exposed area", `${format(environment.exteriorAreaMm2, 2)} mm²`),
    ].join(""));
    const note = input("result-note");
    const energy = environment.energy;
    const additions = [environment.limitation];
    if (energy) {
      additions.push(
        `Integrated selected-object heat: ${format(energy.selectedHotObjectJ / 1000, 3)} kJ.`,
        `Integrated wall radiation into part: ${format(energy.enclosureWallRadiationIntoPartJ / 1000, 3)} kJ.`,
        `Integrated enclosed-air convection into part: ${format(energy.enclosureAirConvectionIntoPartJ / 1000, 3)} kJ.`,
        `Ventilation heat removal from enclosed air: ${format(energy.ventilationLossJ / 1000, 3)} kJ.`,
      );
    }
    note.textContent += `\n${additions.join("\n")}`;
  };

  const enclosureCollectReportBase = collectReport;
  collectReport = function collectReportWithEnvironment() {
    const report = enclosureCollectReportBase();
    if (!latest?.environment) return report;
    report.schemaVersion = Math.max(2, Number(report.schemaVersion) || 0);
    report.solverModel = "nearby-hot-object-plus-lumped-engine-bay-enclosure-v1";
    report.environment = latest.environment;
    report.assumptions = [
      "The printed part retains filaSim's full 3D voxel conduction and thermal/structural result.",
      "Enclosure air is represented by one finite-capacity, well-mixed node; enclosure walls use one prescribed radiant-temperature node.",
      "An equivalent boundary is recalculated at each coupling stage to preserve combined convection and radiation at the current mean part temperature.",
      "Local engine-bay jets, recirculation, stratification, fluid CFD and detailed wall geometry are not resolved.",
      "Built-in environment presets are editable starting assumptions and are not calibrated to a specific vehicle or enclosure.",
    ];
    return report;
  };

  const enclosureBindBase = bind;
  bind = function bindWithEnvironment(group) {
    enclosureBindBase(group);
    group.querySelector("#ti-environmentMode")?.addEventListener("change", (event) => {
      applyEnvironmentPreset(event.target.value);
      invalidate("Environment changed; calculate again.");
    });
    group.querySelector("#ti-mode")?.addEventListener("change", syncEnvironmentUi);
  };

  const enclosureInstallUiBase = installUi;
  installUi = function installUiWithEnvironment() {
    const installed = enclosureInstallUiBase();
    if (installed) syncEnvironmentUi();
    return installed;
  };

