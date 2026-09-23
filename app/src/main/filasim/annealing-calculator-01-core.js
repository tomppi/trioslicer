/*
 * DuoSlicer geometry-aware annealing planner.
 *
 * Uses the validated Thermal Integrity voxel grid and implicit transient solver.
 * Heating completes only after the coldest material voxel reaches the requested
 * annealing threshold for the configured soak. Cooling completes only after the
 * hottest material voxel falls below the handling target.
 */
(() => {
  "use strict";

  const android = window.EnderSlicerAndroid;
  if (!android) return;

  const GROUP_ID = "enderslicer-annealing-calculator";
  const STYLE_ID = "enderslicer-annealing-calculator-style";
  const MOUNT_ID = "enderslicer-annealing-calculator-mount";
  const INVALIDATED_EVENT = "enderslicer-thermal-integrity-invalidated";
  const THERMAL_RESULT_EVENT = "enderslicer-thermal-result-3d";
  const THERMAL_CLEAR_EVENT = "enderslicer-thermal-clear-3d";
  const STORAGE_KEY = "enderslicer.annealingPlanner.v1";
  const CALIBRATION_KEY = "enderslicer.annealingCalibration.v1";
  const REPORT_KEY = "enderslicer.annealingLatestReport.v1";
  const REQUEST_START = -1_800_000_000;
  const ENGINE_OPS = new Set([
    "load", "loadMesh", "setMaterial", "setResolution", "setVoxelSize", "setBcs",
    "voxelInfo", "solve", "optimize", "buildSim", "thermalIntegrity",
    "thermalIntegrityPreflight", "transformMatrix", "setCancelBuffer",
  ]);

  const PRESETS = Object.freeze({
    PLA: Object.freeze({
      materialName: "PLA", conductivityXWmK: 0.18, conductivityYWmK: 0.18,
      conductivityZWmK: 0.13, densityKgM3: 1240, specificHeatJkgK: 1800,
      alphaXyPerK: 0.000096, alphaZPerK: 0.00011, youngsModulusMpa: 2400,
      poissonRatio: 0.35, referenceStrengthMpa: 45, serviceLimitC: 50,
      ovenTemperatureC: 75, soakMinutes: 60, handlingTemperatureC: 45,
      status: "literature-seeded",
    }),
    "PLA+": Object.freeze({
      materialName: "PLA+", conductivityXWmK: 0.18, conductivityYWmK: 0.18,
      conductivityZWmK: 0.13, densityKgM3: 1240, specificHeatJkgK: 1800,
      alphaXyPerK: 0.00009, alphaZPerK: 0.000105, youngsModulusMpa: 2350,
      poissonRatio: 0.35, referenceStrengthMpa: 47, serviceLimitC: 50,
      ovenTemperatureC: 70, soakMinutes: 60, handlingTemperatureC: 45,
      status: "brand-dependent",
    }),
    HTPLA: Object.freeze({
      materialName: "HTPLA", conductivityXWmK: 0.19, conductivityYWmK: 0.19,
      conductivityZWmK: 0.135, densityKgM3: 1240, specificHeatJkgK: 1750,
      alphaXyPerK: 0.000085, alphaZPerK: 0.0001, youngsModulusMpa: 2450,
      poissonRatio: 0.35, referenceStrengthMpa: 48, serviceLimitC: 55,
      ovenTemperatureC: 90, soakMinutes: 30, handlingTemperatureC: 50,
      status: "manufacturer-profile-required",
    }),
    PETG: Object.freeze({
      materialName: "PETG", conductivityXWmK: 0.2, conductivityYWmK: 0.2,
      conductivityZWmK: 0.14, densityKgM3: 1270, specificHeatJkgK: 1200,
      alphaXyPerK: 0.000065, alphaZPerK: 0.00008, youngsModulusMpa: 2000,
      poissonRatio: 0.38, referenceStrengthMpa: 48, serviceLimitC: 70,
      ovenTemperatureC: 85, soakMinutes: 60, handlingTemperatureC: 50,
      status: "experimental",
    }),
    ABS: Object.freeze({
      materialName: "ABS", conductivityXWmK: 0.17, conductivityYWmK: 0.17,
      conductivityZWmK: 0.12, densityKgM3: 1040, specificHeatJkgK: 1300,
      alphaXyPerK: 0.00008, alphaZPerK: 0.000095, youngsModulusMpa: 1800,
      poissonRatio: 0.35, referenceStrengthMpa: 38, serviceLimitC: 85,
      ovenTemperatureC: 105, soakMinutes: 15, handlingTemperatureC: 55,
      status: "experimental",
    }),
  });

  let engineWorker = null;
  let cancelFlag = null;
  let nextRequestId = REQUEST_START;
  let runInFlight = false;
  let latest = null;
  let observer = null;
  let elapsedTimer = null;
  let runStartedAt = 0;

  function installWorkerAccess() {
    const ExistingWorker = window.Worker;
    if (!ExistingWorker || ExistingWorker.__enderSlicerAnnealingPlanner) return;
    const WrappedWorker = new Proxy(ExistingWorker, {
      construct(Target, args) {
        const worker = Reflect.construct(Target, args);
        const nativePost = worker.postMessage.bind(worker);
        worker.postMessage = function postMessage(message, transferOrOptions) {
          if (message?.op === "setCancelBuffer" &&
              typeof SharedArrayBuffer !== "undefined" &&
              message.buf instanceof SharedArrayBuffer) {
            cancelFlag = new Int32Array(message.buf);
          }
          if (message && Number.isSafeInteger(message.id) && ENGINE_OPS.has(message.op)) {
            engineWorker = worker;
          }
          if (arguments.length > 1) nativePost(message, transferOrOptions);
          else nativePost(message);
        };
        return worker;
      },
    });
    Object.defineProperty(WrappedWorker, "__enderSlicerAnnealingPlanner", { value: true });
    window.Worker = WrappedWorker;
  }

  function request(op, payload = {}, onProgress = null) {
    const worker = engineWorker;
    if (!worker) {
      return Promise.reject(new Error("filaSim is not ready. Wait for the model to finish loading."));
    }
    const id = nextRequestId--;
    return new Promise((resolve, reject) => {
      const cleanup = () => {
        worker.removeEventListener("message", onMessage);
        worker.removeEventListener("error", onError);
        worker.removeEventListener("messageerror", onMessageError);
      };
      const onMessage = (event) => {
        const message = event.data;
        if (!message || message.id !== id) return;
        if (message.progress) {
          onProgress?.(message.data);
          return;
        }
        cleanup();
        if (message.ok) resolve(message.data);
        else reject(new Error(message.error || `${op} failed`));
      };
      const onError = (event) => {
        cleanup();
        reject(new Error(event?.message || `${op} worker failed`));
      };
      const onMessageError = () => {
        cleanup();
        reject(new Error(`${op} returned an unreadable worker message`));
      };
      worker.addEventListener("message", onMessage);
      worker.addEventListener("error", onError);
      worker.addEventListener("messageerror", onMessageError);
      worker.postMessage({ id, op, ...payload });
    });
  }

  function input(id) {
    const element = document.getElementById(`ac-${id}`);
    if (!element) throw new Error(`Annealing input is missing: ${id}`);
    return element;
  }
  function value(id) { return input(id).value; }
  function checked(id) { return Boolean(input(id).checked); }
  function setValue(id, newValue) {
    const element = document.getElementById(`ac-${id}`);
    if (element) element.value = String(newValue);
  }
  function finite(entry, label, min = -Infinity, max = Infinity) {
    const number = Number(entry);
    if (!Number.isFinite(number) || number < min || number > max) {
      throw new Error(`${label} must be between ${min} and ${max}.`);
    }
    return number;
  }
  function integer(entry, label, min, max) {
    const number = finite(entry, label, min, max);
    if (!Number.isInteger(number)) throw new Error(`${label} must be a whole number.`);
    return number;
  }
  function field(id, label, defaultValue, step = "any", suffix = "") {
    // Android WebView number inputs can drop keystrokes while the IME is
    // composing; a decimal text input shows the decimal keypad and accepts
    // every key. Values are still validated on read.
    return `<label class="ac-field"><span>${label}</span><div class="ac-input"><input id="ac-${id}" type="text" inputmode="decimal" autocomplete="off" spellcheck="false" value="${defaultValue}">${suffix ? `<em>${suffix}</em>` : ""}</div></label>`;
  }
  function checkbox(id, label, on) {
    return `<label class="ac-check"><input id="ac-${id}" type="checkbox"${on ? " checked" : ""}><span>${label}</span></label>`;
  }

  function installStyle() {
    if (document.getElementById(STYLE_ID)) return;
    const style = document.createElement("style");
    style.id = STYLE_ID;
    style.textContent = `
      #${GROUP_ID} { padding-top:2px; }
      #${GROUP_ID} .ac-lead { font-size:11px; line-height:1.45; opacity:.78; }
      #${GROUP_ID} .ac-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:7px; margin-top:8px; }
      #${GROUP_ID} .ac-field, #${GROUP_ID} .ac-select { display:flex; flex-direction:column; gap:3px; min-width:0; }
      #${GROUP_ID} .ac-field>span, #${GROUP_ID} .ac-select>span { font-size:11px; opacity:.78; }
      #${GROUP_ID} .ac-input { display:flex; align-items:center; gap:5px; }
      #${GROUP_ID} .ac-input input { flex:1; min-width:0; }
      #${GROUP_ID} .ac-input em { font-size:10px; opacity:.65; font-style:normal; }
      #${GROUP_ID} input[type=number], #${GROUP_ID} input[type=text], #${GROUP_ID} select {
        width:100%; box-sizing:border-box; min-height:34px; padding:5px 7px; color:inherit;
        background:rgba(255,255,255,.06); border:1px solid rgba(255,255,255,.2); border-radius:5px;
      }
      #${GROUP_ID} .ac-check { display:flex; align-items:center; gap:7px; font-size:12px; margin:6px 0; }
      #${GROUP_ID} details { margin-top:9px; }
      #${GROUP_ID} summary { cursor:pointer; font-size:12px; opacity:.92; }
      #${GROUP_ID} .ac-actions { display:grid; grid-template-columns:1.4fr .8fr; gap:7px; margin-top:9px; }
      #${GROUP_ID} button { min-height:38px; }
      #${GROUP_ID} .ac-status { white-space:pre-wrap; font-size:11px; line-height:1.4; margin-top:7px; }
      #${GROUP_ID} .ac-progress { margin-top:8px; padding:8px; border-radius:6px; background:rgba(255,255,255,.055); }
      #${GROUP_ID} .ac-progress[hidden] { display:none!important; }
      #${GROUP_ID} .ac-progress-head { display:flex; justify-content:space-between; gap:8px; font-size:11px; }
      #${GROUP_ID} progress { width:100%; height:10px; margin-top:6px; }
      #${GROUP_ID} .ac-results { display:none; margin-top:10px; }
      #${GROUP_ID} .ac-results.ready { display:block; }
      #${GROUP_ID} .ac-kpis { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:5px; }
      #${GROUP_ID} .ac-kpi { padding:7px; border-radius:5px; background:rgba(255,255,255,.055); }
      #${GROUP_ID} .ac-kpi b { display:block; font-size:13px; }
      #${GROUP_ID} .ac-kpi span { display:block; font-size:10px; opacity:.7; margin-top:2px; }
      #${GROUP_ID} canvas { width:100%; display:block; margin-top:8px; border-radius:5px; background:#111; }
      #${GROUP_ID} .ac-warning { color:#ffca78; }
      #${GROUP_ID} .ac-error { color:#ff8d8d; }
      #${GROUP_ID} .ac-cal-result { padding:7px; margin-top:7px; border-radius:5px; background:rgba(255,255,255,.055); font-size:11px; white-space:pre-wrap; }
      #${GROUP_ID} .ac-report-actions { display:grid; grid-template-columns:1fr 1fr; gap:7px; margin-top:8px; }
      @media (max-width:520px) {
        #${GROUP_ID} .ac-grid { grid-template-columns:1fr; }
        #${GROUP_ID} .ac-actions, #${GROUP_ID} .ac-report-actions { grid-template-columns:1fr; }
      }
    `;
    document.head.appendChild(style);
  }
