  // Persistent multi-source markers, explicit marking and direct viewer dragging.
  const HEAT_SOURCE_DRAG_MODE_EVENT = "enderslicer-nearby-hot-object-drag-mode";
  const HEAT_SOURCE_DRAG_EVENT = "enderslicer-nearby-hot-object-drag";

  function combinedHeatSourceMarkers() {
    const toggle = document.getElementById("ti-showSourceMarkers");
    if (toggle && !toggle.checked) return null;
    const markers = [];
    try {
      const primary = sourceMarkerDetail();
      if (primary) markers.push({ ...primary, sourceId: 1, label: "Primary" });
    } catch (_) { /* input validation is shown by the normal workflow */ }
    try {
      const secondary = checked("source2Enabled") ? secondaryMarkerDetail() : null;
      if (secondary) markers.push({ ...secondary, sourceId: 2, label: "Turbo / exhaust" });
    } catch (_) { /* input validation is shown by the normal workflow */ }
    return markers.length ? { markers } : null;
  }

  function renderCombinedHeatSourceMarkers() {
    window.dispatchEvent(new CustomEvent(MARKER_EVENT, {
      detail: combinedHeatSourceMarkers(),
    }));
  }

  function beginHeatSourceDrag(sourceId) {
    if (sourceId === 1 && !selected) {
      const status = input("status");
      status.className = "ti-status ti-warning";
      status.textContent = "Select the primary source point before dragging its marker.";
      return;
    }
    if (sourceId === 2 && (!checked("source2Enabled") || !secondarySelected)) {
      const status = input("status");
      status.className = "ti-status ti-warning";
      status.textContent = "Enable and select the turbo / exhaust source before dragging it.";
      return;
    }
    renderCombinedHeatSourceMarkers();
    window.dispatchEvent(new CustomEvent(HEAT_SOURCE_DRAG_MODE_EVENT, {
      detail: sourceId,
    }));
    const status = input("status");
    status.className = "ti-status dim";
    status.textContent = sourceId === 2
      ? "Drag the blue turbo / exhaust sphere in the 3D viewer. Rotate the view first to choose a different world-space drag plane."
      : "Drag the orange primary sphere in the 3D viewer. Rotate the view first to choose a different world-space drag plane.";
  }

  const markerDragCreateGroupBase = createGroup;
  createGroup = function createGroupWithMarkerDrag() {
    const group = markerDragCreateGroupBase();
    const primaryPick = group.querySelector("#ti-pick-source")?.closest(".ti-pick");
    primaryPick?.insertAdjacentHTML("beforeend", `
      ${checkbox("showSourceMarkers", "Show source markers and calculation-gap guides", true)}
      <div class="ti-grid">
        <button id="ti-mark-sources" type="button">Mark sources in 3D</button>
        <button id="ti-drag-source1" type="button">Drag primary source</button>
      </div>
      <div class="ti-status dim">The centre line points to the effective radiating sphere. The translucent tube shows the surface-to-surface gap used by the solver.</div>
    `);
    const secondaryPick = group.querySelector("#ti-pick-source2")?.closest(".ti-pick");
    secondaryPick?.insertAdjacentHTML("beforeend", `
      <button id="ti-drag-source2" type="button">Drag turbo / exhaust source</button>
    `);
    return group;
  };

  const markerDragBindBase = bind;
  bind = function bindWithMarkerDrag(group) {
    markerDragBindBase(group);
    group.querySelector("#ti-showSourceMarkers")?.addEventListener("change", renderCombinedHeatSourceMarkers);
    group.querySelector("#ti-mark-sources")?.addEventListener("click", renderCombinedHeatSourceMarkers);
    group.querySelector("#ti-drag-source1")?.addEventListener("click", () => beginHeatSourceDrag(1));
    group.querySelector("#ti-drag-source2")?.addEventListener("click", () => beginHeatSourceDrag(2));
    for (const id of (
      "sourceGapMm sourceDiameterMm source2GapMm source2DiameterMm source2Enabled"
    ).split(" ")) {
      group.querySelector(`#ti-${id}`)?.addEventListener("change", renderCombinedHeatSourceMarkers);
    }
  };

  const markerDragUpdateMarkerBase = updateMarker;
  updateMarker = function updateMarkerWithAllSources() {
    markerDragUpdateMarkerBase();
    renderCombinedHeatSourceMarkers();
  };

  const markerDragSecondaryMarkerBase = showSecondaryMarker;
  showSecondaryMarker = function showSecondaryMarkerWithAllSources() {
    markerDragSecondaryMarkerBase();
    renderCombinedHeatSourceMarkers();
  };

  const markerDragRestoreDraftBase = restoreDraft;
  restoreDraft = function restoreDraftWithMarkerVisibility() {
    markerDragRestoreDraftBase();
    const toggle = document.getElementById("ti-showSourceMarkers");
    if (toggle) toggle.checked = true;
    renderCombinedHeatSourceMarkers();
  };

  window.addEventListener(HEAT_SOURCE_DRAG_EVENT, (event) => {
    const detail = event?.detail;
    const sourceId = Number(detail?.sourceId);
    const target = detail?.target;
    const normal = detail?.normal;
    const gapMm = Number(detail?.gapMm);
    if (![1, 2].includes(sourceId)
        || !Array.isArray(target) || target.length !== 3
        || !Array.isArray(normal) || normal.length !== 3
        || !Number.isFinite(gapMm)) return;
    const nextSelection = {
      point: target.map(Number),
      normal: normal.map(Number),
    };
    if (sourceId === 2) {
      secondarySelected = nextSelection;
      setValue("source2GapMm", gapMm.toFixed(2));
      renderSecondarySelection();
    } else {
      selected = nextSelection;
      setValue("sourceGapMm", gapMm.toFixed(2));
      renderSelection();
    }
    renderCombinedHeatSourceMarkers();
    invalidate(`${sourceId === 2 ? "Turbo / exhaust" : "Primary"} source moved; calculate again.`);
  });
