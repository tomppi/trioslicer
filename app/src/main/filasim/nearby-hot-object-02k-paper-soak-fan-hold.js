  // Gao et al. report that the underhood fan continued to operate for the
  // first 30 seconds of the 400-second key-off soak. Preserve that event as a
  // real coupling-stage boundary instead of merely mentioning it in metadata.
  const PAPER_SOAK_HINT_PROFILE = "paper_suv_400s_soak";
  const PAPER_SOAK_FORCED_VENTILATION_ACH = 18;

  function paperSoakStagePlan(profileName, durationSeconds, timeStepSeconds, basePlanner) {
    const planner = basePlanner || planEnclosureStages;
    const duration = Math.max(0, Number(durationSeconds));
    if (profileName !== PAPER_SOAK_HINT_PROFILE || duration <= 0) {
      return planner(durationSeconds, timeStepSeconds);
    }
    const holdSeconds = Math.min(
      duration,
      Number(ENGINE_BAY_HINT_REFERENCE.fanHoldSeconds),
    );
    const remainingSeconds = Math.max(0, duration - holdSeconds);
    const stages = holdSeconds > 0 ? [holdSeconds] : [];
    if (remainingSeconds > 0) stages.push(...planner(remainingSeconds, timeStepSeconds));
    return stages;
  }

  function paperSoakVentilationAchAt(profileName, elapsedSeconds, normalAch) {
    if (profileName === PAPER_SOAK_HINT_PROFILE
        && Number(elapsedSeconds) < Number(ENGINE_BAY_HINT_REFERENCE.fanHoldSeconds)) {
      return PAPER_SOAK_FORCED_VENTILATION_ACH;
    }
    return Number(normalAch);
  }

  function paperSoakWallInputs(profileName, options, elapsedSeconds) {
    if (profileName !== PAPER_SOAK_HINT_PROFILE) {
      return { options, elapsedSeconds: Number(elapsedSeconds) };
    }
    const holdSeconds = Number(ENGINE_BAY_HINT_REFERENCE.fanHoldSeconds);
    const originalRampSeconds = Math.max(0, Number(options.enclosureWallRampSeconds));
    return {
      options: {
        ...options,
        enclosureWallRampSeconds: Math.max(0, originalRampSeconds - holdSeconds),
      },
      elapsedSeconds: Math.max(0, Number(elapsedSeconds) - holdSeconds),
    };
  }

  let activePaperSoakOptions = null;
  let activePaperSoakStageTimeSeconds = null;

  const paperSoakPlanEnclosureStagesBase = planEnclosureStages;
  planEnclosureStages = function planEnclosureStagesWithPaperFanHold(durationSeconds, timeStepSeconds) {
    return paperSoakStagePlan(
      activePaperSoakOptions?.thermalHintProfile,
      durationSeconds,
      timeStepSeconds,
      paperSoakPlanEnclosureStagesBase,
    );
  };

  const paperSoakWallTemperatureAtBase = wallTemperatureAt;
  wallTemperatureAt = function wallTemperatureAtWithPaperFanHold(options, elapsedSeconds) {
    activePaperSoakStageTimeSeconds = Number(elapsedSeconds);
    const mapped = paperSoakWallInputs(
      activePaperSoakOptions?.thermalHintProfile,
      options,
      elapsedSeconds,
    );
    return paperSoakWallTemperatureAtBase(mapped.options, mapped.elapsedSeconds);
  };

  const paperSoakEnclosureAirStepBase = enclosureAirStep;
  enclosureAirStep = function enclosureAirStepWithPaperFanHold(config) {
    const ventilationAch = paperSoakVentilationAchAt(
      activePaperSoakOptions?.thermalHintProfile,
      activePaperSoakStageTimeSeconds,
      config.ventilationAch,
    );
    return paperSoakEnclosureAirStepBase({ ...config, ventilationAch });
  };

  const paperSoakRunEnclosureScenarioBase = runEnclosureScenario;
  runEnclosureScenario = async function runEnclosureScenarioWithPaperFanHold(
    options,
    preflight,
    status,
    requestFn,
  ) {
    activePaperSoakOptions = options;
    activePaperSoakStageTimeSeconds = null;
    try {
      const result = await paperSoakRunEnclosureScenarioBase(options, preflight, status, requestFn);
      if (options?.thermalHintProfile === PAPER_SOAK_HINT_PROFILE && result?.environment) {
        result.environment.paperKeyOffSoak = {
          referenceId: ENGINE_BAY_HINT_REFERENCE.id,
          totalDurationSeconds: Number(options.durationSeconds),
          fanHoldSeconds: Number(ENGINE_BAY_HINT_REFERENCE.fanHoldSeconds),
          fanHoldVentilationAch: PAPER_SOAK_FORCED_VENTILATION_ACH,
          postFanVentilationAch: Number(options.enclosureVentilationAch),
          earlyRadiationConvectionSeconds: Number(
            ENGINE_BAY_HINT_REFERENCE.earlyRadiationConvectionSeconds,
          ),
        };
      }
      return result;
    } finally {
      activePaperSoakOptions = null;
      activePaperSoakStageTimeSeconds = null;
    }
  };

  window.EnderSlicerPaperSoakFanHoldTestApi = Object.freeze({
    PAPER_SOAK_HINT_PROFILE,
    PAPER_SOAK_FORCED_VENTILATION_ACH,
    paperSoakStagePlan,
    paperSoakVentilationAchAt,
    paperSoakWallInputs,
  });
