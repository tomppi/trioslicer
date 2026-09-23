  // Generic closed engine-bay envelope. The viewer may hide hood/floor caps,
  // but the thermal calculation always treats them as closed boundaries.
  const ENGINE_BAY_ENVELOPE_SHAPE_FACTOR = 0.72;

  function isEngineBayEnvelopeMode(mode = value("environmentMode")) {
    return mode === "engine_running" || mode === "engine_heat_soak";
  }

  const shapedEnvelopeVolumeBase = enclosureVolumeFromDimensions;
  enclosureVolumeFromDimensions = function enclosureVolumeFromShapedDimensions(dimensions) {
    const rectangular = shapedEnvelopeVolumeBase(dimensions);
    return isEngineBayEnvelopeMode()
      ? rectangular * ENGINE_BAY_ENVELOPE_SHAPE_FACTOR
      : rectangular;
  };

  const shapedEnvelopeDetailBase = enclosureBoxDetail;
  enclosureBoxDetail = function enclosureEngineBayShapeDetail() {
    const detail = shapedEnvelopeDetailBase();
    if (!detail) return null;
    if (!isEngineBayEnvelopeMode()) return { ...detail, shape: "box" };
    return {
      ...detail,
      shape: "engine-bay",
      hoodVisible: checked("showEngineBayHoodBoundary"),
      floorVisible: checked("showEngineBayFloorBoundary"),
      wallOpacity: 0.075,
      calculationClosed: true,
      shapeFactor: ENGINE_BAY_ENVELOPE_SHAPE_FACTOR,
    };
  };

  const shapedEnvelopeCreateGroupBase = createGroup;
  createGroup = function createGroupWithShapedEngineBayEnvelope() {
    const group = shapedEnvelopeCreateGroupBase();
    const enclosureFields = group.querySelector("#ti-enclosure-fields");
    const firstStatus = enclosureFields?.querySelector(".ti-status.dim");
    firstStatus?.insertAdjacentHTML("beforebegin", `
      <div id="ti-engine-bay-envelope-viewer-options">
        ${checkbox("showEngineBayHoodBoundary", "Show closed-hood boundary in viewer", false)}
        ${checkbox("showEngineBayFloorBoundary", "Show bottom boundary in viewer", false)}
      </div>
    `);
    if (firstStatus) {
      firstStatus.textContent = "Engine modes use a closed generic engine-bay envelope: shaped side walls, firewall/front support, wheel-house intrusions, a closed hood and a closed bottom boundary. Hood and floor can stay hidden in the viewer so heat-source placement remains visible. Non-engine enclosures remain rectangular.";
    }
    return group;
  };

  const shapedEnvelopeCollectOptionsBase = collectOptions;
  collectOptions = function collectOptionsWithClosedEngineBayShape() {
    const options = shapedEnvelopeCollectOptionsBase();
    const engineBay = isEngineBayEnvelopeMode(options.environmentMode);
    options.enclosureShape = engineBay ? "generic-engine-bay-v1" : "rectangular-v1";
    options.enclosureShapeFactor = engineBay ? ENGINE_BAY_ENVELOPE_SHAPE_FACTOR : 1;
    options.enclosureCalculationClosed = options.environmentMode !== "open";
    options.enclosureHoodBoundaryActive = engineBay;
    options.enclosureFloorBoundaryActive = engineBay;
    return options;
  };

  const shapedEnvelopeSyncEnvironmentUiBase = syncEnvironmentUi;
  syncEnvironmentUi = function syncEnvironmentUiWithShapedEnvelope() {
    shapedEnvelopeSyncEnvironmentUiBase();
    const options = document.getElementById("ti-engine-bay-envelope-viewer-options");
    if (options) options.classList.toggle("ti-hidden", !isEngineBayEnvelopeMode());
    syncCalculatedEnclosureVolume();
    renderEnclosureBox();
  };

  const shapedEnvelopeBindBase = bind;
  bind = function bindWithShapedEngineBayEnvelope(group) {
    shapedEnvelopeBindBase(group);
    for (const id of ["showEngineBayHoodBoundary", "showEngineBayFloorBoundary"]) {
      group.querySelector(`#ti-${id}`)?.addEventListener("change", renderEnclosureBox);
    }
  };

  const shapedEnvelopeReportBase = collectReport;
  collectReport = function collectReportWithShapedEngineBayEnvelope() {
    const report = shapedEnvelopeReportBase();
    if (!latest?.options) return report;
    report.enclosureGeometry = {
      model: latest.options.enclosureShape,
      closedCalculationBoundary: Boolean(latest.options.enclosureCalculationClosed),
      hoodBoundaryActive: Boolean(latest.options.enclosureHoodBoundaryActive),
      floorBoundaryActive: Boolean(latest.options.enclosureFloorBoundaryActive),
      shapeFactor: Number(latest.options.enclosureShapeFactor),
      widthMm: Number(latest.options.enclosureWidthMm),
      depthMm: Number(latest.options.enclosureDepthMm),
      heightMm: Number(latest.options.enclosureHeightMm),
      calculatedAirVolumeL: Number(latest.options.enclosureVolumeL),
      viewerHoodVisible: checked("showEngineBayHoodBoundary"),
      viewerFloorVisible: checked("showEngineBayFloorBoundary"),
    };
    return report;
  };

  window.EnderSlicerEngineBayEnvelopeTestApi = Object.freeze({
    ENGINE_BAY_ENVELOPE_SHAPE_FACTOR,
    isEngineBayEnvelopeMode,
  });
