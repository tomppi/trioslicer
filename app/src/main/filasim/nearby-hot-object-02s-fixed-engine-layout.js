  // Fixed engine assembly with hot-component layout presets. The engine is a
  // rigid cluster of hot components (engine block plus a closely coupled
  // turbocharger) defined at fixed world-space offsets from an engine anchor.
  // The engine anchor is FIXED at the model's bounding-box centre so the
  // engine always sits in the middle of the bay; it cannot be dragged around.
  // Only rotation (yaw) is adjustable. The block maps to the existing primary
  // source and the turbo to the existing secondary source, reusing the
  // calibrated two-source radiative exchange.
  const ENGINE_LAYOUT_PRESETS = Object.freeze({
    custom: Object.freeze({ label: "Custom engine layout", components: [] }),
    engine_turbo_cluster: Object.freeze({
      label: "Engine + turbocharger cluster",
      description: "Engine block with a closely coupled turbocharger on the exhaust side.",
      components: [
        Object.freeze({
          slot: "primary",
          label: "Engine block",
          offsetMm: Object.freeze([0, 0, 0]),
          temperatureC: 110,
          endTemperatureC: 125,
          diameterMm: 180,
          emissivity: 0.82,
          gapMm: 25,
        }),
        Object.freeze({
          slot: "secondary",
          label: "Turbocharger",
          offsetMm: Object.freeze([160, 60, 40]),
          temperatureC: 850,
          endTemperatureC: 950,
          diameterMm: 120,
          emissivity: 0.88,
          gapMm: 40,
        }),
      ],
    }),
  });

  const ENGINE_BBOX_EVENT = "enderslicer-nearby-hot-object-model-bbox";
  let engineModelBbox = null;

  function engineLayoutName() {
    return value("engineLayout") || "custom";
  }

  function engineLayoutActive() {
    const name = engineLayoutName();
    return name !== "custom" && Boolean(ENGINE_LAYOUT_PRESETS[name]);
  }

  function engineLayoutComponents() {
    const preset = ENGINE_LAYOUT_PRESETS[engineLayoutName()];
    return preset?.components || [];
  }

  function engineLayoutComponent(slot) {
    return engineLayoutComponents().find((component) => component.slot === slot) || null;
  }

  function engineLayoutOptions() {
    return Object.entries(ENGINE_LAYOUT_PRESETS)
      .map(([key, preset]) => `<option value="${key}">${preset.label}</option>`)
      .join("");
  }

  function engineRotationDeg() {
    return finite(value("engineRotationDeg"), "engine rotation", -360, 360);
  }

  function enginePitchDeg() {
    return finite(value("enginePitchDeg"), "engine pitch", -90, 90);
  }

  function engineRollDeg() {
    return finite(value("engineRollDeg"), "engine roll", -180, 180);
  }

  function engineModelCenter() {
    if (!Array.isArray(engineModelBbox) || engineModelBbox.length !== 6) return [0, 0, 0];
    return [
      (Number(engineModelBbox[0]) + Number(engineModelBbox[3])) * 0.5,
      (Number(engineModelBbox[1]) + Number(engineModelBbox[4])) * 0.5,
      (Number(engineModelBbox[2]) + Number(engineModelBbox[5])) * 0.5,
    ];
  }

  // Rotate a vector by yaw (Z), then pitch (Y), then roll (X) in degrees,
  // applied in the fixed engine's local frame so the whole cluster rotates as
  // a rigid body about the model centre.
  function engineRotateVector(offset) {
    const yaw = engineRotationDeg() * Math.PI / 180;
    const pitch = enginePitchDeg() * Math.PI / 180;
    const roll = engineRollDeg() * Math.PI / 180;
    const cx = Math.cos(roll);
    const sx = Math.sin(roll);
    const cy = Math.cos(yaw);
    const sy = Math.sin(yaw);
    const cp = Math.cos(pitch);
    const sp = Math.sin(pitch);
    let [x, y, z] = offset.map(Number);
    // Yaw about Z.
    [x, y] = [x * cy - y * sy, x * sy + y * cy];
    // Pitch about Y.
    [x, z] = [x * cp + z * sp, -x * sp + z * cp];
    // Roll about X.
    [y, z] = [y * cx - z * sx, y * sx + z * cx];
    return [x, y, z];
  }

  // Fixed world-space component centre: the model centre plus the rotated
  // layout offset. Rotation spins the whole cluster around the engine centre.
  function engineComponentTarget(slot) {
    const component = engineLayoutComponent(slot);
    const centre = engineModelCenter();
    if (!component) return null;
    const rotated = engineRotateVector(component.offsetMm);
    return [
      centre[0] + rotated[0],
      centre[1] + rotated[1],
      centre[2] + rotated[2],
    ];
  }

  function engineSourceMarker(slot) {
    const component = engineLayoutComponent(slot);
    const target = engineComponentTarget(slot);
    if (!component || !target) return null;
    const base = {
      center: target,
      gapMm: Number(component.gapMm),
      diameterMm: Number(component.diameterMm),
      rotationDeg: engineRotationDeg(),
      pitchDeg: enginePitchDeg(),
      rollDeg: engineRollDeg(),
    };
    if (slot === "primary") {
      return {
        ...base,
        shape: "engine",
        blockLengthMm: Number(component.blockLengthMm ?? component.diameterMm * 1.6),
        blockWidthMm: Number(component.blockWidthMm ?? component.diameterMm),
        blockHeightMm: Number(component.blockHeightMm ?? component.diameterMm * 0.8),
      };
    }
    return {
      ...base,
      shape: "turbo",
      turboDiameterMm: Number(component.turboDiameterMm ?? component.diameterMm),
      turboLengthMm: Number(component.turboLengthMm ?? component.diameterMm * 0.7),
    };
  }

  const engineLayoutCreateGroupBase = createGroup;
  createGroup = function createGroupWithFixedEngineLayout() {
    const group = engineLayoutCreateGroupBase();
    const scenarioSelect = group.querySelector("#ti-engineScenario")?.closest("label");
    scenarioSelect?.insertAdjacentHTML("afterend", `
      <label class="ti-select"><span>Engine layout preset</span>
        <select id="ti-engineLayout">${engineLayoutOptions()}</select>
      </label>
      <div id="ti-engine-layout-fields" class="ti-hidden">
        <div class="ti-status dim"><b>Fixed engine assembly.</b> The engine is centred in the middle of the model. Its position is fixed; only the rotation below is adjustable. Rotating spins the whole block + turbo cluster together.</div>
        <div class="ti-grid">
          ${field("engineRotationDeg", "Engine yaw / rotation (degrees)", 0, 1)}
          ${field("enginePitchDeg", "Engine pitch (degrees)", 0, 1)}
          ${field("engineRollDeg", "Engine roll (degrees)", 0, 1)}
          ${field("forcedConvectionWm2K", "Forced-air cooling (W/m²K)", 0, 1)}
        </div>
        <div class="ti-status dim">Forced-air cooling adds a fan/ram-air convection coefficient on every exposed face of the part. 0 = natural convection only; typical cooling fans add 20-60 W/m²K.</div>
        <div id="ti-engine-layout-detail" class="ti-status dim">No engine layout selected.</div>
      </div>
    `);
    return group;
  };

  function syncEngineLayoutUi() {
    const active = engineLayoutActive();
    const fields = document.getElementById("ti-engine-layout-fields");
    if (fields) fields.classList.toggle("ti-hidden", !active);
    const detail = document.getElementById("ti-engine-layout-detail");
    if (!detail) return;
    if (!active) {
      detail.textContent = "No engine layout selected.";
      return;
    }
    const preset = ENGINE_LAYOUT_PRESETS[engineLayoutName()];
    const parts = engineLayoutComponents().map((component) => {
      const target = engineComponentTarget(component.slot);
      const position = target ? `@ ${target.map((n) => format(n, 1)).join(", ")} mm` : "@ —";
      return `${component.label} ${position} · ${format(component.temperatureC, 0)} °C · ${format(component.diameterMm, 0)} mm`;
    });
    detail.textContent = `${preset.label}: ${parts.join(" | ")}`;
  }

  function applyEngineLayout(name) {
    const preset = ENGINE_LAYOUT_PRESETS[name];
    if (!preset || name === "custom") {
      syncEngineLayoutUi();
      return;
    }
    const block = engineLayoutComponent("primary");
    const turbo = engineLayoutComponent("secondary");
    if (block) {
      applySourceType("source", "engine_surface");
      setValue("sourceTemperatureC", block.temperatureC);
      setValue("sourceEndTemperatureC", block.endTemperatureC);
      setValue("sourceDiameterMm", block.diameterMm);
      setValue("sourceEmissivity", block.emissivity);
      setValue("sourceGapMm", block.gapMm);
    }
    if (turbo) {
      const enabled = document.getElementById("ti-source2Enabled");
      if (enabled) enabled.checked = true;
      applySourceType("source2", "turbo_moderate");
      setValue("source2TemperatureC", turbo.temperatureC);
      setValue("source2EndTemperatureC", turbo.endTemperatureC);
      setValue("source2DiameterMm", turbo.diameterMm);
      setValue("source2Emissivity", turbo.emissivity);
      setValue("source2GapMm", turbo.gapMm);
      syncSource2Ui();
    }
    syncEngineLayoutUi();
    renderCombinedHeatSourceMarkers();
  }

  const engineLayoutCollectOptionsBase = collectOptions;
  collectOptions = function collectOptionsWithFixedEngineLayout() {
    const options = engineLayoutCollectOptionsBase();
    options.engineLayout = engineLayoutName();
    if (!engineLayoutActive()) return options;
    const block = engineLayoutComponent("primary");
    const turbo = engineLayoutComponent("secondary");
    const blockTarget = engineComponentTarget("primary");
    if (!block || !blockTarget) {
      throw new Error("Unable to position the engine block at the model centre.");
    }
    // The engine is fixed in viewer/world space (it stays put while the printed
    // part is moved by 02p). Convert the world-space component centres to the
    // fixed solver voxel-grid frame, mirroring 02p's placement translation.
    const placement = (typeof partPlacementVector === "function") ? partPlacementVector() : [0, 0, 0];
    const toSolver = (typeof thermalPointFromViewer === "function")
      ? (point) => thermalPointFromViewer(point, placement)
      : (point) => point.map(Number);
    options.sourceTargetMm = toSolver(blockTarget);
    options.sourceGapMm = Number(block.gapMm);
    options.sourceDiameterMm = Number(block.diameterMm);
    options.sourceTemperatureC = Number(options.sourceTemperatureC);
    options.sourceShape = "engine";
    options.sourceBlockLengthMm = Number(block.blockLengthMm ?? block.diameterMm * 1.6);
    options.sourceBlockWidthMm = Number(block.blockWidthMm ?? block.diameterMm);
    options.sourceBlockHeightMm = Number(block.blockHeightMm ?? block.diameterMm * 0.8);
    if (turbo) {
      const turboTarget = engineComponentTarget("secondary");
      if (!turboTarget) throw new Error("Unable to position the engine turbocharger.");
      options.source2Enabled = true;
      options.source2TargetMm = toSolver(turboTarget);
      options.source2GapMm = Number(turbo.gapMm);
      options.source2DiameterMm = Number(turbo.diameterMm);
      options.source2TemperatureC = Number(options.source2TemperatureC);
      options.source2Shape = "turbo";
      options.source2TurboDiameterMm = Number(turbo.turboDiameterMm ?? turbo.diameterMm);
      options.source2TurboLengthMm = Number(turbo.turboLengthMm ?? turbo.diameterMm * 0.7);
    } else {
      options.source2Enabled = false;
    }
    options.engineRotationDeg = engineRotationDeg();
    options.enginePitchDeg = enginePitchDeg();
    options.engineRollDeg = engineRollDeg();
    options.engineAnchorMm = engineModelCenter().map(Number);
    options.engineAssemblyModel = "fixed-engine-layout-rigid-cluster-v1";
    options.forcedConvectionWm2K = finite(
      value("forcedConvectionWm2K"),
      "forced-air cooling",
      0,
      100000,
    );
    return options;
  };

  const engineLayoutMarkersBase = combinedHeatSourceMarkers;
  combinedHeatSourceMarkers = function combinedHeatSourceMarkersWithEngineLayout() {
    const toggle = document.getElementById("ti-showSourceMarkers");
    if (toggle && !toggle.checked) return null;
    if (!engineLayoutActive()) return engineLayoutMarkersBase();
    const markers = [];
    const blockMarker = engineSourceMarker("primary");
    if (blockMarker) markers.push({ ...blockMarker, sourceId: 1, label: "Engine block" });
    const turbo = engineLayoutComponent("secondary");
    if (turbo && checked("source2Enabled")) {
      const turboMarker = engineSourceMarker("secondary");
      if (turboMarker) markers.push({ ...turboMarker, sourceId: 2, label: "Turbocharger" });
    }
    return markers.length ? { markers } : null;
  };

  const engineLayoutRenderSelectionBase = renderSelection;
  renderSelection = function renderSelectionWithEngineLayout() {
    engineLayoutRenderSelectionBase();
    if (!engineLayoutActive()) return;
    const box = document.getElementById("ti-source-selection");
    if (!box) return;
    const centre = engineModelCenter();
    const c = centre.map((n) => Number(n).toFixed(2)).join(", ");
    box.textContent = `Engine is fixed at the model centre (${c} mm) and rotates around it. The block and turbo move as one rigid cluster.`;
  };

  const engineLayoutBindBase = bind;
  bind = function bindWithFixedEngineLayout(group) {
    engineLayoutBindBase(group);
    group.querySelector("#ti-engineLayout")?.addEventListener("change", (event) => {
      applyEngineLayout(event.target.value);
      invalidate("Engine layout changed; calculate again.");
    });
    group.querySelector("#ti-engineRotationDeg")?.addEventListener("change", () => {
      renderCombinedHeatSourceMarkers();
      invalidate("Engine rotation changed; calculate again.");
    });
    group.querySelector("#ti-enginePitchDeg")?.addEventListener("change", () => {
      renderCombinedHeatSourceMarkers();
      invalidate("Engine pitch changed; calculate again.");
    });
    group.querySelector("#ti-engineRollDeg")?.addEventListener("change", () => {
      renderCombinedHeatSourceMarkers();
      invalidate("Engine roll changed; calculate again.");
    });
  };

  const engineLayoutRestoreDraftBase = restoreDraft;
  restoreDraft = function restoreDraftWithEngineLayout() {
    engineLayoutRestoreDraftBase();
    applyEngineLayout(engineLayoutName());
  };

  const engineLayoutRenderResultsBase = renderResults;
  renderResults = function renderResultsWithEngineLayout() {
    engineLayoutRenderResultsBase();
    if (!latest || !latest.options?.engineLayout || latest.options.engineLayout === "custom") return;
    const preset = ENGINE_LAYOUT_PRESETS[latest.options.engineLayout];
    input("kpis").insertAdjacentHTML("beforeend", [
      kpi("Engine layout", preset?.label || latest.options.engineLayout),
      kpi("Engine yaw", latest.options.engineRotationDeg != null
        ? `${format(Number(latest.options.engineRotationDeg), 1)}°`
        : "—"),
      kpi("Engine pitch / roll", latest.options.enginePitchDeg != null
        ? `${format(Number(latest.options.enginePitchDeg), 1)}° / ${format(Number(latest.options.engineRollDeg ?? 0), 1)}°`
        : "—"),
      kpi("Engine movement", "Fixed at model centre (rotation only)"),
    ].join(""));
    const note = input("result-note");
    note.textContent += `\n${preset?.description || "Fixed engine assembly layout."} The engine is centred in the model and only rotates; the layout offsets, temperatures, sizes and emissivities are editable engineering starting assumptions, not vehicle-specific measured values.`;
  };

  const engineLayoutCollectReportBase = collectReport;
  collectReport = function collectReportWithEngineLayout() {
    const report = engineLayoutCollectReportBase();
    if (latest?.options?.engineLayout && latest.options.engineLayout !== "custom") {
      const preset = ENGINE_LAYOUT_PRESETS[latest.options.engineLayout];
      report.engineLayout = {
        name: latest.options.engineLayout,
        label: preset?.label || latest.options.engineLayout,
        model: latest.options.engineAssemblyModel || "fixed-engine-layout-rigid-cluster-v1",
        anchorMm: latest.options.engineAnchorMm || [0, 0, 0],
        rotationDeg: Number(latest.options.engineRotationDeg ?? 0),
        pitchDeg: Number(latest.options.enginePitchDeg ?? 0),
        rollDeg: Number(latest.options.engineRollDeg ?? 0),
        components: engineLayoutComponents().map((component) => ({
          slot: component.slot,
          label: component.label,
          offsetMm: [...component.offsetMm],
          temperatureC: component.temperatureC,
          endTemperatureC: component.endTemperatureC,
          diameterMm: component.diameterMm,
          emissivity: component.emissivity,
          gapMm: component.gapMm,
        })),
      };
    }
    return report;
  };

  // The engine is fixed at the model centre; dragging a source sphere is
  // ignored so the rigid cluster never moves or points toward the part.
  window.addEventListener(HEAT_SOURCE_DRAG_EVENT, (event) => {
    if (!engineLayoutActive()) return;
    event.stopImmediatePropagation();
    renderCombinedHeatSourceMarkers();
  });

  window.addEventListener(ENGINE_BBOX_EVENT, (event) => {
    const bbox = event?.detail;
    if (Array.isArray(bbox) && bbox.length === 6) engineModelBbox = bbox.map(Number);
    if (engineLayoutActive()) {
      renderCombinedHeatSourceMarkers();
      syncEngineLayoutUi();
    }
  });

  window.EnderSlicerEngineLayoutTestApi = Object.freeze({
    ENGINE_LAYOUT_PRESETS,
    engineLayoutActive,
    engineLayoutComponents,
    engineLayoutComponent,
    engineModelCenter,
    engineRotationDeg,
    engineComponentTarget,
    engineSourceMarker,
  });
