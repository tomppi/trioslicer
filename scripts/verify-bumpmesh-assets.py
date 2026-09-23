#!/usr/bin/env python3
"""Create or verify the complete deterministic BumpMesh asset manifest."""

from __future__ import annotations

import argparse
import hashlib
import pathlib
import sys

MANIFEST = "SHA256SUMS"
SOURCE_MARKER = ".source-version"
REQUIRED_FILES = (
    SOURCE_MARKER,
    "index.html",
    "style.css",
    "LICENSE",
    "android-bridge.js",
    "js/main.js",
    "js/stepWorker.js",
    "js/threeCompat.js",
    "vendor/three/build/three.module.js",
    "vendor/three/LICENSE",
    "vendor/fflate/esm/browser.js",
    "vendor/fflate/LICENSE",
    "vendor/meshstep/dist/index.js",
    "vendor/meshstep/src/index.ts",
    "vendor/meshstep/LICENSE",
)


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def files(root: pathlib.Path) -> list[pathlib.Path]:
    return sorted(
        path
        for path in root.rglob("*")
        if path.is_file() and path.name != MANIFEST
    )


def entries(root: pathlib.Path) -> list[str]:
    result = []
    for path in files(root):
        relative = path.relative_to(root).as_posix()
        result.append(f"{sha256(path)}  {relative}")
    if not result:
        raise RuntimeError("BumpMesh asset workspace is empty")
    return result


def validate_required(root: pathlib.Path) -> None:
    missing = [relative for relative in REQUIRED_FILES if not (root / relative).is_file()]
    if missing:
        raise RuntimeError("BumpMesh runtime files are missing: " + ", ".join(missing))
    marker = (root / SOURCE_MARKER).read_text(encoding="utf-8")
    required_marker_lines = (
        "format=2",
        "BumpMesh=a6ac179149b8a17c71a9469dd4cb6f866c0c01d1",
        "three=r170",
        "fflate=0.8.2",
        "meshstep=0.1.0",
    )
    for line in required_marker_lines:
        if line not in marker.splitlines():
            raise RuntimeError(f"BumpMesh source marker is missing: {line}")


def verify(root: pathlib.Path) -> None:
    validate_required(root)
    manifest = root / MANIFEST
    if not manifest.is_file():
        raise RuntimeError("BumpMesh hash manifest is missing")
    parsed: dict[str, str] = {}
    for raw in manifest.read_text(encoding="utf-8").splitlines():
        expected, separator, relative = raw.partition("  ")
        if not separator or len(expected) != 64 or relative in parsed:
            raise RuntimeError(f"Invalid BumpMesh hash entry: {raw}")
        path = (root / pathlib.PurePosixPath(relative)).resolve()
        if path != root.resolve() and root.resolve() not in path.parents:
            raise RuntimeError(f"Unsafe BumpMesh hash path: {relative}")
        parsed[relative] = expected

    actual_paths = {
        path.relative_to(root).as_posix(): path
        for path in files(root)
    }
    if set(parsed) != set(actual_paths):
        missing = sorted(set(parsed) - set(actual_paths))
        untracked = sorted(set(actual_paths) - set(parsed))
        raise RuntimeError(
            "BumpMesh asset set changed; missing="
            + ",".join(missing[:8])
            + " untracked="
            + ",".join(untracked[:8])
        )
    for relative, path in actual_paths.items():
        if sha256(path) != parsed[relative]:
            raise RuntimeError(f"BumpMesh asset hash mismatch: {relative}")


def invalidate(root: pathlib.Path) -> None:
    # Removing both files forces the Gradle producer to restore the pinned tree
    # and then lets this verifier create a fresh manifest. This also recovers
    # from a damaged manifest instead of repeatedly failing on the same bytes.
    (root / SOURCE_MARKER).unlink(missing_ok=True)
    (root / MANIFEST).unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, type=pathlib.Path)
    parser.add_argument("--check-only", action="store_true")
    args = parser.parse_args()
    root = args.root.resolve()
    if not root.is_dir():
        raise RuntimeError(f"BumpMesh asset directory is missing: {root}")

    try:
        if args.check_only:
            verify(root)
        else:
            validate_required(root)
            manifest = root / MANIFEST
            if not manifest.is_file():
                manifest.write_text("\n".join(entries(root)) + "\n", encoding="utf-8")
            verify(root)
    except Exception:
        invalidate(root)
        raise
    print(f"Verified complete BumpMesh asset manifest at {root / MANIFEST}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"BumpMesh asset verification failed: {error}", file=sys.stderr)
        raise
