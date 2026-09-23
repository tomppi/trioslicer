#!/usr/bin/env python3
"""Thermal Integrity physical-model and native 3D result preparer."""
from __future__ import annotations
import importlib.util
import pathlib
import sys

V10 = pathlib.Path(__file__).with_name("prepare-filasim-assets-with-thermal-integrity-v10.py")
PHYSICAL = pathlib.Path(__file__).with_name("filasim-thermal-integrity-physical-model-v1.py")
for p in (V10, PHYSICAL):
    if not p.is_file(): raise RuntimeError(f"Thermal Integrity v11 component is missing: {p}")
spec = importlib.util.spec_from_file_location("enderslicer_thermal_v10", V10)
if spec is None or spec.loader is None: raise RuntimeError(f"Unable to load {V10}")
v10 = importlib.util.module_from_spec(spec); spec.loader.exec_module(v10)
thermal = v10.v9.thermal
marker = ".enderslicer-thermal-integrity-physical-model-v1"
if PHYSICAL not in thermal.THERMAL_TRANSFORMS:
    thermal.THERMAL_TRANSFORMS = (*thermal.THERMAL_TRANSFORMS, PHYSICAL)
if marker not in thermal.THERMAL_MARKERS:
    thermal.THERMAL_MARKERS = (*thermal.THERMAL_MARKERS, marker)

_base_ui = thermal.patch_thermal_ui_runtime

def once(text: str, old: str, new: str, label: str) -> str:
    if new in text: return text
    count = text.count(old)
    if count != 1: raise RuntimeError(f"Expected one packaged UI {label}, found {count}")
    return text.replace(old, new, 1)

def patch_ui(target: pathlib.Path) -> None:
    _base_ui(target)
    text = target.read_text(encoding="utf-8")
    text = once(text,
        '  const STORAGE_KEY = "enderslicer.thermalIntegrity.v1";\n',
        '  const STORAGE_KEY = "enderslicer.thermalIntegrity.v1";\n'
        '  const THERMAL_RESULT_EVENT = "enderslicer-thermal-result-3d";\n'
        '  const THERMAL_CLEAR_EVENT = "enderslicer-thermal-clear-3d";\n',
        "3D event constants")
    text = once(text,
        '    "thermalIntegrity",\n    "transformMatrix",\n',
        '    "thermalIntegrity",\n    "thermalIntegrityPreflight",\n    "transformMatrix",\n',
        "preflight engine operation")
    text = once(text,
        '  function field(id, label, value, step = "any") {\n',
        '''  function heatedFaceOptions(selected) {
    const labels = {
      xmin: "All exterior X− faces", xmax: "All exterior X+ faces",
      ymin: "All exterior Y− faces", ymax: "All exterior Y+ faces",
      zmin: "All exterior Z− faces", zmax: "All exterior Z+ faces",
    };
    return Object.entries(labels)
      .map(([key, label]) => `<option value="${key}"${key === selected ? " selected" : ""}>${label}</option>`)
      .join("");
  }

  function field(id, label, value, step = "any") {\n''',
        "heated orientation options")
    text = once(text,
        '<label class="ti-select"><span>Heated global plane</span><select id="ti-heatedFace">${faceOptions("zmax")}</select></label>',
        '<label class="ti-select"><span>Contact-heater orientation</span><select id="ti-heatedFace">${heatedFaceOptions("zmax")}</select></label>',
        "heater boundary label")
    text = once(text,
        '${field("heatPowerW", "Surface heat power (W)", 5, 0.1)}',
        '${field("heatPowerW", "Total contact-heater power (W)", 5, 0.1)}',
        "heater power label")
    text = once(text,
        '''      </div>
      ${checkbox("densityAware", "Use optimized Smart Infill density when available", true)}
''',
        '''      </div>
      <div id="ti-boundary-preflight" class="ti-status dim">
        Heater area and effective heat flux will be calculated before the solve.
      </div>
      ${checkbox("densityAware", "Use optimized Smart Infill density when available", true)}
''',
        "boundary preflight display")
    text = once(text,
        '''  function invalidate(message) {
    analysisEpoch += 1;
    latest = null;
''',
        '''  function invalidate(message) {
    analysisEpoch += 1;
    latest = null;
    window.dispatchEvent(new CustomEvent(THERMAL_CLEAR_EVENT));
''',
        "3D invalidation")
    text = once(text,
        '''      if (options.mode === "transient" && solidCells * Math.ceil(options.durationSeconds / options.timeStepSeconds) > 120_000_000) {
        throw new Error("Transient workload exceeds the Android safety budget. Increase voxel size or time step.");
      }
      let transform = null;
''',
        '''      if (options.mode === "transient" && solidCells * Math.ceil(options.durationSeconds / options.timeStepSeconds) > 120_000_000) {
        throw new Error("Transient workload exceeds the Android safety budget. Increase voxel size or time step.");
      }
      status.textContent = "Calculating exact heater area and effective heat flux…";
      const preflight = await request("thermalIntegrityPreflight", { opts: options });
      const preflightBox = document.getElementById("ti-boundary-preflight");
      const area = Number(preflight?.heatedAreaMm2);
      const flux = Number(preflight?.effectiveHeatFluxWm2);
      if (!Number.isFinite(area) || area <= 0 || !Number.isFinite(flux) || flux < 0) {
        throw new Error("filaSim returned invalid heater-boundary preflight data.");
      }
      if (preflightBox) {
        preflightBox.className = "ti-status dim";
        preflightBox.textContent =
          `Contact heater: ${format(area, 2)} mm² · ${format(flux / 1000, 2)} kW/m². ` +
          "Power enters the part through all exterior faces with the selected orientation; that contact is excluded from ambient losses.";
      }
      const physicalWarnings = [];
      if (options.ambientTemperatureC > options.serviceLimitC) {
        physicalWarnings.push(`Ambient ${options.ambientTemperatureC} °C already exceeds the ${options.materialName} service limit ${options.serviceLimitC} °C.`);
      }
      if (options.cooledTemperatureC > options.serviceLimitC) {
        physicalWarnings.push(`Fixed plane ${options.cooledTemperatureC} °C exceeds the material service limit.`);
      }
      if (options.initialTemperatureC > options.serviceLimitC) {
        physicalWarnings.push(`Initial temperature ${options.initialTemperatureC} °C exceeds the material service limit.`);
      }
      if (flux > 100_000) {
        physicalWarnings.push(`Effective contact heat flux is very high: ${format(flux / 1000, 1)} kW/m² over ${format(area, 2)} mm².`);
      }
      if (physicalWarnings.length && !window.confirm(
        `${physicalWarnings.join("\n")}\n\nThe thermal field can still be calculated, but structural FEA will be skipped if the solved field leaves the material model. Continue?`
      )) {
        throw new Error("Thermal Integrity run cancelled before solving.");
      }
      let transform = null;
''',
        "exact preflight and physical warning")
    text = once(text,
        '''      const materialFraction = data?.materialFraction;
      if (
''',
        '''      const materialFraction = data?.materialFraction;
      const vertexTemperatures = data?.vertexTemperatures;
      if (
''',
        "vertex temperature extraction")
    text = once(text,
        '''        !(displacements instanceof Float32Array) ||
        !(materialFraction instanceof Float32Array)
''',
        '''        !(displacements instanceof Float32Array) ||
        !(materialFraction instanceof Float32Array) ||
        !(vertexTemperatures instanceof Float32Array)
''',
        "vertex temperature type validation")
    text = once(text,
        '''        materialFraction.length !== expectedCells
      ) {
''',
        '''        materialFraction.length !== expectedCells ||
        vertexTemperatures.length * 3 !== displacements.length
      ) {
''',
        "vertex temperature length validation")
    text = once(text,
        '''        materialFraction,
        transform,
''',
        '''        materialFraction,
        vertexTemperatures,
        transform,
''',
        "latest 3D field")
    text = once(text,
        '''      currentSaveButton.disabled =
        !transform || typeof android.captureThermalIntegrityReport !== "function";
      const currentStatus = document.getElementById("ti-status") || status;
      currentStatus.className = "ti-status dim";
      currentStatus.textContent = transform
        ? "Thermal integrity solve completed. Exact raw-worker values and pose can be saved."
        : "Thermal solve completed, but the exact pose query failed; reporting is disabled for this run.";
''',
        '''      currentSaveButton.disabled =
        !stats.structuralValid || !transform || typeof android.captureThermalIntegrityReport !== "function";
      const currentStatus = document.getElementById("ti-status") || status;
      currentStatus.className = stats.structuralValid ? "ti-status dim" : "ti-status ti-error";
      currentStatus.textContent = !stats.structuralValid
        ? `THERMAL FAILURE / OUTSIDE MATERIAL MODEL: ${stats.materialValidityReason || "Structural FEA was not calculated."}`
        : transform
          ? "Thermal integrity solve completed. Exact raw-worker values, pose and 3D temperature field can be saved."
          : "Thermal solve completed, but the exact pose query failed; reporting is disabled for this run.";
''',
        "validity-aware completion")
    text = once(text,
        '''    input("kpis").innerHTML = [
      kpi("Maximum temperature", `${format(stats.maximumTemperatureC, 2)} °C`),
      kpi("Temperature margin", `${format(stats.temperatureMarginC, 2)} °C`),
      kpi("Thermal deformation", `${format(stats.maxDisplacementMm, 5)} mm`),
      kpi("Maximum von Mises", `${format(stats.maxVonMisesMpa, 3)} MPa`),
      kpi("Conservative safety factor", format(stats.conservativeSafetyFactor, 3)),
      kpi("Strength retained", `${format(Number(stats.minimumStrengthRetention) * 100, 1)}%`),
      kpi("Heat rejected", `${format(stats.heatRejectedW, 4)} W`),
      kpi("Energy imbalance", `${format(Number(stats.energyBalanceRelative) * 100, 2)}%`),
    ].join("");
''',
        '''    const structuralValid = stats.structuralValid === true;
    input("kpis").innerHTML = [
      kpi("Maximum temperature", `${format(stats.maximumTemperatureC, 2)} °C`),
      kpi("Temperature margin", `${format(stats.temperatureMarginC, 2)} °C`),
      kpi("Heated contact area", `${format(stats.heatedAreaMm2, 2)} mm²`),
      kpi("Effective heat flux", `${format(Number(stats.effectiveHeatFluxWm2) / 1000, 2)} kW/m²`),
      kpi("Thermal deformation", structuralValid ? `${format(stats.maxDisplacementMm, 5)} mm` : "Not calculated"),
      kpi("Maximum von Mises", structuralValid ? `${format(stats.maxVonMisesMpa, 3)} MPa` : "Not calculated"),
      kpi("Conservative safety factor", structuralValid ? format(stats.conservativeSafetyFactor, 3) : "Not calculated"),
      kpi("Heat rejected", `${format(stats.heatRejectedW, 4)} W`),
    ].join("");
    window.dispatchEvent(new CustomEvent(THERMAL_RESULT_EVENT, { detail: {
      vertexTemperatures: latest.vertexTemperatures,
      displacements: latest.displacements,
      minimumTemperatureC: Number(stats.minimumTemperatureC),
      maximumTemperatureC: Number(stats.maximumTemperatureC),
      structuralValid,
      maxDisplacementMm: structuralValid ? Number(stats.maxDisplacementMm) : null,
    }}));
''',
        "validity-aware KPIs and 3D event")
    text = once(text,
        '''    if (stats.propertyExtrapolated) {
      warnings.push("The result exceeds the preset property range; safety values are extrapolated.");
    }
''',
        '''    if (!stats.structuralValid) {
      warnings.push(`THERMAL FAILURE / OUTSIDE MATERIAL MODEL: ${stats.materialValidityReason || "Structural FEA was skipped."}`);
      warnings.push("Temperature convergence and energy balance do not make the polymer structurally valid above its material range.");
    }
''',
        "material failure warning")
    text = once(text,
        '''  function collectReport() {
    if (!latest || !latest.transform) throw new Error("Run Thermal Integrity with a captured model pose first.");
''',
        '''  function collectReport() {
    if (!latest || !latest.transform) throw new Error("Run Thermal Integrity with a captured model pose first.");
    if (latest.stats?.structuralValid !== true) {
      throw new Error("Structural reporting is disabled because the thermal field is outside the material model.");
    }
''',
        "invalid structural report rejection")
    text = once(text,
        '      solverModel: "voxel-finite-volume-implicit-thermomechanical",\n',
        '      solverModel: "voxel-finite-volume-contact-heater-thermomechanical-v2",\n',
        "solver model version")
    target.write_text(text, encoding="utf-8")
    check = target.read_text(encoding="utf-8")
    for value in ("thermalIntegrityPreflight", "effectiveHeatFluxWm2", "THERMAL FAILURE / OUTSIDE MATERIAL MODEL", "vertexTemperatures", "contact-heater"):
        if value not in check: raise RuntimeError(f"Thermal v11 UI contract missing: {value}")

def patch_workspace(target: pathlib.Path) -> None:
    text = target.read_text(encoding="utf-8")
    text = once(text,
        '    if (cancel) cancel.disabled = !cancelFlag;\n',
        '    if (cancel) cancel.disabled = !(cancelFlag || engineWorker);\n',
        "fallback cancel enablement")
    text = once(text,
        '''    chip.textContent = cancelFlag
      ? "Cancel: available at solver checkpoints"
      : "Cancel: unavailable until threaded WASM initializes";
''',
        '''    chip.textContent = cancelFlag
      ? "Cancel: cooperative at solver checkpoints"
      : engineWorker
        ? "Cancel: hard restart available (reloads the Smart Infill session)"
        : "Cancel: waiting for the engine worker";
''',
        "fallback cancel status")
    text = once(text,
        '''  function cancelRun() {
    if (!cancelFlag || typeof Atomics === "undefined") {
      setProgress("Cancellation unavailable", progressState.progress, "Threaded WASM has not exposed its shared cancel flag.");
      return;
    }
    Atomics.store(cancelFlag, 0, 1);
    const cancel = document.getElementById("ti-cancel");
    if (cancel) cancel.disabled = true;
    setProgress("Cancelling", progressState.progress, "Waiting for the active solver iteration to stop safely…");
  }
''',
        '''  function cancelRun() {
    const cancel = document.getElementById("ti-cancel");
    if (cancel) cancel.disabled = true;
    if (cancelFlag && typeof Atomics !== "undefined") {
      Atomics.store(cancelFlag, 0, 1);
      setProgress("Cancelling", progressState.progress, "Waiting for the active solver iteration to stop safely…");
      return;
    }
    if (engineWorker) {
      setProgress(
        "Cancelling and restarting",
        progressState.progress,
        "Threaded cancellation is unavailable. Terminating the worker and reloading the model; unsaved supports, loads and solver progress will reset."
      );
      engineWorker.terminate();
      engineWorker = null;
      activeRequestId = null;
      window.setTimeout(() => window.location.reload(), 80);
      return;
    }
    setProgress("Cancellation unavailable", progressState.progress, "The engine worker is not available.");
  }
''',
        "hard cancel fallback")
    target.write_text(text, encoding="utf-8")
    check = target.read_text(encoding="utf-8")
    if "engineWorker.terminate()" not in check: raise RuntimeError("Hard cancel fallback was not packaged")

thermal.patch_thermal_ui_runtime = patch_ui
_base_inject = thermal.inject_thermal_integrity_runtime

def inject_v11(index: pathlib.Path) -> None:
    _base_inject(index)
    patch_workspace(index.with_name(thermal.THERMAL_WORKSPACE_NAME))
thermal.inject_thermal_integrity_runtime = inject_v11
thermal.BASE.inject_bridge = inject_v11
thermal.THERMAL_PACKAGE_MARKER_TEXT = (
    "format=1\n"
    f"filasim={thermal.BASE.FILASIM_COMMIT}\n"
    "transforms=solver,hardening,audit-fixes,progress-v2,react-tab-v1,bugfix-round1,bugfix-round2,linear-fast-path-v1,physical-model-v1\n"
)

if __name__ == "__main__":
    try: raise SystemExit(thermal.BASE.main())
    except Exception as error:
        print(f"thermal filaSim v11 asset preparation failed: {error}", file=sys.stderr)
        raise
