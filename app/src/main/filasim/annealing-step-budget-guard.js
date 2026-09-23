/* Keep Anneal searches within Android step and cell-step budgets. */
(() => {
  "use strict";

  const MAX_STAGE_STEPS = 2000;
  const MAX_TRANSIENT_CELL_STEPS = 120_000_000;
  const WORKSPACE_EVENT = "enderslicer-annealing-workspace";
  const HINT_ID = "ac-step-budget-hint";

  function positiveNumber(value, label) {
    const number = Number(value);
    if (!Number.isFinite(number) || number <= 0) {
      throw new Error(`${label} must be greater than zero`);
    }
    return number;
  }

  function optionalSolidCells(value) {
    if (value == null || value === "") return null;
    const cells = Number(value);
    if (!Number.isSafeInteger(cells) || cells <= 0) {
      throw new Error("solid voxel count must be a positive whole number");
    }
    return cells;
  }

  function planStageBudget(values) {
    const maxHeatingHours = positiveNumber(values.maxHeatingHours, "maximum heating search");
    const maxCoolingHours = positiveNumber(values.maxCoolingHours, "maximum cooling search");
    const requestedTimeStepSeconds = positiveNumber(values.requestedTimeStepSeconds, "time step");
    const simulateCooling = values.simulateCooling !== false;
    const solidCells = optionalSolidCells(values.solidCells);
    const limitingHours = Math.max(maxHeatingHours, simulateCooling ? maxCoolingHours : 0);
    const workloadStepLimit = solidCells == null
      ? Number.POSITIVE_INFINITY
      : Math.floor(MAX_TRANSIENT_CELL_STEPS / solidCells);
    if (workloadStepLimit < 1) {
      throw new Error(
        `The voxel grid has ${solidCells.toLocaleString()} solid cells, which exceeds the Android transient budget even for one step. Increase voxel size.`
      );
    }
    const effectiveStepLimit = Math.min(MAX_STAGE_STEPS, workloadStepLimit);
    // Use whole seconds because the Android control exposes a 1 s step and a
    // small upward round is safer than landing just over a limit through FP error.
    const minimumTimeStepSeconds = Math.max(
      1,
      Math.ceil((limitingHours * 3600) / effectiveStepLimit)
    );
    const effectiveTimeStepSeconds = Math.max(
      requestedTimeStepSeconds,
      minimumTimeStepSeconds
    );
    const heatingSteps = Math.ceil((maxHeatingHours * 3600) / effectiveTimeStepSeconds);
    const coolingSteps = simulateCooling
      ? Math.ceil((maxCoolingHours * 3600) / effectiveTimeStepSeconds)
      : 0;
    return {
      requestedTimeStepSeconds,
      minimumTimeStepSeconds,
      effectiveTimeStepSeconds,
      adjusted: effectiveTimeStepSeconds !== requestedTimeStepSeconds,
      adjustedForVoxelWorkload:
        solidCells != null && workloadStepLimit < MAX_STAGE_STEPS &&
        effectiveTimeStepSeconds !== requestedTimeStepSeconds,
      heatingSteps,
      coolingSteps,
      maxStageSteps: MAX_STAGE_STEPS,
      maxTransientCellSteps: MAX_TRANSIENT_CELL_STEPS,
      solidCells,
      workloadStepLimit: Number.isFinite(workloadStepLimit) ? workloadStepLimit : null,
      effectiveStepLimit,
      heatingCellSteps: solidCells == null ? null : solidCells * heatingSteps,
      coolingCellSteps: solidCells == null ? null : solidCells * coolingSteps,
    };
  }

  function element(id) {
    return document.getElementById(id);
  }

  function ensureHint() {
    let hint = element(HINT_ID);
    if (hint) return hint;
    const timeStep = element("ac-timeStepSeconds");
    const grid = timeStep?.closest?.(".ac-grid");
    if (!grid?.parentElement) return null;
    hint = document.createElement("div");
    hint.id = HINT_ID;
    hint.className = "ac-status dim";
    grid.parentElement.insertBefore(hint, grid.nextSibling);
    return hint;
  }

  function currentBudget(solidCells = null) {
    const timeStep = element("ac-timeStepSeconds");
    const heating = element("ac-maxHeatingHours");
    const cooling = element("ac-maxCoolingHours");
    const simulateCooling = element("ac-simulateCooling");
    if (!timeStep || !heating || !cooling || !simulateCooling) return null;
    return planStageBudget({
      maxHeatingHours: heating.value,
      maxCoolingHours: cooling.value,
      requestedTimeStepSeconds: timeStep.value,
      simulateCooling: simulateCooling.checked,
      solidCells,
    });
  }

  function formatInteger(value) {
    return Number(value).toLocaleString(undefined, { maximumFractionDigits: 0 });
  }

  function renderBudget(plan, committed) {
    const hint = ensureHint();
    if (!hint) return;
    const counts = plan.coolingSteps > 0
      ? `${formatInteger(plan.heatingSteps)} heating / ${formatInteger(plan.coolingSteps)} cooling steps`
      : `${formatInteger(plan.heatingSteps)} heating steps`;
    const workload = plan.solidCells == null
      ? ""
      : ` · ${formatInteger(plan.solidCells)} solid cells · ${formatInteger(plan.heatingCellSteps)} heating cell-steps`;
    if (plan.adjusted) {
      hint.className = "ac-status ac-warning";
      const reason = plan.adjustedForVoxelWorkload
        ? "the Android transient cell-step budget"
        : "the Android per-stage step limit";
      hint.textContent = committed
        ? `Time step automatically increased from ${plan.requestedTimeStepSeconds} s to ${plan.effectiveTimeStepSeconds} s for ${reason} (${counts}${workload}).`
        : `These settings require at least ${plan.minimumTimeStepSeconds} s per step for ${reason} (${counts}${workload} after adjustment). The value will be corrected when calculation starts.`;
    } else {
      hint.className = "ac-status dim";
      hint.textContent = `Transient workload: ${counts}${workload}; limits ${plan.maxStageSteps} steps and ${formatInteger(plan.maxTransientCellSteps)} solid-cell steps per stage.`;
    }
  }

  function applyBudget(commitAdjustment, solidCells = null) {
    let plan;
    try {
      plan = currentBudget(solidCells);
    } catch (error) {
      const hint = ensureHint();
      if (hint && solidCells != null) {
        hint.className = "ac-status ac-error";
        hint.textContent = error.message;
      }
      if (solidCells != null) throw error;
      return null; // The main form validator owns malformed input messages.
    }
    if (!plan) return null;
    if (commitAdjustment && plan.adjusted) {
      const timeStep = element("ac-timeStepSeconds");
      if (timeStep) timeStep.value = String(plan.effectiveTimeStepSeconds);
    }
    renderBudget(plan, Boolean(commitAdjustment && plan.adjusted));
    return plan;
  }

  function scheduleMountSync() {
    // React may commit the panel after the workspace event returns. A short
    // retry sequence handles both synchronous and deferred Android WebView
    // commits without observing mutations inside the panel.
    for (const delay of [0, 16, 80]) {
      window.setTimeout(() => applyBudget(true), delay);
    }
  }

  document.addEventListener(
    "click",
    (event) => {
      if (!event.target?.closest?.("#ac-run")) return;
      // Capture runs before the calculator's button listener. This first pass
      // enforces the duration-only limit; Anneal's async voxel preflight then
      // applies the stricter solid-cell workload limit before the solver starts.
      applyBudget(true);
    },
    true
  );
  document.addEventListener(
    "input",
    (event) => {
      const id = event.target?.id || "";
      if (["ac-timeStepSeconds", "ac-maxHeatingHours", "ac-maxCoolingHours", "ac-simulateCooling"].includes(id)) {
        applyBudget(false);
      }
    },
    true
  );
  document.addEventListener(
    "change",
    (event) => {
      if (event.target?.id === "ac-simulateCooling") applyBudget(false);
    },
    true
  );
  window.addEventListener(WORKSPACE_EVENT, (event) => {
    if (event?.detail) scheduleMountSync();
  });

  const api = Object.freeze({
    maxStageSteps: MAX_STAGE_STEPS,
    maxTransientCellSteps: MAX_TRANSIENT_CELL_STEPS,
    planStageBudget,
    applyForSolidCells(solidCells, commitAdjustment = true) {
      return applyBudget(Boolean(commitAdjustment), solidCells);
    },
  });
  window.EnderSlicerAnnealingStepBudget = api;
  window.EnderSlicerAnnealingStepBudgetTestApi = api;
})();
