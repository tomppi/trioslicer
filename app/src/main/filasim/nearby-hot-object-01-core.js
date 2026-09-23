/* DuoSlicer Android-only nearby hot object thermal workspace. */
(() => {
  "use strict";

  const android = window.EnderSlicerAndroid;
  if (!android) return;

  const GROUP_ID = "enderslicer-thermal-integrity";
  const STYLE_ID = "enderslicer-nearby-hot-object-style";
  const STORAGE_KEY = "enderslicer.nearbyHotObject.v1";
  const REQUEST_START = -1_000_000_000;
  const THERMAL_RESULT_EVENT = "enderslicer-thermal-result-3d";
  const THERMAL_CLEAR_EVENT = "enderslicer-thermal-clear-3d";
  const PICK_EVENT = "enderslicer-nearby-hot-object-picked";
  const PICK_MODE_EVENT = "enderslicer-nearby-hot-object-pick-mode";
  const MARKER_EVENT = "enderslicer-nearby-hot-object-marker";
  const CLEAR_EVENT = "enderslicer-nearby-hot-object-clear";
  const ENGINE_OPS = new Set([
    "load", "loadMesh", "setMaterial", "setResolution", "setVoxelSize", "setBcs",
    "voxelInfo", "thermalIntegrity", "thermalIntegrityPreflight", "transformMatrix",
  ]);

  const PRESETS = Object.freeze({
    PLA: Object.freeze({ materialName: "PLA", conductivityXWmK: 0.18, conductivityYWmK: 0.18,
      conductivityZWmK: 0.13, densityKgM3: 1240, specificHeatJkgK: 1800,
      conductivityExponent: 1, alphaXyPerK: 0.000096, alphaZPerK: 0.00011,
      youngsModulusMpa: 2400, poissonRatio: 0.35, referenceStrengthMpa: 45, serviceLimitC: 50 }),
    PETG: Object.freeze({ materialName: "PETG", conductivityXWmK: 0.2, conductivityYWmK: 0.2,
      conductivityZWmK: 0.14, densityKgM3: 1270, specificHeatJkgK: 1200,
      conductivityExponent: 1, alphaXyPerK: 0.000065, alphaZPerK: 0.00008,
      youngsModulusMpa: 2000, poissonRatio: 0.38, referenceStrengthMpa: 48, serviceLimitC: 70 }),
    ABS: Object.freeze({ materialName: "ABS", conductivityXWmK: 0.17, conductivityYWmK: 0.17,
      conductivityZWmK: 0.12, densityKgM3: 1040, specificHeatJkgK: 1300,
      conductivityExponent: 1, alphaXyPerK: 0.00008, alphaZPerK: 0.000095,
      youngsModulusMpa: 1800, poissonRatio: 0.35, referenceStrengthMpa: 38, serviceLimitC: 85 }),
  });

  let engineWorker = null;
  let nextRequestId = REQUEST_START;
  let latest = null;
  let selected = null;
  let runInFlight = false;
  let analysisEpoch = 0;

  function installWorkerAccess() {
    const ExistingWorker = window.Worker;
    if (!ExistingWorker || ExistingWorker.__enderSlicerNearbyHotObject) return;
    const WrappedWorker = new Proxy(ExistingWorker, {
      construct(Target, args) {
        const worker = Reflect.construct(Target, args);
        const nativePost = worker.postMessage.bind(worker);
        worker.postMessage = function postMessage(message, transferOrOptions) {
          if (message && Number.isSafeInteger(message.id) && ENGINE_OPS.has(message.op)) {
            engineWorker = worker;
          }
          if (arguments.length > 1) nativePost(message, transferOrOptions);
          else nativePost(message);
        };
        return worker;
      },
    });
    Object.defineProperty(WrappedWorker, "__enderSlicerNearbyHotObject", { value: true });
    window.Worker = WrappedWorker;
  }

  function request(op, payload = {}) {
    const worker = engineWorker;
    if (!worker) return Promise.reject(new Error("filaSim engine is not ready yet."));
    const id = nextRequestId--;
    return new Promise((resolve, reject) => {
      const cleanup = () => {
        worker.removeEventListener("message", onMessage);
        worker.removeEventListener("error", onError);
        worker.removeEventListener("messageerror", onMessageError);
      };
      const onMessage = (event) => {
        const message = event.data;
        if (!message || message.id !== id || message.progress) return;
        cleanup();
        if (message.ok) resolve(message.data);
        else reject(new Error(message.error || `${op} failed`));
      };
      const onError = (event) => { cleanup(); reject(new Error(event?.message || `${op} worker failed`)); };
      const onMessageError = () => { cleanup(); reject(new Error(`${op} returned an unreadable message`)); };
      worker.addEventListener("message", onMessage);
      worker.addEventListener("error", onError);
      worker.addEventListener("messageerror", onMessageError);
      worker.postMessage({ id, op, ...payload });
    });
  }

  function finite(raw, label, min = -Infinity, max = Infinity) {
    const number = Number(raw);
    if (!Number.isFinite(number) || number < min || number > max) {
      throw new Error(`${label} must be between ${min} and ${max}.`);
    }
    return number;
  }
  function integer(raw, label, min, max) {
    const number = finite(raw, label, min, max);
    if (!Number.isInteger(number)) throw new Error(`${label} must be a whole number.`);
    return number;
  }
  function input(id) {
    const element = document.getElementById(`ti-${id}`);
    if (!element) throw new Error(`Nearby Hot Object input is missing: ${id}`);
    return element;
  }
  function value(id) { return input(id).value; }
  function checked(id) { return Boolean(input(id).checked); }
  function setValue(id, next) { const el = document.getElementById(`ti-${id}`); if (el) el.value = String(next); }
  function field(id, label, initial, step = "any") {
    // Android WebView number inputs can drop keystrokes (only the 7/8/9/0 row
    // registering) because Chromium applies step/min/max validation while the
    // IME is composing. Use a decimal text input so the device shows a decimal
    // keypad and accepts every key; validation still happens in collectOptions.
    return `<label class="ti-field"><span>${label}</span><input id="ti-${id}" type="text" inputmode="decimal" autocomplete="off" spellcheck="false" value="${initial}"></label>`;
  }
  function checkbox(id, label, on) {
    return `<label class="ti-check"><input id="ti-${id}" type="checkbox"${on ? " checked" : ""}><span>${label}</span></label>`;
  }
  function faceOptions(selectedFace) {
    const labels = { xmin: "Global X− plane", xmax: "Global X+ plane", ymin: "Global Y− plane",
      ymax: "Global Y+ plane", zmin: "Global Z− plane", zmax: "Global Z+ plane" };
    return Object.entries(labels).map(([key, label]) =>
      `<option value="${key}"${key === selectedFace ? " selected" : ""}>${label}</option>`).join("");
  }

  function installStyle() {
    if (document.getElementById(STYLE_ID)) return;
    const style = document.createElement("style");
    style.id = STYLE_ID;
    style.textContent = `
      #${GROUP_ID} { border-top:1px solid rgba(255,255,255,.16); padding-top:10px; }
      #${GROUP_ID} .ti-title { display:flex; align-items:center; justify-content:space-between; gap:8px; }
      #${GROUP_ID} .ti-title strong { font-size:14px; }
      #${GROUP_ID} .ti-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:7px; }
      #${GROUP_ID} .ti-field, #${GROUP_ID} .ti-select { display:flex; flex-direction:column; gap:3px; min-width:0; }
      #${GROUP_ID} .ti-field span, #${GROUP_ID} .ti-select span { font-size:11px; opacity:.78; }
      #${GROUP_ID} input[type=number], #${GROUP_ID} select { width:100%; box-sizing:border-box;
        min-height:34px; padding:5px 7px; color:inherit; background:rgba(255,255,255,.06);
        border:1px solid rgba(255,255,255,.2); border-radius:5px; }
      #${GROUP_ID} .ti-check { display:flex; align-items:center; gap:7px; font-size:12px; margin:5px 0; }
      #${GROUP_ID} .ti-pick { display:grid; grid-template-columns:1fr; gap:6px; margin:8px 0; padding:8px;
        border-radius:6px; background:rgba(255,120,70,.08); border:1px solid rgba(255,120,70,.24); }
      #${GROUP_ID} .ti-pick button { min-height:40px; }
      #${GROUP_ID} .ti-actions { display:grid; grid-template-columns:1fr 1fr; gap:7px; margin-top:8px; }
      #${GROUP_ID} button { min-height:37px; }
      #${GROUP_ID} details { margin-top:8px; }
      #${GROUP_ID} summary { cursor:pointer; font-size:12px; opacity:.9; }
      #${GROUP_ID} .ti-status { white-space:pre-wrap; font-size:11px; margin-top:7px; }
      #${GROUP_ID} .ti-results { display:none; margin-top:9px; }
      #${GROUP_ID} .ti-results.ready { display:block; }
      #${GROUP_ID} .ti-kpis { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:5px; }
      #${GROUP_ID} .ti-kpi { padding:6px; border-radius:5px; background:rgba(255,255,255,.055); }
      #${GROUP_ID} .ti-kpi b { display:block; font-size:13px; }
      #${GROUP_ID} .ti-kpi span { font-size:10px; opacity:.72; }
      #${GROUP_ID} .ti-warning { color:#ffca78; } #${GROUP_ID} .ti-error { color:#ff8d8d; }
      #${GROUP_ID} .ti-hidden { display:none !important; }
      @media (max-width:520px) { #${GROUP_ID} .ti-grid { grid-template-columns:1fr; } }
    `;
    document.head.appendChild(style);
  }

