/*
 * Live residual telemetry for the EnderSlicer Thermal Integrity workspace.
 *
 * filaSim writes convergence data into a SharedArrayBuffer because ordinary
 * worker messages cannot be delivered while the WASM solve blocks its worker.
 */
(() => {
  "use strict";

  const STYLE_ID = "enderslicer-thermal-live-progress-style";
  const CHIP_ID = "ti-progress-solver";
  let progressCount = null;
  let progressData = null;
  let thermalRequestId = null;
  let pollTimer = null;
  let lastCount = -1;
  let lastResidual = Number.NaN;

  function installStyle() {
    if (document.getElementById(STYLE_ID)) return;
    const style = document.createElement("style");
    style.id = STYLE_ID;
    style.textContent = `
      #${CHIP_ID}.ti-live-active::before {
        content:""; display:inline-block; width:6px; height:6px; margin-right:5px;
        border-radius:50%; background:currentColor; vertical-align:1px;
        animation:ti-live-pulse 1.1s ease-in-out infinite;
      }
      @keyframes ti-live-pulse { 0%,100% { opacity:.25; } 50% { opacity:1; } }
    `;
    document.head.appendChild(style);
  }

  function ensureChip() {
    installStyle();
    const grid = document.querySelector("#enderslicer-thermal-integrity .ti-progress-grid");
    if (!grid) return null;
    let chip = document.getElementById(CHIP_ID);
    if (!chip) {
      chip = document.createElement("div");
      chip.id = CHIP_ID;
      chip.className = "ti-progress-chip";
      chip.textContent = "Solver: waiting for live residual data";
      grid.appendChild(chip);
    }
    return chip;
  }

  function formatResidual(value) {
    if (!Number.isFinite(value)) return "—";
    if (value === 0) return "0";
    return value.toExponential(2);
  }

  function renderLiveProgress() {
    const chip = ensureChip();
    if (!chip) return;
    if (!progressCount || !progressData) {
      chip.classList.remove("ti-live-active");
      chip.textContent = "Solver: live residual unavailable";
      return;
    }

    const count = Math.max(0, Math.min(Atomics.load(progressCount, 0), progressData.length));
    if (count <= 0) {
      chip.classList.toggle("ti-live-active", thermalRequestId !== null);
      chip.textContent = thermalRequestId === null
        ? "Solver: ready"
        : "Solver: initializing convergence trace";
      return;
    }

    const residual = progressData[count - 1];
    const changing = count !== lastCount || residual !== lastResidual;
    chip.classList.toggle("ti-live-active", thermalRequestId !== null && changing);
    chip.textContent = `Solver: ${count.toLocaleString()} residual samples · r=${formatResidual(residual)}`;
    lastCount = count;
    lastResidual = residual;
  }

  function startPolling() {
    if (pollTimer !== null) return;
    renderLiveProgress();
    pollTimer = window.setInterval(renderLiveProgress, 250);
  }

  function stopPolling() {
    if (pollTimer !== null) window.clearInterval(pollTimer);
    pollTimer = null;
    renderLiveProgress();
  }

  function installWorkerAccess() {
    const ExistingWorker = window.Worker;
    if (!ExistingWorker || ExistingWorker.__enderSlicerThermalLiveProgress) return;
    const WrappedWorker = new Proxy(ExistingWorker, {
      construct(Target, args) {
        const worker = Reflect.construct(Target, args);
        const nativePost = worker.postMessage.bind(worker);

        worker.addEventListener("message", (event) => {
          const message = event.data;
          if (!message || message.id !== thermalRequestId || message.progress) return;
          thermalRequestId = null;
          stopPolling();
        });

        worker.postMessage = function postMessage(message, transferOrOptions) {
          if (
            message?.op === "setProgressBuffer" &&
            typeof SharedArrayBuffer !== "undefined" &&
            message.buf instanceof SharedArrayBuffer
          ) {
            progressCount = new Int32Array(message.buf, 0, 1);
            progressData = new Float32Array(message.buf, 4, (message.buf.byteLength - 4) >> 2);
            renderLiveProgress();
          }
          if (message?.op === "thermalIntegrity" && Number.isSafeInteger(message.id)) {
            thermalRequestId = message.id;
            lastCount = -1;
            lastResidual = Number.NaN;
            if (progressCount) Atomics.store(progressCount, 0, 0);
            startPolling();
          }
          if (arguments.length > 1) nativePost(message, transferOrOptions);
          else nativePost(message);
        };
        return worker;
      },
    });
    Object.defineProperty(WrappedWorker, "__enderSlicerThermalLiveProgress", { value: true });
    window.Worker = WrappedWorker;
  }

  installWorkerAccess();
  ensureChip();
  new MutationObserver(ensureChip).observe(document.documentElement, { childList: true, subtree: true });
})();
