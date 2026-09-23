  // World-space placement for the printed plastic object. The viewer moves the
  // mesh, while the solver keeps its voxel grid fixed and translates the
  // engine-bay boundary/source coordinates by the opposite offset. This keeps
  // the expensive voxel model reusable while preserving identical relative
  // geometry and heat-transfer distances.
  const ENGINE_BAY_PART_PLACEMENT_EVENT = "enderslicer-engine-bay-part-placement";
  const ENGINE_BAY_PART_PLACEMENT_CHANGED_EVENT = "enderslicer-engine-bay-part-placement-changed";

  function partPlacementVector() {
    return [
      finite(value("partPlacementXMm"), "plastic object X position", -100000, 100000),
      finite(value("partPlacementYMm"), "plastic object Y position", -100000, 100000),
      finite(value("partPlacementZMm"), "plastic object Z position", -100000, 100000),
    ];
  }

  function thermalPointFromViewer(point, placement) {
    return point.map((coordinate, axis) => Number(coordinate) - Number(placement[axis]));
  }

  function solverEnvelopeOffsets(viewerOffsets, placement) {
    return viewerOffsets.map((coordinate, axis) => Number(coordinate) - Number(placement[axis]));
  }

  function placementEnvelopeDetail() {
    if (value("environmentMode") === "open") return null;
    const dimensions = enclosureBoxDimensions();
    return {
      widthMm: dimensions.widthMm,
      depthMm: dimensions.depthMm,
      heightMm: dimensions.heightMm,
      offsetXmm: dimensions.offsetXmm,
      offsetYmm: dimensions.offsetYmm,
      offsetZmm: dimensions.offsetZmm,
      shape: isEngineBayEnvelopeMode() ? "engine-bay" : "box",
    };
  }

  function dispatchPartPlacement(dragMode = "none", notify = true) {
    let positionMm;
    let envelope;
    try {
      positionMm = partPlacementVector();
      envelope = placementEnvelopeDetail();
    } catch (_) {
      return;
    }
    window.dispatchEvent(new CustomEvent(ENGINE_BAY_PART_PLACEMENT_EVENT, {
      detail: { positionMm, dragMode, envelope, notify },
    }));
  }

  function updatePartPlacementUi(positionMm) {
    setValue("partPlacementXMm", Number(positionMm[0]).toFixed(2));
    setValue("partPlacementYMm", Number(positionMm[1]).toFixed(2));
    setValue("partPlacementZMm", Number(positionMm[2]).toFixed(2));
  }

  let applyingViewerPartPlacement = false;
  window.addEventListener(ENGINE_BAY_PART_PLACEMENT_CHANGED_EVENT, (event) => {
    const detail = event.detail;
    if (!detail?.positionMm || detail.positionMm.length !== 3) return;
    applyingViewerPartPlacement = true;
    try {
      updatePartPlacementUi(detail.positionMm);
    } finally {
      applyingViewerPartPlacement = false;
    }
    // The viewer updates the mesh itself on every pointer move. Re-projecting
    // both heat sources scans the complete triangle mesh, so do that only once
    // when the gesture finishes instead of on every animation frame.
    if (!detail.final) return;
    window.requestAnimationFrame(renderCombinedHeatSourceMarkers);
    const message = detail.fits === false
      ? "The plastic object is larger than the available engine-bay cavity. It was centred at the nearest valid position; reduce the object or enlarge the calculation zone."
      : detail.clamped
        ? "Plastic-object placement reached the closed engine-bay boundary and was clamped inside it. Calculate again."
        : "Plastic-object placement changed; calculate again.";
    invalidate(message);
    const status = input("status");
    if (status) {
      status.className = detail.fits === false ? "ti-status ti-warning" : "ti-status dim";
      status.textContent = message;
    }
  });

  const partPlacementBeginHeatSourceDragBase = beginHeatSourceDrag;
  beginHeatSourceDrag = function beginHeatSourceDragWithPartPlacementDisabled(sourceId) {
    dispatchPartPlacement("none", false);
    return partPlacementBeginHeatSourceDragBase(sourceId);
  };

  const partPlacementCreateGroupBase = createGroup;
  createGroup = function createGroupWithPartPlacement() {
    const group = partPlacementCreateGroupBase();
    const enclosureFields = group.querySelector("#ti-enclosure-fields");
    const viewerOptions = group.querySelector("#ti-engine-bay-envelope-viewer-options");
    const insertionTarget = viewerOptions || enclosureFields?.firstElementChild;
    insertionTarget?.insertAdjacentHTML("afterend", `
      <div id="ti-engine-bay-part-placement">
        <div class="ti-status dim"><b>Plastic-object placement.</b> Move the printed object anywhere inside the closed calculation envelope. Heat sources and the engine-bay shell remain fixed in world space.</div>
        <div class="ti-grid">
          ${field("partPlacementXMm", "Plastic object X position (mm)", 0, 1)}
          ${field("partPlacementYMm", "Plastic object Y position (mm)", 0, 1)}
          ${field("partPlacementZMm", "Plastic object Z position (mm)", 0, 1)}
        </div>
        <div class="ti-actions">
          <button id="ti-drag-part-xy" type="button">Move plastic object X/Y</button>
          <button id="ti-drag-part-z" type="button">Move plastic object Z</button>
          <button id="ti-center-part" type="button">Centre plastic object</button>
        </div>
        <div class="ti-status dim">X/Y mode drags the object across the bay. Z mode moves it vertically. The complete object bounding box is constrained beneath the closed hood, above the lower boundary, and inside the shaped side walls.</div>
      </div>
    `);
    return group;
  };

  const partPlacementCollectOptionsBase = collectOptions;
  collectOptions = function collectOptionsWithWorldPartPlacement() {
    const options = partPlacementCollectOptionsBase();
    const placement = partPlacementVector();
    options.partPlacementXMm = placement[0];
    options.partPlacementYMm = placement[1];
    options.partPlacementZMm = placement[2];
    options.partPlacementMm = [...placement];
    options.partPlacementCoordinateModel = "fixed-voxel-grid-inverse-world-transform-v1";

    if (Array.isArray(options.sourceTargetMm) && options.sourceTargetMm.length === 3) {
      options.sourceTargetMm = thermalPointFromViewer(options.sourceTargetMm, placement);
    }
    if (options.source2Enabled && Array.isArray(options.source2TargetMm)
        && options.source2TargetMm.length === 3) {
      options.source2TargetMm = thermalPointFromViewer(options.source2TargetMm, placement);
    }

    const viewerOffsets = [
      Number(options.enclosureOffsetXMm),
      Number(options.enclosureOffsetYMm),
      Number(options.enclosureOffsetZMm),
    ];
    options.viewerEnclosureOffsetXMm = viewerOffsets[0];
    options.viewerEnclosureOffsetYMm = viewerOffsets[1];
    options.viewerEnclosureOffsetZMm = viewerOffsets[2];
    const solverOffsets = solverEnvelopeOffsets(viewerOffsets, placement);
    options.enclosureOffsetXMm = solverOffsets[0];
    options.enclosureOffsetYMm = solverOffsets[1];
    options.enclosureOffsetZMm = solverOffsets[2];
    return options;
  };

  const partPlacementSaveDraftBase = saveDraft;
  saveDraft = function saveDraftWithViewerEnclosureOffsets(options) {
    const persisted = {
      ...options,
      enclosureOffsetXMm: Number(options.viewerEnclosureOffsetXMm),
      enclosureOffsetYMm: Number(options.viewerEnclosureOffsetYMm),
      enclosureOffsetZMm: Number(options.viewerEnclosureOffsetZMm),
    };
    return partPlacementSaveDraftBase(persisted);
  };

  const partPlacementRestoreDraftBase = restoreDraft;
  restoreDraft = function restoreDraftWithPartPlacement() {
    partPlacementRestoreDraftBase();
    dispatchPartPlacement("none", true);
  };

  const partPlacementSyncEnvironmentUiBase = syncEnvironmentUi;
  syncEnvironmentUi = function syncEnvironmentUiWithPartPlacement() {
    partPlacementSyncEnvironmentUiBase();
    const controls = document.getElementById("ti-engine-bay-part-placement");
    if (controls) controls.classList.toggle("ti-hidden", value("environmentMode") === "open");
    dispatchPartPlacement("none", false);
  };

  const partPlacementBindBase = bind;
  bind = function bindWithPartPlacement(group) {
    partPlacementBindBase(group);
    for (const id of ["partPlacementXMm", "partPlacementYMm", "partPlacementZMm"]) {
      group.querySelector(`#ti-${id}`)?.addEventListener("change", () => {
        if (applyingViewerPartPlacement) return;
        dispatchPartPlacement("none", true);
      });
    }
    group.querySelector("#ti-drag-part-xy")?.addEventListener("click", () => {
      dispatchPartPlacement("xy", false);
      const status = input("status");
      status.className = "ti-status dim";
      status.textContent = "Drag the plastic object in the 3D viewer to move it in world X/Y.";
    });
    group.querySelector("#ti-drag-part-z")?.addEventListener("click", () => {
      dispatchPartPlacement("z", false);
      const status = input("status");
      status.className = "ti-status dim";
      status.textContent = "Drag the plastic object vertically in the 3D viewer.";
    });
    group.querySelector("#ti-center-part")?.addEventListener("click", () => {
      updatePartPlacementUi([0, 0, 0]);
      dispatchPartPlacement("none", true);
    });
  };

  const partPlacementInstallUiBase = installUi;
  installUi = function installUiWithPartPlacement() {
    const installed = partPlacementInstallUiBase();
    if (installed) dispatchPartPlacement("none", false);
    return installed;
  };

  const partPlacementCollectReportBase = collectReport;
  collectReport = function collectReportWithPartPlacement() {
    const report = partPlacementCollectReportBase();
    if (!latest?.options) return report;
    report.partPlacement = {
      worldPositionMm: [
        Number(latest.options.partPlacementXMm),
        Number(latest.options.partPlacementYMm),
        Number(latest.options.partPlacementZMm),
      ],
      coordinateModel: latest.options.partPlacementCoordinateModel,
      viewerEnclosureOffsetMm: [
        Number(latest.options.viewerEnclosureOffsetXMm),
        Number(latest.options.viewerEnclosureOffsetYMm),
        Number(latest.options.viewerEnclosureOffsetZMm),
      ],
      solverRelativeEnclosureOffsetMm: [
        Number(latest.options.enclosureOffsetXMm),
        Number(latest.options.enclosureOffsetYMm),
        Number(latest.options.enclosureOffsetZMm),
      ],
      constraint: "complete-object-bounding-box-inside-closed-envelope-v1",
    };
    return report;
  };

  window.addEventListener(CLEAR_EVENT, () => {
    setValue("partPlacementXMm", 0);
    setValue("partPlacementYMm", 0);
    setValue("partPlacementZMm", 0);
    dispatchPartPlacement("none", false);
  });

  window.EnderSlicerPartPlacementTestApi = Object.freeze({
    thermalPointFromViewer,
    solverEnvelopeOffsets,
  });
