#!/usr/bin/env bash
# Cross-build the OrcaSlicer dependency bundle for Android arm64-v8a.
#
# Runs OrcaSlicer's own deps/ CMake project (versions and patches stay upstream's) with the
# NDK toolchain. The GUI-only recipes (GLEW, GLFW, OpenCSG, wxWidgets, OpenCV) are skipped:
# this port ships a headless console, not the GUI app.
#
# Usage:  ORCA_SRC=/path/to/OrcaSlicer scripts/build-orca-deps-android.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}/ndk/28.2.13676358}"
TC="$NDK/build/cmake/android.toolchain.cmake"
SRC="${ORCA_SRC:-$HERE/../.build/orca-src}"
BUILD="$SRC/deps/build-android"
API="${ANDROID_API:-29}"
JOBS="${DEP_JOBS:-2}"

[ -f "$TC" ] || { echo "FATAL: NDK toolchain missing at $TC" >&2; exit 1; }
[ -d "$SRC/deps" ] || { echo "FATAL: no OrcaSlicer checkout at $SRC (set ORCA_SRC)" >&2; exit 1; }

# The tracked patches gate the GUI-only dependency recipes (GLEW, GLFW, OpenCSG, wxWidgets,
# OpenCV) that this headless port never builds. They have to be in place *before* the deps
# project is configured: without them deps/CMakeLists.txt has no SLIC3R_GUI option at all, so
# -DSLIC3R_GUI=OFF is ignored and the configure fails looking for OpenGL. --reverse --check
# tells an already-patched tree from a fresh one, so re-running is safe.
for patch in "$(cd "$HERE/.." && pwd)"/native/orca/patches/*.patch; do
  if git -C "$SRC" apply --reverse --check "$patch" >/dev/null 2>&1; then
    echo "already applied: "$(basename "$patch")""
  else
    echo "applying: "$(basename "$patch")""
    git -C "$SRC" apply --verbose "$patch"
  fi
done

# The build directory must live inside the checkout: deps/ computes a repo-relative path for
# the GMP patch step and git apply resolves it from the repository root.
cmake -S "$SRC/deps" -B "$BUILD" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$TC" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM="android-$API" \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DSLIC3R_GUI=OFF \
  -DSLIC3R_CAD=ON

# -j at the top level only limits how many dependencies build at once; each dependency runs
# its own parallel build internally.
ninja -C "$BUILD" -j"$JOBS" deps

# A recipe that mis-detects the compiler installs host objects, which only surfaces much later
# as "incompatible with aarch64linux" while linking something else. Check every archive, by
# reading the ELF header of its members - `file` reports plain "current ar archive" for most
# of them and would check nothing.
PREFIX="$BUILD/OrcaSlicer_dep/usr/local"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
count=0
mismatched=""
for lib in "$PREFIX/lib/"*.a; do
  [ -e "$lib" ] || continue
  count=$((count + 1))
  # Read the header whole. A reader that closes the pipe early (grep -m1 did) makes
  # llvm-readelf write into a broken pipe and exit 74 (EX_IOERR), and with pipefail that
  # turned a healthy archive into a failed build - silently, because the failing command was
  # the assignment itself. awk reads to the end, so nothing is cut off.
  header=$("$READELF" -h "$lib" 2>/dev/null) || header=""
  machine=$(printf '%s\n' "$header" | awk '/Machine:/ { machine = $2 } END { print machine }')
  [ "$machine" = "AArch64" ] || mismatched="$mismatched\n  $(basename "$lib"): ${machine:-unreadable}"
done
if [ -n "$mismatched" ]; then
  echo "FATAL: dependencies installed for the wrong architecture:"
  printf "%b\n" "$mismatched"
  exit 1
fi
echo "architecture check: $count archives, all AArch64"
echo "ORCA-DEPS-READY $PREFIX"
