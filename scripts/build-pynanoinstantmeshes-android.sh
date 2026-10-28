#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Builds PyNanoInstantMeshes' nanobind module for the app's Android CPython 3.11 / arm64
# and stages it (package + licence texts) under app/src/main/assets/mesh2step/lib/.
#
# The engine imports pynanoinstantmeshes in mmm/smooth.py for the Smooth preset's quad
# remeshing; the engine code itself stays unmodified. All source changes below happen in the
# extracted work copy, never in upstream or in our assets:
#   1. nanobind: drop STABLE_ABI (a cp312+ ABI; the target is cp311), use the target's own
#      .cpython-311.so suffix, and link the Android libpython.
#   2. Instant Meshes: its 2017 sources use std::unary_function (removed in C++17), so only
#      InstantMeshesLib is C++14 while the nanobind module remains C++17.
#   3. TBB 2020.2: static Android build (bionic has no -lpthread, no host try_run for the
#      libc++ check, and the sdist omits the generated version_string.ver template).
#
# Requirements: the Android CPython prefix and host interpreter from
# scripts/build-klipper-python-android.sh, an Android NDK under /opt/android-sdk/ndk, cmake,
# ninja. Usage:  scripts/build-pynanoinstantmeshes-android.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$BASH_SOURCE[0]")/.." && pwd)"
PREFIX="$(printf %s "${PREFIX:-$ROOT/.build/py311-android}")"
HOST="$(printf %s "${HOST_PYTHON:-$ROOT/.build/klipper-python/py-host/bin/python3.11}")"
WORK="$(printf %s "${DEPS_DIR:-$ROOT/.build/nim-android}")"
NDK="$(printf %s "${ANDROID_NDK_HOME:-$(ls -d /opt/android-sdk/ndk/* 2>/dev/null | tail -1)}")"
ASSETS="$ROOT/app/src/main/assets/mesh2step"
LIB="$ASSETS/lib/pynanoinstantmeshes"

SDIST="pynanoinstantmeshes-0.0.3.tar.gz"
SDIST_URL="https://files.pythonhosted.org/packages/a9/9c/3c36e62a8a600f2ae993f7dfa61a6d3e1dc45cff41d3a77c70e40f8657ab/$SDIST"
SDIST_SHA256="d827eba612753a4bad6e8f644b8cc6c195e47f4afc6936823c0be5c47bb3c23f"
NANOBIND="nanobind-3.1.0-py3-none-any.whl"
NANOBIND_URL="https://files.pythonhosted.org/packages/36/c1/6fd56f7a02a478d54a1a3e6000eccd34b446b1c45cbf0d002fe5d2937126/$NANOBIND"
NANOBIND_SHA256="96984dbe8154ffb86e653b1b60aaeed6985c710c5e75ad0279eb87943c66292d"

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

fetch() {
  local url out expected actual
  url="$1"; out="$2"; expected="$3"
  if [ ! -f "$out" ]; then
    echo "downloading $(basename "$out")"
    curl -fL --progress-bar "$url" -o "$out"
  fi
  actual="$(sha256_of "$out")"
  if [ "$actual" != "$expected" ]; then
    echo "FATAL: sha256 mismatch for $out" >&2
    echo "  expected $expected" >&2
    echo "  actual   $actual" >&2
    exit 1
  fi
}

if [ ! -e "$PREFIX/include/python3.11/Python.h" ] || [ ! -e "$PREFIX/lib/libpython3.11.so" ] || [ ! -x "$HOST" ]; then
  echo "FATAL: run scripts/build-klipper-python-android.sh first (need $PREFIX and $HOST)" >&2
  exit 1
fi
if [ -z "$NDK" ]; then echo "FATAL: no Android NDK found" >&2; exit 1; fi

mkdir -p "$WORK"
cd "$WORK"
fetch "$SDIST_URL" "$WORK/$SDIST" "$SDIST_SHA256"

SITE="$("$HOST" -c 'import site; print(site.getsitepackages()[0])')"
if ! "$HOST" -c 'import nanobind' 2>/dev/null; then
  fetch "$NANOBIND_URL" "$WORK/$NANOBIND" "$NANOBIND_SHA256"
  python3 -m zipfile -e "$WORK/$NANOBIND" "$SITE"
fi
NB_VERSION="$("$HOST" -c 'import nanobind; print(nanobind.__version__)')"
if [ "$NB_VERSION" != "3.1.0" ]; then
  echo "FATAL: host nanobind is $NB_VERSION, expected 3.1.0" >&2
  exit 1
fi
NB_DIR="$("$HOST" -m nanobind --cmake_dir)"

rm -rf "$WORK/src" "$WORK/build"
tar xzf "$WORK/$SDIST" -C "$WORK"
mv "$WORK/pynanoinstantmeshes-0.0.3" "$WORK/src"
cd "$WORK/src"

python3 - <<'PATCH'
from pathlib import Path

top = Path('CMakeLists.txt')
text = top.read_text()
old = 'nanobind_add_module(_pynim NB_STATIC STABLE_ABI src/process_numpy.cpp)'
assert old in text, 'unexpected top-level CMakeLists (nanobind_add_module)'
text = text.replace(
    old,
    'nanobind_add_module(_pynim NB_STATIC src/process_numpy.cpp)'
    '\n\n# Target suffix from the payload sysconfig, not the host CMake runs on.\n'
    'set_target_properties(_pynim PROPERTIES SUFFIX ".cpython-311.so")\n'
    '# Android extension modules resolve interpreter symbols through DT_NEEDED.\n'
    'target_link_libraries(_pynim PRIVATE "${PYNIM_LIBPYTHON}")',
    1,
)
old = '# Set non-Windows deps in case it\'s not detected\nif (NOT WIN32)\n    set(PYNIM_LINUX_DEPS pthread dl)\nendif()'
assert old in text, 'unexpected top-level CMakeLists (PYNIM_LINUX_DEPS)'
text = text.replace(
    old,
    '# Android bionic keeps pthreads in libc and has no -lpthread.\n'
    'if (NOT WIN32 AND NOT ANDROID)\n'
    '    set(PYNIM_LINUX_DEPS pthread dl)\n'
    'endif()',
    1,
)
top.write_text(text)

native = Path('native/CMakeLists.txt')
text = native.read_text()
needle = 'target_include_directories(InstantMeshesLib PRIVATE im/include)'
assert needle in text, 'unexpected native/CMakeLists.txt'
text = text.replace(
    needle,
    needle
    + '\n\n# Instant Meshes (2017) still uses std::unary_function, removed in C++17;\n'
    '# only this library stays on C++14 while the nanobind module is C++17.\n'
    'set_target_properties(InstantMeshesLib PROPERTIES CXX_STANDARD 14 CXX_STANDARD_REQUIRED ON)',
    1,
)
native.write_text(text)

tbb = Path('native/ext/tbb/CMakeLists.txt')
lines = tbb.read_text().splitlines(keepends=True)
patched = 0
for index, line in enumerate(lines):
    if 'CMAKE_CXX_FLAGS MATCHES' in line and '-stdlib=libc' in line:
        lines[index] = line.replace(')', ' OR ANDROID)', 1)
        patched += 1
assert patched == 1, 'unexpected TBB libc++ check'
text = ''.join(lines)
old = '    target_link_libraries(tbb_static PUBLIC pthread dl)'
assert old in text, 'unexpected TBB static link line'
text = text.replace(
    old,
    '    if (ANDROID)\n'
    '      # bionic keeps pthreads in libc; there is no -lpthread to link against.\n'
    '      target_link_libraries(tbb_static PUBLIC dl)\n'
    '    else()\n'
    '      target_link_libraries(tbb_static PUBLIC pthread dl)\n'
    '    endif()',
    1,
)
tbb.write_text(text)

tbb_build = Path('native/ext/tbb/build')
tbb_build.mkdir(parents=True, exist_ok=True)
(tbb_build / 'version_string.ver.in').write_text(
    '// Generated from build/version_string.ver.in; no build-host details are embedded.\n'
    '#ifndef __TBB_VERSION_STRINGS\n'
    '#define __TBB_VERSION_STRINGS(N) ""\n'
    '#endif\n'
    '#ifndef __TBB_DATETIME\n'
    '#define __TBB_DATETIME "Unknown"\n'
    '#endif\n'
)
print('patched nanobind, Instant Meshes and TBB build files')
PATCH

cmake -S "$WORK/src" -B "$WORK/build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_CXX_FLAGS='-fPIC -fno-emulated-tls -O2' \
  -Dnanobind_DIR="$NB_DIR" \
  -DPython_EXECUTABLE="$HOST" \
  -DPython_INCLUDE_DIR="$PREFIX/include/python3.11" \
  -DPython_LIBRARY="$PREFIX/lib/libpython3.11.so" \
  -DPYNIM_LIBPYTHON="$PREFIX/lib/libpython3.11.so"

cmake --build "$WORK/build" --parallel

BUILT="$WORK/build/_pynim.cpython-311.so"
if [ ! -f "$BUILT" ]; then echo "FATAL: cmake did not produce _pynim.cpython-311.so" >&2; exit 1; fi

mkdir -p "$LIB/licenses"
cp "$WORK/src/src/pynanoinstantmeshes/__init__.py" "$LIB/__init__.py"
cp "$BUILT" "$LIB/_pynim.cpython-311.so"
cp "$WORK/src/native/LICENSE.txt" "$LIB/licenses/INSTANT-MESHES.txt"
cp "$WORK/src/native/ext/eigen/COPYING.MPL2" "$LIB/licenses/EIGEN-MPL2.txt"
cp "$WORK/src/native/ext/tbb/LICENSE" "$LIB/licenses/TBB-APACHE-2.0.txt"

echo '=== built module ==='
ls -l "$LIB/_pynim.cpython-311.so"
READELF="$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/llvm-readelf)"
"$READELF" -h "$LIB/_pynim.cpython-311.so" | grep -E 'Class|Machine|Type'
"$READELF" -d "$LIB/_pynim.cpython-311.so" | grep -E 'NEEDED|SONAME'

python3 "$ROOT/scripts/verify-mesh2step-assets.py" --root "$ASSETS" --write
python3 "$ROOT/scripts/verify-mesh2step-assets.py" --root "$ASSETS" --check-only
echo "staged PyNanoInstantMeshes 0.0.3 at $LIB"