#!/usr/bin/env bash
# Build the OpenCASCADE (OCCT) console for Android arm64-v8a and stage it into jniLibs.
#
# It also links FreeCAD's PlaneGCS constraint solver, which is what makes the "sketch" command
# possible: state relationships instead of coordinates, and the solver finds the geometry.
# PlaneGCS needs C++20 (std::ranges in GCS.cpp) and its own include tree.
#
# This is the CAD kernel behind STEP and IGES import. It is NOT rebuilt from scratch here:
# the OrcaSlicer dependency tree already cross-compiles OCCT 7.6.0 for arm64 (38 toolkits,
# including TKSTEP, TKIGES, TKBO, TKMesh and TKShHealing), so this script only builds the
# three toolkits Orca's recipe leaves out and then links the driver against both sets.
#
# Those three are missing for a reason worth recording: Orca passes
# -DBUILD_MODULE_ModelingAlgorithms=OFF, and CMake still builds whatever DataExchange
# depends on. TKFillet, TKOffset and TKFeat are not in that closure, so they were skipped.
# Turning the module back on is the whole fix.
#
# Usage: scripts/build-occt-engine-android.sh [--clean]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ORCA_DEPS="${ORCA_DEPS:-/root/src/OrcaSlicer/deps}"
OCCT_SRC="${OCCT_SRC:-/root/occt-port/build/OCCT-7_6_0}"
WORK="${WORK:-/root/occt-port}"
NDK="${ANDROID_NDK_HOME:-/opt/android-sdk/ndk/28.2.13676358}"
PREFIX="${ORCA_DEPS}/build-android/OrcaSlicer_dep/usr/local"
TARGET_ABI=arm64-v8a
TARGET_API=24
OUT="${REPO_ROOT}/app/src/main/jniLibs/${TARGET_ABI}/libocct_exec.so"

TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/linux-x86_64"
CXX="${TOOLCHAIN}/bin/aarch64-linux-android${TARGET_API}-clang++"
STRIP="${TOOLCHAIN}/bin/llvm-strip"
BUILTINS="${TOOLCHAIN}/lib/clang/19/lib/linux/libclang_rt.builtins-aarch64-android.a"

command -v "${CXX}" >/dev/null || { echo "no NDK clang++ at ${CXX}" >&2; exit 1; }
[ -d "${PREFIX}/lib" ] || { echo "Orca deps not built at ${PREFIX}" >&2; exit 1; }
[ -d "${OCCT_SRC}/src" ] || { echo "OCCT source not extracted at ${OCCT_SRC}" >&2; exit 1; }

if [ "${1:-}" = "--clean" ]; then
    rm -rf "${OCCT_SRC}/build-arm64"
fi

# 1. Configure and build the toolkits Orca's recipe omits.
if [ ! -f "${OCCT_SRC}/build-arm64/build.ninja" ]; then
    echo "== configuring OCCT"
    cmake -S "${OCCT_SRC}" -B "${OCCT_SRC}/build-arm64" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="${NDK}/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="${TARGET_ABI}" -DANDROID_PLATFORM="android-${TARGET_API}" \
        -DCMAKE_BUILD_TYPE=Release -DBUILD_LIBRARY_TYPE=Static \
        -DBUILD_MODULE_ModelingAlgorithms=ON -DBUILD_MODULE_DataExchange=ON \
        -DBUILD_MODULE_ApplicationFramework=OFF -DBUILD_MODULE_Draw=OFF \
        -DBUILD_MODULE_Visualization=OFF -DBUILD_DOC_Overview=OFF \
        -DUSE_TK=OFF -DUSE_TBB=OFF -DUSE_VTK=OFF -DUSE_FFMPEG=OFF -DUSE_FREETYPE=ON \
        -D3RDPARTY_FREETYPE_INCLUDE_DIR_ft2build="${PREFIX}/include/freetype2" \
        -D3RDPARTY_FREETYPE_INCLUDE_DIR_freetype2="${PREFIX}/include/freetype2"
fi

echo "== building TKFillet TKOffset TKFeat"
cmake --build "${OCCT_SRC}/build-arm64" --target TKFillet TKOffset TKFeat -j "$(nproc)"

NEW_LIB="${OCCT_SRC}/build-arm64/lin64/clang/lib"

# 2. Link the driver against both sets of archives, plus the constraint solver.
#    C++20 is required by planeGCS; the deprecation suppressions silence OCCT headers that
#    predate it and would otherwise bury real errors in noise.
PLANEGCS="${PLANEGCS:-${WORK}/planegcs}"
GCS="${PLANEGCS}/src/planegcs"
[ -d "${GCS}" ] || { echo "planeGCS not at ${GCS}" >&2; exit 1; }

echo "== linking occt_exec (OCCT + planeGCS)"
"${CXX}" -std=c++20 -O1 -fexceptions -frtti \
    -Wno-deprecated-enum-enum-conversion -Wno-deprecated-declarations \
    -I"${WORK}" \
    -I"${OCCT_SRC}/build-arm64/include/opencascade" -I"${PREFIX}/include/opencascade" \
    -I"${GCS}" -I"${GCS}/shims" -I"${GCS}/shims/Base" \
    -I"${PREFIX}/include/eigen3" -I"${PREFIX}/include" \
    -o "${WORK}/occt_exec" "${WORK}/occt_exec.cpp" "${WORK}/sketch.cpp" \
        "${GCS}/GCS.cpp" "${GCS}/Geo.cpp" "${GCS}/Constraints.cpp" \
        "${GCS}/SubSystem.cpp" "${GCS}/qp_eq.cpp" \
    -Wl,--start-group "${PREFIX}"/lib/libTK*.a \
        "${NEW_LIB}/libTKFillet.a" "${NEW_LIB}/libTKOffset.a" "${NEW_LIB}/libTKFeat.a" \
        "${BUILTINS}" -Wl,--end-group \
    -lm -lc++_static -lc++abi -llog

# 3. Strip and stage. OCCT ships with debug_info; unstripped this is 229 MB.
mkdir -p "$(dirname "${OUT}")"
cp "${WORK}/occt_exec" "${OUT}"
"${STRIP}" --strip-all "${OUT}"

echo "== staged ${OUT} ($(( $(stat -c%s "${OUT}") / 1048576 )) MB)"

# OCCT needs exceptions and RTTI; -fno-exceptions/-fno-rtti break it immediately.
# -fno-rtti also breaks it. The builtins archive must be in the link or the binary dies on
# device with "cannot locate symbol __emutls_get_address".
