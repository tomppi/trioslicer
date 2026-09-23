/*
 * DuoSlicer Android-only thermal integrity workspace.
 *
 * This is deliberately separate from filaSim's print-build shrink simulation:
 * it solves service-temperature heat conduction and couples the temperature
 * field into structural FEA through local thermal strain and reduced material
 * properties. The result remains an experimental, literature-seeded estimate.
 */
(() => {
  "use strict";

  const android = window.EnderSlicerAndroid;
  if (!android) return;

  const GROUP_ID = "enderslicer-thermal-integrity";
  const STYLE_ID = "enderslicer-thermal-integrity-style";
  const REQUEST_START = -1_000_000_000;
  const STORAGE_KEY = "enderslicer.thermalIntegrity.v1";
  const ENGINE_OPS = new Set([
    "load",
    "loadMesh",
    "setMaterial",
    "setResolution",
    "setVoxelSize",
    "setBcs",
    "voxelInfo",
    "solve",
    "optimize",
    "buildSim",
    "thermalIntegrity",
    "transformMatrix",
  ]);

  const PRESETS = Object.freeze({
    PLA: Object.freeze({
      materialName: "PLA",
      conductivityXWmK: 0.18,
      conductivityYWmK: 0.18,
      conductivityZWmK: 0.13,
      densityKgM3: 1240,
      specificHeatJkgK: 1800,
      conductivityExponent: 1.0,
      alphaXyPerK: 0.000096,
      alphaZPerK: 0.00011,
      youngsModulusMpa: 2400,
      poissonRatio: 0.35,
      referenceStrengthMpa: 45,
      serviceLimitC: 50,
    }),
    PETG: Object.freeze({
      materialName: "PETG",
      conductivityXWmK: 0.2,
      conductivityYWmK: 0.2,
      conductivityZWmK: 0.14,
      densityKgM3: 1270,
      specificHeatJkgK: 1200,
      conductivityExponent: 1.0,
      alphaXyPerK: 0.000065,
      alphaZPerK: 0.00008,
      youngsModulusMpa: 2000,
      poissonRatio: 0.38,
      referenceStrengthMpa: 48,
      serviceLimitC: 70,
    }),
    ABS: Object.freeze({
      materialName: "ABS",
      conductivityXWmK: 0.17,
      conductivityYWmK: 0.17,
      conductivityZWmK: 0.12,
      densityKgM3: 1040,
      specificHeatJkgK: 1300,
      conductivityExponent: 1.0,
      alphaXyPerK: 0.00008,
      alphaZPerK: 0.000095,
      youngsModulusMpa: 1800,
      poissonRatio: 0.35,
      referenceStrengthMpa: 38,
      serviceLimitC: 85,
    }),
  });

  let engineWorker = null;
  let nextRequestId = REQUEST_START;
  let latest = null;
  let observer = null;

  function installWorkerAccess() {
    const ExistingWorker = window.Worker;
    if (!ExistingWorker || ExistingWorker.__enderSlicerThermalIntegrity) return;
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
    Object.defineProperty(WrappedWorker, "__enderSlicerThermalIntegrity", { value: true });
    window.Worker = WrappedWorker;
  }

  installWorkerAccess();

  function request(op, payload = {}) {
    const worker = engineWorker;
    if (!worker) {
      return Promise.reject(
        new Error("filaSim engine is not ready yet. Wait for the Android model to finish loading.")
      );
    }
    const id = nextRequestId--;
    return new Promise((resolve, reject) => {
      const listener = (event) => {
        const message = event.data;
        if (!message || message.id !== id || message.progress) return;
        worker.removeEventListener("message", listener);
        if (message.ok) resolve(message.data);
        else reject(new Error(message.error || `${op} failed`));
      };
      worker.addEventListener("message", listener);
      worker.postMessage({ id, op, ...payload });
    });
  }

  function finite(value, label, min = -Infinity, max = Infinity) {
    const number = Number(value);
    if (!Number.isFinite(number) || number < min || number > max) {
      throw new Error(`${label} must be between ${min} and ${max}`);
    }
    return number;
  }

  function integer(value, label, min, max) {
    const number = finite(value, label, min, max);
    if (!Number.isInteger(number)) throw new Error(`${label} must be a whole number`);
    return number;
  }

  function input(id) {
    const element = document.getElementById(`ti-${id}`);
    if (!element) throw new Error(`Thermal integrity input is missing: ${id}`);
    return element;
  }

  function value(id) {
    return input(id).value;
  }

  function checked(id) {
    return Boolean(input(id).checked);
  }

  function setValue(id, newValue) {
    const element = document.getElementById(`ti-${id}`);
    if (element) element.value = String(newValue);
  }

  function faceOptions(selected) {
    const labels = {
      xmin: "X− face",
      xmax: "X+ face",
      ymin: "Y− face",
      ymax: "Y+ face",
      zmin: "Z− face",
      zmax: "Z+ face",
    };
    return Object.entries(labels)
      .map(([key, label]) => `<option value="${key}"${key === selected ? " selected" : ""}>${label}</option>`)
      .join("");
  }

  function field(id, label, value, step = "any") {
    // Android WebView number inputs can drop keystrokes while the IME is
    // composing; a decimal text input shows the decimal keypad and accepts
    // every key. Values are still validated on read.
    return `<label class="ti-field"><span>${label}</span><input id="ti-${id}" type="text" inputmode="decimal" autocomplete="off" spellcheck="false" value="${value}"></label>`;
  }

  function checkbox(id, label, on) {
    return `<label class="ti-check"><input id="ti-${id}" type="checkbox"${on ? " checked" : ""}><span>${label}</span></label>`;
  }

  function installStyle() {
    if (document.getElementById(STYLE_ID)) return;
    const style = document.createElement("style");
    style.id = STYLE_ID;
    style.textContent = `
      #${GROUP_ID} { border-top: 1px solid rgba(255,255,255,.16); padding-top: 10px; }
      #${GROUP_ID} .ti-title { display:flex; align-items:center; justify-content:space-between; gap:8px; }
      #${GROUP_ID} .ti-title strong { font-size: 14px; }
      #${GROUP_ID} .ti-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:7px; }
      #${GROUP_ID} .ti-field, #${GROUP_ID} .ti-select { display:flex; flex-direction:column; gap:3px; min-width:0; }
      #${GROUP_ID} .ti-field span, #${GROUP_ID} .ti-select span { font-size:11px; opacity:.78; }
      #${GROUP_ID} input[type=number], #${GROUP_ID} select {
        width:100%; box-sizing:border-box; min-height:34px; padding:5px 7px;
        color:inherit; background:rgba(255,255,255,.06); border:1px solid rgba(255,255,255,.2);
        border-radius:5px;
      }
      #${GROUP_ID} .ti-check { display:flex; align-items:center; gap:7px; font-size:12px; margin:5px 0; }
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
      #${GROUP_ID} canvas { width:100%; display:block; margin-top:7px; border-radius:5px; background:#111; }
      #${GROUP_ID} .ti-slice-controls { display:grid; grid-template-columns:90px 1fr; gap:7px; align-items:center; margin-top:7px; }
      #${GROUP_ID} .ti-warning { color:#ffca78; }
      #${GROUP_ID} .ti-error { color:#ff8d8d; }
      @media (max-width:520px) {
        #${GROUP_ID} .ti-grid { grid-template-columns:1fr; }
      }
    `;
    document.head.appendChild(style);
  }

  function createGroup() {
    const group = document.createElement("div");
    group.id = GROUP_ID;
    group.className = "group";
    group.innerHTML = `
      <div class="ti-title">
        <strong>Thermal Integrity</strong>
        <span class="dim small">service-temperature FEA</span>
      </div>
      <div class="dim small">
        Steady or transient anisotropic heat conduction, convection, radiation,
        thermal expansion, temperature-reduced stiffness and combined mechanical loading.
        Literature-seeded estimates are not certification.
      </div>

      <div class="ti-grid" style="margin-top:8px">
        <label class="ti-select"><span>Analysis</span>
          <select id="ti-mode"><option value="steady">Steady state</option><option value="transient">Transient warm-up/cool-down</option></select>
        </label>
        <label class="ti-select"><span>Material preset</span>
          <select id="ti-preset"><option>PLA</option><option>PETG</option><option>ABS</option></select>
        </label>
        <label class="ti-select"><span>Heated surface</span><select id="ti-heatedFace">${faceOptions("zmax")}</select></label>
        <label class="ti-select"><span>Fixed-temperature surface</span><select id="ti-cooledFace">${faceOptions("zmin")}</select></label>
        ${field("heatPowerW", "Surface heat power (W)", 5, 0.1)}
        ${field("volumetricPowerW", "Internal heat power (W)", 0, 0.1)}
        ${field("ambientTemperatureC", "Ambient (°C)", 23, 0.1)}
        ${field("cooledTemperatureC", "Fixed surface (°C)", 23, 0.1)}
        ${field("convectionWm2K", "Convection (W/m²K)", 8, 0.1)}
        ${field("emissivity", "Emissivity", 0.9, 0.01)}
        ${field("durationSeconds", "Transient duration (s)", 600, 1)}
        ${field("timeStepSeconds", "Time step (s)", 10, 0.1)}
      </div>
      ${checkbox("densityAware", "Use optimized Smart Infill density when available", true)}
      ${checkbox("freeExpansion", "Free thermal expansion (3-2-1 grounding); turn off to use filaSim supports", true)}

      <details>
        <summary>Material and print-property inputs</summary>
        <div class="ti-grid" style="margin-top:7px">
          ${field("conductivityXWmK", "Conductivity X (W/mK)", 0.18, 0.001)}
          ${field("conductivityYWmK", "Conductivity Y (W/mK)", 0.18, 0.001)}
          ${field("conductivityZWmK", "Conductivity Z (W/mK)", 0.13, 0.001)}
          ${field("densityKgM3", "Density (kg/m³)", 1240, 1)}
          ${field("specificHeatJkgK", "Specific heat (J/kgK)", 1800, 1)}
          ${field("conductivityExponent", "Infill conductivity exponent", 1, 0.05)}
          ${field("alphaXyPerK", "CTE XY (1/K)", 0.000096, 0.000001)}
          ${field("alphaZPerK", "CTE Z (1/K)", 0.00011, 0.000001)}
          ${field("youngsModulusMpa", "Young's modulus at reference (MPa)", 2400, 10)}
          ${field("poissonRatio", "Poisson ratio", 0.35, 0.01)}
          ${field("referenceStrengthMpa", "Reference strength (MPa)", 45, 0.5)}
          ${field("strengthDensityExponent", "Infill strength exponent", 1.5, 0.05)}
          ${field("referenceTemperatureC", "Property reference (°C)", 23, 0.1)}
          ${field("serviceLimitC", "Preset service limit (°C)", 50, 0.5)}
          ${field("modulusFloorFraction", "Modulus floor fraction", 0.05, 0.01)}
          ${field("strengthFloorFraction", "Strength floor fraction", 0.05, 0.01)}
          ${field("initialTemperatureC", "Initial temperature (°C)", 23, 0.1)}
          ${field("infillPct", "Fallback infill (%)", 25, 1)}
          ${field("exponent", "Stiffness density exponent", 1.5, 0.05)}
          ${field("coeff", "Stiffness density coefficient", 1, 0.05)}
          ${field("perimeters", "Perimeters", 2, 1)}
          ${field("lineWidth", "Line width (mm)", 0.45, 0.01)}
          ${field("topBottomLayers", "Top/bottom layers", 5, 1)}
          ${field("layerHeight", "Layer height (mm)", 0.2, 0.01)}
        </div>
      </details>

      <div class="ti-actions">
        <button id="ti-run" class="primary">Run Thermal Integrity</button>
        <button id="ti-save" disabled>Save Integrity Report</button>
      </div>
      <div id="ti-status" class="ti-status dim">Load the model, choose boundaries, then run the solver.</div>

      <div id="ti-results" class="ti-results">
        <div id="ti-kpis" class="ti-kpis"></div>
        <div class="ti-slice-controls">
          <select id="ti-axis"><option value="z">Z slice</option><option value="y">Y slice</option><option value="x">X slice</option></select>
          <input id="ti-slice" type="range" min="0" max="0" value="0">
        </div>
        <canvas id="ti-heatmap" width="640" height="420"></canvas>
        <canvas id="ti-history" width="640" height="240"></canvas>
        <div id="ti-result-note" class="ti-status dim"></div>
      </div>
    `;
    return group;
  }

  function applyPreset(name) {
    const preset = PRESETS[name];
    if (!preset) return;
    Object.entries(preset).forEach(([key, presetValue]) => {
      if (key !== "materialName") setValue(key, presetValue);
    });
    setValue("referenceTemperatureC", 23);
    setValue("initialTemperatureC", value("ambientTemperatureC") || 23);
    invalidate("Material properties changed; run Thermal Integrity again.");
  }

  function collectOptions() {
    const presetName = value("preset");
    const preset = PRESETS[presetName];
    const mode = value("mode");
    const options = {
      mode,
      materialName: preset?.materialName || presetName,
      conductivityXWmK: finite(value("conductivityXWmK"), "X conductivity", 0.005, 1000),
      conductivityYWmK: finite(value("conductivityYWmK"), "Y conductivity", 0.005, 1000),
      conductivityZWmK: finite(value("conductivityZWmK"), "Z conductivity", 0.005, 1000),
      densityKgM3: finite(value("densityKgM3"), "density", 50, 30000),
      specificHeatJkgK: finite(value("specificHeatJkgK"), "specific heat", 50, 10000),
      conductivityExponent: finite(value("conductivityExponent"), "conductivity exponent", 0.25, 4),
      alphaXyPerK: finite(value("alphaXyPerK"), "XY CTE", 0, 0.01),
      alphaZPerK: finite(value("alphaZPerK"), "Z CTE", 0, 0.01),
      youngsModulusMpa: finite(value("youngsModulusMpa"), "Young's modulus", 1, 10_000_000),
      poissonRatio: finite(value("poissonRatio"), "Poisson ratio", -0.49, 0.49),
      referenceStrengthMpa: finite(value("referenceStrengthMpa"), "reference strength", 0.1, 10000),
      strengthDensityExponent: finite(value("strengthDensityExponent"), "strength exponent", 0.5, 4),
      referenceTemperatureC: finite(value("referenceTemperatureC"), "reference temperature", -200, 1000),
      serviceLimitC: finite(value("serviceLimitC"), "service limit", -100, 1000),
      modulusFloorFraction: finite(value("modulusFloorFraction"), "modulus floor", 0.001, 1),
      strengthFloorFraction: finite(value("strengthFloorFraction"), "strength floor", 0.001, 1),
      ambientTemperatureC: finite(value("ambientTemperatureC"), "ambient temperature", -200, 1000),
      initialTemperatureC: finite(value("initialTemperatureC"), "initial temperature", -200, 1000),
      cooledTemperatureC: finite(value("cooledTemperatureC"), "fixed surface temperature", -200, 1000),
      convectionWm2K: finite(value("convectionWm2K"), "convection", 0, 100000),
      emissivity: finite(value("emissivity"), "emissivity", 0, 1),
      heatedFace: value("heatedFace"),
      cooledFace: value("cooledFace"),
      heatPowerW: finite(value("heatPowerW"), "surface power", 0, 100000),
      volumetricPowerW: finite(value("volumetricPowerW"), "internal power", 0, 100000),
      durationSeconds: finite(value("durationSeconds"), "duration", 0.01, 31_536_000),
      timeStepSeconds: finite(value("timeStepSeconds"), "time step", 0.0001, 86400),
      freeExpansion: checked("freeExpansion"),
      densityAware: checked("densityAware"),
      infillPct: finite(value("infillPct"), "infill", 1, 100),
      exponent: finite(value("exponent"), "stiffness exponent", 1, 3.5),
      coeff: finite(value("coeff"), "stiffness coefficient", 0.05, 2),
      perimeters: integer(value("perimeters"), "perimeters", 0, 20),
      lineWidth: finite(value("lineWidth"), "line width", 0.05, 5),
      topBottomLayers: integer(value("topBottomLayers"), "top/bottom layers", 0, 20),
      layerHeight: finite(value("layerHeight"), "layer height", 0.04, 0.6),
    };
    if (options.heatedFace === options.cooledFace && options.heatPowerW > 0) {
      throw new Error("The heated and fixed-temperature surfaces must be different.");
    }
    if (mode === "transient") {
      const steps = Math.ceil(options.durationSeconds / options.timeStepSeconds);
      if (steps > 2000) throw new Error("Transient run exceeds 2000 steps; increase the time step.");
    }
    return options;
  }

  function saveDraft(options) {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(options));
    } catch (_) {
      // Storage is optional.
    }
  }

  function restoreDraft() {
    let draft = null;
    try {
      draft = JSON.parse(localStorage.getItem(STORAGE_KEY) || "null");
    } catch (_) {
      return;
    }
    if (!draft || typeof draft !== "object") return;
    Object.entries(draft).forEach(([key, draftValue]) => {
      const element = document.getElementById(`ti-${key}`);
      if (!element) return;
      if (element.type === "checkbox") element.checked = Boolean(draftValue);
      else element.value = String(draftValue);
    });
    const preset = Object.keys(PRESETS).find((key) => PRESETS[key].materialName === draft.materialName);
    if (preset) setValue("preset", preset);
  }

  function invalidate(message) {
    latest = null;
    const save = document.getElementById("ti-save");
    if (save) save.disabled = true;
    const status = document.getElementById("ti-status");
    if (status && message) status.textContent = message;
  }

  async function runAnalysis() {
    const runButton = input("run");
    const saveButton = input("save");
    const status = input("status");
    runButton.disabled = true;
    saveButton.disabled = true;
    status.className = "ti-status dim";
    status.textContent = "Capturing the exact model pose…";
    latest = null;

    try {
      const options = collectOptions();
      saveDraft(options);
      let transform = null;
      try {
        const pose = await request("transformMatrix");
        if (Array.isArray(pose) && pose.length === 12 && pose.every(Number.isFinite)) {
          transform = pose.slice();
        }
      } catch (error) {
        console.error("Thermal integrity pose capture failed; solve remains enabled", error);
      }

      status.textContent =
        options.mode === "transient"
          ? "Solving implicit transient heat flow and coupled structural response…"
          : "Solving nonlinear steady heat flow and coupled structural response…";
      const data = await request("thermalIntegrity", { opts: options });
      const stats = data?.stats;
      const temperatures = data?.temperatures;
      const history = data?.history;
      const displacements = data?.displacements;
      const materialFraction = data?.materialFraction;
      if (
        !stats ||
        !(temperatures instanceof Float32Array) ||
        !(history instanceof Float64Array) ||
        !(displacements instanceof Float32Array) ||
        !(materialFraction instanceof Float32Array)
      ) {
        throw new Error("filaSim returned an incomplete thermal integrity result.");
      }
      const expectedCells = Number(stats.nx) * Number(stats.ny) * Number(stats.nz);
      if (
        !Number.isSafeInteger(expectedCells) ||
        expectedCells <= 0 ||
        temperatures.length !== expectedCells ||
        materialFraction.length !== expectedCells
      ) {
        throw new Error("Thermal result grid dimensions do not match the returned field.");
      }
      latest = {
        options,
        stats,
        temperatures,
        history,
        displacements,
        materialFraction,
        transform,
        completedAtEpochMillis: Date.now(),
      };
      renderResults();
      saveButton.disabled =
        !transform || typeof android.captureThermalIntegrityReport !== "function";
      status.textContent = transform
        ? "Thermal integrity solve completed. Exact raw-worker values and pose can be saved."
        : "Thermal solve completed, but the exact pose query failed; reporting is disabled for this run.";
    } catch (error) {
      status.className = "ti-status ti-error";
      status.textContent = `Thermal integrity failed: ${error?.message || error}`;
      console.error("EnderSlicer thermal integrity failed", error);
    } finally {
      runButton.disabled = false;
    }
  }

  function format(value, digits = 3) {
    const number = Number(value);
    if (!Number.isFinite(number)) return "—";
    return number.toLocaleString(undefined, { maximumFractionDigits: digits });
  }

  function kpi(label, value) {
    return `<div class="ti-kpi"><b>${value}</b><span>${label}</span></div>`;
  }

  function renderResults() {
    if (!latest) return;
    const stats = latest.stats;
    const results = input("results");
    results.classList.add("ready");
    input("kpis").innerHTML = [
      kpi("Maximum temperature", `${format(stats.maximumTemperatureC, 2)} °C`),
      kpi("Temperature margin", `${format(stats.temperatureMarginC, 2)} °C`),
      kpi("Thermal deformation", `${format(stats.maxDisplacementMm, 5)} mm`),
      kpi("Maximum von Mises", `${format(stats.maxVonMisesMpa, 3)} MPa`),
      kpi("Conservative safety factor", format(stats.conservativeSafetyFactor, 3)),
      kpi("Strength retained", `${format(Number(stats.minimumStrengthRetention) * 100, 1)}%`),
      kpi("Heat rejected", `${format(stats.heatRejectedW, 4)} W`),
      kpi("Energy imbalance", `${format(Number(stats.energyBalanceRelative) * 100, 2)}%`),
    ].join("");

    const axis = input("axis").value;
    const slice = input("slice");
    const dimension = axis === "x" ? stats.nx : axis === "y" ? stats.ny : stats.nz;
    slice.max = String(Math.max(0, Number(dimension) - 1));
    slice.value = String(Math.floor((Number(dimension) - 1) / 2));
    drawHeatmap();
    drawHistory();

    const warnings = [];
    warnings.push(
      stats.densityAware
        ? "Thermal/stiffness fields used the optimized Smart Infill density."
        : `No optimized density was used; the fallback infill was ${latest.options.infillPct}%.`
    );
    if (stats.propertyExtrapolated) {
      warnings.push("The result exceeds the preset property range; safety values are extrapolated.");
    }
    if (Number(stats.energyBalanceRelative) > 0.05) {
      warnings.push("Energy imbalance exceeds 5%; refine the grid or transient time step.");
    }
    if (!stats.structuralConverged) {
      warnings.push("The coupled structural solve did not reach its requested residual.");
    }
    warnings.push(
      latest.options.freeExpansion
        ? "Structural result uses stress-free 3-2-1 grounding and current mechanical loads."
        : "Structural result uses the supports and mechanical loads currently defined in filaSim."
    );
    warnings.push(
      "This model does not include G-code reheating, interlayer weld kinetics, creep, fatigue, moisture, or certified contact resistance."
    );
    const note = input("result-note");
    note.className = `ti-status ${warnings.length > 1 ? "ti-warning" : "dim"}`;
    note.textContent = warnings.join("\n");
  }

  function colorFor(value, minimum, maximum) {
    const t = maximum > minimum ? Math.max(0, Math.min(1, (value - minimum) / (maximum - minimum))) : 0;
    const stops = [
      [0, 26, 45, 105],
      [0.25, 0, 154, 255],
      [0.5, 0, 220, 170],
      [0.75, 255, 220, 55],
      [1, 230, 40, 30],
    ];
    for (let index = 1; index < stops.length; index += 1) {
      if (t <= stops[index][0]) {
        const a = stops[index - 1];
        const b = stops[index];
        const f = (t - a[0]) / (b[0] - a[0]);
        return [
          Math.round(a[1] + (b[1] - a[1]) * f),
          Math.round(a[2] + (b[2] - a[2]) * f),
          Math.round(a[3] + (b[3] - a[3]) * f),
        ];
      }
    }
    return stops[stops.length - 1].slice(1);
  }

  function drawHeatmap() {
    if (!latest) return;
    const canvas = input("heatmap");
    const context = canvas.getContext("2d");
    const { nx, ny, nz, minimumTemperatureC, maximumTemperatureC } = latest.stats;
    const axis = input("axis").value;
    const sliceIndex = Number(input("slice").value);
    const widthCells = axis === "x" ? ny : nx;
    const heightCells = axis === "z" ? ny : nz;
    const image = context.createImageData(widthCells, heightCells);

    for (let v = 0; v < heightCells; v += 1) {
      for (let u = 0; u < widthCells; u += 1) {
        let x;
        let y;
        let z;
        if (axis === "x") {
          x = sliceIndex;
          y = u;
          z = heightCells - 1 - v;
        } else if (axis === "y") {
          x = u;
          y = sliceIndex;
          z = heightCells - 1 - v;
        } else {
          x = u;
          y = heightCells - 1 - v;
          z = sliceIndex;
        }
        const cell = (z * ny + y) * nx + x;
        const pixel = (v * widthCells + u) * 4;
        if (latest.materialFraction[cell] <= 1e-7) {
          image.data[pixel] = 15;
          image.data[pixel + 1] = 15;
          image.data[pixel + 2] = 18;
          image.data[pixel + 3] = 255;
          continue;
        }
        const [red, green, blue] = colorFor(
          latest.temperatures[cell],
          minimumTemperatureC,
          maximumTemperatureC
        );
        image.data[pixel] = red;
        image.data[pixel + 1] = green;
        image.data[pixel + 2] = blue;
        image.data[pixel + 3] = 255;
      }
    }
    const scratch = document.createElement("canvas");
    scratch.width = widthCells;
    scratch.height = heightCells;
    scratch.getContext("2d").putImageData(image, 0, 0);
    context.imageSmoothingEnabled = false;
    context.clearRect(0, 0, canvas.width, canvas.height);
    context.drawImage(scratch, 0, 24, canvas.width, canvas.height - 48);
    context.fillStyle = "rgba(255,255,255,.9)";
    context.font = "14px sans-serif";
    context.fillText(
      `${axis.toUpperCase()} slice ${sliceIndex + 1}/${input("slice").maxAsNumber + 1} · ${format(
        minimumTemperatureC,
        2
      )}–${format(maximumTemperatureC, 2)} °C`,
      10,
      17
    );
    const gradient = context.createLinearGradient(10, canvas.height - 15, canvas.width - 10, canvas.height - 15);
    for (let i = 0; i <= 20; i += 1) {
      const [r, g, b] = colorFor(i, 0, 20);
      gradient.addColorStop(i / 20, `rgb(${r},${g},${b})`);
    }
    context.fillStyle = gradient;
    context.fillRect(10, canvas.height - 16, canvas.width - 20, 8);
  }

  function drawHistory() {
    const canvas = input("history");
    const context = canvas.getContext("2d");
    context.clearRect(0, 0, canvas.width, canvas.height);
    if (!latest || latest.history.length < 6) {
      canvas.style.display = "none";
      return;
    }
    canvas.style.display = "block";
    const points = [];
    for (let index = 0; index + 2 < latest.history.length; index += 3) {
      points.push({
        time: latest.history[index],
        maximum: latest.history[index + 1],
        mean: latest.history[index + 2],
      });
    }
    const maxTime = Math.max(...points.map((point) => point.time), 1);
    const minTemp = Math.min(...points.map((point) => point.mean));
    const maxTemp = Math.max(...points.map((point) => point.maximum));
    const left = 45;
    const right = canvas.width - 14;
    const top = 24;
    const bottom = canvas.height - 28;
    const x = (time) => left + (time / maxTime) * (right - left);
    const y = (temperature) =>
      bottom - ((temperature - minTemp) / Math.max(maxTemp - minTemp, 1e-9)) * (bottom - top);

    context.strokeStyle = "rgba(255,255,255,.22)";
    context.strokeRect(left, top, right - left, bottom - top);
    const draw = (key, stroke) => {
      context.beginPath();
      points.forEach((point, index) => {
        const px = x(point.time);
        const py = y(point[key]);
        if (index === 0) context.moveTo(px, py);
        else context.lineTo(px, py);
      });
      context.strokeStyle = stroke;
      context.lineWidth = 2;
      context.stroke();
    };
    draw("maximum", "#ff7e5f");
    draw("mean", "#63d7ff");
    context.fillStyle = "rgba(255,255,255,.9)";
    context.font = "13px sans-serif";
    context.fillText("Transient temperature history", 10, 16);
    context.fillText(`${format(minTemp, 1)} °C`, 3, bottom);
    context.fillText(`${format(maxTemp, 1)} °C`, 3, top + 5);
    context.fillText(`${format(maxTime, 1)} s`, right - 45, canvas.height - 8);
    context.fillStyle = "#ff7e5f";
    context.fillText("max", left + 8, top + 16);
    context.fillStyle = "#63d7ff";
    context.fillText("mean", left + 45, top + 16);
  }

  function collectReport() {
    if (!latest || !latest.transform) throw new Error("Run Thermal Integrity with a captured model pose first.");
    const { options, stats, transform } = latest;
    const number = (entry, label) => {
      const result = Number(entry);
      if (!Number.isFinite(result)) throw new Error(`Invalid ${label}`);
      return result;
    };
    return {
      schemaVersion: 1,
      analysisKind: "fdm-service-thermal-integrity",
      solverModel: "voxel-finite-volume-implicit-thermomechanical",
      precisionSource: "raw-worker-response",
      sourceName: String(android.sourceFileName()),
      sourceSha256: String(android.sourceSha256()),
      upstreamCommit: String(android.upstreamCommit()),
      generatedAtEpochMillis: Date.now(),
      pose: { transform3x4: transform.map((entry, index) => number(entry, `transform[${index}]`)) },
      material: {
        name: options.materialName,
        propertyBasis: "literature-seeded",
        conductivityXWmK: options.conductivityXWmK,
        conductivityYWmK: options.conductivityYWmK,
        conductivityZWmK: options.conductivityZWmK,
        densityKgM3: options.densityKgM3,
        specificHeatJkgK: options.specificHeatJkgK,
        conductivityExponent: options.conductivityExponent,
        alphaXyPerK: options.alphaXyPerK,
        alphaZPerK: options.alphaZPerK,
        youngsModulusMpa: options.youngsModulusMpa,
        poissonRatio: options.poissonRatio,
        referenceStrengthMpa: options.referenceStrengthMpa,
        strengthDensityExponent: options.strengthDensityExponent,
        referenceTemperatureC: options.referenceTemperatureC,
        serviceLimitC: options.serviceLimitC,
        modulusFloorFraction: options.modulusFloorFraction,
        strengthFloorFraction: options.strengthFloorFraction,
      },
      boundary: {
        mode: options.mode,
        heatedFace: options.heatedFace,
        cooledFace: options.cooledFace,
        heatPowerW: options.heatPowerW,
        volumetricPowerW: options.volumetricPowerW,
        ambientTemperatureC: options.ambientTemperatureC,
        initialTemperatureC: options.initialTemperatureC,
        cooledTemperatureC: options.cooledTemperatureC,
        convectionWm2K: options.convectionWm2K,
        emissivity: options.emissivity,
        durationSeconds: options.durationSeconds,
        timeStepSeconds: options.timeStepSeconds,
        freeExpansion: options.freeExpansion,
        densityAwareRequested: options.densityAware,
        infillPct: options.infillPct,
        stiffnessExponent: options.exponent,
        stiffnessCoefficient: options.coeff,
        perimeters: options.perimeters,
        lineWidthMm: options.lineWidth,
        topBottomLayers: options.topBottomLayers,
        layerHeightMm: options.layerHeight,
      },
      mesh: {
        voxelSizeMm: number(stats.h, "voxel size"),
        nx: number(stats.nx, "grid X"),
        ny: number(stats.ny, "grid Y"),
        nz: number(stats.nz, "grid Z"),
        activeCells: number(stats.activeCells, "active cells"),
      },
      results: {
        minimumTemperatureC: number(stats.minimumTemperatureC, "minimum temperature"),
        meanTemperatureC: number(stats.meanTemperatureC, "mean temperature"),
        maximumTemperatureC: number(stats.maximumTemperatureC, "maximum temperature"),
        hotspotMm: stats.hotspotMm.map((entry, index) => number(entry, `hotspot[${index}]`)),
        heatInputW: number(stats.heatInputW, "heat input"),
        heatRejectedW: number(stats.heatRejectedW, "heat rejected"),
        storageRateW: number(stats.storageRateW, "storage rate"),
        energyBalanceRelative: number(stats.energyBalanceRelative, "energy balance"),
        thermalIterations: number(stats.thermalIterations, "thermal iterations"),
        thermalResidual: number(stats.thermalResidual, "thermal residual"),
        timeSteps: number(stats.timeSteps, "time steps"),
        finalTimeSeconds: number(stats.finalTimeSeconds, "final time"),
        peakTemperatureC: number(stats.peakTemperatureC, "peak temperature"),
        peakTimeSeconds: number(stats.peakTimeSeconds, "peak time"),
        heatedAreaMm2: number(stats.heatedAreaMm2, "heated area"),
        cooledAreaMm2: number(stats.cooledAreaMm2, "cooled area"),
        maxDisplacementMm: number(stats.maxDisplacementMm, "thermal displacement"),
        maxVonMisesMpa: number(stats.maxVonMisesMpa, "von Mises stress"),
        minimumModulusRetention: number(stats.minimumModulusRetention, "modulus retention"),
        minimumStrengthRetention: number(stats.minimumStrengthRetention, "strength retention"),
        conservativeSafetyFactor: number(stats.conservativeSafetyFactor, "safety factor"),
        temperatureMarginC: number(stats.temperatureMarginC, "temperature margin"),
        propertyExtrapolated: Boolean(stats.propertyExtrapolated),
        densityAware: Boolean(stats.densityAware),
        structuralIterations: number(stats.structuralIterations, "structural iterations"),
        structuralResidual: number(stats.structuralResidual, "structural residual"),
        structuralConverged: Boolean(stats.structuralConverged),
        solverSeconds: number(stats.seconds, "solver time"),
        historyPoints: latest.history.length / 3,
      },
      confidence: {
        level: "experimental-literature-seeded",
        calibratedToPrinter: false,
      },
    };
  }

  function saveReport() {
    try {
      const payload = JSON.stringify(collectReport());
      if (typeof android.captureThermalIntegrityReport !== "function") {
        throw new Error("This Android host does not expose thermal integrity reporting.");
      }
      if (!android.captureThermalIntegrityReport(payload)) {
        throw new Error("Android rejected the thermal integrity report.");
      }
    } catch (error) {
      alert(`Unable to save thermal integrity report: ${error?.message || error}`);
    }
  }

  function attachListeners(group) {
    input("preset").addEventListener("change", () => applyPreset(value("preset")));
    input("run").addEventListener("click", runAnalysis);
    input("save").addEventListener("click", saveReport);
    input("axis").addEventListener("change", () => {
      if (!latest) return;
      const stats = latest.stats;
      const dimension =
        value("axis") === "x" ? stats.nx : value("axis") === "y" ? stats.ny : stats.nz;
      input("slice").max = String(Number(dimension) - 1);
      input("slice").value = String(Math.floor((Number(dimension) - 1) / 2));
      drawHeatmap();
    });
    input("slice").addEventListener("input", drawHeatmap);
    group.querySelectorAll("input:not(#ti-slice), select:not(#ti-axis)").forEach((element) => {
      if (element.id === "ti-preset") return;
      element.addEventListener("change", () => invalidate("Inputs changed; run Thermal Integrity again."));
    });
  }

  function installUi() {
    installStyle();
    if (document.getElementById(GROUP_ID)) return true;
    const panel = document.querySelector(".panel");
    if (!panel) return false;
    const group = createGroup();
    panel.appendChild(group);
    restoreDraft();
    attachListeners(group);
    return true;
  }

  installUi();
  observer = new MutationObserver(() => installUi());
  observer.observe(document.documentElement, { childList: true, subtree: true });
})();
