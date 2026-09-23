  // Automatic source-to-model projection, full-model explanation and visible enclosure walls.
  const ENCLOSURE_BOX_EVENT = "enderslicer-nearby-hot-object-enclosure-box";

  function sourceRequest(sourceId, selection, gapId, diameterId, label) {
    const request = {
      sourceId,
      label,
      gapMm: finite(value(gapId), `${label} source distance`, 0, 100000),
      diameterMm: finite(value(diameterId), `${label} source diameter`, 0.1, 100000),
      autoPlace: !selection,
    };
    if (selection?.point?.length === 3 && selection?.normal?.length === 3) {
      request.target = selection.point.map(Number);
      request.normal = selection.normal.map(Number);
    }
    return request;
  }

  combinedHeatSourceMarkers = function combinedHeatSourceMarkersAutomatic() {
    const toggle = document.getElementById("ti-showSourceMarkers");
    if (toggle && !toggle.checked) return null;
    const markers = [sourceRequest(
      1,
      selected,
      "sourceGapMm",
      "sourceDiameterMm",
      "Primary",
    )];
    if (checked("source2Enabled")) {
      markers.push(sourceRequest(
        2,
        secondarySelected,
        "source2GapMm",
        "source2DiameterMm",
        "Turbo / exhaust",
      ));
    }
    return { markers };
  };

  beginHeatSourceDrag = function beginAutomaticallyProjectedHeatSourceDrag(sourceId) {
    if (sourceId === 2 && !checked("source2Enabled")) {
      const status = input("status");
      status.className = "ti-status ti-warning";
      status.textContent = "Enable the turbo / exhaust source before moving it.";
      return;
    }
    renderCombinedHeatSourceMarkers();
    window.dispatchEvent(new CustomEvent(HEAT_SOURCE_DRAG_MODE_EVENT, { detail: sourceId }));
    const status = input("status");
    status.className = "ti-status dim";
    status.textContent = sourceId === 2
      ? "Move the blue heat source in X/Y. Hold the source sphere still for 5 seconds to switch this drag to Z-only."
      : "Move the orange heat source in X/Y. Hold the source sphere still for 5 seconds to switch this drag to Z-only.";
  };

  function enclosureBoxDimensions() {
    return {
      widthMm: finite(value("enclosureWidthMm"), "enclosure width", 0.1, 100000),
      depthMm: finite(value("enclosureDepthMm"), "enclosure depth", 0.1, 100000),
      heightMm: finite(value("enclosureHeightMm"), "enclosure height", 0.1, 100000),
      offsetXmm: finite(value("enclosureOffsetXmm"), "enclosure X offset", -100000, 100000),
      offsetYmm: finite(value("enclosureOffsetYmm"), "enclosure Y offset", -100000, 100000),
      offsetZmm: finite(value("enclosureOffsetZmm"), "enclosure Z offset", -100000, 100000),
    };
  }

  function enclosureVolumeFromDimensions(dimensions) {
    return dimensions.widthMm * dimensions.depthMm * dimensions.heightMm / 1_000_000;
  }

  function syncCalculatedEnclosureVolume() {
    try {
      const dimensions = enclosureBoxDimensions();
      setValue("enclosureVolumeL", enclosureVolumeFromDimensions(dimensions).toFixed(3));
    } catch (_) { /* normal validation is shown during calculation */ }
  }

  function enclosureBoxDetail() {
    if (value("environmentMode") === "open" || !checked("showEnclosureWalls")) return null;
    return { visible: true, ...enclosureBoxDimensions() };
  }

  function renderEnclosureBox() {
    let detail = null;
    try { detail = enclosureBoxDetail(); } catch (_) { /* normal validation is shown on run */ }
    window.dispatchEvent(new CustomEvent(ENCLOSURE_BOX_EVENT, { detail }));
  }

  function applyEnclosureBoxPreset(mode) {
    const presets = {
      engine_running: [800, 600, 500],
      engine_heat_soak: [800, 600, 500],
      ventilated_enclosure: [500, 400, 400],
      sealed_enclosure: [300, 300, 300],
    };
    let dimensions = presets[mode];
    if (!dimensions && mode === "custom") {
      const volume = Math.max(0.001, Number(value("enclosureVolumeL")) || 27);
      const side = Math.cbrt(volume * 1_000_000);
      dimensions = [side, side, side];
    }
    if (!dimensions) return;
    setValue("enclosureWidthMm", Number(dimensions[0]).toFixed(1));
    setValue("enclosureDepthMm", Number(dimensions[1]).toFixed(1));
    setValue("enclosureHeightMm", Number(dimensions[2]).toFixed(1));
    setValue("enclosureOffsetXmm", 0);
    setValue("enclosureOffsetYmm", 0);
    setValue("enclosureOffsetZmm", 0);
    syncCalculatedEnclosureVolume();
    renderEnclosureBox();
  }

  const spatialCreateGroupBase = createGroup;
  createGroup = function createGroupWithSpatialEnvironment() {
    const group = spatialCreateGroupBase();
    const primaryPickButton = group.querySelector("#ti-pick-source");
    if (primaryPickButton) primaryPickButton.style.display = "none";
    const primarySelection = group.querySelector("#ti-source-selection");
    if (primarySelection) {
      primarySelection.textContent = "The nearest model surface is derived automatically from the movable heat-source position.";
    }
    const secondaryPickButton = group.querySelector("#ti-pick-source2");
    if (secondaryPickButton) secondaryPickButton.style.display = "none";
    const secondarySelection = group.querySelector("#ti-source2-selection");
    if (secondarySelection) {
      secondarySelection.textContent = "The nearest model surface is derived automatically for this heat source.";
    }
    const primaryPick = primaryPickButton?.closest(".ti-pick");
    primaryPick?.insertAdjacentHTML("afterbegin", `
      <div class="ti-status dim"><b>No model-point marking is required.</b> The heat source, complete model and environment define the simulation. Radiation is evaluated on every visible exterior voxel face and heat then conducts through the entire 3D model.</div>
    `);
    const dragHelp = primaryPick?.querySelector(".ti-status.dim:last-child");
    if (dragHelp) {
      dragHelp.textContent = "The line joins the automatically calculated closest model surface to the source centre. Normal dragging is global X/Y only. Hold the heat-source sphere still for 5 seconds, then drag to move only in global Z.";
    }
    const enclosureGrid = group.querySelector("#ti-enclosure-fields .ti-grid");
    enclosureGrid?.insertAdjacentHTML("afterbegin", `
      ${field("enclosureWidthMm", "Enclosure inside width X (mm)", 800, 1)}
      ${field("enclosureDepthMm", "Enclosure inside depth Y (mm)", 600, 1)}
      ${field("enclosureHeightMm", "Enclosure inside height Z (mm)", 500, 1)}
      ${field("enclosureOffsetXmm", "Enclosure centre offset X (mm)", 0, 1)}
      ${field("enclosureOffsetYmm", "Enclosure centre offset Y (mm)", 0, 1)}
      ${field("enclosureOffsetZmm", "Enclosure centre offset Z (mm)", 0, 1)}
    `);
    const enclosureFields = group.querySelector("#ti-enclosure-fields");
    enclosureFields?.insertAdjacentHTML("afterbegin", `
      ${checkbox("showEnclosureWalls", "Show the enclosure / engine-bay walls in 3D", true)}
      <div class="ti-status dim">These visible inside dimensions define the enclosed-air volume used by the environment calculation. The box is centred on the model plus the offsets below. Air and wall temperature remain lumped uniform nodes rather than CFD.</div>
    `);
    const volumeInput = group.querySelector("#ti-enclosureVolumeL");
    if (volumeInput) {
      volumeInput.readOnly = true;
      const label = volumeInput.closest("label")?.querySelector("span");
      if (label) label.textContent = "Calculated enclosed air volume (L)";
    }
    const drag1 = group.querySelector("#ti-drag-source1");
    if (drag1) drag1.textContent = "Move primary heat source";
    const drag2 = group.querySelector("#ti-drag-source2");
    if (drag2) drag2.textContent = "Move turbo / exhaust heat source";
    return group;
  };

  const spatialCollectOptionsBase = collectOptions;
  collectOptions = function collectOptionsWithSpatialEnvironment() {
    const options = spatialCollectOptionsBase();
    const dimensions = enclosureBoxDimensions();
    options.enclosureWidthMm = dimensions.widthMm;
    options.enclosureDepthMm = dimensions.depthMm;
    options.enclosureHeightMm = dimensions.heightMm;
    options.enclosureOffsetXmm = dimensions.offsetXmm;
    options.enclosureOffsetYmm = dimensions.offsetYmm;
    options.enclosureOffsetZmm = dimensions.offsetZmm;
    options.enclosureVolumeL = enclosureVolumeFromDimensions(dimensions);
    setValue("enclosureVolumeL", options.enclosureVolumeL.toFixed(3));
    options.sourcePlacementMode = "movable-source-automatic-nearest-surface";
    options.thermalDomain = "entire-voxel-model";
    return options;
  };

  const spatialRenderSelectionBase = renderSelection;
  renderSelection = function renderAutomaticPrimaryProjection() {
    spatialRenderSelectionBase();
    const box = document.getElementById("ti-source-selection");
    if (!box) return;
    if (!selected) {
      box.textContent = "Automatic closest-surface projection is initializing from the heat-source position.";
      return;
    }
    const p = selected.point.map((number) => Number(number).toFixed(2)).join(", ");
    box.textContent = `Automatic closest surface: ${p} mm. This is only the source geometry anchor; the entire model is calculated.`;
  };

  const spatialRenderSecondaryBase = renderSecondarySelection;
  renderSecondarySelection = function renderAutomaticSecondaryProjection() {
    spatialRenderSecondaryBase();
    const box = document.getElementById("ti-source2-selection");
    if (!box) return;
    if (!secondarySelected) {
      box.textContent = "Automatic closest-surface projection is initializing for this heat source.";
      return;
    }
    const p = secondarySelected.point.map((number) => Number(number).toFixed(2)).join(", ");
    box.textContent = `Automatic closest surface: ${p} mm. Radiation is still evaluated over the entire visible model surface.`;
  };

  const spatialApplyEngineScenarioBase = applyEngineScenario;
  applyEngineScenario = function applyEngineScenarioWithSpatialWalls(name) {
    spatialApplyEngineScenarioBase(name);
    const preset = ENGINE_SCENARIO_PRESETS[name];
    if (preset?.environmentMode) applyEnclosureBoxPreset(preset.environmentMode);
    renderCombinedHeatSourceMarkers();
  };

  const spatialRestoreDraftBase = restoreDraft;
  restoreDraft = function restoreDraftWithSpatialEnvironment() {
    spatialRestoreDraftBase();
    syncCalculatedEnclosureVolume();
    renderEnclosureBox();
    renderCombinedHeatSourceMarkers();
  };

  const spatialSyncEnvironmentUiBase = syncEnvironmentUi;
  syncEnvironmentUi = function syncEnvironmentUiWithWalls() {
    spatialSyncEnvironmentUiBase();
    syncCalculatedEnclosureVolume();
    renderEnclosureBox();
  };

  const spatialBindBase = bind;
  bind = function bindWithSpatialEnvironment(group) {
    spatialBindBase(group);
    const dimensionIds = [
      "enclosureWidthMm",
      "enclosureDepthMm",
      "enclosureHeightMm",
      "enclosureOffsetXmm",
      "enclosureOffsetYmm",
      "enclosureOffsetZmm",
    ];
    for (const id of dimensionIds) {
      group.querySelector(`#ti-${id}`)?.addEventListener("change", () => {
        syncCalculatedEnclosureVolume();
        renderEnclosureBox();
        invalidate("Enclosure geometry changed; calculate again.");
      });
    }
    group.querySelector("#ti-showEnclosureWalls")?.addEventListener("change", renderEnclosureBox);
    group.querySelector("#ti-environmentMode")?.addEventListener("change", (event) => {
      applyEnclosureBoxPreset(event.target.value);
    });
  };

  const spatialInstallUiBase = installUi;
  installUi = function installUiWithSpatialEnvironment() {
    const installed = spatialInstallUiBase();
    if (installed) {
      syncCalculatedEnclosureVolume();
      renderEnclosureBox();
      renderCombinedHeatSourceMarkers();
    }
    return installed;
  };

  window.addEventListener(CLEAR_EVENT, () => {
    window.setTimeout(() => {
      renderCombinedHeatSourceMarkers();
      renderEnclosureBox();
    }, 0);
  });
