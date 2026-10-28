#!/usr/bin/env python3
# SPDX-License-Identifier: AGPL-3.0-only
"""Create or verify the hash manifest of the bundled Mesh-Mix-S2C engine.

The engine is vendored as Python source (upstream v1.6.0 at the pinned commit) plus
TrioSlicer's launcher, OCP 7.9 compatibility module and the six optional accelerators the
real program bundles (trimesh, manifold3d, fast_simplification, pymeshfix, rtree +
libspatialindex, pynanoinstantmeshes). This script is the asset gate:
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
    "accelerator_trimesh": "5.1.1",
    "accelerator_trimesh_source_sha256": (
        "c5be85a3e31e9ff749f9b0aecc204e37a2cf7cca4e92cd066bb5f3d3c1127765"
    ),
    "accelerator_trimesh_build": "pure-python",
    "accelerator_manifold3d": "3.5.4",
    "accelerator_manifold3d_source_sha256": (
        "5bd88c482c6fd7fc01aa7b715b3e31c2585ee98343d5559d286ba30db0d0b6f0"
    ),
    "accelerator_manifold3d_build": "android-24-arm64-cpython-311",
    "accelerator_fast_simplification": "0.2.0",
    "accelerator_fast_simplification_source_sha256": (
        "ed02ea6f4968ec98f963f2d3665dbafd0297ec12aea9e4d0a87c4b3c14d608a8"
    ),
    "accelerator_fast_simplification_build": "android-24-arm64-cpython-311",
    "accelerator_pymeshfix": "0.18.1",
    "accelerator_pymeshfix_source_sha256": (
        "c3386a123e3ecfcff59f229a4fc620ae0e03fa9c7fec025fbd2885349fa8fa3f"
    ),
    "accelerator_pymeshfix_build": "android-24-arm64-cpython-311",
    "accelerator_rtree": "1.4.1",
    "accelerator_rtree_source_sha256": (
        "c6b1b3550881e57ebe530cc6cffefc87cd9bf49c30b37b894065a9f810875e46"
    ),
    "accelerator_rtree_build": "android-24-arm64-cpython-311",
    "accelerator_rtree_libspatialindex": "2.1.0",
    "accelerator_rtree_libspatialindex_sha256": (
        "a04513cea04dd20ab2c9d153c14cc45692805ee496b30619103f7929f6fb81bf"
    ),
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
    "lib/trimesh/__init__.py",
    "lib/manifold3d.cpython-311.so",
    "lib/fast_simplification/__init__.py",
    "lib/fast_simplification/_simplify.cpython-311.so",
    "lib/fast_simplification/_replay.cpython-311.so",
    "lib/pymeshfix/__init__.py",
    "lib/pymeshfix/_meshfix.cpython-311.so",
    "lib/rtree/__init__.py",
    "lib/rtree/lib/libspatialindex_c.so",
    "lib/rtree/lib/libspatialindex.so",
    "lib/LICENSES/trimesh-MIT.txt",
    "lib/LICENSES/manifold3d-Apache-2.0.txt",
    "lib/LICENSES/manifold3d-AUTHORS.txt",
    "lib/LICENSES/fast_simplification-MIT.txt",
    "lib/LICENSES/meshfix-GPL-3.0.txt",
    "lib/LICENSES/pymeshfix-AGPL-3.0.txt",
    "lib/LICENSES/rtree-MIT.txt",
    "lib/LICENSES/libspatialindex-MIT.txt",
) + tuple("mmm/" + module + ".py" for module in MMM_MODULES)

# The six optional accelerators the real program bundles, all cross-built (or vendored
# pure Python) for the app's own CPython 3.11 / bionic / arm64. They are drop-in imports
# for unmodified engine code; see SOURCE.md and scripts/build-*-android.sh.
ACCELERATOR_DIR = "lib/pynanoinstantmeshes"
ACCELERATOR_MODULE = ACCELERATOR_DIR + "/_pynim.cpython-311.so"
ACCELERATOR_PACKAGE = ACCELERATOR_DIR + "/__init__.py"

COMPILED_ACCELERATORS = (
    "lib/manifold3d.cpython-311.so",
    "lib/fast_simplification/_simplify.cpython-311.so",
    "lib/fast_simplification/_replay.cpython-311.so",
    "lib/pymeshfix/_meshfix.cpython-311.so",
    "lib/rtree/lib/libspatialindex_c.so",
    "lib/rtree/lib/libspatialindex.so",
)

ACCELERATOR_LICENCES = {
    "lib/LICENSES/trimesh-MIT.txt": "MIT",
    "lib/LICENSES/manifold3d-Apache-2.0.txt": "Apache License",
    "lib/LICENSES/fast_simplification-MIT.txt": "MIT",
    "lib/LICENSES/meshfix-GPL-3.0.txt": "GNU GENERAL PUBLIC LICENSE",
    "lib/LICENSES/pymeshfix-AGPL-3.0.txt": "GNU AFFERO GENERAL PUBLIC LICENSE",
    "lib/LICENSES/rtree-MIT.txt": "MIT",
    "lib/LICENSES/libspatialindex-MIT.txt": "MIT",
}

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


def check_elf_shared_object(root: pathlib.Path, relative: str) -> None:
    """Every compiled accelerator must be an AArch64 shared object; a host or x86 build
    would import nowhere on the phone and is not caught by a hash of a wrong file."""
    header = (root / relative).read_bytes()[:20]
    if len(header) < 20 or header[:4] != b"\x7fELF":
        raise RuntimeError(relative + " is not an ELF file")
    if header[4] != 2 or header[5] != 1:
        raise RuntimeError(relative + " is not a 64-bit little-endian ELF")
    if int.from_bytes(header[16:18], "little") != 3:
        raise RuntimeError(relative + " is not a shared object")
    if int.from_bytes(header[18:20], "little") != 183:
        raise RuntimeError(relative + " is not AArch64")


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

    for relative in COMPILED_ACCELERATORS:
        check_elf_shared_object(root, relative)

    fast_modules = sorted(path.name for path in (root / "lib/fast_simplification").glob("_*.so"))
    if fast_modules != ["_replay.cpython-311.so", "_simplify.cpython-311.so"]:
        raise RuntimeError("fast_simplification module set changed: " + ",".join(fast_modules))
    pymeshfix_modules = sorted(path.name for path in (root / "lib/pymeshfix").glob("_*.so"))
    if pymeshfix_modules != ["_meshfix.cpython-311.so"]:
        raise RuntimeError("pymeshfix module set changed: " + ",".join(pymeshfix_modules))
    rtree_libraries = sorted(path.name for path in (root / "lib/rtree/lib").glob("*.so"))
    if rtree_libraries != ["libspatialindex.so", "libspatialindex_c.so"]:
        raise RuntimeError("rtree library set changed: " + ",".join(rtree_libraries))

    package_imports = (
        ("lib/fast_simplification/__init__.py", "from .simplify import"),
        ("lib/pymeshfix/__init__.py", "from pymeshfix._meshfix import"),
        ("lib/rtree/finder.py", "libspatialindex_c"),
        ("lib/trimesh/__init__.py", "trimesh"),
    )
    for relative, needle in package_imports:
        if needle not in (root / relative).read_text(encoding="utf-8"):
            raise RuntimeError(relative + " does not expose its expected entry point")
    for relative, marker_text in ACCELERATOR_LICENCES.items():
        if marker_text not in (root / relative).read_text(encoding="utf-8", errors="replace"):
            raise RuntimeError(relative + " does not contain the expected licence text")

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