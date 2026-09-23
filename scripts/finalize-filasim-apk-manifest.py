#!/usr/bin/env python3
"""Finalize filaSim assets for Android APK packaging.

Android's asset packager omits hidden dotfiles. The build cache deliberately
uses `.source-version`, but including that path in SHA256SUMS makes the manifest
impossible to verify after installation. Preserve the cache marker locally,
copy its contents to package-safe `source-version.txt`, and hash only files that
can be packaged.
"""

from __future__ import annotations

import argparse
import hashlib
import pathlib

HASH_MANIFEST = "SHA256SUMS"
CACHE_MARKER = ".source-version"
PACKAGE_MARKER = "source-version.txt"


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def is_packageable(relative: pathlib.PurePosixPath) -> bool:
    return all(not part.startswith(".") for part in relative.parts)


def finalize(root: pathlib.Path) -> None:
    root = root.resolve()
    if not root.is_dir():
        raise RuntimeError(f"filaSim asset directory is missing: {root}")

    cache_marker = root / CACHE_MARKER
    if not cache_marker.is_file():
        raise RuntimeError(f"filaSim cache marker is missing: {cache_marker}")
    marker_text = cache_marker.read_text(encoding="utf-8")
    if not marker_text.endswith("\n"):
        raise RuntimeError("filaSim cache marker must end with a newline")
    package_marker = root / PACKAGE_MARKER
    package_marker.write_text(marker_text, encoding="utf-8")
    if package_marker.read_bytes() != cache_marker.read_bytes():
        raise RuntimeError("Package-safe filaSim source marker did not verify byte-for-byte")

    entries: list[str] = []
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.name == HASH_MANIFEST:
            continue
        relative = pathlib.PurePosixPath(path.relative_to(root).as_posix())
        if not is_packageable(relative):
            continue
        entries.append(f"{sha256_file(path)}  {relative.as_posix()}")
    if not entries:
        raise RuntimeError("filaSim packageable asset workspace is empty")

    manifest = root / HASH_MANIFEST
    manifest.write_text("\n".join(entries) + "\n", encoding="utf-8")

    seen: set[str] = set()
    for raw_line in manifest.read_text(encoding="utf-8").splitlines():
        expected, separator, relative_text = raw_line.partition("  ")
        if not separator or len(expected) != 64 or relative_text in seen:
            raise RuntimeError(f"Invalid filaSim asset hash entry: {raw_line}")
        seen.add(relative_text)
        relative = pathlib.PurePosixPath(relative_text)
        if not is_packageable(relative):
            raise RuntimeError(f"Unpackageable filaSim asset was hashed: {relative_text}")
        path = (root / pathlib.Path(*relative.parts)).resolve()
        if root not in path.parents or not path.is_file() or sha256_file(path) != expected:
            raise RuntimeError(f"filaSim package hash mismatch: {relative_text}")

    if PACKAGE_MARKER not in seen:
        raise RuntimeError("Package-safe filaSim source marker is absent from SHA256SUMS")
    if CACHE_MARKER in seen:
        raise RuntimeError("Hidden filaSim cache marker must not appear in SHA256SUMS")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, type=pathlib.Path)
    args = parser.parse_args()
    finalize(args.root)
