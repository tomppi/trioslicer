#!/usr/bin/env bash
# Cross-compile FreeCAD's PlaneGCS 2D constraint solver for Android arm64.
#
# This is the constraint half of FreeCAD's paradigm: instead of giving coordinates, you state
# relationships - equal length, horizontal, a fixed distance - and the solver finds the geometry.
# It is FreeCAD's own solver (GCS::System), extracted by github.com/spookylukey/planegcs so it
# builds outside FreeCAD. LGPL-2.1-or-later, same as FreeCAD.
#
# No pybind11 and no Python: src/bindings.cpp is the only Python-touching file and is skipped.
# The solver itself is 5 .cpp files and comes out at 1.4 MB stripped.
#
# Usage: scripts/build-planegcs-probe-android.sh [--run]
#
# Three things fail silently if you get them wrong, all of them learned the hard way:
#   1. It needs -std=c++20. C++17 dies on std::ranges in GCS.cpp.
#   2. Raw C++ must call declareUnknowns() and initSolution() before adding constraints.
#      The Python bindings do it for you; without it solve() has nothing to move and returns
#      Failed with the caller's doubles untouched.
#   3. solve() only computes. applySolution() writes the result back through the double*
#      pointers the GCS::Point objects hold. Skip it and the coordinates never change even
#      though the status says Success.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${WORK:-/root/occt-port}"
PLANEGCS="${PLANEGCS:-${WORK}/planegcs}"
PREFIX="${ORCA_DEPS:-/root/src/OrcaSlicer/deps}/build-android/OrcaSlicer_dep/usr/local"
NDK="${ANDROID_NDK_HOME:-/opt/android-sdk/ndk/28.2.13676358}"
TARGET_API=24

TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/linux-x86_64"
CXX="${TOOLCHAIN}/bin/aarch64-linux-android${TARGET_API}-clang++"
STRIP="${TOOLCHAIN}/bin/llvm-strip"
BUILTINS="${TOOLCHAIN}/lib/clang/19/lib/linux/libclang_rt.builtins-aarch64-android.a"
SRC="${PLANEGCS}/src/planegcs"

[ -d "${SRC}" ] || { echo "planegcs sources not at ${SRC}" >&2; exit 1; }
[ -d "${PREFIX}/include/eigen3" ] || { echo "eigen3 not at ${PREFIX}" >&2; exit 1; }
[ -d "${PREFIX}/include/boost" ] || { echo "boost not at ${PREFIX}" >&2; exit 1; }

echo "== compiling planegcs for arm64"
"${CXX}" -std=c++20 -O1 -fexceptions -frtti \
    -I"${SRC}" -I"${SRC}/shims" -I"${SRC}/shims/Base" \
    -I"${PREFIX}/include/eigen3" -I"${PREFIX}/include" \
    -o "${WORK}/sketch_probe" "${WORK}/sketch_probe.cpp" \
    "${SRC}/GCS.cpp" "${SRC}/Geo.cpp" "${SRC}/Constraints.cpp" \
    "${SRC}/SubSystem.cpp" "${SRC}/qp_eq.cpp" \
    "${BUILTINS}" -lm -lc++_static -lc++abi -llog

cp "${WORK}/sketch_probe" "${WORK}/sketch_probe.stripped"
"${STRIP}" --strip-all "${WORK}/sketch_probe.stripped"
echo "== built ${WORK}/sketch_probe.stripped ($(( $(stat -c%s "${WORK}/sketch_probe.stripped") / 1024 )) KB)"

if [ "${1:-}" = "--run" ]; then
    adb push "${WORK}/sketch_probe.stripped" /data/local/tmp/sketch_probe
    adb shell 'chmod 755 /data/local/tmp/sketch_probe; /data/local/tmp/sketch_probe'
fi
