#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Create or verify the hash manifest of the vendored ScanRuler offline bundle.

ScanRuler (CNCKitchen/ScanRuler, AGPL-3.0-only) is a static Vite/React build pinned at
v0.9.4 / f183e9d074a2 and served by the app's WebView host from assets/scanruler/. This
script is the asset gate: it checks the pin marker, the files the page needs (wasm,
module workers, fonts and their OFL texts) and that the upstream dist/samples/ directory is
not staged, that every root-absolute URL in
index.html points under /assets/scanruler/, and every file's hash - failing closed on a
changed or unexpected file (the upstream bundle must never be edited).

    python3 scripts/verify-scanruler-assets.py --root app/src/main/assets/scanruler --check-only
    python3 scripts/verify-scanruler-assets.py --root app/src/main/assets/scanruler --write
"""

from __future__ import annotations

import argparse
import hashlib
import pathlib
import re
import sys

MANIFEST = "SHA256SUMS"
SOURCE_MARKER = ".source-version"

PIN = {
    "format": "1",
    "project": "CNCKitchen/ScanRuler",
    "tag": "v0.9.4",
    "commit": "f183e9d074a2ce95d1059174a173b8150b3b71eb",
    "base": "/assets/scanruler/",
    "node": "22.23.4",
    "npm": "10.9.9",
    "build": "npm ci && npm run build -- --base=/assets/scanruler/",
    "sample": "excluded",
}

REQUIRED_FILES = (
    "index.html",
    "LICENSE",
    "SOURCE.md",
    SOURCE_MARKER,
    "fonts/OFL-barlow.txt",
    "fonts/OFL-b612mono.txt",
    "fonts/OFL-barlowsemicondensed.txt",
)


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def files(root: pathlib.Path) -> list[pathlib.Path]:
    return sorted(
        path for path in root.rglob("*") if path.is_file() and path.name != MANIFEST
    )


def entries(root: pathlib.Path) -> list[str]:
    result = []
    for path in files(root):
        relative = path.relative_to(root).as_posix()
        result.append(f"{sha256(path)}  {relative}")
    if not result:
        raise RuntimeError("ScanRuler asset directory is empty")
    return result


def exactly(root: pathlib.Path, pattern: str, expected: int, what: str) -> list[pathlib.Path]:
    matches = sorted(root.glob(pattern))
    if len(matches) != expected:
        raise RuntimeError(
            f"ScanRuler {what} expected {expected} file(s) matching {pattern}, found {len(matches)}"
        )
    return matches


def validate_required(root: pathlib.Path) -> None:
    missing = [relative for relative in REQUIRED_FILES if not (root / relative).is_file()]
    if missing:
        raise RuntimeError("ScanRuler files are missing: " + ", ".join(missing))

    marker = {}
    for raw in (root / SOURCE_MARKER).read_text(encoding="utf-8").splitlines():
        key, separator, value = raw.partition("=")
        if separator:
            marker[key.strip()] = value.strip()
    for key, expected in PIN.items():
        if marker.get(key) != expected:
            raise RuntimeError(
                f"ScanRuler pin marker has {key}={marker.get(key)!r}, expected {expected!r}"
            )

    # index.html: the Vite build must have been made with --base=/assets/scanruler/, and
    # every root-absolute asset URL must stay under that path (the WebViewAssetLoader mounts
    # only /assets/). A build without the base would silently fetch /assets/... from the
    # wrong place or fail offline.
    index = (root / "index.html").read_text(encoding="utf-8")
    if "/assets/scanruler/assets/" not in index:
        raise RuntimeError("ScanRuler index.html was not built with --base=/assets/scanruler/")
    for match in re.findall(r'(?:src|href)="(/[^"]*)"', index):
        if not match.startswith("/assets/scanruler/"):
            raise RuntimeError("ScanRuler index.html has a root-absolute URL outside the bundle: " + match)

    # WASM: WebAssembly.instantiateStreaming needs a real wasm binary; both runtime kernels
    # must be present (OCCT for STEP, Manifold for booleans).
    wasm_files = sorted(root.glob("assets/*.wasm"))
    if len(wasm_files) != 2:
        raise RuntimeError("ScanRuler wasm set changed: expected occt-wasm and manifold wasm")
    occt = [path for path in wasm_files if path.name.startswith("occt-wasm-")]
    manifold = [path for path in wasm_files if path.name.startswith("manifold-")]
    if len(occt) != 1 or len(manifold) != 1:
        raise RuntimeError("ScanRuler wasm names changed: " + ", ".join(p.name for p in wasm_files))
    for path, minimum in ((occt[0], 20_000_000), (manifold[0], 400_000)):
        if path.stat().st_size < minimum:
            raise RuntimeError(f"ScanRuler {path.name} is smaller than expected")
        header = path.read_bytes()[:8]
        if header[:4] != b"\x00asm":
            raise RuntimeError(f"ScanRuler {path.name} is not a wasm binary")

    # The page spawns module workers by relative asset names; all three must be in the bundle.
    for prefix in ("meshWorker-", "reWorker-", "reHelperWorker-"):
        exactly(root, f"assets/{prefix}*.js", 1, prefix)

    # The main bundle and stylesheet are what index.html loads; a missing build is a failure.
    # (Several plugin chunks are also named index-*.js, so take the one the page loads.)
    scripts = re.findall(r'<script[^>]+src="(/assets/scanruler/[^"]+\.js)"', index)
    if len(scripts) != 1:
        raise RuntimeError("ScanRuler index.html does not load exactly one script: " + repr(scripts))
    main_js = root / scripts[0].split("/assets/scanruler/", 1)[1]
    if not main_js.is_file() or main_js.stat().st_size < 3_000_000:
        raise RuntimeError("ScanRuler main bundle is missing or smaller than expected")
    styles = re.findall(r'<link[^>]+href="(/assets/scanruler/[^"]+\.css)"', index)
    if len(styles) != 1:
        raise RuntimeError("ScanRuler index.html does not load exactly one stylesheet: " + repr(styles))
    main_css = root / styles[0].split("/assets/scanruler/", 1)[1]
    if not main_css.is_file() or main_css.stat().st_size < 50_000:
        raise RuntimeError("ScanRuler stylesheet is missing or smaller than expected")

    # Fonts and their OFL texts are shipped in the bundle (the only third-party notices the
    # upstream dist carries).
    exactly(root, "fonts/*.woff2", 8, "font")
    exactly(root, "fonts/OFL-*.txt", 3, "font licence")

    # The upstream dist/samples/ directory (the 4.2 MB fridge-bracket 3MF used only by the
    # repo's e2e test script) is deliberately not shipped; the page does not reference it.
    if (root / "samples").exists():
        raise RuntimeError("ScanRuler samples/ must not be staged in the APK bundle")

    if len(files(root)) < 55:
        raise RuntimeError("ScanRuler asset directory has too few files; the bundle is incomplete")


def verify(root: pathlib.Path) -> None:
    validate_required(root)
    manifest = root / MANIFEST
    if not manifest.is_file():
        raise RuntimeError("ScanRuler hash manifest is missing; run scripts/fetch-scanruler-assets.sh")
    parsed: dict[str, str] = {}
    for raw in manifest.read_text(encoding="utf-8").splitlines():
        expected, separator, relative = raw.partition("  ")
        if not separator or len(expected) != 64 or relative in parsed:
            raise RuntimeError(f"Invalid ScanRuler hash entry: {raw}")
        path = (root / pathlib.PurePosixPath(relative)).resolve()
        if path != root.resolve() and root.resolve() not in path.parents:
            raise RuntimeError(f"Unsafe ScanRuler hash path: {relative}")
        parsed[relative] = expected

    actual_paths = {path.relative_to(root).as_posix(): path for path in files(root)}
    if set(parsed) != set(actual_paths):
        missing = sorted(set(parsed) - set(actual_paths))
        untracked = sorted(set(actual_paths) - set(parsed))
        raise RuntimeError(
            "ScanRuler asset set changed; missing=" + ",".join(missing[:8])
            + " untracked=" + ",".join(untracked[:8])
        )
    for relative, path in actual_paths.items():
        if sha256(path) != parsed[relative]:
            raise RuntimeError(f"ScanRuler asset hash mismatch: {relative}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, type=pathlib.Path)
    parser.add_argument("--write", action="store_true", help="rewrite SHA256SUMS from the staged tree")
    parser.add_argument("--check-only", action="store_true", help="verify the committed manifest")
    args = parser.parse_args()
    root = args.root.resolve()
    if not root.is_dir():
        raise RuntimeError(f"ScanRuler asset directory is missing: {root}")
    if args.write and args.check_only:
        raise RuntimeError("--write and --check-only are exclusive")
    validate_required(root)
    if args.write:
        (root / MANIFEST).write_text("\n".join(entries(root)) + "\n", encoding="utf-8")
    verify(root)
    print(f"Verified {len(files(root))} ScanRuler files at {root / MANIFEST}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"ScanRuler asset verification failed: {error}", file=sys.stderr)
        raise