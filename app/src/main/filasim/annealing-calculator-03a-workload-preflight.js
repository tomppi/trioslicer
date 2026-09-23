  // Query the actual voxel grid before starting either transient stage. The
  // WASM boundary limits Android work to 120 million solid-cell steps, which
  // cannot be planned correctly from duration alone.
  const runCycleWithoutVoxelBudget = runCycle;
  let voxelBudgetPreflightInFlight = false;

  runCycle = async function runCycleWithVoxelBudget() {
    if (runInFlight || voxelBudgetPreflightInFlight) return;
    const run = input("run");
    const cancel = input("cancel");
    const status = input("status");
    voxelBudgetPreflightInFlight = true;
    run.disabled = true;
    cancel.disabled = true;
    status.className = "ac-status dim";
    status.textContent = "Reading the current voxel grid and planning an Android-safe transient workload…";

    try {
      const voxel = await request("voxelInfo");
      const solidCells = Number(voxel?.solid);
      if (!Number.isSafeInteger(solidCells) || solidCells <= 0) {
        throw new Error("filaSim returned an invalid solid-voxel count during annealing preflight.");
      }
      const budgetApi = window.EnderSlicerAnnealingStepBudget;
      if (!budgetApi?.applyForSolidCells) {
        throw new Error("The annealing workload planner is unavailable.");
      }
      const plan = budgetApi.applyForSolidCells(solidCells, true);
      if (!plan) {
        throw new Error("Unable to plan the annealing transient workload.");
      }
      const maximumWork = Math.max(plan.heatingCellSteps || 0, plan.coolingCellSteps || 0);
      if (maximumWork > plan.maxTransientCellSteps) {
        throw new Error(
          `Annealing preflight could not reduce the workload below ${plan.maxTransientCellSteps.toLocaleString()} solid-cell steps.`
        );
      }
      status.textContent =
        `Voxel preflight: ${solidCells.toLocaleString()} solid cells, ` +
        `${plan.effectiveTimeStepSeconds} s timestep, ` +
        `${plan.heatingSteps.toLocaleString()} heating` +
        `${plan.coolingSteps ? ` / ${plan.coolingSteps.toLocaleString()} cooling` : ""} steps.`;
      await runCycleWithoutVoxelBudget();
    } catch (error) {
      latest = null;
      status.className = "ac-status ac-error";
      status.textContent = `Annealing calculation failed: ${error?.message || error}`;
      console.error("EnderSlicer annealing workload preflight failed", error);
    } finally {
      voxelBudgetPreflightInFlight = false;
      if (!runInFlight) {
        run.disabled = false;
        cancel.disabled = true;
      }
    }
  };
