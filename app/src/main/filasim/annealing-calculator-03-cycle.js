  function progress(stage, raw) {
    let data = raw;
    if (typeof data === "string") {
      try { data = JSON.parse(data); } catch (_) { data = { phase: "Solving", detail: raw }; }
    }
    const local = Number(data?.progress);
    const base = stage === "heating" ? 0 : 0.58;
    const span = stage === "heating" ? 0.58 : 0.42;
    const combined = base + (Number.isFinite(local) ? local : 0) * span;
    setProgress(`${stage === "heating" ? "Heating" : "Cooling"}: ${data?.phase || "Solving"}`, combined, String(data?.detail || ""));
  }

  function setProgress(phase, fraction, detail = "") {
    const shell = document.getElementById("ac-progress");
    const bar = document.getElementById("ac-progress-bar");
    const phaseElement = document.getElementById("ac-progress-phase");
    const time = document.getElementById("ac-progress-time");
    const detailElement = document.getElementById("ac-progress-detail");
    if (!shell || !bar || !phaseElement || !time || !detailElement) return;
    const bounded = Math.max(0, Math.min(1, Number(fraction) || 0));
    shell.hidden = false;
    bar.value = Math.round(bounded * 100);
    phaseElement.textContent = phase;
    time.textContent = `${Math.round(bounded * 100)}% · ${formatDuration((performance.now() - runStartedAt) / 1000, false)}`;
    detailElement.textContent = detail;
  }

  function beginElapsedClock() {
    runStartedAt = performance.now();
    if (elapsedTimer !== null) clearInterval(elapsedTimer);
    elapsedTimer = setInterval(() => {
      const time = document.getElementById("ac-progress-time");
      const bar = document.getElementById("ac-progress-bar");
      if (time && bar) time.textContent = `${bar.value}% · ${formatDuration((performance.now() - runStartedAt) / 1000, false)}`;
    }, 500);
  }
  function endElapsedClock() {
    if (elapsedTimer !== null) clearInterval(elapsedTimer);
    elapsedTimer = null;
  }

  function validateResult(data, label) {
    const stats = data?.stats;
    const temperatures = data?.temperatures;
    const history = data?.history;
    const displacements = data?.displacements;
    const materialFraction = data?.materialFraction;
    const vertexTemperatures = data?.vertexTemperatures;
    if (!stats || !(temperatures instanceof Float32Array) || !(history instanceof Float64Array) ||
        !(displacements instanceof Float32Array) || !(materialFraction instanceof Float32Array) ||
        !(vertexTemperatures instanceof Float32Array)) {
      throw new Error(`${label} returned an incomplete thermal field.`);
    }
    const expected = Number(stats.nx) * Number(stats.ny) * Number(stats.nz);
    if (!Number.isSafeInteger(expected) || expected <= 0 || temperatures.length !== expected || materialFraction.length !== expected) {
      throw new Error(`${label} grid dimensions do not match the returned fields.`);
    }
    return { stats, temperatures, history, displacements, materialFraction, vertexTemperatures };
  }

  async function runCycle() {
    if (runInFlight) return;
    const run = input("run");
    const cancel = input("cancel");
    const status = input("status");
    runInFlight = true;
    latest = null;
    window.dispatchEvent(new CustomEvent(THERMAL_CLEAR_EVENT));
    run.disabled = true;
    cancel.disabled = false;
    input("results").classList.remove("ready");
    status.className = "ac-status dim";
    status.textContent = "Capturing model identity and preparing the oven boundary…";
    beginElapsedClock();
    setProgress("Preflight", 0.01, "Validating inputs and current voxel grid");

    try {
      const common = collectCommon();
      saveDraft(common);
      let transform = null;
      try {
        const pose = await request("transformMatrix");
        if (Array.isArray(pose) && pose.length === 12 && pose.every(Number.isFinite)) transform = pose.slice();
      } catch (error) {
        console.error("Annealing pose capture failed", error);
      }

      const heatingOptions = thermalOptions(common, "heating");
      status.textContent = "Heating the voxelized part from every oven-exposed surface…";
      const heatingRaw = await request("thermalIntegrity", { opts: heatingOptions }, (data) => progress("heating", data));
      const heating = validateResult(heatingRaw, "Heating solve");
      const heatReached = Number(heating.stats.readinessReachedTimeSeconds);
      const heatComplete = Number(heating.stats.readinessCompleteTimeSeconds);
      if (!Number.isFinite(heatReached) || !Number.isFinite(heatComplete)) {
        throw new Error(`The coldest voxel did not reach ${common.readinessTemperatureC.toFixed(1)} °C and complete the soak within ${formatDuration(common.maxHeatingSeconds)}. Increase the maximum heating search, time step, oven airflow estimate, or lower the target.`);
      }

      let cooling = null;
      let coolingOptions = null;
      if (common.simulateCooling) {
        setProgress("Preparing cooling", 0.58, "Starting conservatively from a uniformly oven-hot part");
        status.textContent = "Calculating cooling until the hottest voxel is safe to handle…";
        coolingOptions = thermalOptions(common, "cooling");
        const coolingRaw = await request("thermalIntegrity", { opts: coolingOptions }, (data) => progress("cooling", data));
        cooling = validateResult(coolingRaw, "Cooling solve");
        const coolComplete = Number(cooling.stats.readinessCompleteTimeSeconds);
        if (!Number.isFinite(coolComplete)) {
          throw new Error(`The hottest voxel did not cool below ${common.handlingTemperatureC.toFixed(1)} °C within ${formatDuration(common.maxCoolingSeconds)}. Increase the cooling search duration or time step.`);
        }
      }

      const sourceIdentity = {
        sourceName: String(android.sourceFileName?.() || "model.stl"),
        sourceSha256: String(android.sourceSha256?.() || "unknown"),
        upstreamCommit: String(android.upstreamCommit?.() || "unknown"),
      };
      latest = {
        common, heatingOptions, coolingOptions, heating, cooling, transform,
        sourceIdentity, completedAtEpochMillis: Date.now(),
      };
      try { localStorage.setItem(REPORT_KEY, JSON.stringify(buildReport())); } catch (_) { /* optional */ }
      renderResults();
      showHeating3d();
      setProgress("Complete", 1, `Geometry-aware oven cycle calculated in ${formatDuration((performance.now() - runStartedAt) / 1000, false)}.`);
      status.className = "ac-status dim";
      status.textContent = "Cycle complete. The heat-up timer is geometry-specific; the soak begins only after the coldest voxel reaches target.";
    } catch (error) {
      latest = null;
      status.className = "ac-status ac-error";
      status.textContent = `Annealing calculation failed: ${error?.message || error}`;
      setProgress(/cancel/i.test(String(error)) ? "Cancelled" : "Failed", Number(input("progress-bar").value) / 100, String(error?.message || error));
      console.error("EnderSlicer annealing calculation failed", error);
    } finally {
      runInFlight = false;
      run.disabled = false;
      cancel.disabled = true;
      endElapsedClock();
    }
  }

  function cancelRun() {
    const cancel = input("cancel");
    cancel.disabled = true;
    if (cancelFlag && typeof Atomics !== "undefined") {
      Atomics.store(cancelFlag, 0, 1);
      setProgress("Cancelling", Number(input("progress-bar").value) / 100, "Waiting for a safe solver cancellation checkpoint…");
      return;
    }
    if (engineWorker) {
      setProgress("Cancelling and restarting", Number(input("progress-bar").value) / 100, "Threaded cancellation is unavailable. The filaSim worker will restart and reload the model.");
      engineWorker.terminate();
      engineWorker = null;
      setTimeout(() => window.location.reload(), 100);
    }
  }

  function kpi(label, display) { return `<div class="ac-kpi"><b>${display}</b><span>${label}</span></div>`; }
  function formatNumber(number, digits = 2) {
    const value = Number(number);
    return Number.isFinite(value) ? value.toLocaleString(undefined, { maximumFractionDigits: digits }) : "—";
  }
  function formatDuration(seconds, verbose = true) {
    const total = Math.max(0, Math.round(Number(seconds) || 0));
    const hours = Math.floor(total / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    const secs = total % 60;
    if (!verbose) return `${hours}:${String(minutes).padStart(2, "0")}:${String(secs).padStart(2, "0")}`;
    const parts = [];
    if (hours) parts.push(`${hours} h`);
    if (minutes || hours) parts.push(`${minutes} min`);
    if (!hours && (secs || !minutes)) parts.push(`${secs} s`);
    return parts.join(" ");
  }

  function renderResults() {
    if (!latest) return;
    const heatReached = Number(latest.heating.stats.readinessReachedTimeSeconds);
    const heatComplete = Number(latest.heating.stats.readinessCompleteTimeSeconds);
    const coolTime = latest.cooling ? Number(latest.cooling.stats.readinessCompleteTimeSeconds) : 0;
    const total = heatComplete + coolTime;
    input("results").classList.add("ready");
    input("kpis").innerHTML = [
      kpi("Core heat-up · coldest voxel ready", formatDuration(heatReached)),
      kpi("Effective soak after core target", formatDuration(heatComplete - heatReached)),
      kpi("Remove from oven", formatDuration(heatComplete)),
      kpi("Cooling to handling target", latest.cooling ? formatDuration(coolTime) : "Not simulated"),
      kpi("Complete cycle", formatDuration(total)),
      kpi("Heating temperature spread", `${formatNumber(Number(latest.heating.stats.maximumTemperatureC) - Number(latest.heating.stats.minimumTemperatureC), 2)} °C`),
      kpi("Cold/mean/hot at oven removal", `${formatNumber(latest.heating.stats.minimumTemperatureC, 1)} / ${formatNumber(latest.heating.stats.meanTemperatureC, 1)} / ${formatNumber(latest.heating.stats.maximumTemperatureC, 1)} °C`),
      kpi("Voxel grid", `${latest.heating.stats.nx}×${latest.heating.stats.ny}×${latest.heating.stats.nz} · ${formatNumber(latest.heating.stats.h, 2)} mm`),
    ].join("");
    drawHeatmap();
    const notes = [
      `Soak begins at ${formatDuration(heatReached)}, when every active material voxel is at least ${formatNumber(latest.common.readinessTemperatureC, 1)} °C.`,
      latest.heating.stats.densityAware
        ? "The printed material field used optimized Smart Infill density."
        : `The material field used ${latest.common.infillPct}% fallback infill plus configured skins.`,
      "Cooling starts conservatively from a uniformly oven-temperature part, so the safe-handling estimate should not be shorter than the modeled final heating field would produce.",
      `Material profile: ${PRESETS[latest.common.materialName]?.status || "custom"}. Annealing shrinkage and property changes remain filament/brand dependent; calibrate a coupon before applying compensation to critical parts.`,
      "Oven temperature accuracy and airflow dominate boundary uncertainty. Use an independent thermometer and avoid direct contact with heating elements.",
    ];
    input("result-note").className = "ac-status ac-warning";
    input("result-note").textContent = notes.join("\n");
  }

  function colorFor(value, minimum, maximum) {
    const t = maximum > minimum ? Math.max(0, Math.min(1, (value - minimum) / (maximum - minimum))) : 0;
    const stops = [[0,26,45,105],[0.25,0,154,255],[0.5,0,220,170],[0.75,255,220,55],[1,230,40,30]];
    for (let i = 1; i < stops.length; i += 1) {
      if (t <= stops[i][0]) {
        const a = stops[i - 1], b = stops[i], f = (t - a[0]) / (b[0] - a[0]);
        return [Math.round(a[1] + (b[1] - a[1]) * f), Math.round(a[2] + (b[2] - a[2]) * f), Math.round(a[3] + (b[3] - a[3]) * f)];
      }
    }
    return stops[stops.length - 1].slice(1);
  }

  function drawHeatmap() {
    if (!latest) return;
    const canvas = input("heatmap");
    const context = canvas.getContext("2d");
    const stats = latest.heating.stats;
    const { nx, ny, nz } = stats;
    const z = Math.floor((Number(nz) - 1) / 2);
    const image = context.createImageData(nx, ny);
    for (let row = 0; row < ny; row += 1) {
      for (let x = 0; x < nx; x += 1) {
        const y = ny - 1 - row;
        const cell = (z * ny + y) * nx + x;
        const pixel = (row * nx + x) * 4;
        if (latest.heating.materialFraction[cell] <= 1e-7) {
          image.data.set([15, 15, 18, 255], pixel);
        } else {
          const rgb = colorFor(latest.heating.temperatures[cell], stats.minimumTemperatureC, stats.maximumTemperatureC);
          image.data.set([...rgb, 255], pixel);
        }
      }
    }
    const scratch = document.createElement("canvas");
    scratch.width = nx; scratch.height = ny;
    scratch.getContext("2d").putImageData(image, 0, 0);
    context.imageSmoothingEnabled = false;
    context.clearRect(0, 0, canvas.width, canvas.height);
    context.drawImage(scratch, 0, 30, canvas.width, canvas.height - 60);
    context.fillStyle = "rgba(255,255,255,.92)";
    context.font = "14px sans-serif";
    context.fillText(`Oven removal · middle Z slice · ${formatNumber(stats.minimumTemperatureC, 1)}–${formatNumber(stats.maximumTemperatureC, 1)} °C`, 10, 20);
    const gradient = context.createLinearGradient(10, canvas.height - 16, canvas.width - 10, canvas.height - 16);
    for (let i = 0; i <= 20; i += 1) {
      const [r,g,b] = colorFor(i, 0, 20);
      gradient.addColorStop(i / 20, `rgb(${r},${g},${b})`);
    }
    context.fillStyle = gradient;
    context.fillRect(10, canvas.height - 18, canvas.width - 20, 9);
  }

  function showHeating3d() {
    if (!latest) return;
    window.dispatchEvent(new CustomEvent(THERMAL_RESULT_EVENT, { detail: {
      vertexTemperatures: latest.heating.vertexTemperatures,
      displacements: latest.heating.displacements,
      minimumTemperatureC: Number(latest.heating.stats.minimumTemperatureC),
      maximumTemperatureC: Number(latest.heating.stats.maximumTemperatureC),
      structuralValid: false,
      maxDisplacementMm: null,
      thermalOnly: true,
    }}));
  }
