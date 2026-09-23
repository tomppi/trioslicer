#!/usr/bin/env python3
"""Build the pinned single-threaded filaSim web app for Android WebView."""

from __future__ import annotations

import argparse
import hashlib
import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import zipfile

FILASIM_COMMIT = "e7485ec22d4ebe8baca04190404fbb877c90e031"
ASSET_FORMAT = 7
HASH_MANIFEST = "SHA256SUMS"
MINIMUM_NODE_VERSION = (22, 18, 0)


def run(command: list[str], cwd: pathlib.Path, env: dict[str, str] | None = None) -> None:
    print(">", " ".join(command), flush=True)
    merged = os.environ.copy()
    if env:
        merged.update(env)
    subprocess.run(command, cwd=cwd, env=merged, check=True)


def require_supported_node() -> None:
    raw = subprocess.check_output(["node", "--version"], text=True).strip().lstrip("v")
    try:
        parts = tuple(int(value) for value in raw.split(".")[:3])
    except ValueError as error:
        raise RuntimeError(f"Unable to parse Node.js version: {raw}") from error
    if len(parts) != 3 or parts < MINIMUM_NODE_VERSION:
        expected = ".".join(str(value) for value in MINIMUM_NODE_VERSION)
        raise RuntimeError(f"filaSim requires Node.js {expected} or newer; found {raw}")


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def write_hash_manifest(root: pathlib.Path) -> None:
    entries: list[str] = []
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.name == HASH_MANIFEST:
            continue
        relative = path.relative_to(root).as_posix()
        entries.append(f"{sha256_file(path)}  {relative}")
    if not entries:
        raise RuntimeError("filaSim asset workspace is empty")
    (root / HASH_MANIFEST).write_text("\n".join(entries) + "\n", encoding="utf-8")


def verify_hash_manifest(root: pathlib.Path) -> None:
    manifest = root / HASH_MANIFEST
    if not manifest.is_file():
        raise RuntimeError("filaSim asset hash manifest is missing")
    seen: set[str] = set()
    for raw_line in manifest.read_text(encoding="utf-8").splitlines():
        expected, separator, relative = raw_line.partition("  ")
        if not separator or len(expected) != 64 or relative in seen:
            raise RuntimeError(f"Invalid filaSim asset hash entry: {raw_line}")
        seen.add(relative)
        path = (root / pathlib.PurePosixPath(relative)).resolve()
        if root.resolve() not in path.parents:
            raise RuntimeError(f"Unsafe filaSim asset hash path: {relative}")
        if not path.is_file() or sha256_file(path) != expected:
            raise RuntimeError(f"filaSim asset hash mismatch: {relative}")


def safe_extract(archive: zipfile.ZipFile, destination: pathlib.Path) -> pathlib.Path:
    roots: set[str] = set()
    destination = destination.resolve()
    for info in archive.infolist():
        parts = pathlib.PurePosixPath(info.filename).parts
        if not parts:
            continue
        roots.add(parts[0])
        target = (destination / pathlib.Path(*parts)).resolve()
        if target != destination and destination not in target.parents:
            raise RuntimeError(f"Unsafe filaSim archive entry: {info.filename}")
        if info.is_dir():
            target.mkdir(parents=True, exist_ok=True)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.open(info) as source, target.open("wb") as output:
                shutil.copyfileobj(source, output, 1024 * 1024)
    if len(roots) != 1:
        raise RuntimeError("Pinned filaSim archive did not contain one source root")
    root = destination / next(iter(roots))
    if not root.is_dir():
        raise RuntimeError("Pinned filaSim source root is missing")
    return root


def patch_android_export(store_file: pathlib.Path) -> None:
    text = store_file.read_text(encoding="utf-8")

    if "bridge?.captureModifierZip" not in text:
        old_modifiers = '''  async downloadStls() {
    try {
      const bytes = await engine.exportStls();
      const base = (get().fileName ?? "part").replace(/\\.(stl|3mf)$/i, "");
      download(bytes, `${base}_modifiers.zip`, "application/zip");
'''
        new_modifiers = '''  async downloadStls() {
    try {
      const bytes = await engine.exportStls();
      const state = get();
      const bridge = (window as any).EnderSlicerBridge;
      // EnderSlicerBridge captureModifierZip
      if (bridge?.captureModifierZip) {
        if (!state.optSummary) throw new Error("No optimized infill result is available");
        await bridge.captureModifierZip(bytes, {
          sourceName: state.fileName ?? "part.stl",
          baseDensityPercent: state.optSummary.baseDensity * 100,
          pattern: state.optMode === "binary" ? state.solidPattern : state.pattern,
          mode: state.optMode,
          perimeters: state.perimeters,
          lineWidthMm: state.lineWidth,
          topBottomLayers: state.topBottomLayers,
          layerHeightMm: state.layerHeight,
        });
        return;
      }
      const base = (state.fileName ?? "part").replace(/\\.(stl|3mf)$/i, "");
      download(bytes, `${base}_modifiers.zip`, "application/zip");
'''
        if old_modifiers not in text:
            raise RuntimeError("Unable to locate filaSim modifier-export function for Android patching")
        text = text.replace(old_modifiers, new_modifiers)

    if "bridge?.captureOptimizedShape" not in text:
        old_shape = '''  async downloadShape() {
    try {
      const bytes = await engine.exportSolidStl();
      const base = (get().fileName ?? "part").replace(/\\.(stl|3mf)$/i, "");
      download(bytes, `${base}_optimized.stl`, "model/stl");
'''
        new_shape = '''  async downloadShape() {
    try {
      const bytes = await engine.exportSolidStl();
      const bridge = (window as any).EnderSlicerBridge;
      // EnderSlicerBridge captureOptimizedShape
      if (bridge?.captureOptimizedShape) {
        await bridge.captureOptimizedShape(bytes);
        return;
      }
      const base = (get().fileName ?? "part").replace(/\\.(stl|3mf)$/i, "");
      download(bytes, `${base}_optimized.stl`, "model/stl");
'''
        if old_shape not in text:
            raise RuntimeError("Unable to locate filaSim topology-shape export for Android patching")
        text = text.replace(old_shape, new_shape)

    if "EnderSlicerBridge captureModifierZip" not in text:
        text = text.replace(
            "      if (bridge?.captureModifierZip) {",
            "      // EnderSlicerBridge captureModifierZip\n      if (bridge?.captureModifierZip) {",
            1,
        )
    if "EnderSlicerBridge captureOptimizedShape" not in text:
        text = text.replace(
            "      if (bridge?.captureOptimizedShape) {",
            "      // EnderSlicerBridge captureOptimizedShape\n      if (bridge?.captureOptimizedShape) {",
            1,
        )

    # Upgrade already-patched cached sources from earlier Android formats.
    text = text.replace(
        '          pattern: state.pattern,\n          mode: state.optMode,',
        '          pattern: state.optMode === "binary" ? state.solidPattern : state.pattern,\n          mode: state.optMode,',
    )
    store_file.write_text(text, encoding="utf-8")


def patch_android_startup(app_file: pathlib.Path) -> None:
    text = app_file.read_text(encoding="utf-8")
    old = '    if (!s.sampleSkipped) void s.loadSampleModel();'
    marker = "EnderSlicer Android host supplies the exact displayed model"
    if marker not in text:
        if old not in text:
            raise RuntimeError("Unable to locate filaSim sample startup for Android patching")
        new = (
            f"    // {marker}.\n"
            '    if (!new URLSearchParams(window.location.search).has("android") && !s.sampleSkipped) {\n'
            "      void s.loadSampleModel();\n"
            "    }"
        )
        text = text.replace(old, new, 1)
    app_file.write_text(text, encoding="utf-8")


def patch_android_topbar(topbar_file: pathlib.Path) -> None:
    text = topbar_file.read_text(encoding="utf-8")
    marker = "EnderSlicer Android owns project persistence"
    if marker not in text:
        function_start = "export function TopBar() {\n"
        if function_start not in text:
            raise RuntimeError("Unable to locate filaSim top bar function for Android patching")
        text = text.replace(
            function_start,
            function_start
            + '  // EnderSlicer Android owns project persistence and model loading.\n'
            + '  const androidHosted = new URLSearchParams(window.location.search).has("android");\n',
            1,
        )

        project_controls_start = '''      <input
        ref={openRef}
        type="file"
'''
        if project_controls_start not in text:
            raise RuntimeError("Unable to locate filaSim project controls for Android patching")
        text = text.replace(
            project_controls_start,
            '''      {!androidHosted && (
        <>
          <input
        ref={openRef}
        type="file"
''',
            1,
        )

        project_controls_end = '''        Load<span className="btxt"> Project</span>
      </button>
      <button
        className="ghost"
        onClick={() => s.openSettings(true)}
'''
        if project_controls_end not in text:
            raise RuntimeError("Unable to locate the end of filaSim project controls for Android patching")
        text = text.replace(
            project_controls_end,
            '''        Load<span className="btxt"> Project</span>
      </button>
        </>
      )}
      <button
        className="ghost"
        onClick={() => s.openSettings(true)}
''',
            1,
        )
    topbar_file.write_text(text, encoding="utf-8")


def patch_android_viewer(scene_file: pathlib.Path) -> None:
    text = scene_file.read_text(encoding="utf-8")
    marker = "EnderSlicer Android deterministic touch pan"
    if marker in text:
        return

    orbit_fields = '''  private orbiting = false;
  private _oq1 = new THREE.Quaternion();
'''
    if orbit_fields not in text:
        raise RuntimeError("Unable to locate filaSim orbit state for Android touch patching")
    text = text.replace(
        orbit_fields,
        '''  private orbiting = false;
  // EnderSlicer Android deterministic touch pan: one finger keeps the custom
  // pivot orbit; two or more fingers use one manual screen-space pan path.
  private orbitPointerId: number | null = null;
  private touchPointers = new Map<number, { x: number; y: number }>();
  private touchPanLast: { x: number; y: number } | null = null;
  private _oq1 = new THREE.Quaternion();
''',
        1,
    )

    pointer_move = '''  private onPointerMove = (ev: PointerEvent) => {
    if (this.orbiting) return; // camera drag in progress — skip hover/brush
'''
    if pointer_move not in text:
        raise RuntimeError("Unable to locate filaSim pointer-move handler for Android touch patching")
    text = text.replace(
        pointer_move,
        '''  private onPointerMove = (ev: PointerEvent) => {
    if (ev.pointerType === "touch" && this.touchPointers.has(ev.pointerId)) {
      this.touchPointers.set(ev.pointerId, { x: ev.clientX, y: ev.clientY });
      if (this.touchPanLast && this.touchPointers.size > 1) {
        const next = this.touchCentroid();
        this.panTouchCamera(next.x - this.touchPanLast.x, next.y - this.touchPanLast.y);
        this.touchPanLast = next;
        return;
      }
    }
    if (this.orbiting) return; // camera drag in progress — skip hover/brush
''',
        1,
    )

    pointer_down = '''  private onPointerDown = (ev: PointerEvent) => {
    if (!this.mesh) return;
    // RMB removes from the active selection: paint-erase in "brush", and in
'''
    if pointer_down not in text:
        raise RuntimeError("Unable to locate filaSim pointer-down handler for Android touch patching")
    text = text.replace(
        pointer_down,
        '''  private onPointerDown = (ev: PointerEvent) => {
    if (!this.mesh) return;
    if (ev.pointerType === "touch") {
      this.touchPointers.set(ev.pointerId, { x: ev.clientX, y: ev.clientY });
      if (this.touchPointers.size > 1) {
        // OrbitControls has already observed this pointer. Disable its move
        // path before either implementation can apply a delta, then pan from
        // the touch centroid ourselves until every finger is released.
        this.brushing = false;
        this.finishOrbitGesture();
        this.controls.enabled = false;
        this.touchPanLast = this.touchCentroid();
        return;
      }
    }
    // RMB removes from the active selection: paint-erase in "brush", and in
''',
        1,
    )

    pointer_up = '''  private onPointerUp = (ev: PointerEvent) => {
    this.brushing = false;
    if (ev.button === 2 && this.rmbDown && this.tool === "select") {
'''
    if pointer_up not in text:
        raise RuntimeError("Unable to locate filaSim pointer-up handler for Android touch patching")
    text = text.replace(
        pointer_up,
        '''  private onPointerUp = (ev: PointerEvent) => {
    this.brushing = false;
    if (ev.pointerType === "touch") {
      const wasTouchPan = this.touchPanLast !== null;
      this.touchPointers.delete(ev.pointerId);
      if (wasTouchPan) {
        // Keep OrbitControls disabled while one finger remains after a pan.
        // Its two-to-one transition otherwise resumes a stale dolly-pan state.
        this.touchPanLast = this.touchPointers.size > 1 ? this.touchCentroid() : null;
        if (this.touchPointers.size === 0) this.controls.enabled = true;
        return;
      }
      if (this.orbitPointerId === ev.pointerId) this.finishOrbitGesture();
      if (this.touchPointers.size === 0) this.controls.enabled = true;
      return;
    }
    if (ev.button === 2 && this.rmbDown && this.tool === "select") {
''',
        1,
    )

    pointer_cancel_registration = '''    canvas.addEventListener("pointerup", this.onPointerUp);
    // RMB is a selection tool (erase) — never the browser context menu.
'''
    if pointer_cancel_registration not in text:
        raise RuntimeError("Unable to locate filaSim pointer registration for Android touch patching")
    text = text.replace(
        pointer_cancel_registration,
        '''    canvas.addEventListener("pointerup", this.onPointerUp);
    canvas.addEventListener("pointercancel", this.onPointerUp);
    // RMB is a selection tool (erase) — never the browser context menu.
''',
        1,
    )

    begin_orbit_end = '''    this.orbitStart = { x: ev.clientX, y: ev.clientY };
    this.orbitLast = { x: ev.clientX, y: ev.clientY };
    this.orbiting = false; // promoted once the drag passes the threshold
  }
'''
    if begin_orbit_end not in text:
        raise RuntimeError("Unable to locate filaSim orbit start for Android touch patching")
    text = text.replace(
        begin_orbit_end,
        '''    this.orbitStart = { x: ev.clientX, y: ev.clientY };
    this.orbitLast = { x: ev.clientX, y: ev.clientY };
    this.orbitPointerId = ev.pointerId;
    this.orbiting = false; // promoted once the drag passes the threshold
  }
''',
        1,
    )

    orbit_move = '''  private onOrbitMove = (ev: PointerEvent) => {
    if (!this.orbitPivot || !this.orbitLast || !this.controls.enabled) return;
'''
    if orbit_move not in text:
        raise RuntimeError("Unable to locate filaSim orbit move for Android touch patching")
    text = text.replace(
        orbit_move,
        '''  private onOrbitMove = (ev: PointerEvent) => {
    if (this.orbitPointerId !== ev.pointerId) return;
    if (!this.orbitPivot || !this.orbitLast || !this.controls.enabled) return;
''',
        1,
    )

    orbit_up = '''  private onOrbitUp = () => {
    if (!this.orbitPivot) return;
    this.orbitPivot = null;
    this.orbitStart = null;
    this.orbitLast = null;
    if (this.orbiting) {
      this.orbiting = false;
      // Re-level: hand the up vector back to OrbitControls upright.
      this.camera.up.set(0, 0, 1);
      this.camera.lookAt(this.controls.target);
    }
    if (this.pivotMarker) this.pivotMarker.visible = false;
  };
'''
    if orbit_up not in text:
        raise RuntimeError("Unable to locate filaSim orbit release for Android touch patching")
    text = text.replace(
        orbit_up,
        '''  private touchCentroid(): { x: number; y: number } {
    let x = 0;
    let y = 0;
    for (const point of this.touchPointers.values()) {
      x += point.x;
      y += point.y;
    }
    const count = Math.max(1, this.touchPointers.size);
    return { x: x / count, y: y / count };
  }

  private panTouchCamera(dx: number, dy: number) {
    if (dx === 0 && dy === 0) return;
    const width = this.canvas.clientWidth || this.viewW || 1;
    const height = this.canvas.clientHeight || this.viewH || 1;
    this.camera.updateMatrixWorld();
    this._oTmp
      .setFromMatrixColumn(this.camera.matrixWorld, 0)
      .multiplyScalar((-dx * (this.camera.right - this.camera.left)) / this.camera.zoom / width);
    this._oTmp2
      .setFromMatrixColumn(this.camera.matrixWorld, 1)
      .multiplyScalar((dy * (this.camera.top - this.camera.bottom)) / this.camera.zoom / height);
    this._oTmp.add(this._oTmp2);
    this.camera.position.add(this._oTmp);
    this.controls.target.add(this._oTmp);
    this.lastOrbitPivot?.add(this._oTmp);
    this.camera.updateMatrixWorld();
  }

  private finishOrbitGesture() {
    if (!this.orbitPivot && this.orbitPointerId === null) return;
    this.orbitPivot = null;
    this.orbitStart = null;
    this.orbitLast = null;
    this.orbitPointerId = null;
    if (this.orbiting) {
      this.orbiting = false;
      // Re-level: hand the up vector back to OrbitControls upright.
      this.camera.up.set(0, 0, 1);
      this.camera.lookAt(this.controls.target);
    }
    if (this.pivotMarker) this.pivotMarker.visible = false;
  }

  private onOrbitUp = (ev: PointerEvent) => {
    if (this.orbitPointerId !== null && ev.pointerId !== this.orbitPointerId) return;
    this.finishOrbitGesture();
  };
''',
        1,
    )

    scene_file.write_text(text, encoding="utf-8")


def inject_bridge(index_file: pathlib.Path) -> None:
    text = index_file.read_text(encoding="utf-8")
    script = '<script src="./android-bridge.js"></script>'
    if script in text:
        return
    if "</head>" in text:
        text = text.replace("</head>", f"  {script}\n</head>", 1)
    elif "</body>" in text:
        text = text.replace("</body>", f"  {script}\n</body>", 1)
    else:
        raise RuntimeError("Unable to inject the Android bridge into filaSim index.html")
    index_file.write_text(text, encoding="utf-8")


def copy_source_manifests(source_root: pathlib.Path, staging: pathlib.Path) -> None:
    destination = staging / "source-manifest"
    destination.mkdir(parents=True, exist_ok=True)
    for relative in (
        "Cargo.toml",
        "Cargo.lock",
        "deny.toml",
        "web/package.json",
        "web/package-lock.json",
    ):
        source = source_root / relative
        if not source.is_file():
            raise RuntimeError(f"Pinned filaSim source manifest is missing: {relative}")
        target = destination / relative.replace("/", "-")
        shutil.copy2(source, target)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project-root", default=pathlib.Path(__file__).resolve().parents[1], type=pathlib.Path)
    args = parser.parse_args()
    project_root = args.project_root.resolve()
    output = project_root / "app/src/main/assets/filasim"
    bridge = project_root / "app/src/main/filasim/android-bridge.js"
    marker_text = f"format={ASSET_FORMAT}\ncommit={FILASIM_COMMIT}\n"

    if not bridge.is_file():
        raise RuntimeError(f"Android filaSim bridge is missing: {bridge}")
    for executable in ("node", "npm", "wasm-pack"):
        if shutil.which(executable) is None:
            raise RuntimeError(
                f"{executable} is required to prepare filaSim assets. "
                "Install Rust, wasm-pack and Node.js before building EnderSlicerCura."
            )
    require_supported_node()

    build_root = project_root / ".build/filasim-android"
    build_root.mkdir(parents=True, exist_ok=True)
    source_root = build_root / f"{FILASIM_COMMIT}-format{ASSET_FORMAT}"
    if not source_root.is_dir():
        with tempfile.TemporaryDirectory(dir=build_root) as temporary:
            temporary_path = pathlib.Path(temporary)
            archive_path = temporary_path / "filasim.zip"
            url = f"https://github.com/CNCKitchen/smartInfillGenerator/archive/{FILASIM_COMMIT}.zip"
            request = urllib.request.Request(url, headers={"User-Agent": "EnderSlicerCura-build"})
            with urllib.request.urlopen(request, timeout=180) as response, archive_path.open("wb") as output_file:
                shutil.copyfileobj(response, output_file, 1024 * 1024)
            extract_path = temporary_path / "source"
            extract_path.mkdir()
            with zipfile.ZipFile(archive_path) as archive:
                extracted = safe_extract(archive, extract_path)
            shutil.move(str(extracted), source_root)

    web_root = source_root / "web"
    store_file = web_root / "src/store.ts"
    app_file = web_root / "src/App.tsx"
    topbar_file = web_root / "src/ui/TopBar.tsx"
    scene_file = web_root / "src/viewer/SceneManager.ts"
    if not all(path.is_file() for path in (store_file, app_file, topbar_file, scene_file)):
        raise RuntimeError("Pinned filaSim source did not contain its Android patch targets")
    patch_android_export(store_file)
    patch_android_startup(app_file)
    patch_android_topbar(topbar_file)
    patch_android_viewer(scene_file)

    npm_environment = {"NPM_CONFIG_ENGINE_STRICT": "true"}
    # Windows: CreateProcess cannot resolve the bare "npm" name - npm is
    # npm.cmd there - so invoke it explicitly on win32.
    npm = ["npm.cmd"] if os.name == "nt" else ["npm"]
    run([*npm, "ci", "--no-audit", "--no-fund"], cwd=web_root, env=npm_environment)
    run(["node", "scripts/build-wasm.mjs", "st"], cwd=web_root)
    run([*npm, "run", "build"], cwd=web_root, env={**npm_environment, "VITE_BASE": "./"})

    dist = web_root / "dist"
    if not (dist / "index.html").is_file():
        raise RuntimeError("filaSim production build did not create dist/index.html")
    staging = build_root / "assets.next"
    shutil.rmtree(staging, ignore_errors=True)
    shutil.copytree(dist, staging)
    shutil.copy2(bridge, staging / "android-bridge.js")
    shutil.copy2(source_root / "LICENSE", staging / "LICENSE")
    copy_source_manifests(source_root, staging)
    (staging / "SOURCE.md").write_text(
        "# filaSim source\n\n"
        f"Pinned upstream commit: `{FILASIM_COMMIT}`\n\n"
        "The complete corresponding source is the CNCKitchen/smartInfillGenerator repository "
        "at the commit above. EnderSlicerCura's Android bridge and deterministic build script "
        "are stored in this repository. Exact dependency manifests are packaged in "
        "`source-manifest/`.\n\n"
        "The same pinned core is also compiled into the native Android library shipped in "
        "this APK (`lib/arm64-v8a/libfilasim_jni.so`). Its corresponding source is that "
        "commit plus TrioSlicer's `native/filasim/` (the JNI session layer) and the "
        "reproducible build script `scripts/build-filasim-engine-android.sh`.\n",
        encoding="utf-8",
    )
    inject_bridge(staging / "index.html")
    (staging / ".source-version").write_text(marker_text, encoding="utf-8")
    write_hash_manifest(staging)
    verify_hash_manifest(staging)

    shutil.rmtree(output, ignore_errors=True)
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(staging), output)
    verify_hash_manifest(output)
    print(f"Prepared and verified filaSim Android assets at {output}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"filaSim asset preparation failed: {error}", file=sys.stderr)
        raise
