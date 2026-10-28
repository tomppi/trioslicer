#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Create or verify the hash manifest of the bundled Mesh-Mix-S2C engine.

The engine is vendored as Python source (upstream v1.6.0 at the pinned commit) plus
TrioSlicer's launcher and OCP 7.9 compatibility module. This script is the asset gate:
it checks the pin marker, the required files and the module list, proves the launcher
installs the shim, and hashes every file into SHA256SUMS (or verifies the committed
manifest, which is what CI uses).

    python3 scripts/verify-mesh2step-assets.py --root app/src/main/assets/mesh2step --check-only
    python3 scripts/verify-mesh2step-assets.py --root app/src/main/assets/mesh2step --write
"""

from __future__ import annotations

import argparse
import hashlib
import pathlib
import sys

MANIFEST = "SHA256SUMS"
SOURCE_MARKER = ".source-version"

PIN = {
    "format": "1",
    "engine": "1.6.0",
    "source": "STL2CURVES-MMM/Mesh-Mix-S2C",
    "tag": "v1.6.0",
    "commit": "4cf72ea5900f40d99116bda7877a8e7550ec604f",
    "accelerator": "pynanoinstantmeshes",
    "accelerator_version": "0.0.3",
    "accelerator_source_sha256": (
        "d827eba612753a4bad6e8f644b8cc6c195e47f4afc6936823c0be5c47bb3c23f"
    ),
    "accelerator_build": "android-24-arm64-cpython-311",
}

MMM_MODULES = (
    "__init__",
    "brep",
    "cad",
    "cli",
    "fidelity",
    "fillet",
    "fitting",
    "freeform",
    "meshcheck",
    "meshio",
    "patchmerge",
    "pipeline",
    "planar",
    "repair",
    "report",
    "smooth",
    "stepio",
    "sweep",
    "threemf",
    "topology",
)
REQUIRED_FILES = (
    "mmm_engine.py",
    "launcher.py",
    "ocp_compat.py",
    "engine_compat.py",
    "VERSION",
    "LICENSE",
    "THIRD_PARTY_NOTICES.md",
    "SOURCE.md",
    SOURCE_MARKER,
    "lib/pynanoinstantmeshes/__init__.py",
    "lib/pynanoinstantmeshes/licenses/INSTANT-MESHES.txt",
    "lib/pynanoinstantmeshes/licenses/EIGEN-MPL2.txt",
    "lib/pynanoinstantmeshes/licenses/TBB-APACHE-2.0.txt",
    "lib/pynanoinstantmeshes/licenses/PYNANOINSTANTMESHES.txt",
) + tuple("mmm/" + module + ".py" for module in MMM_MODULES)

# The one optional accelerator we ship: the Smooth preset's quad remesher, cross-built
# for the app's own CPython 3.11 / bionic / arm64. It is a drop-in import for unmodified
# engine code; see SOURCE.md and scripts/build-pynanoinstantmeshes-android.sh.
ACCELERATOR_DIR = "lib/pynanoinstantmeshes"
ACCELERATOR_MODULE = ACCELERATOR_DIR + "/_pynim.cpython-311.so"
ACCELERATOR_PACKAGE = ACCELERATOR_DIR + "/__init__.py"

# The names Mesh-Mix-S2C imports from OCP.collections (OCP 8.0); the shim maps them
# onto OCP 7.9 generic collection classes. A missing name is an engine import failure.
SHIM_NAMES = (
    "Array1_gp_Pnt",
    "Array2_gp_Pnt",
    "Array1_double",
    "Array1_int",
    "Array1_gp_Pnt2d",
    "HArray1_gp_Pnt",
    "HArray1_gp_Pnt2d",
    "HArray1_double",
    "IndexedMap_TopoDS_Shape_TopTools_ShapeMapHasher",
    "Sequence_TDF_Label",
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
        raise RuntimeError("Mesh-Mix-S2C asset directory is empty")
    return result


def validate_required(root: pathlib.Path) -> None:
    missing = [relative for relative in REQUIRED_FILES if not (root / relative).is_file()]
    if missing:
        raise RuntimeError("Mesh-Mix-S2C engine files are missing: " + ", ".join(missing))

    marker = {}
    for raw in (root / SOURCE_MARKER).read_text(encoding="utf-8").splitlines():
        key, separator, value = raw.partition("=")
        if separator:
            marker[key.strip()] = value.strip()
    for key, expected in PIN.items():
        if marker.get(key) != expected:
            raise RuntimeError(
                f"Mesh-Mix-S2C pin marker has {key}={marker.get(key)!r}, expected {expected!r}"
            )

    version = (root / "VERSION").read_text(encoding="utf-8").strip()
    if version != PIN["engine"]:
        raise RuntimeError(f"Mesh-Mix-S2C VERSION is {version!r}, expected {PIN['engine']!r}")

    shim = (root / "ocp_compat.py").read_text(encoding="utf-8")
    missing_names = [name for name in SHIM_NAMES if name not in shim]
    if missing_names:
        raise RuntimeError("OCP compatibility shim is missing: " + ", ".join(missing_names))
    if "OCP.collections" not in shim:
        raise RuntimeError("OCP compatibility shim does not install OCP.collections")

    launcher = (root / "launcher.py").read_text(encoding="utf-8")
    if "ocp_compat" not in launcher or "install()" not in launcher:
        raise RuntimeError("Mesh-Mix-S2C launcher does not install the OCP shim")
    if "engine_compat" not in launcher:
        raise RuntimeError("Mesh-Mix-S2C launcher does not install the STEP reader compat")
    if "mmm.cli" not in launcher:
        raise RuntimeError("Mesh-Mix-S2C launcher does not start the engine CLI")
    engine_compat = (root / "engine_compat.py").read_text(encoding="utf-8")
    for needle in ("read_step", "TransferRoots", "read_step_named", "BRepExtrema_DistShapeShape", "PerformDist"):
        if needle not in engine_compat:
            raise RuntimeError("STEP reader compatibility module is missing " + needle)
    engine = (root / "mmm_engine.py").read_text(encoding="utf-8")
    if "mmm.cli" not in engine:
        raise RuntimeError("mmm_engine.py does not start the engine CLI")

    accelerator = root / ACCELERATOR_MODULE
    if not accelerator.is_file():
        raise RuntimeError("PyNanoInstantMeshes accelerator is missing: " + ACCELERATOR_MODULE)
    staged_modules = sorted(path.name for path in (root / ACCELERATOR_DIR).glob("_pynim*.so"))
    if staged_modules != ["_pynim.cpython-311.so"]:
        raise RuntimeError(
            "PyNanoInstantMeshes module set changed: staged=" + ",".join(staged_modules)
        )
    header = accelerator.read_bytes()[:20]
    if len(header) < 20 or header[:4] != b"\x7fELF":
        raise RuntimeError("PyNanoInstantMeshes accelerator is not an ELF file")
    if header[4] != 2 or header[5] != 1:
        raise RuntimeError("PyNanoInstantMeshes accelerator must be 64-bit little-endian ELF")
    if int.from_bytes(header[16:18], "little") != 3:
        raise RuntimeError("PyNanoInstantMeshes accelerator is not a shared object")
    if int.from_bytes(header[18:20], "little") != 183:
        raise RuntimeError("PyNanoInstantMeshes accelerator is not AArch64")
    package = (root / ACCELERATOR_PACKAGE).read_text(encoding="utf-8")
    if "from ._pynim import remesh" not in package:
        raise RuntimeError("PyNanoInstantMeshes package does not expose remesh")
    if 'os.path.join(here, "lib")' not in launcher:
        raise RuntimeError("Mesh-Mix-S2C launcher does not add the bundled accelerator directory")

    # Compiled bytecode is a build-host artefact (and the wrong CPython tag); shipping it
    # would also make the asset set depend on what ran the verifier.
    bytecode = sorted(str(path) for path in root.rglob("*.pyc")) + sorted(
        str(path) for path in root.rglob("__pycache__") if path.is_dir()
    )
    if bytecode:
        raise RuntimeError(
            "Mesh-Mix-S2C assets contain compiled Python bytecode: " + ", ".join(bytecode[:4])
        )

    # The engine modules are vendored byte-for-byte; a stray extra or renamed module
    # would change what runs without failing a hash of the files that are present.
    staged_modules = sorted(
        path.name for path in (root / "mmm").glob("*.py")
    )
    expected_modules = sorted(module + ".py" for module in MMM_MODULES)
    if staged_modules != expected_modules:
        raise RuntimeError(
            "Mesh-Mix-S2C module set changed: staged=" + ",".join(staged_modules)
        )


def verify(root: pathlib.Path) -> None:
    validate_required(root)
    manifest = root / MANIFEST
    if not manifest.is_file():
        raise RuntimeError(
            "Mesh-Mix-S2C hash manifest is missing; run scripts/stage-mesh2step-engine.sh"
        )
    parsed: dict[str, str] = {}
    for raw in manifest.read_text(encoding="utf-8").splitlines():
        expected, separator, relative = raw.partition("  ")
        if not separator or len(expected) != 64 or relative in parsed:
            raise RuntimeError(f"Invalid Mesh-Mix-S2C hash entry: {raw}")
        path = (root / pathlib.PurePosixPath(relative)).resolve()
        if path != root.resolve() and root.resolve() not in path.parents:
            raise RuntimeError(f"Unsafe Mesh-Mix-S2C hash path: {relative}")
        parsed[relative] = expected

    actual_paths = {path.relative_to(root).as_posix(): path for path in files(root)}
    if set(parsed) != set(actual_paths):
        missing = sorted(set(parsed) - set(actual_paths))
        untracked = sorted(set(actual_paths) - set(parsed))
        raise RuntimeError(
            "Mesh-Mix-S2C asset set changed; missing="
            + ",".join(missing[:8])
            + " untracked="
            + ",".join(untracked[:8])
        )
    for relative, path in actual_paths.items():
        if sha256(path) != parsed[relative]:
            raise RuntimeError(f"Mesh-Mix-S2C asset hash mismatch: {relative}")


def write_manifest(root: pathlib.Path) -> None:
    validate_required(root)
    (root / MANIFEST).write_text("\n".join(entries(root)) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", required=True, type=pathlib.Path)
    parser.add_argument(
        "--write",
        action="store_true",
        help="rewrite SHA256SUMS from the staged tree (the staging script does this)",
    )
    parser.add_argument(
        "--check-only",
        action="store_true",
        help="verify the committed manifest; never rewrite it (CI and Gradle use this)",
    )
    args = parser.parse_args()
    root = args.root.resolve()
    if not root.is_dir():
        raise RuntimeError(f"Mesh-Mix-S2C asset directory is missing: {root}")
    if args.write and args.check_only:
        raise RuntimeError("--write and --check-only are exclusive")

    validate_required(root)
    if args.write:
        (root / MANIFEST).write_text("\n".join(entries(root)) + "\n", encoding="utf-8")
    verify(root)
    count = len(files(root))
    print(f"Verified {count} Mesh-Mix-S2C engine files at {root / MANIFEST}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"Mesh-Mix-S2C asset verification failed: {error}", file=sys.stderr)
        raise