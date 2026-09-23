  // filaSim material-profile integration. This runs before the report/listener
  // part, while later function declarations are already hoisted in this IIFE.
  const materialProfiles = window.EnderSlicerFilaSimProfiles;
  let resolvedFilaSimMaterial = null;
  let materialUiInitialized = false;
  let selectedMaterialName = null;

  function annealingDraftMaterialName() {
    try {
      const draft = JSON.parse(localStorage.getItem(STORAGE_KEY) || "null");
      return typeof draft?.preset === "string" ? draft.preset : null;
    } catch (_) { return null; }
  }
  function setInheritedMaterialFields(resolved) {
    for (const [id, fieldValue] of Object.entries({
      densityKgM3: resolved.densityKgM3,
      alphaXyPerK: resolved.alphaXyPerK,
      alphaZPerK: resolved.alphaZPerK,
      youngsModulusMpa: resolved.youngsModulusMpa,
      poissonRatio: resolved.poissonRatio,
      referenceStrengthMpa: resolved.referenceStrengthMpa,
    })) setValue(id, fieldValue);
    for (const id of ["densityKgM3", "alphaXyPerK", "alphaZPerK", "youngsModulusMpa", "poissonRatio", "referenceStrengthMpa"]) {
      const element = document.getElementById(`ac-${id}`);
      if (!element) continue;
      element.readOnly = true;
      element.dataset.filasimSource = "true";
      element.title = "Inherited from the selected filaSim material profile";
    }
  }
  function setComplementFields(resolved) {
    for (const [id, fieldValue] of Object.entries({
      conductivityXWmK: resolved.conductivityXWmK,
      conductivityYWmK: resolved.conductivityYWmK,
      conductivityZWmK: resolved.conductivityZWmK,
      specificHeatJkgK: resolved.specificHeatJkgK,
      serviceLimitC: resolved.serviceLimitC,
      ovenTemperatureC: resolved.ovenTemperatureC,
      soakMinutes: resolved.soakMinutes,
      handlingTemperatureC: resolved.handlingTemperatureC,
      emissivity: resolved.emissivity,
    })) setValue(id, fieldValue);
  }
  function ensureAnnealingMaterialNote() {
    let note = document.getElementById("ac-material-source");
    if (note) return note;
    const group = document.getElementById(GROUP_ID);
    if (!group) return null;
    note = document.createElement("div");
    note.id = "ac-material-source";
    note.className = "ac-status dim";
    const details = group.querySelector("details");
    if (details) group.insertBefore(note, details);
    else group.appendChild(note);
    return note;
  }
  function applyResolvedFilaSimMaterial(name, resetComplements) {
    if (!materialProfiles) return null;
    const resolved = materialProfiles.resolveMaterial(name);
    resolvedFilaSimMaterial = resolved;
    setValue("preset", resolved.name);
    setInheritedMaterialFields(resolved);
    if (resetComplements) setComplementFields(resolved);
    const note = ensureAnnealingMaterialNote();
    if (note) {
      const material = resolved.filaSim;
      note.textContent =
        `Material source: ${resolved.profileSource} · ${resolved.name}. ` +
        `filaSim supplies density, E, ν, strengths, yield, shrink (${(Number(material.shrink || 0) * 100).toFixed(2)}% XY / ${(Number(material.shrinkZ || 0) * 100).toFixed(2)}% Z), locking temperature and CTE. ` +
        `Only conductivity, heat capacity, missing Z-CTE, service limit and annealing schedule are complemented from ${resolved.family}.`;
    }
    const status = document.getElementById("ac-status");
    if (status && !runInFlight && !latest) {
      status.className = `ac-status ${resolved.status === "literature-seeded" ? "dim" : "ac-warning"}`;
      status.textContent = `${resolved.name}: filaSim profile + ${resolved.family} thermal complement (${resolved.status}). Validate the oven schedule and dimensional change with a coupon.`;
    }
    return resolved;
  }
  function syncAnnealingMaterialUi(preferActive = false) {
    if (!materialProfiles) return false;
    const select = document.getElementById("ac-preset");
    if (!select) return false;
    const snapshot = materialProfiles.getSnapshot();
    const materials = materialProfiles.fdmMaterials(snapshot);
    const draftName = annealingDraftMaterialName();
    const desired = preferActive ? snapshot.activeMaterialName : (draftName || select.value || snapshot.activeMaterialName);
    const selected = materialProfiles.populateSelect(select, materials, desired);
    const materialChanged = selected !== selectedMaterialName;
    const resetComplements = materialUiInitialized ? materialChanged : !draftName;
    applyResolvedFilaSimMaterial(selected, resetComplements);
    materialUiInitialized = true;
    selectedMaterialName = selected;
    return true;
  }

  const legacyApplyPreset = applyPreset;
  applyPreset = function applyFilaSimPreset(name) {
    if (!materialProfiles) return legacyApplyPreset(name);
    const resolved = applyResolvedFilaSimMaterial(name, true);
    materialUiInitialized = true;
    selectedMaterialName = resolved?.name || name;
    return resolved;
  };

  const legacyCollectCommon = collectCommon;
  collectCommon = function collectFilaSimCommon() {
    const common = legacyCollectCommon();
    const resolved = materialProfiles?.resolveMaterial(common.materialName) || resolvedFilaSimMaterial;
    if (!resolved) return common;
    return {
      ...common,
      materialName: resolved.name,
      materialFamily: resolved.family,
      materialProfileStatus: resolved.status,
      materialProfileSource: resolved.profileSource,
      materialFieldProvenance: { ...resolved.fieldProvenance },
      filaSimMaterialProfile: { ...resolved.filaSim },
      densityKgM3: resolved.densityKgM3,
      alphaXyPerK: resolved.alphaXyPerK,
      alphaZPerK: resolved.alphaZPerK,
      youngsModulusMpa: resolved.youngsModulusMpa,
      poissonRatio: resolved.poissonRatio,
      referenceStrengthMpa: resolved.referenceStrengthMpa,
    };
  };

  const legacyBuildReport = buildReport;
  buildReport = function buildFilaSimSourcedReport() {
    const report = legacyBuildReport();
    const common = latest?.common;
    if (!common?.filaSimMaterialProfile) return report;
    report.schemaVersion = 2;
    report.material = {
      name: common.materialName,
      profileSource: common.materialProfileSource,
      familyComplement: common.materialFamily,
      profileStatus: common.materialProfileStatus,
      filaSimProfile: { ...common.filaSimMaterialProfile },
      resolvedForThermalSolver: {
        densityKgM3: common.densityKgM3,
        ctePerK: [common.alphaXyPerK, common.alphaXyPerK, common.alphaZPerK],
        youngsModulusMpa: common.youngsModulusMpa,
        poissonRatio: common.poissonRatio,
        conservativeReferenceStrengthMpa: common.referenceStrengthMpa,
      },
      complementedThermalData: {
        conductivityWmK: [common.conductivityXWmK, common.conductivityYWmK, common.conductivityZWmK],
        specificHeatJkgK: common.specificHeatJkgK,
        serviceLimitC: common.serviceLimitC,
      },
      fieldProvenance: { ...common.materialFieldProvenance },
    };
    report.assumptions = [
      ...report.assumptions,
      "Density, elastic properties, strengths, shrink, locking temperature and available CTE values come from the selected live filaSim material profile; only absent thermal/process fields are complemented.",
    ];
    return report;
  };

  const legacyAttachListeners = attachListeners;
  attachListeners = function attachFilaSimMaterialListeners(group) {
    const select = input("preset");
    select.addEventListener("change", () => materialProfiles?.setActiveMaterial(select.value), true);
    legacyAttachListeners(group);
  };

  const legacyInstallUi = installUi;
  installUi = function installFilaSimMaterialUi() {
    const installed = legacyInstallUi();
    if (installed) syncAnnealingMaterialUi(false);
    return installed;
  };

  if (materialProfiles) {
    window.addEventListener(materialProfiles.eventName, () => syncAnnealingMaterialUi(true));
  }
