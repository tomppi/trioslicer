  function compensationScale(before, after) {
    const initial = finite(before, "before dimension", 0.000001, 1e9);
    const final = finite(after, "after dimension", 0.000001, 1e9);
    return initial / final;
  }
  function calculateCalibration() {
    const axes = ["X", "Y", "Z"].map((axis) => {
      const before = finite(value(`before${axis}`), `before ${axis}`, 0.000001, 1e9);
      const after = finite(value(`after${axis}`), `after ${axis}`, 0.000001, 1e9);
      return { axis, before, after, shrinkPct: (before - after) / before * 100, scale: compensationScale(before, after) };
    });
    const output = axes.map((item) => `${item.axis}: ${item.shrinkPct >= 0 ? "shrink" : "growth"} ${Math.abs(item.shrinkPct).toFixed(3)}% · print scale ${(item.scale * 100).toFixed(3)}%`).join("\n");
    input("calibration-result").className = "ac-cal-result";
    input("calibration-result").textContent = output;
    return axes;
  }
  function readCalibrationProfiles() {
    try {
      const profiles = JSON.parse(localStorage.getItem(CALIBRATION_KEY) || "[]");
      return Array.isArray(profiles) ? profiles : [];
    } catch (_) { return []; }
  }
  function refreshCalibrationProfiles(selected = "") {
    const select = input("calibrationProfile");
    const profiles = readCalibrationProfiles();
    select.innerHTML = '<option value="">None</option>' + profiles.map((profile, index) => `<option value="${index}"${String(index) === String(selected) ? " selected" : ""}>${String(profile.name).replace(/[<>&]/g, "")}</option>`).join("");
  }
  function saveCalibration() {
    const axes = calculateCalibration();
    const name = String(value("calibrationName") || "").trim();
    if (!name) throw new Error("Calibration profile name is required.");
    const profiles = readCalibrationProfiles();
    const profile = {
      schemaVersion: 1, name, materialName: value("preset"), ovenTemperatureC: Number(value("ovenTemperatureC")),
      soakMinutes: Number(value("soakMinutes")), axes, savedAtEpochMillis: Date.now(),
    };
    const existing = profiles.findIndex((entry) => entry.name === name);
    if (existing >= 0) profiles[existing] = profile; else profiles.push(profile);
    localStorage.setItem(CALIBRATION_KEY, JSON.stringify(profiles));
    refreshCalibrationProfiles(existing >= 0 ? existing : profiles.length - 1);
    input("calibration-result").textContent += `\nSaved as “${name}”.`;
  }
  function loadCalibrationProfile() {
    const rawIndex = value("calibrationProfile");
    if (rawIndex === "") return;
    const index = Number(rawIndex);
    if (!Number.isInteger(index)) return;
    const profile = readCalibrationProfiles()[index];
    if (!profile) return;
    setValue("calibrationName", profile.name);
    for (const item of profile.axes || []) {
      setValue(`before${item.axis}`, item.before);
      setValue(`after${item.axis}`, item.after);
    }
    calculateCalibration();
  }

  function buildReport() {
    if (!latest) throw new Error("Calculate an annealing cycle first.");
    const heatReached = Number(latest.heating.stats.readinessReachedTimeSeconds);
    const heatComplete = Number(latest.heating.stats.readinessCompleteTimeSeconds);
    const cooling = latest.cooling ? Number(latest.cooling.stats.readinessCompleteTimeSeconds) : null;
    return {
      schemaVersion: 1,
      analysisKind: "fdm-geometry-aware-annealing-cycle",
      solverModel: "voxel-finite-volume-implicit-oven-convection-radiation-v1",
      precisionSource: "raw-worker-response",
      ...latest.sourceIdentity,
      generatedAtEpochMillis: latest.completedAtEpochMillis,
      pose: { transform3x4: latest.transform },
      material: {
        name: latest.common.materialName,
        profileStatus: PRESETS[latest.common.materialName]?.status || "custom",
        conductivityWmK: [latest.common.conductivityXWmK, latest.common.conductivityYWmK, latest.common.conductivityZWmK],
        densityKgM3: latest.common.densityKgM3,
        specificHeatJkgK: latest.common.specificHeatJkgK,
      },
      oven: {
        setTemperatureC: latest.common.ovenTemperatureC,
        coreTargetC: latest.common.readinessTemperatureC,
        toleranceC: latest.common.targetToleranceC,
        convectionWm2K: latest.common.convectionWm2K,
        emissivity: latest.common.emissivity,
        startingTemperatureC: latest.common.initialTemperatureC,
      },
      schedule: {
        coreHeatUpSeconds: heatReached,
        soakSeconds: heatComplete - heatReached,
        removeFromOvenSeconds: heatComplete,
        coolingSeconds: cooling,
        totalCycleSeconds: heatComplete + (cooling || 0),
        handlingTargetC: latest.common.handlingTemperatureC,
      },
      grid: {
        nx: latest.heating.stats.nx, ny: latest.heating.stats.ny, nz: latest.heating.stats.nz,
        voxelSizeMm: latest.heating.stats.h, activeCells: latest.heating.stats.activeCells,
        densityAware: latest.heating.stats.densityAware,
      },
      ovenRemovalField: {
        minimumTemperatureC: latest.heating.stats.minimumTemperatureC,
        meanTemperatureC: latest.heating.stats.meanTemperatureC,
        maximumTemperatureC: latest.heating.stats.maximumTemperatureC,
        timeSteps: latest.heating.stats.timeSteps,
        finalTimeSeconds: latest.heating.stats.finalTimeSeconds,
      },
      assumptions: [
        "All exterior-connected printed-part surfaces exchange heat with uniform oven ambient by convection and radiation.",
        "No direct heating-element contact, enclosure airflow CFD, polymer crystallization kinetics, viscoelastic sag, or phase change is modeled.",
        "Cooling starts conservatively from a uniformly oven-temperature part.",
        "Material properties and annealing shrinkage require filament-specific coupon validation.",
      ],
    };
  }

  async function copyReport() {
    const text = JSON.stringify(buildReport(), null, 2);
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
    } else {
      const area = document.createElement("textarea");
      area.value = text; area.style.position = "fixed"; area.style.opacity = "0";
      document.body.appendChild(area); area.select();
      if (!document.execCommand("copy")) throw new Error("Clipboard copy is unavailable.");
      area.remove();
    }
    input("status").textContent = "Annealing cycle report copied as JSON.";
  }

  function invalidate(message) {
    latest = null;
    const results = document.getElementById("ac-results");
    results?.classList.remove("ready");
    window.dispatchEvent(new CustomEvent(THERMAL_CLEAR_EVENT));
    const status = document.getElementById("ac-status");
    if (status && message && !runInFlight) {
      status.className = "ac-status dim";
      status.textContent = message;
    }
  }

  function attachListeners(group) {
    input("preset").addEventListener("change", () => { applyPreset(value("preset")); invalidate("Material profile changed; calculate the cycle again."); });
    input("ovenMode").addEventListener("change", () => { syncOvenMode(); invalidate("Oven airflow changed; calculate the cycle again."); });
    input("run").addEventListener("click", runCycle);
    input("cancel").addEventListener("click", cancelRun);
    input("show3d").addEventListener("click", showHeating3d);
    input("copy-report").addEventListener("click", () => copyReport().catch((error) => { input("status").className = "ac-status ac-error"; input("status").textContent = `Unable to copy report: ${error.message}`; }));
    input("calculate-calibration").addEventListener("click", () => { try { calculateCalibration(); } catch (error) { input("calibration-result").className = "ac-cal-result ac-error"; input("calibration-result").textContent = error.message; } });
    input("save-calibration").addEventListener("click", () => { try { saveCalibration(); } catch (error) { input("calibration-result").className = "ac-cal-result ac-error"; input("calibration-result").textContent = error.message; } });
    input("calibrationProfile").addEventListener("change", loadCalibrationProfile);
    group.addEventListener("input", (event) => {
      const id = event.target?.id || "";
      if (id.startsWith("ac-") && !id.includes("calibration") && !id.startsWith("ac-before") && !id.startsWith("ac-after") && id !== "ac-run") {
        invalidate("Annealing inputs changed; calculate the cycle again.");
      }
    });
  }

  function installUi() {
    installStyle();
    const mount = document.getElementById(MOUNT_ID);
    if (!mount) return false;
    if (document.getElementById(GROUP_ID)) return true;
    const group = createGroup();
    mount.appendChild(group);
    restoreDraft();
    syncOvenMode();
    refreshCalibrationProfiles();
    attachListeners(group);
    if (runInFlight) input("run").disabled = true;
    if (latest) renderResults();
    return true;
  }

  // Pure helpers are exposed solely for deterministic Node contract tests.
  window.EnderSlicerAnnealingTestApi = Object.freeze({
    compensationScale,
    formatDuration,
    validateCycleInputs(values) {
      const oven = finite(values.ovenTemperatureC, "oven", -100, 300);
      const initial = finite(values.initialTemperatureC, "initial", -100, 250);
      const tolerance = finite(values.targetToleranceC, "tolerance", 0.1, 30);
      const handling = finite(values.handlingTemperatureC, "handling", -100, 250);
      const room = finite(values.roomTemperatureC, "room", -100, 250);
      if (oven - tolerance <= initial) throw new Error("core target must exceed initial");
      if (handling <= room || handling >= oven) throw new Error("handling target is invalid");
      return true;
    },
  });

  installWorkerAccess();
  window.addEventListener(INVALIDATED_EVENT, (event) => {
    invalidate(event?.detail?.message || "The model or solver settings changed; calculate the annealing cycle again.");
  });
  installUi();
  observer = new MutationObserver(installUi);
  observer.observe(document.documentElement, { childList: true, subtree: true });
})();
