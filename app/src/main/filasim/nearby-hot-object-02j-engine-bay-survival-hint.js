  // Conservative decision hint for engine-bay placement. This deliberately
  // reports a screening verdict rather than certification. The source
  // temperatures come from EnderSlicer's existing generic engine presets;
  // the transient timing follows Gao et al. (Scientific Reports 2024).
  const ENGINE_BAY_HINT_REFERENCE = Object.freeze({
    id: "gao-2024-suv-key-off-soak",
    label: "Gao et al. SUV key-off heat soak (Scientific Reports, 2024)",
    doi: "10.1038/s41598-024-70521-4",
    durationSeconds: 400,
    fanHoldSeconds: 30,
    earlyRadiationConvectionSeconds: 120,
    publishedComponentAgreementC: 10,
  });

  const ENGINE_BAY_HINT_PROFILES = Object.freeze({
    off: Object.freeze({
      label: "No decision hint",
      uncertaintyC: 0,
    }),
    generic_conservative: Object.freeze({
      label: "Generic engine-bay screening — conservative",
      uncertaintyC: 25,
      explanation: "Existing generic engine/turbo source presets plus a 25 °C reduced-order uncertainty buffer.",
    }),
    paper_suv_400s_soak: Object.freeze({
      label: "Published SUV — 400 s key-off heat soak",
      uncertaintyC: 20,
      explanation: "Existing EnderSlicer engine/exhaust temperatures with the published 400 s soak timing, 30 s fan hold and 0–120 s radiation/convection-dominant period.",
      scenario: Object.freeze({
        environmentMode: "engine_heat_soak",
        ambientTemperatureC: 25,
        initialTemperatureC: 60,
        convectionWm2K: 8,
        enclosureInitialAirTemperatureC: 70,
        enclosureWallStartTemperatureC: 90,
        enclosureWallEndTemperatureC: 135,
        enclosureWallRampSeconds: 120,
        enclosureVentilationAch: 3,
        enclosureUaWPerK: 8,
        enclosureInternalHeatW: 0,
        sourceType: "engine_surface",
        sourceTemperatureC: 115,
        sourceEndTemperatureC: 100,
        sourceRampSeconds: 400,
        source2Enabled: true,
        source2Type: "turbo_high",
        source2TemperatureC: 750,
        source2EndTemperatureC: 450,
        source2RampSeconds: 400,
        durationSeconds: 400,
        timeStepSeconds: 10,
      }),
    }),
  });

  const HINT_PEAK_KEYS = new Set([
    "maximumtemperaturec",
    "maxtemperaturec",
    "peaktemperaturec",
    "peakparttemperaturec",
    "maxparttemperaturec",
    "maximumtemperature",
    "maxtemperature",
  ]);
  const HINT_SAFETY_KEYS = new Set([
    "minimumsafetyfactor",
    "minsafetyfactor",
    "safetyfactor",
  ]);

  function hintProfileOptions() {
    return Object.entries(ENGINE_BAY_HINT_PROFILES).map(([key, profile]) =>
      `<option value="${key}">${profile.label}</option>`).join("");
  }

  function applyHintProfile(name) {
    const profile = ENGINE_BAY_HINT_PROFILES[name];
    if (!profile) return;
    if (profile.scenario) {
      setValue("engineScenario", "custom");
      applyEnvironmentPreset(profile.scenario.environmentMode);
      applyEnclosureBoxPreset(profile.scenario.environmentMode);
      applySourceType("source", profile.scenario.sourceType);
      if (profile.scenario.source2Enabled) applySourceType("source2", profile.scenario.source2Type);
      Object.entries(profile.scenario).forEach(([key, presetValue]) => setValue(key, presetValue));
      const source2Enabled = document.getElementById("ti-source2Enabled");
      if (source2Enabled) source2Enabled.checked = Boolean(profile.scenario.source2Enabled);
      syncSource2Ui();
      syncEnvironmentUi();
      renderCombinedHeatSourceMarkers();
    }
    const note = document.getElementById("ti-engine-hint-note");
    if (note) {
      note.textContent = name === "off"
        ? "No pass/caution/fail screening verdict will be added."
        : `${profile.explanation} This is a thermal screening hint, not vehicle certification.`;
    }
  }

  function finiteNumber(value) {
    if (value === null || value === undefined || value === "") return null;
    const number = Number(value);
    return Number.isFinite(number) ? number : null;
  }

  function directNumber(root, paths) {
    for (const path of paths) {
      let value = root;
      for (const key of path) value = value?.[key];
      const number = finiteNumber(value);
      if (number !== null) return number;
    }
    return null;
  }

  function scanNamedNumbers(root, acceptedKeys, maximumDepth = 5) {
    if (!root || typeof root !== "object") return [];
    const queue = [{ value: root, depth: 0 }];
    const visited = new Set();
    const found = [];
    while (queue.length) {
      const entry = queue.shift();
      if (!entry?.value || typeof entry.value !== "object" || visited.has(entry.value)) continue;
      visited.add(entry.value);
      for (const [key, value] of Object.entries(entry.value)) {
        const normalized = key.toLowerCase().replace(/[^a-z0-9]/g, "");
        const number = finiteNumber(value);
        if (acceptedKeys.has(normalized) && number !== null) found.push(number);
        if (entry.depth < maximumDepth && value && typeof value === "object" && !ArrayBuffer.isView(value)) {
          queue.push({ value, depth: entry.depth + 1 });
        }
      }
    }
    return found;
  }

  function peakTemperatureFromLatest(result) {
    const direct = directNumber(result, [
      ["maxTemperatureC"],
      ["maximumTemperatureC"],
      ["peakTemperatureC"],
      ["thermal", "maxTemperatureC"],
      ["thermal", "maximumTemperatureC"],
      ["summary", "maxTemperatureC"],
      ["result", "maxTemperatureC"],
    ]);
    if (direct !== null) return direct;
    const values = scanNamedNumbers(result, HINT_PEAK_KEYS);
    if (values.length) return Math.max(...values);
    const kpis = document.querySelectorAll?.("#ti-kpis .ti-kpi") || [];
    for (const card of kpis) {
      const label = card.querySelector?.("span")?.textContent?.toLowerCase() || "";
      if (!label.includes("temperature") || (!label.includes("max") && !label.includes("peak"))) continue;
      const parsed = Number.parseFloat(card.querySelector?.("b")?.textContent || "");
      if (Number.isFinite(parsed)) return parsed;
    }
    return null;
  }

  function safetyFactorFromLatest(result) {
    const direct = directNumber(result, [
      ["minimumSafetyFactor"],
      ["minSafetyFactor"],
      ["safetyFactor"],
      ["structural", "minimumSafetyFactor"],
      ["structural", "safetyFactor"],
      ["summary", "minimumSafetyFactor"],
    ]);
    if (direct !== null) return direct;
    const values = scanNamedNumbers(result, HINT_SAFETY_KEYS);
    return values.length ? Math.min(...values) : null;
  }

  function hasThermalInvalidity(result) {
    const text = [
      result?.thermalValidity,
      result?.materialValidity,
      result?.status,
      document.getElementById?.("ti-result-note")?.textContent,
      document.getElementById?.("ti-status")?.textContent,
    ].filter(Boolean).join(" ").toLowerCase();
    return text.includes("outside material model")
      || text.includes("thermal failure")
      || text.includes("material invalid");
  }

  function classifyThermalHint({
    peakTemperatureC,
    serviceLimitC,
    uncertaintyC,
    safetyFactor = null,
    thermallyInvalid = false,
  }) {
    const peak = finiteNumber(peakTemperatureC);
    const limit = finiteNumber(serviceLimitC);
    const uncertainty = Math.max(0, finiteNumber(uncertaintyC) ?? 0);
    const safety = finiteNumber(safetyFactor);
    if (peak === null || limit === null) {
      return Object.freeze({
        verdict: "UNKNOWN",
        severity: "unknown",
        explanation: "The solver result did not expose a usable peak part temperature.",
        peakTemperatureC: peak,
        conservativePeakTemperatureC: null,
        serviceLimitC: limit,
        uncertaintyC: uncertainty,
        safetyFactor: safety,
      });
    }
    const conservativePeak = peak + uncertainty;
    const cautionBandC = Math.max(5, Math.min(15, Math.abs(limit) * 0.1));
    let verdict = "LIKELY OK";
    let severity = "pass";
    let explanation = `Conservative peak ${conservativePeak.toFixed(1)} °C remains below the ${limit.toFixed(1)} °C service limit.`;
    if (thermallyInvalid || conservativePeak >= limit || (safety !== null && safety < 1)) {
      verdict = "UNLIKELY TO WORK";
      severity = "fail";
      explanation = thermallyInvalid
        ? "The calculated field is outside the selected material model."
        : conservativePeak >= limit
          ? `Conservative peak ${conservativePeak.toFixed(1)} °C reaches or exceeds the ${limit.toFixed(1)} °C service limit.`
          : `Calculated minimum safety factor ${safety.toFixed(2)} is below 1.0.`;
    } else if (conservativePeak >= limit - cautionBandC || (safety !== null && safety < 1.5)) {
      verdict = "CAUTION / TEST REQUIRED";
      severity = "caution";
      explanation = conservativePeak >= limit - cautionBandC
        ? `Conservative peak ${conservativePeak.toFixed(1)} °C is within ${cautionBandC.toFixed(1)} °C of the service limit.`
        : `Calculated minimum safety factor ${safety.toFixed(2)} is below the 1.5 screening margin.`;
    }
    return Object.freeze({
      verdict,
      severity,
      explanation,
      peakTemperatureC: peak,
      conservativePeakTemperatureC: conservativePeak,
      serviceLimitC: limit,
      uncertaintyC: uncertainty,
      safetyFactor: safety,
    });
  }

  function currentHintAssessment() {
    const profileName = value("thermalHintProfile");
    const profile = ENGINE_BAY_HINT_PROFILES[profileName] || ENGINE_BAY_HINT_PROFILES.off;
    if (profileName === "off") return null;
    return Object.freeze({
      ...classifyThermalHint({
        peakTemperatureC: peakTemperatureFromLatest(latest),
        serviceLimitC: value("serviceLimitC"),
        uncertaintyC: profile.uncertaintyC,
        safetyFactor: safetyFactorFromLatest(latest),
        thermallyInvalid: hasThermalInvalidity(latest),
      }),
      profile: profileName,
      profileLabel: profile.label,
      reference: profileName === "paper_suv_400s_soak" ? ENGINE_BAY_HINT_REFERENCE : null,
      limitation: "Screening hint only. It does not resolve vehicle-specific airflow, ageing, creep, fatigue, fuel/oil exposure, vibration or certified polymer allowables.",
    });
  }

  const hintCreateGroupBase = createGroup;
  createGroup = function createGroupWithEngineBayHint() {
    const group = hintCreateGroupBase();
    const firstGrid = group.querySelector(".ti-grid");
    firstGrid?.insertAdjacentHTML("beforeend", `
      <label class="ti-select"><span>Will it work? thermal hint</span><select id="ti-thermalHintProfile">${hintProfileOptions()}</select></label>
    `);
    firstGrid?.insertAdjacentHTML("afterend", `
      <div id="ti-engine-hint-note" class="ti-status dim">No pass/caution/fail screening verdict will be added.</div>
    `);
    return group;
  };

  const hintCollectOptionsBase = collectOptions;
  collectOptions = function collectOptionsWithEngineBayHint() {
    const options = hintCollectOptionsBase();
    const profileName = value("thermalHintProfile");
    const profile = ENGINE_BAY_HINT_PROFILES[profileName] || ENGINE_BAY_HINT_PROFILES.off;
    options.thermalHintProfile = profileName;
    options.thermalHintUncertaintyC = profile.uncertaintyC;
    options.thermalHintReference = profileName === "paper_suv_400s_soak" ? ENGINE_BAY_HINT_REFERENCE : null;
    return options;
  };

  const hintRenderResultsBase = renderResults;
  renderResults = function renderResultsWithEngineBayHint() {
    hintRenderResultsBase();
    const assessment = currentHintAssessment();
    if (!assessment) return;
    latest.decisionHint = assessment;
    const kpis = input("kpis");
    kpis.insertAdjacentHTML("beforeend", [
      kpi("Thermal placement hint", assessment.verdict),
      kpi("Conservative peak", assessment.conservativePeakTemperatureC === null
        ? "Not available"
        : `${format(assessment.conservativePeakTemperatureC, 1)} °C`),
      kpi("Hint uncertainty buffer", `+${format(assessment.uncertaintyC, 1)} °C`),
    ].join(""));
    const note = input("result-note");
    note.textContent += `\n${assessment.explanation}`;
    note.textContent += `\n${assessment.profileLabel}. ${assessment.limitation}`;
    if (assessment.reference) {
      note.textContent += `\nReference timing: ${assessment.reference.durationSeconds} s soak; first ${assessment.reference.fanHoldSeconds} s fan-stabilized; radiation/convection dominant through approximately ${assessment.reference.earlyRadiationConvectionSeconds} s. DOI ${assessment.reference.doi}.`;
    }
  };

  const hintCollectReportBase = collectReport;
  collectReport = function collectReportWithEngineBayHint() {
    const report = hintCollectReportBase();
    const assessment = latest?.decisionHint || currentHintAssessment();
    if (!assessment) return report;
    report.schemaVersion = Math.max(3, Number(report.schemaVersion) || 0);
    report.decisionHint = assessment;
    report.assumptions = [
      ...(Array.isArray(report.assumptions) ? report.assumptions : []),
      "The decision hint adds a conservative temperature buffer to the simulated peak before comparing it with the selected material service limit.",
      "The published paper's validation error is not treated as the accuracy of this reduced-order model; an additional simplification allowance is included in the default 20 °C paper profile buffer.",
      "Pass/caution/fail is a placement-screening aid and requires physical underhood validation before use.",
    ];
    return report;
  };

  const hintRestoreDraftBase = restoreDraft;
  restoreDraft = function restoreDraftWithEngineBayHint() {
    hintRestoreDraftBase();
    const element = document.getElementById("ti-thermalHintProfile");
    if (element && !ENGINE_BAY_HINT_PROFILES[element.value]) element.value = "off";
    applyHintProfile(element?.value || "off");
  };

  const hintBindBase = bind;
  bind = function bindWithEngineBayHint(group) {
    hintBindBase(group);
    group.querySelector("#ti-thermalHintProfile")?.addEventListener("change", (event) => {
      applyHintProfile(event.target.value);
      invalidate("Thermal decision-hint profile changed; calculate again.");
    });
  };

  const hintInstallUiBase = installUi;
  installUi = function installUiWithEngineBayHint() {
    const installed = hintInstallUiBase();
    if (installed) applyHintProfile(value("thermalHintProfile"));
    return installed;
  };

  window.EnderSlicerThermalHintTestApi = Object.freeze({
    ENGINE_BAY_HINT_REFERENCE,
    ENGINE_BAY_HINT_PROFILES,
    classifyThermalHint,
  });
