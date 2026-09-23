#!/usr/bin/env python3
"""Prepare format-8 filaSim Android assets with thermal-integrity support.

This wrapper deliberately composes the already-validated Android/pinch asset
preparer instead of replacing its cache and packaging contract. The thermal
solver transforms are applied to the same pinned source tree immediately before
the existing Android source transforms and production build.
"""

from __future__ import annotations

import importlib.util
import pathlib
import shutil
import subprocess
import sys

PINCH_SCRIPT = pathlib.Path(__file__).with_name("prepare-filasim-assets-with-pinch-v8-base.py")
SPEC = importlib.util.spec_from_file_location("enderslicer_filasim_pinch", PINCH_SCRIPT)
if SPEC is None or SPEC.loader is None:
    raise RuntimeError(f"Unable to load filaSim Android preparer: {PINCH_SCRIPT}")
PINCH = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PINCH)

BASE = PINCH.BASE
PROJECT_ROOT = pathlib.Path(__file__).resolve().parents[1]
INVALIDATED_EVENT = "enderslicer-thermal-integrity-invalidated"
THERMAL_TRANSFORMS = (
    pathlib.Path(__file__).with_name("filasim-thermal-integrity-patch.py"),
    pathlib.Path(__file__).with_name("filasim-thermal-integrity-hardening.py"),
    pathlib.Path(__file__).with_name("filasim-thermal-integrity-audit-fixes.py"),
    pathlib.Path(__file__).with_name("filasim-thermal-integrity-progress.py"),
    pathlib.Path(__file__).with_name("filasim-thermal-integrity-react-tab.py"),
    pathlib.Path(__file__).with_name("filasim-thermal-integrity-bugfix-round1.py"),
)
THERMAL_MARKERS = (
    ".enderslicer-thermal-integrity",
    ".enderslicer-thermal-integrity-hardening",
    ".enderslicer-thermal-integrity-audit-fixes",
    ".enderslicer-thermal-integrity-progress-v2",
    ".enderslicer-thermal-integrity-react-tab-v1",
    ".enderslicer-thermal-integrity-bugfix-round1",
)
THERMAL_UI_SOURCE = PROJECT_ROOT / "app/src/main/filasim/thermal-integrity.js"
THERMAL_UI_NAME = "thermal-integrity.js"
THERMAL_GUARD_SOURCE = PROJECT_ROOT / "app/src/main/filasim/thermal-integrity-guard.js"
THERMAL_GUARD_NAME = "thermal-integrity-guard.js"
THERMAL_WORKSPACE_SOURCE = PROJECT_ROOT / "app/src/main/filasim/thermal-integrity-workspace.js"
THERMAL_WORKSPACE_NAME = "thermal-integrity-workspace.js"
THERMAL_LIVE_SOURCE = PROJECT_ROOT / "app/src/main/filasim/thermal-integrity-live-progress.js"
THERMAL_LIVE_NAME = "thermal-integrity-live-progress.js"
THERMAL_UI_TAG = f'<script src="./{THERMAL_UI_NAME}"></script>'
THERMAL_GUARD_TAG = f'<script src="./{THERMAL_GUARD_NAME}"></script>'
THERMAL_WORKSPACE_TAG = f'<script src="./{THERMAL_WORKSPACE_NAME}"></script>'
THERMAL_LIVE_TAG = f'<script src="./{THERMAL_LIVE_NAME}"></script>'
THERMAL_RUNTIME_TAGS = (
    f"{THERMAL_GUARD_TAG}\n  {THERMAL_LIVE_TAG}\n  {THERMAL_WORKSPACE_TAG}\n  {THERMAL_UI_TAG}"
)
THERMAL_PACKAGE_MARKER = "thermal-integrity-version.txt"
THERMAL_PACKAGE_MARKER_TEXT = (
    "format=1\n"
    f"filasim={BASE.FILASIM_COMMIT}\n"
    "transforms=solver,hardening,audit-fixes,progress-v2,react-tab-v1,bugfix-round1\n"
)

_BASE_PATCH_ANDROID_EXPORT = BASE.patch_android_export
_BASE_INJECT_BRIDGE = BASE.inject_bridge


def apply_thermal_transforms(source_root: pathlib.Path) -> None:
    source_root = source_root.resolve()
    marker_paths = tuple(source_root / name for name in THERMAL_MARKERS)
    marker_state = tuple(path.is_file() for path in marker_paths)

    # Valid cached states are a contiguous prefix of the ordered transform set.
    # Missing suffixes are upgraded deterministically; arbitrary partial states
    # fail closed instead of guessing which source edits are present.
    first_missing = next((index for index, present in enumerate(marker_state) if not present), len(marker_state))
    if any(marker_state[first_missing:]):
        missing = [path.name for path, present in zip(marker_paths, marker_state) if not present]
        raise RuntimeError(
            "Thermal-integrity source is only partially transformed; missing markers: "
            + ", ".join(missing)
        )

    for transform in THERMAL_TRANSFORMS[first_missing:]:
        if not transform.is_file():
            raise RuntimeError(f"Thermal-integrity transform is missing: {transform}")
        subprocess.run(
            [sys.executable, str(transform), str(source_root)],
            cwd=PROJECT_ROOT,
            check=True,
        )

    missing = [path.name for path in marker_paths if not path.is_file()]
    if missing:
        raise RuntimeError(
            "Thermal-integrity source markers are missing after transformation: "
            + ", ".join(missing)
        )

    core_module = source_root / "crates/filasim-core/src/thermal.rs"
    wasm_entry = source_root / "crates/filasim-wasm/src/lib.rs"
    worker_entry = source_root / "web/src/worker/engine.worker.ts"
    protocol_entry = source_root / "web/src/engine/EngineProtocol.ts"
    rail_entry = source_root / "web/src/ui/StepRail.tsx"
    panel_entry = source_root / "web/src/ui/StepPanel.tsx"
    topbar_entry = source_root / "web/src/ui/TopBar.tsx"
    required_contracts = (
        (core_module, "solve_thermal"),
        (core_module, "progress::publish"),
        (core_module, "MAX_SUPPORTED_TEMPERATURE_C"),
        (core_module, "steady thermal radiation iteration did not converge"),
        (wasm_entry, "solve_thermal_integrity"),
        (wasm_entry, "Preparing voxel model"),
        (wasm_entry, "MAX_MOBILE_GRID_CELLS"),
        (wasm_entry, "service limit must exceed its reference temperature"),
        (worker_entry, "thermalIntegrity"),
        (worker_entry, "progress: true"),
        (protocol_entry, "thermalIntegrity"),
        (rail_entry, "enderslicer-thermal-workspace"),
        (rail_entry, "Thermal Integrity — service-temperature"),
        (rail_entry, "if (!s.model && thermalActive)"),
        (panel_entry, "enderslicer-thermal-integrity-mount"),
        (panel_entry, "if (!s.model && thermalActive)"),
        (topbar_entry, "new CustomEvent<boolean>(\"enderslicer-thermal-workspace\""),
    )
    for path, marker in required_contracts:
        if not path.is_file() or marker not in path.read_text(encoding="utf-8"):
            raise RuntimeError(f"Thermal-integrity contract {marker!r} is missing from {path}")


def patch_android_export_with_thermal_integrity(store_file: pathlib.Path) -> None:
    # store.ts lives at <source>/web/src/store.ts.
    apply_thermal_transforms(store_file.parents[2])
    _BASE_PATCH_ANDROID_EXPORT(store_file)


def copy_verified(source: pathlib.Path, target: pathlib.Path, label: str) -> None:
    if not source.is_file():
        raise RuntimeError(f"{label} is missing: {source}")
    shutil.copy2(source, target)
    if target.read_bytes() != source.read_bytes():
        raise RuntimeError(f"Copied {label} did not verify byte-for-byte")


def replace_ui_once(text: str, old: str, new: str, label: str) -> str:
    if new in text:
        return text
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"Expected one Thermal UI {label}, found {count}")
    return text.replace(old, new, 1)


def patch_thermal_ui_runtime(target: pathlib.Path) -> None:
    text = target.read_text(encoding="utf-8")

    text = replace_ui_once(
        text,
        '  const STORAGE_KEY = "enderslicer.thermalIntegrity.v1";\n',
        '  const STORAGE_KEY = "enderslicer.thermalIntegrity.v1";\n'
        f'  const INVALIDATED_EVENT = "{INVALIDATED_EVENT}";\n',
        "invalidation event constant",
    )
    text = replace_ui_once(
        text,
        '''  let latest = null;
  let observer = null;
''',
        '''  let latest = null;
  let observer = null;
  let runInFlight = false;
  let analysisEpoch = 0;
''',
        "lifecycle state",
    )
    text = replace_ui_once(
        text,
        '''    return new Promise((resolve, reject) => {
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
''',
        '''    return new Promise((resolve, reject) => {
      const cleanup = () => {
        worker.removeEventListener("message", listener);
        worker.removeEventListener("error", onError);
        worker.removeEventListener("messageerror", onMessageError);
      };
      const listener = (event) => {
        const message = event.data;
        if (!message || message.id !== id || message.progress) return;
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
      worker.addEventListener("message", listener);
      worker.addEventListener("error", onError);
      worker.addEventListener("messageerror", onMessageError);
      worker.postMessage({ id, op, ...payload });
    });
''',
        "worker request cleanup",
    )
    text = replace_ui_once(
        text,
        '''    };
    if (options.heatedFace === options.cooledFace && options.heatPowerW > 0) {
''',
        '''    };
    if (mode !== "steady" && mode !== "transient") {
      throw new Error("Analysis mode must be steady or transient.");
    }
    if (options.serviceLimitC <= options.referenceTemperatureC) {
      throw new Error("The material service limit must be higher than the property reference temperature.");
    }
    if (options.heatedFace === options.cooledFace && options.heatPowerW > 0) {
''',
        "cross-field validation",
    )
    text = replace_ui_once(
        text,
        '''  function invalidate(message) {
    latest = null;
    const save = document.getElementById("ti-save");
    if (save) save.disabled = true;
    const status = document.getElementById("ti-status");
    if (status && message) status.textContent = message;
  }
''',
        '''  function invalidate(message) {
    analysisEpoch += 1;
    latest = null;
    const results = document.getElementById("ti-results");
    if (results) results.classList.remove("ready");
    const save = document.getElementById("ti-save");
    if (save) save.disabled = true;
    const status = document.getElementById("ti-status");
    if (status && message) {
      status.className = "ti-status dim";
      status.textContent = message;
    }
  }
''',
        "result invalidation",
    )
    text = replace_ui_once(
        text,
        '''  async function runAnalysis() {
    const runButton = input("run");
    const saveButton = input("save");
    const status = input("status");
    runButton.disabled = true;
''',
        '''  async function runAnalysis() {
    const runButton = input("run");
    const saveButton = input("save");
    const status = input("status");
    if (runInFlight) {
      status.className = "ti-status ti-warning";
      status.textContent = "A Thermal Integrity solve is already running.";
      return;
    }
    runInFlight = true;
    const runEpoch = analysisEpoch;
    runButton.disabled = true;
''',
        "single-run guard",
    )
    text = replace_ui_once(
        text,
        '''      const options = collectOptions();
      saveDraft(options);
      let transform = null;
''',
        '''      const options = collectOptions();
      saveDraft(options);
      const sourceIdentity = {
        sourceName: String(android.sourceFileName()),
        sourceSha256: String(android.sourceSha256()),
        upstreamCommit: String(android.upstreamCommit()),
      };
      const voxel = await request("voxelInfo");
      const gridCells = Number(voxel?.nx) * Number(voxel?.ny) * Number(voxel?.nz);
      const solidCells = Number(voxel?.solid || 0);
      if (!Number.isSafeInteger(gridCells) || gridCells <= 0 || !Number.isFinite(solidCells)) {
        throw new Error("filaSim returned invalid voxel-grid metadata.");
      }
      if (gridCells > 2_000_000 || solidCells > 1_000_000) {
        throw new Error(
          `Thermal grid exceeds the Android safety budget (${solidCells.toLocaleString()} solid / ${gridCells.toLocaleString()} total cells). Increase voxel size.`
        );
      }
      if (options.mode === "transient" && solidCells * Math.ceil(options.durationSeconds / options.timeStepSeconds) > 120_000_000) {
        throw new Error("Transient workload exceeds the Android safety budget. Increase voxel size or time step.");
      }
      let transform = null;
''',
        "source identity and workload preflight",
    )
    text = replace_ui_once(
        text,
        '''      latest = {
        options,
        stats,
''',
        '''      if (runEpoch !== analysisEpoch) {
        throw new Error("The model, grid, loads or optimization changed during the solve; the stale result was discarded.");
      }
      latest = {
        options,
        sourceIdentity,
        stats,
''',
        "stale completion rejection",
    )
    text = replace_ui_once(
        text,
        '''      saveButton.disabled =
        !transform || typeof android.captureThermalIntegrityReport !== "function";
      status.textContent = transform
''',
        '''      const currentSaveButton = document.getElementById("ti-save") || saveButton;
      currentSaveButton.disabled =
        !transform || typeof android.captureThermalIntegrityReport !== "function";
      const currentStatus = document.getElementById("ti-status") || status;
      currentStatus.className = "ti-status dim";
      currentStatus.textContent = transform
''',
        "remount-safe completion UI",
    )
    text = replace_ui_once(
        text,
        '''    } catch (error) {
      status.className = "ti-status ti-error";
      status.textContent = `Thermal integrity failed: ${error?.message || error}`;
      console.error("EnderSlicer thermal integrity failed", error);
    } finally {
      runButton.disabled = false;
    }
''',
        '''    } catch (error) {
      const currentStatus = document.getElementById("ti-status") || status;
      currentStatus.className = "ti-status ti-error";
      currentStatus.textContent = `Thermal integrity failed: ${error?.message || error}`;
      console.error("EnderSlicer thermal integrity failed", error);
    } finally {
      runInFlight = false;
      const currentRunButton = document.getElementById("ti-run") || runButton;
      currentRunButton.disabled = false;
    }
''',
        "remount-safe failure and finalization",
    )
    text = replace_ui_once(
        text,
        '''      sourceName: String(android.sourceFileName()),
      sourceSha256: String(android.sourceSha256()),
      upstreamCommit: String(android.upstreamCommit()),
      generatedAtEpochMillis: Date.now(),
''',
        '''      sourceName: latest.sourceIdentity.sourceName,
      sourceSha256: latest.sourceIdentity.sourceSha256,
      upstreamCommit: latest.sourceIdentity.upstreamCommit,
      generatedAtEpochMillis: latest.completedAtEpochMillis,
''',
        "solve-time report identity",
    )
    text = replace_ui_once(
        text,
        '''    const panel = document.querySelector(".panel");
    if (!panel) return false;
    const group = createGroup();
    panel.appendChild(group);
''',
        '''    const mount = document.getElementById("enderslicer-thermal-integrity-mount");
    if (!mount) return false;
    const group = createGroup();
    mount.appendChild(group);
''',
        "React-owned mount",
    )
    text = replace_ui_once(
        text,
        '''    restoreDraft();
    attachListeners(group);
    return true;
''',
        '''    restoreDraft();
    attachListeners(group);
    if (runInFlight) input("run").disabled = true;
    if (latest) {
      renderResults();
      input("save").disabled =
        !latest.transform || typeof android.captureThermalIntegrityReport !== "function";
    }
    return true;
''',
        "React remount state restoration",
    )
    text = replace_ui_once(
        text,
        '''  installUi();
  observer = new MutationObserver(() => installUi());
''',
        '''  window.addEventListener(INVALIDATED_EVENT, (event) => {
    const message = event?.detail?.message || "Thermal Integrity inputs changed; run the analysis again.";
    invalidate(message);
  });

  installUi();
  observer = new MutationObserver(() => installUi());
''',
        "external invalidation listener",
    )

    target.write_text(text, encoding="utf-8")
    verified = target.read_text(encoding="utf-8")
    required = (
        'document.getElementById("enderslicer-thermal-integrity-mount")',
        "runInFlight",
        "analysisEpoch",
        "sourceIdentity",
        "2_000_000",
        "stale result was discarded",
        "service limit must be higher",
        INVALIDATED_EVENT,
    )
    for marker in required:
        if marker not in verified:
            raise RuntimeError(f"Thermal Integrity UI bug-fix contract is missing: {marker}")
    if 'document.querySelector(".panel")' in verified:
        raise RuntimeError("Thermal Integrity UI was not isolated to the React-owned mount")


def inject_thermal_integrity_runtime(index_file: pathlib.Path) -> None:
    _BASE_INJECT_BRIDGE(index_file)

    copy_verified(
        THERMAL_GUARD_SOURCE,
        index_file.with_name(THERMAL_GUARD_NAME),
        "thermal-integrity lifecycle guard runtime",
    )
    copy_verified(
        THERMAL_LIVE_SOURCE,
        index_file.with_name(THERMAL_LIVE_NAME),
        "thermal-integrity live-progress runtime",
    )
    copy_verified(
        THERMAL_WORKSPACE_SOURCE,
        index_file.with_name(THERMAL_WORKSPACE_NAME),
        "thermal-integrity workspace runtime",
    )
    thermal_ui_target = index_file.with_name(THERMAL_UI_NAME)
    copy_verified(
        THERMAL_UI_SOURCE,
        thermal_ui_target,
        "thermal-integrity UI runtime",
    )
    patch_thermal_ui_runtime(thermal_ui_target)

    text = index_file.read_text(encoding="utf-8")
    for tag in (THERMAL_GUARD_TAG, THERMAL_LIVE_TAG, THERMAL_WORKSPACE_TAG):
        text = text.replace(f"  {tag}\n", "").replace(tag, "")
    if THERMAL_UI_TAG in text:
        text = text.replace(THERMAL_UI_TAG, THERMAL_RUNTIME_TAGS, 1)
    elif "</body>" in text:
        text = text.replace("</body>", f"  {THERMAL_RUNTIME_TAGS}\n</body>", 1)
    elif "</head>" in text:
        text = text.replace("</head>", f"  {THERMAL_RUNTIME_TAGS}\n</head>", 1)
    else:
        raise RuntimeError("Unable to inject the thermal-integrity runtimes into index.html")
    index_file.write_text(text, encoding="utf-8")

    verified = index_file.read_text(encoding="utf-8")
    for tag in (THERMAL_GUARD_TAG, THERMAL_LIVE_TAG, THERMAL_WORKSPACE_TAG, THERMAL_UI_TAG):
        if verified.count(tag) != 1:
            raise RuntimeError(f"Thermal-integrity runtime tag was not retained exactly once: {tag}")
    positions = [
        verified.index(THERMAL_GUARD_TAG),
        verified.index(THERMAL_LIVE_TAG),
        verified.index(THERMAL_WORKSPACE_TAG),
        verified.index(THERMAL_UI_TAG),
    ]
    if positions != sorted(positions):
        raise RuntimeError("Thermal-integrity runtimes are not in guard/live/workspace/UI load order")

    marker = index_file.with_name(THERMAL_PACKAGE_MARKER)
    marker.write_text(THERMAL_PACKAGE_MARKER_TEXT, encoding="utf-8")
    if marker.read_text(encoding="utf-8") != THERMAL_PACKAGE_MARKER_TEXT:
        raise RuntimeError("Thermal-integrity package marker did not verify byte-for-byte")


BASE.patch_android_export = patch_android_export_with_thermal_integrity
BASE.inject_bridge = inject_thermal_integrity_runtime


if __name__ == "__main__":
    try:
        raise SystemExit(BASE.main())
    except Exception as error:
        print(f"thermal filaSim asset preparation failed: {error}", file=sys.stderr)
        raise
