/*
 * Thermal Integrity bug-fix round 1 runtime guard.
 *
 * The thermal UI intentionally talks to filaSim's raw worker so exact typed
 * arrays can be reported. This guard supplies the lifecycle protection that a
 * normal EngineClient call would otherwise provide: one active thermal request,
 * invalidation when model/grid/load state changes, cooperative cancellation on
 * mutation, recovery from worker errors or React panel remounts, and protection
 * against MutationObserver feedback loops in the progress shell.
 */
(() => {
  "use strict";

  const INVALIDATED_EVENT = "enderslicer-thermal-integrity-invalidated";
  const RUN_STATE_EVENT = "enderslicer-thermal-integrity-run-state";
  const THERMAL_GROUP_ID = "enderslicer-thermal-integrity";
  const MUTATING_OPS = new Set([
    "load",
    "loadMesh",
    "transform",
    "resegment",
    "useCadFaces",
    "setMaterial",
    "setResolution",
    "setVoxelSize",
    "setSnapWall",
    "setCompositeSkin",
    "setSmoothStress",
    "setMaterialStress",
    "setBcs",
    "optimize",
    "resmooth",
    "settingsSweep",
    "buildSim",
    "openProjectRestore",
  ]);

  let activeRequestId = null;
  let cancelFlag = null;
  let invalidationEpoch = 0;

  function recordsAddThermalGroup(records) {
    return records.some((record) =>
      Array.from(record.addedNodes || []).some((node) =>
        node instanceof Element &&
        (node.id === THERMAL_GROUP_ID || Boolean(node.querySelector?.(`#${THERMAL_GROUP_ID}`)))
      )
    );
  }

  function installMutationObserverGuard() {
    const NativeMutationObserver = window.MutationObserver;
    if (!NativeMutationObserver || NativeMutationObserver.__enderSlicerThermalObserverGuard) return;

    const WrappedMutationObserver = new Proxy(NativeMutationObserver, {
      construct(Target, args) {
        const callback = args[0];
        const guardedCallback =
          typeof callback === "function" && callback.name === "ensureUi"
            ? (records, observer) => {
                if (recordsAddThermalGroup(records)) callback(records, observer);
              }
            : callback;
        return Reflect.construct(Target, [guardedCallback]);
      },
    });
    Object.defineProperty(WrappedMutationObserver, "__enderSlicerThermalObserverGuard", {
      value: true,
    });
    window.MutationObserver = WrappedMutationObserver;
  }

  function dispatchRunState(active, error = "") {
    window.dispatchEvent(
      new CustomEvent(RUN_STATE_EVENT, {
        detail: { active: Boolean(active), requestId: activeRequestId, error: String(error || "") },
      })
    );
    syncUi();
  }

  function invalidate(reason) {
    invalidationEpoch += 1;
    const message = String(reason || "Thermal Integrity inputs changed; run the analysis again.");
    window.dispatchEvent(
      new CustomEvent(INVALIDATED_EVENT, {
        detail: { epoch: invalidationEpoch, message },
      })
    );
    const results = document.getElementById("ti-results");
    if (results) results.classList.remove("ready");
    const save = document.getElementById("ti-save");
    if (save) save.disabled = true;
    const status = document.getElementById("ti-status");
    if (status && activeRequestId === null) {
      status.className = "ti-status dim";
      status.textContent = message;
    }
  }

  function cancelForMutation() {
    if (
      activeRequestId !== null &&
      cancelFlag &&
      typeof Atomics !== "undefined"
    ) {
      Atomics.store(cancelFlag, 0, 1);
    }
  }

  function rejectOverlappingRequest(worker, message) {
    const error = "A Thermal Integrity solve is already running. Cancel it or wait for completion.";
    queueMicrotask(() => {
      worker.dispatchEvent(
        new MessageEvent("message", {
          data: { id: message.id, ok: false, error },
        })
      );
    });
  }

  function installWorkerGuard() {
    const ExistingWorker = window.Worker;
    if (!ExistingWorker || ExistingWorker.__enderSlicerThermalBugfixRound1) return;

    const WrappedWorker = new Proxy(ExistingWorker, {
      construct(Target, args) {
        const worker = Reflect.construct(Target, args);
        const nativePost = worker.postMessage.bind(worker);

        worker.addEventListener("message", (event) => {
          const message = event.data;
          if (!message || message.id !== activeRequestId || message.progress) return;
          activeRequestId = null;
          dispatchRunState(false, message.ok ? "" : message.error);
        });
        worker.addEventListener("error", (event) => {
          if (activeRequestId === null) return;
          const error = event?.message || "filaSim worker crashed during Thermal Integrity.";
          activeRequestId = null;
          dispatchRunState(false, error);
          invalidate(error);
        });
        worker.addEventListener("messageerror", () => {
          if (activeRequestId === null) return;
          const error = "filaSim returned an unreadable Thermal Integrity message.";
          activeRequestId = null;
          dispatchRunState(false, error);
          invalidate(error);
        });

        worker.postMessage = function postMessage(message, transferOrOptions) {
          if (
            message?.op === "setCancelBuffer" &&
            typeof SharedArrayBuffer !== "undefined" &&
            message.buf instanceof SharedArrayBuffer
          ) {
            cancelFlag = new Int32Array(message.buf);
          }

          if (message?.op === "thermalIntegrity" && Number.isSafeInteger(message.id)) {
            if (activeRequestId !== null) {
              rejectOverlappingRequest(worker, message);
              return;
            }
            activeRequestId = message.id;
            dispatchRunState(true);
          } else if (message && MUTATING_OPS.has(message.op)) {
            cancelForMutation();
            invalidate(`Thermal Integrity invalidated by filaSim operation: ${message.op}.`);
          }

          if (arguments.length > 1) nativePost(message, transferOrOptions);
          else nativePost(message);
        };
        return worker;
      },
    });

    Object.defineProperty(WrappedWorker, "__enderSlicerThermalBugfixRound1", { value: true });
    window.Worker = WrappedWorker;
  }

  function syncUi() {
    const run = document.getElementById("ti-run");
    if (run && activeRequestId !== null) run.disabled = true;
    const cancel = document.getElementById("ti-cancel");
    if (cancel && activeRequestId === null) cancel.disabled = true;
  }

  document.addEventListener(
    "click",
    (event) => {
      const target = event.target;
      if (!(target instanceof Element) || !target.closest("#ti-run")) return;
      if (activeRequestId === null) return;
      event.preventDefault();
      event.stopImmediatePropagation();
      const status = document.getElementById("ti-status");
      if (status) {
        status.className = "ti-status ti-warning";
        status.textContent = "A Thermal Integrity solve is already running.";
      }
    },
    true
  );

  installMutationObserverGuard();
  installWorkerGuard();
  syncUi();
  new MutationObserver(syncUi).observe(document.documentElement, { childList: true, subtree: true });
})();
