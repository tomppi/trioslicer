#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Create or verify the complete deterministic BumpMesh asset manifest.

The workspace assets (HTML/JS/vendor and the web UI's own textures) come from the pinned
BumpMesh commit. The texture library under library/ is generated from a second, explicitly
pinned upstream commit and carries the full catalogue manifest plus its credits; this verifier
checks that manifest against the staged tree, and then hashes every file in the workspace so a
warm or repaired build cannot ship a partial or altered library.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys

MANIFEST = "SHA256SUMS"
SOURCE_MARKER = ".source-version"
LIBRARY = "library"
TEXTURES_COMMIT = "c46678436001b622f71ebeff763c16c186049316"
TEXTURE_COUNT = 101
EXPECTED_CATEGORIES = {
    "geometric",
    "patterns",
    "organic",
    "fabric",
    "natural",
    "grip",
    "mold",
    "tires",
}
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
    "library/manifest.json",
    "library/CREDITS.md",
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


def validate_library(root: pathlib.Path) -> None:
    library = root / LIBRARY
    manifest_path = library / "manifest.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise RuntimeError(f"BumpMesh texture library manifest is unreadable: {error}") from error
    if not isinstance(manifest, dict):
        raise RuntimeError("BumpMesh texture library manifest is not a JSON object")
    if manifest.get("format") != 1:
        raise RuntimeError("BumpMesh texture library manifest has an unexpected format")
    if manifest.get("count") != TEXTURE_COUNT:
        raise RuntimeError(
            f"BumpMesh texture library count is {manifest.get('count')}, expected {TEXTURE_COUNT}"
        )
    source = manifest.get("source") or {}
    if source.get("commit") != TEXTURES_COMMIT:
        raise RuntimeError("BumpMesh texture library is not from the pinned texture commit")
    if source.get("pipeline_license") != "AGPL-3.0-only":
        raise RuntimeError("BumpMesh texture library does not record the pipeline licence")
    if manifest.get("asset_root") != "bumpmesh/library/":
        raise RuntimeError("BumpMesh texture library does not name its AssetManager asset root")
    if not isinstance(manifest.get("generated_by"), str) or not manifest["generated_by"]:
        raise RuntimeError("BumpMesh texture library does not record its generator")

    categories = manifest.get("categories") or []
    category_ids = {
        entry.get("id") for entry in categories if isinstance(entry, dict)
    }
    if category_ids != EXPECTED_CATEGORIES:
        raise RuntimeError(
            "BumpMesh texture library categories changed: " + ",".join(sorted(map(str, category_ids)))
        )
    credits = manifest.get("credits") or {}
    expected_credit_licences = {"hero": "CC-BY-4.0", "cc0": "CC0-1.0"}
    for credit in ("hero", "cc0", "ff"):
        entry = credits.get(credit) or {}
        attribution = entry.get("attribution")
        if not isinstance(attribution, str) or not attribution:
            raise RuntimeError(f"BumpMesh texture library is missing {credit} attribution")
        if credit in expected_credit_licences and entry.get("license") != expected_credit_licences[credit]:
            raise RuntimeError(f"BumpMesh texture library has an unexpected {credit} licence")

    textures = manifest.get("textures")
    if not isinstance(textures, list) or len(textures) != TEXTURE_COUNT:
        raise RuntimeError(
            f"BumpMesh texture library lists {len(textures) if isinstance(textures, list) else 'no'} "
            f"textures, expected {TEXTURE_COUNT}"
        )
    listed_images: set[str] = set()
    listed_thumbs: set[str] = set()
    names: set[str] = set()
    for entry in textures:
        if not isinstance(entry, dict):
            raise RuntimeError("BumpMesh texture library entry is not an object")
        name = entry.get("name")
        relative = entry.get("file")
        thumb = entry.get("thumb")
        category = entry.get("category")
        if not all(isinstance(value, str) and value for value in (name, relative, thumb, category)):
            raise RuntimeError("BumpMesh texture library entry is missing name, file, thumb or category")
        if name in names:
            raise RuntimeError(f"BumpMesh texture library duplicates display name: {name}")
        names.add(name)
        if category not in category_ids:
            raise RuntimeError(f"BumpMesh texture library entry has an unknown category: {category}")
        if entry.get("credit") not in (None, "hero", "cc0", "ff"):
            raise RuntimeError(f"BumpMesh texture library entry has an unknown credit: {entry.get('credit')}")
        if not relative.startswith("textures/") or "/" in relative.removeprefix("textures/"):
            raise RuntimeError(f"BumpMesh texture library entry has an unexpected file path: {relative}")
        if not thumb.startswith("textures/thumbs/") or "/" in thumb.removeprefix("textures/thumbs/"):
            raise RuntimeError(f"BumpMesh texture library entry has an unexpected thumb path: {thumb}")
        # The gallery opens these AssetManager paths directly, so a manifest entry that
        # points at a missing or empty file is a gallery bug even when the hash manifest has
        # no say in it.
        for asset, kind in ((relative, "texture"), (thumb, "thumbnail")):
            asset_path = library / asset
            if not asset_path.is_file():
                raise RuntimeError(
                    f"BumpMesh texture library manifest {kind} is missing from the staged assets: {asset}"
                )
            if asset_path.stat().st_size == 0:
                raise RuntimeError(
                    f"BumpMesh texture library manifest {kind} is empty: {asset}"
                )
        listed_images.add(relative)
        listed_thumbs.add(thumb)
    if len(listed_images) != TEXTURE_COUNT or len(listed_thumbs) != TEXTURE_COUNT:
        raise RuntimeError("BumpMesh texture library does not list one image and one thumb per entry")

    staged_images = {
        path.relative_to(library).as_posix()
        for path in (library / "textures").iterdir()
        if path.is_file()
    }
    staged_thumbs = {
        path.relative_to(library).as_posix()
        for path in (library / "textures" / "thumbs").iterdir()
        if path.is_file()
    }
    if staged_images != listed_images:
        missing = sorted(listed_images - staged_images)
        untracked = sorted(staged_images - listed_images)
        raise RuntimeError(
            "BumpMesh texture library images changed; missing="
            + ",".join(missing[:8])
            + " untracked="
            + ",".join(untracked[:8])
        )
    if staged_thumbs != listed_thumbs:
        missing = sorted(listed_thumbs - staged_thumbs)
        untracked = sorted(staged_thumbs - listed_thumbs)
        raise RuntimeError(
            "BumpMesh texture library thumbnails changed; missing="
            + ",".join(missing[:8])
            + " untracked="
            + ",".join(untracked[:8])
        )


def validate_required(root: pathlib.Path) -> None:
    missing = [relative for relative in REQUIRED_FILES if not (root / relative).is_file()]
    if missing:
        raise RuntimeError("BumpMesh runtime files are missing: " + ", ".join(missing))
    marker = (root / SOURCE_MARKER).read_text(encoding="utf-8")
    required_marker_lines = (
        "format=4",
        "BumpMesh=a6ac179149b8a17c71a9469dd4cb6f866c0c01d1",
        "textures=c46678436001b622f71ebeff763c16c186049316",
        "three=r170",
        "fflate=0.8.2",
        "meshstep=0.1.0",
    )
    for line in required_marker_lines:
        if line not in marker.splitlines():
            raise RuntimeError(f"BumpMesh source marker is missing: {line}")
    validate_library(root)


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
    # Removing both files forces the Gradle producer to restore the pinned trees
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
