  // Enclosed modes use explicit finite calculation worlds. Open air remains
  // unbounded and does not use these dimensions. The displayed inside box and
  // the well-mixed air-node volume are always derived from the same values.
  const ENCLOSURE_WORLD_PRESETS = Object.freeze({
    // Published full-scale simplified passenger-car underhood reference.
    engine_running: Object.freeze({ widthMm: 1090, depthMm: 660, heightMm: 542 }),
    engine_heat_soak: Object.freeze({ widthMm: 1090, depthMm: 660, heightMm: 542 }),
    // Generic editable equipment / printer-style ventilated enclosure.
    ventilated_enclosure: Object.freeze({ widthMm: 600, depthMm: 500, heightMm: 500 }),
    // Generic editable compact sealed enclosure.
    sealed_enclosure: Object.freeze({ widthMm: 400, depthMm: 400, heightMm: 400 }),
  });

  function enclosureWorldVolumeL(dimensions) {
    return Number(dimensions.widthMm) * Number(dimensions.depthMm)
      * Number(dimensions.heightMm) / 1_000_000;
  }

  const finiteWorldApplyEnvironmentPresetBase = applyEnvironmentPreset;
  applyEnvironmentPreset = function applyEnvironmentPresetWithFiniteWorld(mode) {
    finiteWorldApplyEnvironmentPresetBase(mode);
    const dimensions = ENCLOSURE_WORLD_PRESETS[mode];
    if (!dimensions) return;
    setValue("enclosureVolumeL", enclosureWorldVolumeL(dimensions).toFixed(3));
  };

  applyEnclosureBoxPreset = function applyEnclosureBoxPresetWithFiniteWorld(mode) {
    let dimensions = ENCLOSURE_WORLD_PRESETS[mode];
    if (!dimensions && mode === "custom") {
      const volumeL = Math.max(0.001, Number(value("enclosureVolumeL")) || 64);
      const sideMm = Math.cbrt(volumeL * 1_000_000);
      dimensions = { widthMm: sideMm, depthMm: sideMm, heightMm: sideMm };
    }
    if (!dimensions) return;
    setValue("enclosureWidthMm", Number(dimensions.widthMm).toFixed(1));
    setValue("enclosureDepthMm", Number(dimensions.depthMm).toFixed(1));
    setValue("enclosureHeightMm", Number(dimensions.heightMm).toFixed(1));
    setValue("enclosureOffsetXmm", 0);
    setValue("enclosureOffsetYmm", 0);
    setValue("enclosureOffsetZmm", 0);
    syncCalculatedEnclosureVolume();
    renderEnclosureBox();
  };

  window.EnderSlicerEnclosureWorldPresetTestApi = Object.freeze({
    ENCLOSURE_WORLD_PRESETS,
    enclosureWorldVolumeL,
  });

  // Give the synchronous viewer event and its queued automatic projection one frame
  // to populate source geometry before the legacy option collector validates it.
  const spatialAutomaticProjectionRunBase = runAnalysis;
  runAnalysis = async function runAnalysisWithAutomaticSourceProjection() {
    renderCombinedHeatSourceMarkers();
    await new Promise((resolve) => window.requestAnimationFrame(resolve));
    return spatialAutomaticProjectionRunBase();
  };
