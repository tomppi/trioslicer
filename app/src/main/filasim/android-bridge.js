/*
 * Android host adapter for the pinned filaSim web workspace.
 * The upstream application remains responsible for analysis and optimization;
 * this adapter injects the displayed DuoSlicer STL, returns validated
 * optimizer outputs, and captures an auditable build-process thermal FEA report.
 */
(() => {
  "use strict";

  const android = window.EnderSlicerAndroid;
  if (!android) return;

  const CHUNK_BYTES = 256 * 1024;
  const POSE_CAPTURE_TIMEOUT_MS = 2000;
  const APPLY_SMART_INFILL_LABEL = "Apply Smart Infill";
  const APPLY_SMART_INFILL_NOTE =
    "Transfers the optimized infill regions to TrioSlicer and returns to the model.";
  const THERMAL_REPORT_GROUP_ID = "enderslicer-thermal-fea-report";
  const THERMAL_REPORT_LABEL = "Save Thermal FEA Report";
  const THERMAL_REPORT_NOTE =
    "Saves exact solver values, build orientation, and model identity. Bed reactions are indicators, not an absolute pass/fail threshold.";
  let modelLoadStarted = false;
  let exporting = false;
  let exportUiObserver = null;
  let latestBuildSimRaw = null;

  function normalizedText(element) {
    return String(element?.textContent || "").replace(/\s+/g, " ").trim();
  }

  function currentWorkspace() {
    return document.querySelector("label.workspace select")?.value || "optimize";
  }

  function findGroup(label) {
    const wanted = label.toLowerCase();
    return Array.from(document.querySelectorAll(".panel .group")).find((group) => {
      const heading = normalizedText(group.querySelector(".g-label span")).toLowerCase();
      return heading === wanted;
    }) || null;
  }

  function readMaterialName() {
    return normalizedText(findGroup("Material")?.querySelector(".g-label b"));
  }

  function postNative(nativePostMessage, message, hasTransfer, transferOrOptions) {
    if (hasTransfer) nativePostMessage(message, transferOrOptions);
    else nativePostMessage(message);
  }

  /*
   * Capture the exact request and response of filaSim's typed buildSim worker
   * operation before React formats values for display. Immediately before a
   * build run, query the worker's cumulative model transform and only then
   * forward buildSim. The report is therefore bound to both source STL bytes
   * and the exact orientation/placement actually solved.
   *
   * Report capture is fail-open: if the auxiliary pose query fails or times
   * out, the original buildSim request is still forwarded unchanged. A report
   * will simply not be offered for that run, so instrumentation can never block
   * the actual FEA calculation.
   */
  function installBuildSimWorkerCapture() {
    const NativeWorker = window.Worker;
    if (!NativeWorker || NativeWorker.__enderSlicerThermalCapture) return;

    const WrappedWorker = new Proxy(NativeWorker, {
      construct(Target, args) {
        const worker = Reflect.construct(Target, args);
        const pendingBuildSim = new Map();
        const pendingPose = new Map();
        const nativePostMessage = worker.postMessage.bind(worker);
        let nextPoseRequestId = -1;

        function forwardWithoutReport(poseRequest, reason) {
          latestBuildSimRaw = null;
          console.error(`DuoSlicer thermal report capture disabled for this run: ${reason}`);
          postNative(
            nativePostMessage,
            poseRequest.buildMessage,
            poseRequest.hasTransfer,
            poseRequest.transferOrOptions,
          );
        }

        worker.postMessage = function postMessage(message, transferOrOptions) {
          const hasTransfer = arguments.length > 1;
          if (message?.op === "buildSim" && Number.isSafeInteger(message.id)) {
            latestBuildSimRaw = null;
            const poseRequestId = nextPoseRequestId--;
            const poseRequest = {
              buildMessage: message,
              hasTransfer,
              transferOrOptions,
              opts: { ...(message.opts || {}) },
              materialName: readMaterialName(),
              requestedAtEpochMillis: Date.now(),
              timeout: null,
            };
            poseRequest.timeout = setTimeout(() => {
              const stillPending = pendingPose.get(poseRequestId);
              if (!stillPending) return;
              pendingPose.delete(poseRequestId);
              forwardWithoutReport(stillPending, "model-transform query timed out");
            }, POSE_CAPTURE_TIMEOUT_MS);
            pendingPose.set(poseRequestId, poseRequest);
            nativePostMessage({ id: poseRequestId, op: "transformMatrix" });
            return;
          }
          postNative(nativePostMessage, message, hasTransfer, transferOrOptions);
        };

        worker.addEventListener("message", (event) => {
          const message = event.data;
          if (!message || !Number.isSafeInteger(message.id)) return;

          const poseRequest = pendingPose.get(message.id);
          if (poseRequest) {
            pendingPose.delete(message.id);
            clearTimeout(poseRequest.timeout);
            const transform = message.ok && Array.isArray(message.data) ? message.data.slice() : null;
            if (!transform || transform.length !== 12 || !transform.every(Number.isFinite)) {
              forwardWithoutReport(poseRequest, "model-transform response was invalid");
              return;
            }
            pendingBuildSim.set(poseRequest.buildMessage.id, {
              opts: poseRequest.opts,
              materialName: poseRequest.materialName,
              modelTransform: transform,
              requestedAtEpochMillis: poseRequest.requestedAtEpochMillis,
            });
            postNative(
              nativePostMessage,
              poseRequest.buildMessage,
              poseRequest.hasTransfer,
              poseRequest.transferOrOptions,
            );
            return;
          }

          if (message.progress) return;
          const request = pendingBuildSim.get(message.id);
          if (!request) return;
          pendingBuildSim.delete(message.id);
          if (!message.ok || !message.data?.stats) {
            latestBuildSimRaw = null;
            return;
          }
          const stats = message.data.stats;
          latestBuildSimRaw = {
            opts: request.opts,
            materialName: request.materialName,
            modelTransform: request.modelTransform,
            stats: {
              maxDisplacement: stats.maxDisplacement,
              bondedMax: stats.bondedMax,
              releasedMax: stats.releasedMax,
              peakLift: stats.peakLift,
              peakShear: stats.peakShear,
              layers: stats.layers,
              itersMax: stats.itersMax,
              itersMean: stats.itersMean,
              cells: stats.cells,
              seconds: stats.seconds,
              densityAware: stats.densityAware,
              nx: stats.nx,
              ny: stats.ny,
              nz: stats.nz,
              h: stats.h,
            },
            completedAtEpochMillis: Date.now(),
          };
        });
        return worker;
      },
    });
    Object.defineProperty(WrappedWorker, "__enderSlicerThermalCapture", { value: true });
    window.Worker = WrappedWorker;
  }

  installBuildSimWorkerCapture();

  function sameText(element, value) {
    if (normalizedText(element) !== value) element.textContent = value;
  }

  function staleThermalResult() {
    return Array.from(document.querySelectorAll(".panel .warnbanner")).some((element) => {
      const text = normalizedText(element).toLowerCase();
      return text.includes("settings changed") || text.includes("out of date") || text.includes("stale");
    });
  }

  function finite(value, label) {
    const number = Number(value);
    if (!Number.isFinite(number)) throw new Error(`filaSim returned a non-finite ${label}`);
    return number;
  }

  function optionalFinite(value, label) {
    if (value == null) return null;
    return finite(value, label);
  }

  function collectThermalReport() {
    if (currentWorkspace() !== "buildsim") {
      throw new Error("Switch filaSim to Build Simulation first");
    }
    if (staleThermalResult()) {
      throw new Error("The build-simulation result is stale; run the simulation again");
    }
    const raw = latestBuildSimRaw;
    if (!raw?.opts || !raw?.stats || !Array.isArray(raw.modelTransform)) {
      throw new Error("Run the complete build simulation before saving a report");
    }
    const materialName = String(raw.materialName || "").trim();
    if (!materialName) throw new Error("Unable to identify the material used by the solver request");

    const opts = raw.opts;
    const stats = raw.stats;
    const requestedState = String(opts.state || "");
    if (requestedState !== "bonded" && requestedState !== "released") {
      throw new Error("filaSim returned an unsupported build-simulation state");
    }
    return {
      schemaVersion: 1,
      analysisKind: "fdm-build-thermomechanical",
      solverModel: "sequential-voxel-inherent-strain",
      precisionSource: "raw-worker-response",
      sourceName: String(android.sourceFileName()),
      sourceSha256: String(android.sourceSha256()),
      upstreamCommit: String(android.upstreamCommit()),
      generatedAtEpochMillis: Date.now(),
      pose: {
        transform3x4: raw.modelTransform.map((value, index) => finite(value, `transform[${index}]`)),
      },
      material: {
        name: materialName,
        shrinkXyPercent: Math.abs(finite(opts.shrink, "XY shrink")) * 100,
        shrinkZPercent: Math.abs(finite(opts.shrinkZ, "Z shrink")) * 100,
        yieldStrengthMpa: optionalFinite(opts.yieldStrength, "yield strength"),
        lockingTemperatureC: optionalFinite(opts.tLock, "locking temperature"),
      },
      process: {
        bedTemperatureC: optionalFinite(opts.tBed, "bed temperature"),
        chamberTemperatureC: optionalFinite(opts.tChamber, "chamber temperature"),
        finalTemperatureC: optionalFinite(opts.tFinal, "final temperature"),
        thermalDecayMm: optionalFinite(opts.decayMm, "thermal decay depth"),
        requestedState,
        densityAware: Boolean(stats.densityAware),
      },
      mesh: {
        voxelSizeMm: finite(stats.h, "voxel size"),
        nx: finite(stats.nx, "grid X dimension"),
        ny: finite(stats.ny, "grid Y dimension"),
        nz: finite(stats.nz, "grid Z dimension"),
        activeCells: finite(stats.cells, "active cell count"),
        buildLayers: finite(stats.layers, "build layer count"),
      },
      results: {
        bondedWarpMm: Math.abs(finite(stats.bondedMax, "bonded warp")),
        releasedWarpMm: Math.abs(finite(stats.releasedMax, "released warp")),
        peakLiftMpa: Math.abs(finite(stats.peakLift, "bed lift traction")),
        peakShearMpa: Math.abs(finite(stats.peakShear, "bed shear traction")),
        solverSeconds: finite(stats.seconds, "solver time"),
        meanIterationsPerLayer: finite(stats.itersMean, "mean iteration count"),
        maxIterationsPerLayer: finite(stats.itersMax, "maximum iteration count"),
      },
      confidence: {
        level: "experimental-literature-seeded",
        calibratedToPrinter: false,
      },
    };
  }

  function thermalResultReady() {
    return currentWorkspace() === "buildsim" && Boolean(latestBuildSimRaw?.stats);
  }

  function syncThermalReportUi() {
    const existing = document.getElementById(THERMAL_REPORT_GROUP_ID);
    if (!thermalResultReady()) {
      existing?.remove();
      return false;
    }

    const panel = document.querySelector(".panel");
    if (!panel) return false;
    const group = existing || document.createElement("div");
    if (!existing) {
      group.id = THERMAL_REPORT_GROUP_ID;
      group.className = "group";

      const heading = document.createElement("div");
      heading.className = "g-label";
      const title = document.createElement("span");
      title.textContent = "TrioSlicer report";
      heading.appendChild(title);

      const button = document.createElement("button");
      button.className = "primary";
      button.textContent = THERMAL_REPORT_LABEL;
      button.addEventListener("click", () => {
        try {
          const payload = JSON.stringify(collectThermalReport());
          if (!android.captureThermalReport(payload)) {
            throw new Error("Android rejected the thermal FEA report");
          }
        } catch (error) {
          console.error("DuoSlicer thermal FEA report capture failed", error);
          alert(`Unable to save the thermal FEA report: ${error?.message || error}`);
        }
      });

      const note = document.createElement("div");
      note.className = "dim small";
      note.textContent = THERMAL_REPORT_NOTE;

      group.appendChild(heading);
      group.appendChild(button);
      group.appendChild(note);
      panel.appendChild(group);
    }

    const button = group.querySelector("button");
    if (button) {
      const stale = staleThermalResult();
      button.disabled = stale;
      button.title = stale
        ? "Settings changed after the solve; run Build Simulation again"
        : "Save exact raw-worker values and model transform in a native report";
    }
    const note = group.querySelector(".dim");
    if (note) sameText(note, THERMAL_REPORT_NOTE);
    return true;
  }

  /**
   * filaSim's browser build offers Orca/Bambu/Prusa 3MF projects plus a raw
   * modifier ZIP. EnderSlicer only consumes the validated modifier package, so
   * the Android host exposes that one working action with app-native wording.
   * Solid-topology export is a separate workflow and is intentionally untouched.
   */
  function simplifyModifierExportUi() {
    const buttons = Array.from(document.querySelectorAll("button"));
    const modifierButton = buttons.find((button) => {
      const label = normalizedText(button);
      return label === "Download modifier STLs (.zip)" || label === APPLY_SMART_INFILL_LABEL;
    });
    if (!modifierButton) return false;

    const group = modifierButton.closest(".group");
    if (!group) return false;

    for (const child of Array.from(group.children)) {
      if (child === modifierButton) continue;
      const label = normalizedText(child);
      const isSlicerChoice = child.classList.contains("seg");
      const isHandoffHeading = child.classList.contains("g-label");
      const isThreeMfProject =
        child.tagName === "BUTTON" && /^Download .+ project \(\.3mf\)$/i.test(label);
      if (isSlicerChoice || isHandoffHeading || isThreeMfProject) {
        child.hidden = true;
        child.setAttribute("aria-hidden", "true");
      }
    }

    if (normalizedText(modifierButton) !== APPLY_SMART_INFILL_LABEL) {
      modifierButton.textContent = APPLY_SMART_INFILL_LABEL;
    }
    modifierButton.classList.add("primary");
    modifierButton.title = "Use the generated infill regions in TrioSlicer";
    modifierButton.setAttribute("aria-label", APPLY_SMART_INFILL_LABEL);

    const note = modifierButton.nextElementSibling;
    if (note?.classList.contains("dim") && normalizedText(note) !== APPLY_SMART_INFILL_NOTE) {
      note.textContent = APPLY_SMART_INFILL_NOTE;
    }
    return true;
  }

  function installAndroidHostUi() {
    simplifyModifierExportUi();
    syncThermalReportUi();
    if (exportUiObserver) return;
    exportUiObserver = new MutationObserver(() => {
      simplifyModifierExportUi();
      syncThermalReportUi();
    });
    exportUiObserver.observe(document.documentElement, {
      childList: true,
      characterData: true,
      subtree: true,
    });
  }

  function bytesToBase64(bytes) {
    let binary = "";
    const step = 0x8000;
    for (let offset = 0; offset < bytes.length; offset += step) {
      const slice = bytes.subarray(offset, Math.min(bytes.length, offset + step));
      binary += String.fromCharCode.apply(null, slice);
    }
    return btoa(binary);
  }

  async function waitForModelInput() {
    for (let attempt = 0; attempt < 240; attempt += 1) {
      const input = Array.from(document.querySelectorAll('input[type="file"]'))
        .find((element) => String(element.accept || "").toLowerCase().includes(".stl"));
      if (input) return input;
      await new Promise((resolve) => setTimeout(resolve, 100));
    }
    throw new Error("filaSim did not expose its model input");
  }

  async function loadModelFromAndroid() {
    if (modelLoadStarted) return;
    modelLoadStarted = true;
    try {
      const response = await fetch("/model/current.stl", { cache: "no-store" });
      if (!response.ok) throw new Error(`Unable to read Android STL (${response.status})`);
      const bytes = await response.arrayBuffer();
      const name = String(android.sourceFileName() || "model.stl");
      const file = new File([bytes], name, { type: "model/stl", lastModified: Date.now() });
      const input = await waitForModelInput();
      const transfer = new DataTransfer();
      transfer.items.add(file);
      input.files = transfer.files;
      input.dispatchEvent(new Event("change", { bubbles: true }));
    } catch (error) {
      modelLoadStarted = false;
      console.error("DuoSlicer filaSim model handoff failed", error);
      alert(`Unable to load the DuoSlicer model into filaSim: ${error?.message || error}`);
    }
  }

  async function streamBytes(bytes, begin, append, finish, cancel, beginArgs) {
    if (exporting) throw new Error("A filaSim export is already running");
    if (!(bytes instanceof Uint8Array)) bytes = new Uint8Array(bytes);
    if (!bytes.length) throw new Error("filaSim returned an empty export");
    exporting = true;
    try {
      if (!begin(...beginArgs, bytes.byteLength)) {
        throw new Error("Android rejected the filaSim export metadata or size");
      }
      for (let offset = 0; offset < bytes.length; offset += CHUNK_BYTES) {
        const chunk = bytes.subarray(offset, Math.min(bytes.length, offset + CHUNK_BYTES));
        if (!append(bytesToBase64(chunk))) {
          throw new Error("Android rejected a filaSim export chunk");
        }
        await new Promise((resolve) => setTimeout(resolve, 0));
      }
      if (!finish()) throw new Error("Android could not validate the filaSim export");
    } catch (error) {
      cancel();
      throw error;
    } finally {
      exporting = false;
    }
  }

  function normalizeModifierMetadata(metadata) {
    const mode = metadata?.mode === "binary" ? "binary" : "graded";
    const normalized = {
      ...metadata,
      metadataVersion: 2,
      // This pinned filaSim solver has one calibrated sparse pattern. Old or
      // restored WebView state can still expose null/retired values, so the
      // Android boundary writes the actual solver contract deterministically.
      basePattern: "cubic",
      gradedFullDensityPattern: "rectilinear",
      mode,
    };

    if (mode === "binary") {
      normalized.binarySolidPattern =
        metadata?.binarySolidPattern === "concentric" || metadata?.solidPattern === "concentric"
          ? "concentric"
          : "rectilinear";
    } else {
      // JSONObject.optString() turns an explicit JSON null into the literal
      // string "null". Omit this optional field for graded mode instead.
      delete normalized.binarySolidPattern;
      delete normalized.solidPattern;
    }
    return normalized;
  }

  async function captureModifierZip(bytes, metadata) {
    const normalized = normalizeModifierMetadata(metadata);
    const info = {
      ...normalized,
      sourceName: String(android.sourceFileName() || normalized?.sourceName || "model.stl"),
      sourceSha256: String(android.sourceSha256()),
      upstreamCommit: String(android.upstreamCommit()),
    };
    const filename = `${info.sourceName.replace(/\.(stl|3mf)$/i, "")}_smart_infill_modifiers.zip`;
    await streamBytes(
      bytes,
      (name, json, size) => android.beginModifierExport(name, size, json),
      (chunk) => android.appendModifierChunk(chunk),
      () => android.finishModifierExport(),
      () => android.cancelModifierExport(),
      [filename, JSON.stringify(info)],
    );
  }

  async function captureOptimizedShape(bytes) {
    const sourceName = String(android.sourceFileName() || "part.stl");
    const filename = `${sourceName.replace(/\.(stl|3mf)$/i, "")}_optimized.stl`;
    await streamBytes(
      bytes,
      (name, size) => android.beginShapeExport(name, size),
      (chunk) => android.appendShapeChunk(chunk),
      () => android.finishShapeExport(),
      () => android.cancelShapeExport(),
      [filename],
    );
  }

  window.EnderSlicerBridge = {
    loadModelFromAndroid,
    captureModifierZip,
    captureOptimizedShape,
  };

  const startAndroidHost = () => {
    installAndroidHostUi();
    setTimeout(loadModelFromAndroid, 250);
  };
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", startAndroidHost, { once: true });
  } else {
    startAndroidHost();
  }
})();
