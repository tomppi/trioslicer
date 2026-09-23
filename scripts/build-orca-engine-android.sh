#!/usr/bin/env bash
# Cross-compile the headless OrcaSlicer console for Android arm64-v8a and stage it for the APK.
#
#   deps:  scripts/build-orca-deps-android.sh   (once; the dependency bundle)
#   this:  scripts/build-orca-engine-android.sh
#
# Outputs:
#   app/src/main/jniLibs/arm64-v8a/liborca_console_exec.so   (stripped, AArch64)
#
# ORCA_SRC may point at an existing checkout; without it the pinned tag is cloned into
# .build/orca-src, which is what CI does.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}/ndk/28.2.13676358}"
TC="$NDK/build/cmake/android.toolchain.cmake"
PINNED_TAG="${ORCA_TAG:-v2.4.2}"
SRC="${ORCA_SRC:-$REPO/.build/orca-src}"
BUILD="$SRC/build-android"
PREFIX="$SRC/deps/build-android/OrcaSlicer_dep/usr/local"
OUT="$REPO/app/src/main/jniLibs/arm64-v8a"
API="${ANDROID_API:-29}"
JOBS="${JOBS:-$(nproc)}"

[ -f "$TC" ] || { echo "FATAL: NDK toolchain missing at $TC" >&2; exit 1; }
[ -d "$PREFIX/include" ] || { echo "FATAL: dependency prefix missing at $PREFIX - run scripts/build-orca-deps-android.sh first" >&2; exit 1; }

if [ ! -d "$SRC/src" ]; then
  echo "Cloning OrcaSlicer $PINNED_TAG into $SRC"
  mkdir -p "$(dirname "$SRC")"
  git clone --depth 1 --branch "$PINNED_TAG" https://github.com/SoftFever/OrcaSlicer.git "$SRC"
fi

# Apply the tracked build patches once. --reverse --check tells an already-patched tree from a
# fresh one, so re-running the script is safe.
for patch in "$REPO"/native/orca/patches/*.patch; do
  if git -C "$SRC" apply --reverse --check "$patch" >/dev/null 2>&1; then
    echo "already applied: $(basename "$patch")"
  else
    echo "applying: $(basename "$patch")"
    git -C "$SRC" apply --verbose "$patch"
  fi
done

mkdir -p "$SRC/src/console"
cp "$REPO/native/orca/console/"*.cpp "$REPO/native/orca/console/CMakeLists.txt" "$SRC/src/console/"

# OrcaSlicer's root CMakeLists points OpenCASCADE_DIR at <prefix>/lib/cmake/occt, while OCCT
# 7.6 installs its config package under lib/cmake/opencascade.
if [ -d "$PREFIX/lib/cmake/opencascade" ] && [ ! -e "$PREFIX/lib/cmake/occt" ]; then
  ln -s opencascade "$PREFIX/lib/cmake/occt"
fi

# SLIC3R_ENC_CHECK is only auto-disabled when IS_CROSS_COMPILE is set, which the NDK toolchain
# does not set, so it is forced off here.
cmake -S "$SRC" -B "$BUILD" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$TC" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM="android-$API" -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_PREFIX_PATH="$PREFIX" \
  -DCMAKE_FIND_ROOT_PATH="$PREFIX" \
  -DCMAKE_MODULE_PATH="$REPO/native/orca/cmake-shims" \
  -DSLIC3R_GUI=OFF -DSLIC3R_CAD=ON -DSLIC3R_OPENCV=OFF -DSLIC3R_STATIC=ON \
  -DSLIC3R_ENC_CHECK=OFF -DBUILD_TESTS=OFF -DORCA_TOOLS=OFF \
  -DBOOST_ROOT="$PREFIX" -DBoost_NO_SYSTEM_PATHS=ON \
  "-DCMAKE_CXX_FLAGS=-isystem $PREFIX/include"

ninja -C "$BUILD" -j"$JOBS" orca-console

mkdir -p "$OUT"
cp "$BUILD/src/console/orca-console" "$OUT/liborca_console_exec.so"
# The link keeps full DWARF: 858 MB unstripped for 18 MB of code. Only the debug sections go,
# so the symbol table survives and a crash trace stays readable.
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-debug "$OUT/liborca_console_exec.so"

echo "== engine binary =="
ls -la "$OUT/liborca_console_exec.so" | awk '{print "size:", $5, "bytes"}'
readelf -d "$OUT/liborca_console_exec.so" | grep NEEDED || echo "(no dynamic dependencies beyond the interpreter)"
echo "ORCA-ENGINE-READY $OUT/liborca_console_exec.so"
