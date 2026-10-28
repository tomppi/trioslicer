#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Builds and stages the Mesh-Mix-S2C optional accelerators for the app's Android CPython
# 3.11 / arm64, matching the real program's bundled set:
#   trimesh 5.1.1              pure Python, vendored from the pinned wheel
#   manifold3d 3.5.4           CMake + nanobind, Manifold sources
#   fast_simplification 0.2.0  CMake + nanobind, two modules
#   pymeshfix 0.18.1           CMake + nanobind, MeshFix sources
#   rtree 1.4.1                pure Python + libspatialindex 2.1.0 shared libraries
# (pynanoinstantmeshes has its own script; it was the first accelerator shipped.)
#
# Every source is pinned by sha256. All patches apply to work copies only; our assets and
# the upstream sources are never edited. The packages are staged under
# app/src/main/assets/mesh2step/lib/ as drop-in imports for the unmodified engine; run
# scripts/verify-mesh2step-assets.py --write afterwards to refresh SHA256SUMS.
#
# Usage:
#   scripts/build-mesh2step-accelerators-android.sh [all|trimesh|manifold3d|fast_simplification|pymeshfix|rtree]
#   scripts/build-trimesh-android.sh, scripts/build-manifold3d-android.sh, ... are thin wrappers.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets/mesh2step"
LIB="$ASSETS/lib"
PREFIX="${PREFIX:-$ROOT/.build/py311-android}"
HOST="${HOST_PYTHON:-$ROOT/.build/klipper-python/py-host/bin/python3.11}"
WORK="${ACCEL_WORK:-$ROOT/.build/accel-script}"
NDK="${ANDROID_NDK_HOME:-$(ls -d /opt/android-sdk/ndk/* 2>/dev/null | tail -1)}"
mkdir -p "$WORK" "$LIB" "$LIB/LICENSES"

if [ ! -x "$HOST" ] || [ ! -e "$PREFIX/include/python3.11/Python.h" ]; then
  echo "FATAL: run scripts/build-klipper-python-android.sh first" >&2
  exit 1
fi
if [ -z "$NDK" ]; then echo "FATAL: no Android NDK" >&2; exit 1; fi
NB_VERSION="$("$HOST" -c 'import nanobind; print(nanobind.__version__)')"
if [ "$NB_VERSION" != "3.1.0" ]; then
  echo "FATAL: host nanobind is $NB_VERSION, need 3.1.0 (see build-pynanoinstantmeshes-android.sh)" >&2
  exit 1
fi
NB_DIR="$("$HOST" -m nanobind --cmake_dir)"

sha256_of() { if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
fetch() {
  local url="$1" out="$2" expected="$3" actual
  if [ ! -f "$out" ]; then echo "downloading $(basename "$out")"; curl -fL --progress-bar "$url" -o "$out"; fi
  actual="$(sha256_of "$out")"
  if [ "$actual" != "$expected" ]; then echo "FATAL: sha256 mismatch for $out" >&2; echo " expected $expected" >&2; echo " actual   $actual" >&2; exit 1; fi
}

CMAKE_COMMON=(
  -G Ninja
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
  -DANDROID_ABI=arm64-v8a
  -DANDROID_PLATFORM=android-24
  -DANDROID_STL=c++_shared
  -DCMAKE_BUILD_TYPE=Release
  -DCMAKE_CXX_FLAGS="-fPIC -fno-emulated-tls -O2"
  -Dnanobind_DIR="$NB_DIR"
  -DPython_EXECUTABLE="$HOST"
  -DPython_INCLUDE_DIR="$PREFIX/include/python3.11"
  -DPython_LIBRARY="$PREFIX/lib/libpython3.11.so"
  -DPYNIM_LIBPYTHON="$PREFIX/lib/libpython3.11.so"
)

build_trimesh() {
  local wheel="trimesh-5.1.1-py3-none-any.whl"
  local sha="c5be85a3e31e9ff749f9b0aecc204e37a2cf7cca4e92cd066bb5f3d3c1127765"
  local url="https://files.pythonhosted.org/packages/50/1c/2f2d0c9c3f9d2f5a3ba1f6c1a2a6f9f9e9f9c4d4c7f2b5c0a1e5f7a8b9c0/trimesh-5.1.1-py3-none-any.whl"
  echo '=== trimesh 5.1.1 (pure Python) ==='
  if [ ! -f "$WORK/$wheel" ]; then
    url="$(python3 -c "import json,urllib.request; d=json.load(urllib.request.urlopen('https://pypi.org/pypi/trimesh/5.1.1/json')); print(next(u['url'] for u in d['urls'] if u['filename']=='$wheel'))")"
    fetch "$url" "$WORK/$wheel" "$sha"
  fi
  actual="$(sha256_of "$WORK/$wheel")"
  [ "$actual" = "$sha" ] || { echo "FATAL: trimesh wheel sha256 mismatch" >&2; exit 1; }
  rm -rf "$WORK/trimesh-wheel" "$LIB/trimesh"
  python3 -m zipfile -e "$WORK/$wheel" "$WORK/trimesh-wheel"
  cp -a "$WORK/trimesh-wheel/trimesh" "$LIB/trimesh"
  cp "$WORK/trimesh-wheel/trimesh-5.1.1.dist-info/licenses/LICENSE.md" "$LIB/LICENSES/trimesh-MIT.txt"
}

build_fast_simplification() {
  local sdist="fast_simplification-0.2.0.tar.gz"
  local sha="ed02ea6f4968ec98f963f2d3665dbafd0297ec12aea9e4d0a87c4b3c14d608a8"
  local url="https://files.pythonhosted.org/packages/79/32/3d5b0b3f4a2d1c4b2b8e0c9e3f6d0d4a5e0f6b8c9d1e2f3a4b5c6d7e8f/fast_simplification-0.2.0.tar.gz"
  echo '=== fast_simplification 0.2.0 ==='
  if [ ! -f "$WORK/$sdist" ]; then
    url="$(python3 -c "import json,urllib.request; d=json.load(urllib.request.urlopen('https://pypi.org/pypi/fast_simplification/0.2.0/json')); print(next(u['url'] for u in d['urls'] if u['filename']=='$sdist'))")"
    fetch "$url" "$WORK/$sdist" "$sha"
  fi
  rm -rf "$WORK/fast" && mkdir -p "$WORK/fast" && tar xzf "$WORK/$sdist" -C "$WORK/fast" --strip-components=1
  python3 - "$WORK/fast/CMakeLists.txt" <<'PY'
import sys
from pathlib import Path
path = Path(sys.argv[1])
text = path.read_text()
old = '''  nanobind_add_module(
    ${name}
    # Target the stable ABI for Python 3.12+, which reduces the number of
    # binary wheels that must be built. This does nothing on older Python
    # versions.
    STABLE_ABI
    # Conserve space by reusing a shared libnanobind across libraries.
    NB_STATIC
    ${source}
  )'''
assert old in text, 'unexpected fast_simplification nanobind block'
path.write_text(text.replace(old, '''  nanobind_add_module(
    ${name}
    NB_STATIC
    ${source}
  )''', 1))
PY
  printf '\nset_target_properties(_simplify _replay PROPERTIES SUFFIX ".cpython-311.so")\ntarget_link_libraries(_simplify PRIVATE "${PYNIM_LIBPYTHON}")\ntarget_link_libraries(_replay PRIVATE "${PYNIM_LIBPYTHON}")\n' >> "$WORK/fast/CMakeLists.txt"
  rm -rf "$WORK/fast/build"
  cmake -S "$WORK/fast" -B "$WORK/fast/build" "${CMAKE_COMMON[@]}" -DCMAKE_DISABLE_FIND_PACKAGE_OpenMP=TRUE > "$WORK/fast-cmake.log" 2>&1
  cmake --build "$WORK/fast/build" --parallel
  rm -rf "$LIB/fast_simplification" && mkdir -p "$LIB/fast_simplification"
  cp "$WORK/fast/fast_simplification/"*.py "$WORK/fast/fast_simplification/"*.h "$LIB/fast_simplification/"
  cp "$WORK/fast/build/_simplify.cpython-311.so" "$WORK/fast/build/_replay.cpython-311.so" "$LIB/fast_simplification/"
  cp "$WORK/fast/LICENSE" "$LIB/LICENSES/fast_simplification-MIT.txt"
}

build_manifold3d() {
  local sdist="manifold3d-3.5.4.tar.gz"
  local sha="5bd88c482c6fd7fc01aa7b715b3e31c2585ee98343d5559d286ba30db0d0b6f0"
  local url="https://files.pythonhosted.org/packages/67/2b/4d0e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b/manifold3d-3.5.4.tar.gz"
  echo '=== manifold3d 3.5.4 ==='
  if [ ! -f "$WORK/$sdist" ]; then
    url="$(python3 -c "import json,urllib.request; d=json.load(urllib.request.urlopen('https://pypi.org/pypi/manifold3d/3.5.4/json')); print(next(u['url'] for u in d['urls'] if u['filename']=='$sdist'))")"
    fetch "$url" "$WORK/$sdist" "$sha"
  fi
  rm -rf "$WORK/manifold" && mkdir -p "$WORK/manifold" && tar xzf "$WORK/$sdist" -C "$WORK/manifold" --strip-components=1
  python3 - "$WORK/manifold" <<'PY'
import sys
from pathlib import Path
root = Path(sys.argv[1])
path = root / 'bindings/python/CMakeLists.txt'
text = path.read_text()
old = 'nanobind_add_module(manifold3d NB_STATIC STABLE_ABI LTO autogen_docstrings.inl'
assert old in text, 'unexpected manifold3d nanobind block'
text = text.replace(old, 'nanobind_add_module(manifold3d NB_STATIC LTO autogen_docstrings.inl', 1)
old2 = 'target_link_libraries(manifold3d PRIVATE manifold)'
assert old2 in text
text = text.replace(old2, old2 + '\nset_target_properties(manifold3d PROPERTIES SUFFIX ".cpython-311.so")\ntarget_link_libraries(manifold3d PRIVATE "${PYNIM_LIBPYTHON}")', 1)
text = text.replace('if(MANIFOLD_PYBIND_STUBGEN)', 'if(FALSE)')
path.write_text(text)
PY
  rm -rf "$WORK/manifold/build"
  cmake -S "$WORK/manifold" -B "$WORK/manifold/build" "${CMAKE_COMMON[@]}" \
    -DMANIFOLD_PYBIND=ON -DMANIFOLD_CBIND=OFF -DMANIFOLD_CROSS_SECTION=ON \
    -DMANIFOLD_CROSS_SECTION_BACKEND=boolean2 -DMANIFOLD_PAR=OFF -DMANIFOLD_TEST=OFF \
    -DMANIFOLD_DOWNLOADS=OFF -DBUILD_SHARED_LIBS=OFF -DMANIFOLD_ASSERT=OFF > "$WORK/manifold-cmake.log" 2>&1
  cmake --build "$WORK/manifold/build" --parallel
  cp "$WORK/manifold/build/bindings/python/manifold3d.cpython-311.so" "$LIB/"
  cp "$WORK/manifold/LICENSE" "$LIB/LICENSES/manifold3d-Apache-2.0.txt"
  cp "$WORK/manifold/AUTHORS" "$LIB/LICENSES/manifold3d-AUTHORS.txt"
}

build_pymeshfix() {
  local sdist="pymeshfix-0.18.1.tar.gz"
  local sha="c3386a123e3ecfcff59f229a4fc620ae0e03fa9c7fec025fbd2885349fa8fa3f"
  local url="https://files.pythonhosted.org/packages/9d/1e/0c8b2a4f6e8d0c2b4a6f8e0d2c4b6a8f0e2d4c6b8a0f2e4d6c8b0a2f4e6d/pymeshfix-0.18.1.tar.gz"
  echo '=== pymeshfix 0.18.1 ==='
  if [ ! -f "$WORK/$sdist" ]; then
    url="$(python3 -c "import json,urllib.request; d=json.load(urllib.request.urlopen('https://pypi.org/pypi/pymeshfix/0.18.1/json')); print(next(u['url'] for u in d['urls'] if u['filename']=='$sdist'))")"
    fetch "$url" "$WORK/$sdist" "$sha"
  fi
  rm -rf "$WORK/pymeshfix" && mkdir -p "$WORK/pymeshfix" && tar xzf "$WORK/$sdist" -C "$WORK/pymeshfix" --strip-components=1
  python3 - "$WORK/pymeshfix/CMakeLists.txt" <<'PY'
import sys
from pathlib import Path
path = Path(sys.argv[1])
text = path.read_text()
old = '''nanobind_add_module(
  # Name of the extension
  _meshfix

  # Target the stable ABI for Python 3.12+, which reduces
  # the number of binary wheels that must be built. This
  # does nothing on older Python versions
  STABLE_ABI

  src/_meshfix.cpp'''
assert old in text, 'unexpected pymeshfix nanobind block'
text = text.replace(old, '''nanobind_add_module(
  _meshfix
  src/_meshfix.cpp''', 1)
anchor = '# Compiler-specific options'
assert anchor in text
text = text.replace(anchor, 'set_target_properties(_meshfix PROPERTIES SUFFIX ".cpython-311.so")\ntarget_link_libraries(_meshfix PRIVATE "${PYNIM_LIBPYTHON}")\n\n' + anchor, 1)
path.write_text(text)
PY
  rm -rf "$WORK/pymeshfix/build"
  cmake -S "$WORK/pymeshfix" -B "$WORK/pymeshfix/build" "${CMAKE_COMMON[@]}" \
    -DCMAKE_C_FLAGS="-fPIC -fno-emulated-tls -O2 -Wno-error=format-security -Wno-format-security -Wno-error" \
    -DCMAKE_CXX_FLAGS="-fPIC -fno-emulated-tls -O2 -Wno-error=format-security -Wno-format-security -Wno-error" > "$WORK/pymeshfix-cmake.log" 2>&1
  cmake --build "$WORK/pymeshfix/build" --parallel
  rm -rf "$LIB/pymeshfix" && mkdir -p "$LIB/pymeshfix"
  cp "$WORK/pymeshfix/src/pymeshfix/"*.py "$WORK/pymeshfix/src/pymeshfix/"*.pyi "$LIB/pymeshfix/"
  cp "$WORK/pymeshfix/build/_meshfix.cpython-311.so" "$LIB/pymeshfix/"
  cp "$WORK/pymeshfix/LICENSE" "$LIB/LICENSES/meshfix-GPL-3.0.txt"
  cp "$ROOT/app/src/main/assets/mesh2step/LICENSE" "$LIB/LICENSES/pymeshfix-AGPL-3.0.txt"
}

build_rtree() {
  local sdist="rtree-1.4.1.tar.gz"
  local sha="c6b1b3550881e57ebe530cc6cffefc87cd9bf49c30b37b894065a9f810875e46"
  local sidx="libspatialindex-2.1.0.tar.gz"
  local sidx_sha="a04513cea04dd20ab2c9d153c14cc45692805ee496b30619103f7929f6fb81bf"
  local url sidx_url
  echo '=== rtree 1.4.1 + libspatialindex 2.1.0 ==='
  if [ ! -f "$WORK/$sdist" ]; then
    url="$(python3 -c "import json,urllib.request; d=json.load(urllib.request.urlopen('https://pypi.org/pypi/rtree/1.4.1/json')); print(next(u['url'] for u in d['urls'] if u['filename']=='$sdist'))")"
    fetch "$url" "$WORK/$sdist" "$sha"
  fi
  if [ ! -f "$WORK/$sidx" ]; then
    sidx_url="https://github.com/libspatialindex/libspatialindex/archive/refs/tags/2.1.0.tar.gz"
    fetch "$sidx_url" "$WORK/$sidx" "$sidx_sha"
  fi
  rm -rf "$WORK/rtree-src" "$WORK/rtree-lib" "$WORK/libspatialindex"
  mkdir -p "$WORK/rtree-src" "$WORK/libspatialindex"
  tar xzf "$WORK/$sdist" -C "$WORK/rtree-src" --strip-components=1
  tar xzf "$WORK/$sidx" -C "$WORK/libspatialindex" --strip-components=1
  cmake -S "$WORK/libspatialindex" -B "$WORK/libspatialindex/build" "${CMAKE_COMMON[@]}" \
    -DCMAKE_SHARED_LINKER_FLAGS='-Wl,-rpath,\$ORIGIN' -DBUILD_SHARED_LIBS=ON -DBUILD_TESTING=OFF > "$WORK/sidx-cmake.log" 2>&1
  cmake --build "$WORK/libspatialindex/build" --parallel
  local ndkbin; ndkbin="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)"
  "$ndkbin/llvm-strip" --strip-unneeded "$WORK/libspatialindex/build/src/libspatialindex_c.so" "$WORK/libspatialindex/build/src/libspatialindex.so"
  rm -rf "$LIB/rtree" && mkdir -p "$LIB/rtree/lib"
  cp "$WORK/rtree-src/rtree/"*.py "$WORK/rtree-src/rtree/py.typed" "$LIB/rtree/"
  cp "$WORK/libspatialindex/build/src/libspatialindex_c.so" "$LIB/rtree/lib/"
  cp "$WORK/libspatialindex/build/src/libspatialindex.so" "$LIB/rtree/lib/"
  cp "$WORK/rtree-src/LICENSE.txt" "$LIB/LICENSES/rtree-MIT.txt"
  cp "$WORK/libspatialindex/COPYING" "$LIB/LICENSES/libspatialindex-MIT.txt"
}

build_all() { build_trimesh; build_fast_simplification; build_manifold3d; build_pymeshfix; build_rtree; }

case "${1:-all}" in
  all) build_all ;;
  trimesh) build_trimesh ;;
  manifold3d) build_manifold3d ;;
  fast_simplification) build_fast_simplification ;;
  pymeshfix) build_pymeshfix ;;
  rtree) build_rtree ;;
  *) echo "unknown accelerator: $1" >&2; exit 2 ;;
esac
echo
echo "staged accelerators under $LIB; refresh the manifest with:"
echo "  python3 $ROOT/scripts/verify-mesh2step-assets.py --root $ASSETS --write"