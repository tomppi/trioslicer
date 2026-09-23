  async function runAnalysis() {
    const run = input("run"), save = input("save"), status = input("status");
    if (runInFlight) return;
    runInFlight = true; run.disabled = true; save.disabled = true;
    status.className = "ti-status dim"; status.textContent = "Calculating source visibility…";
    const epoch = analysisEpoch;
    try {
      const options = collectOptions();
      saveDraft(options);
      const voxel = await request("voxelInfo");
      const solidCells = Number(voxel?.solid || 0);
      if (options.mode === "transient" && solidCells * Math.ceil(options.durationSeconds / options.timeStepSeconds) > 120_000_000) {
        throw new Error("Transient workload exceeds the Android safety budget. Increase voxel size or time step.");
      }
      const preflight = await request("thermalIntegrityPreflight", { opts: options });
      const preflightBox = document.getElementById("ti-source-preflight");
      if (preflightBox) {
        preflightBox.className = "ti-status dim";
        preflightBox.textContent = `Visible surface: ${format(preflight.heatedAreaMm2, 2)} mm² · initial absorbed heat: ${format(preflight.sourceInitialAbsorbedW, 4)} W · effective flux: ${format(Number(preflight.effectiveHeatFluxWm2) / 1000, 3)} kW/m².`;
      }
      let transform = null;
      try {
        const pose = await request("transformMatrix");
        if (Array.isArray(pose) && pose.length === 12 && pose.every(Number.isFinite)) transform = pose.slice();
      } catch (error) { console.error("Nearby Hot Object pose capture failed", error); }
      status.textContent = options.mode === "transient"
        ? `Calculating the 3D temperature after ${format(options.durationSeconds, 1)} seconds…`
        : "Calculating steady 3D temperature and coupled structural response…";
      const data = await request("thermalIntegrity", { opts: options });
      const stats = data?.stats, temperatures = data?.temperatures, history = data?.history;
      const displacements = data?.displacements, materialFraction = data?.materialFraction;
      const vertexTemperatures = data?.vertexTemperatures;
      if (!stats || !(temperatures instanceof Float32Array) || !(history instanceof Float64Array)
          || !(displacements instanceof Float32Array) || !(materialFraction instanceof Float32Array)
          || !(vertexTemperatures instanceof Float32Array)) {
        throw new Error("filaSim returned an incomplete nearby-hot-object result.");
      }
      const expectedCells = Number(stats.nx) * Number(stats.ny) * Number(stats.nz);
      if (!Number.isSafeInteger(expectedCells) || temperatures.length !== expectedCells
          || materialFraction.length !== expectedCells || vertexTemperatures.length * 3 !== displacements.length) {
        throw new Error("The returned temperature field does not match the model/grid.");
      }
      if (epoch !== analysisEpoch) throw new Error("Inputs changed during the solve; the stale result was discarded.");
      latest = { options, stats, temperatures, history, displacements, materialFraction,
        vertexTemperatures, transform, completedAtEpochMillis: Date.now() };
      renderResults();
      window.dispatchEvent(new CustomEvent(THERMAL_RESULT_EVENT, { detail: {
        vertexTemperatures, displacements,
        minimumTemperatureC: Number(stats.minimumTemperatureC),
        maximumTemperatureC: Number(stats.maximumTemperatureC),
        structuralValid: stats.structuralValid === true,
        maxDisplacementMm: stats.structuralValid === true ? Number(stats.maxDisplacementMm) : null,
      }}));
      save.disabled = !transform || stats.structuralValid !== true || typeof android.captureThermalIntegrityReport !== "function";
      status.className = stats.structuralValid === true ? "ti-status dim" : "ti-status ti-warning";
      status.textContent = stats.structuralValid === true
        ? "Complete. The model now shows the final temperature in 3D colours; tap/hover it to read °C."
        : `Temperature solved and displayed in 3D. Structural FEA was skipped: ${stats.materialValidityReason || "outside material range"}`;
    } catch (error) {
      status.className = "ti-status ti-error";
      status.textContent = `Nearby Hot Object failed: ${error?.message || error}`;
      console.error("Nearby Hot Object failed", error);
    } finally { runInFlight = false; run.disabled = false; }
  }

  function format(raw, digits = 3) {
    const number = Number(raw); if (!Number.isFinite(number)) return "—";
    return number.toLocaleString(undefined, { maximumFractionDigits: digits });
  }
  function kpi(label, display) { return `<div class="ti-kpi"><b>${display}</b><span>${label}</span></div>`; }
  function renderResults() {
    if (!latest) return;
    const { stats } = latest;
    input("results").classList.add("ready");
    const structuralValid = stats.structuralValid === true;
    input("kpis").innerHTML = [
      kpi("Maximum temperature", `${format(stats.maximumTemperatureC, 2)} °C`),
      kpi("Mean temperature", `${format(stats.meanTemperatureC, 2)} °C`),
      kpi("Minimum temperature", `${format(stats.minimumTemperatureC, 2)} °C`),
      kpi("Margin to service limit", `${format(stats.temperatureMarginC, 2)} °C`),
      kpi("Heat absorbed from object", `${format(stats.sourceAbsorbedW ?? stats.heatInputW, 4)} W`),
      kpi("Radiatively visible area", `${format(stats.heatedAreaMm2, 2)} mm²`),
      kpi("Effective incident flux", `${format(Number(stats.effectiveHeatFluxWm2) / 1000, 3)} kW/m²`),
      kpi("Thermal deformation", structuralValid ? `${format(stats.maxDisplacementMm, 5)} mm` : "Not calculated"),
    ].join("");
    const notes = [
      "The 3D object is coloured by the final calculated temperature. The legend and model probe use °C.",
      "The hot object is approximated as a diffuse sphere; diameter means the effective radiating hot region.",
      "Self-shadowing is included with a one-time voxel visibility pass. Ambient convection and radiation remain active on exposed surfaces.",
    ];
    if (Number(stats.energyBalanceRelative) > 0.05) notes.push("Warning: energy imbalance exceeds 5%; refine the grid or time step.");
    if (!structuralValid) notes.push(`Structural result unavailable: ${stats.materialValidityReason || "temperature outside the material model"}.`);
    const note = input("result-note"); note.className = "ti-status dim"; note.textContent = notes.join("\n");
  }

  function collectReport() {
    if (!latest?.transform) throw new Error("Run the calculation with a captured pose first.");
    const o = latest.options, s = latest.stats;
    return {
      schemaVersion: 1, solverModel: "nearby-diffuse-hot-sphere-view-factor-v1",
      generatedAtEpochMillis: latest.completedAtEpochMillis,
      sourceName: String(android.sourceFileName()), sourceSha256: String(android.sourceSha256()),
      upstreamCommit: String(android.upstreamCommit()), modelTransform3x4: latest.transform,
      material: {
        name:o.materialName, propertyBasis:"filaSim material plus thermal complements",
        conductivityXWmK:o.conductivityXWmK, conductivityYWmK:o.conductivityYWmK, conductivityZWmK:o.conductivityZWmK,
        densityKgM3:o.densityKgM3, specificHeatJkgK:o.specificHeatJkgK, conductivityExponent:o.conductivityExponent,
        alphaXyPerK:o.alphaXyPerK, alphaZPerK:o.alphaZPerK, youngsModulusMpa:o.youngsModulusMpa,
        poissonRatio:o.poissonRatio, referenceStrengthMpa:o.referenceStrengthMpa,
        strengthDensityExponent:o.strengthDensityExponent, referenceTemperatureC:o.referenceTemperatureC,
        serviceLimitC:o.serviceLimitC, modulusFloorFraction:o.modulusFloorFraction,
        strengthFloorFraction:o.strengthFloorFraction,
      },
      boundary: { ...o, heatedFace:"picked-nearest-point", heatPowerW:0, volumetricPowerW:0 },
      mesh: { voxelSizeMm:s.h, nx:s.nx, ny:s.ny, nz:s.nz, activeCells:s.activeCells },
      results: { ...s, solverSeconds:0, historyPoints:latest.history.length / 3 },
    };
  }
  function saveReport() {
    try { android.captureThermalIntegrityReport(JSON.stringify(collectReport())); }
    catch (error) { const status = input("status"); status.className = "ti-status ti-error"; status.textContent = `Report failed: ${error?.message || error}`; }
  }
  function toggleFixedFields() {
    const box = document.getElementById("ti-fixed-fields");
    if (box) box.classList.toggle("ti-hidden", !checked("useFixedTemperatureSurface"));
  }

  function bind(group) {
    group.querySelector("#ti-pick-source")?.addEventListener("click", () => {
      window.dispatchEvent(new CustomEvent(PICK_MODE_EVENT, { detail: true }));
      const status = input("status"); status.className = "ti-status dim"; status.textContent = "Tap the model point nearest the hot object.";
    });
    group.querySelector("#ti-run")?.addEventListener("click", runAnalysis);
    group.querySelector("#ti-save")?.addEventListener("click", saveReport);
    group.querySelector("#ti-preset")?.addEventListener("change", (event) => applyPreset(event.target.value));
    group.querySelector("#ti-useFixedTemperatureSurface")?.addEventListener("change", () => { toggleFixedFields(); invalidate("Mounting boundary changed; calculate again."); });
    for (const element of group.querySelectorAll("input, select")) {
      if (["ti-preset", "ti-useFixedTemperatureSurface"].includes(element.id)) continue;
      element.addEventListener("change", () => { if (["ti-sourceGapMm", "ti-sourceDiameterMm"].includes(element.id)) updateMarker(); invalidate("Inputs changed; calculate again."); });
    }
  }

  function installUi() {
    installStyle();
    const mount = document.getElementById("enderslicer-thermal-integrity-mount");
    if (!mount) return false;
    let group = document.getElementById(GROUP_ID);
    if (!group) { group = createGroup(); mount.appendChild(group); bind(group); restoreDraft(); }
    else if (group.parentElement !== mount) mount.appendChild(group);
    renderSelection(); toggleFixedFields(); updateMarker();
    return true;
  }

  window.addEventListener(PICK_EVENT, (event) => {
    const point = event?.detail?.point, normal = event?.detail?.normal;
    if (!Array.isArray(point) || point.length !== 3 || !Array.isArray(normal) || normal.length !== 3) return;
    selected = { point: point.map(Number), normal: normal.map(Number) };
    renderSelection(); updateMarker(); invalidate("Source position selected; calculate the result.");
  });
  window.addEventListener(CLEAR_EVENT, () => {
    selected = null; renderSelection();
    window.dispatchEvent(new CustomEvent(MARKER_EVENT, { detail: null }));
    invalidate("The model changed. Select the nearest point again.");
  });

  installWorkerAccess();
  installUi();
  new MutationObserver(installUi).observe(document.documentElement, { childList:true, subtree:true });
})();
