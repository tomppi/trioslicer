#!/usr/bin/env bash
# Cross-compile the Manifold boolean engine (github.com/elalish/manifold, Apache-2.0)
# for Android arm64-v8a, together with the spike program that measures the two
# operations the split-and-snap feature needs: UNION and SUBTRACT.
#
# Manifold is a header + 20 .cpp files with no mandatory dependency: of its optional
# ones this build takes none. What is switched off and what it costs:
#   MANIFOLD_CROSS_SECTION=OFF  - drops the 2D CrossSection API and, with it, Clipper2
#                                 (which the build would otherwise clone from GitHub).
#                                 Cost: no polygon offsetting / 2D booleans - the
#                                 split-and-snap feature is 3D only.
#   MANIFOLD_PAR=OFF            - serial execution (MANIFOLD_PAR=-1). Cost: the
#                                 booleans run on one core; TBB or OpenMP would use
#                                 more, at the price of shipping a thread pool into
#                                 the app. The timings are therefore the
#                                 single-threaded worst case.
#   MANIFOLD_TEST=OFF           - no googletest download, no test/samples/extras.
#   ASSIMP_ENABLE=OFF (default) - no file IO in the library; the spike reads and
#                                 writes binary STL itself.
#   CUDA                        - not applicable: Manifold 3.x has no CUDA backend
#                                 (it was removed after 2.x); there is no CUDA code
#                                 left in the tree to disable.
# Everything else is vendored in the release: nothing is fetched at build time
# (MANIFOLD_DOWNLOADS=OFF makes that a hard failure rather than a silent clone).
#
# Pinned release: v3.5.4, commit ce50d78021d64507f89e8c9fc2c2e51018117857. The clone
# lives in .build/manifold-src (gitignored, like the other engine sources) and its
# HEAD is checked against that commit, so a moved tag cannot change the build.
#
# The second output is the app's engine: native/manifold-jni/manifold_jni.cpp is a
# JNI shim over Manifold (load a soup, build a box, union, subtract, status,
# closedness, read back), linked against a *static* Manifold so the app loads one
# self-contained libmanifold_jni.so and no second library has to travel with it.
# It is staged into app/src/main/jniLibs/arm64-v8a/ exactly the way the other
# engine libraries are - by this script, never by the Gradle build. A fresh clone
# reproduces the app's copy with:
#   scripts/build-manifold-android.sh && ./gradlew assembleDebug
#
# Usage:
#   scripts/build-manifold-android.sh            # cross-compile, stage the JNI library
#   scripts/build-manifold-android.sh --run      # also push the spike to the dev phone
#
# Outputs (in .build/manifold-android/out):
#   libmanifold.so        shared library for the spike, arm64-v8a, android-29
#   manifold_spike        the spike executable (links libmanifold.so)
#   libmanifold_jni.so    the app's JNI engine, Manifold linked in statically
# and the staged copy the APK packages:
#   app/src/main/jniLibs/arm64-v8a/libmanifold_jni.so
#
# The device half of the spike: the binary and its library go to /data/local/tmp
# (the SD card is mounted noexec), everything the phone should keep - the input STL
# and the resulting STLs - goes to /sdcard/Download/dsh-agent/.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="${MANIFOLD_TAG:-v3.5.4}"
PIN_SHA="${MANIFOLD_SHA:-ce50d78021d64507f89e8c9fc2c2e51018117857}"
SRC="${MANIFOLD_SRC:-$ROOT/.build/manifold-src}"
WORK="${MANIFOLD_BUILD:-$ROOT/.build/manifold-android}"
OUT="$WORK/out"
SPIKE_SRC="$ROOT/native/manifold-spike/spike.cpp"
JNI_SRC="$ROOT/native/manifold-jni/manifold_jni.cpp"
ABI="${ANDROID_ABI:-arm64-v8a}"
STAGE_DIR="$ROOT/app/src/main/jniLibs/$ABI"
API=29                             # the app's minSdk

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/28.2.13676358}"
TC="$NDK/build/cmake/android.toolchain.cmake"
[ -f "$TC" ] || { echo "FATAL: NDK toolchain missing at $TC ($ABI)" >&2; exit 1; }
TOOLBIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
CXX="${TOOLBIN}/aarch64-linux-android${API}-clang++"
STRIP="$TOOLBIN/llvm-strip"
[ "$ABI" = "arm64-v8a" ] || { echo "FATAL: this spike is arm64-v8a only, got $ABI" >&2; exit 1; }
[ -x "$CXX" ] || { echo "FATAL: no arm64 clang wrapper at $CXX" >&2; exit 1; }
[ -f "$SPIKE_SRC" ] || { echo "FATAL: spike source missing at $SPIKE_SRC" >&2; exit 1; }
[ -f "$JNI_SRC" ] || { echo "FATAL: JNI shim source missing at $JNI_SRC" >&2; exit 1; }

step () { echo; echo "===== $* ====="; }

step "[1/6] fetch Manifold $TAG"
if [ ! -d "$SRC/.git" ]; then
  for i in 1 2 3; do
    git clone --depth 1 --branch "$TAG" https://github.com/elalish/manifold.git "$SRC" && break
    rm -rf "$SRC"
  done
fi
HEAD_SHA="$(git -C "$SRC" rev-parse HEAD)"
if [ "$HEAD_SHA" != "$PIN_SHA" ]; then
  echo "FATAL: $SRC is at $HEAD_SHA, expected the pinned $TAG commit $PIN_SHA" >&2
  exit 1
fi
echo "manifold $TAG at $HEAD_SHA ($(du -sh "$SRC" | cut -f1) of source)"

step "[2/6] configure $ABI android-$API (shared lib, no optional deps)"
cmake -S "$SRC" -B "$WORK/build" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$TC" \
  -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=ON \
  -DMANIFOLD_DOWNLOADS=OFF \
  -DMANIFOLD_CROSS_SECTION=OFF \
  -DMANIFOLD_CBIND=OFF \
  -DMANIFOLD_PAR=OFF \
  -DMANIFOLD_TEST=OFF \
  -DMANIFOLD_PYBIND=OFF \
  -DMANIFOLD_STRICT=OFF \
  2>&1 | tail -20

step "[3/6] build libmanifold.so"
ninja -C "$WORK/build" manifold

step "[4/6] build the spike and stage it in $OUT"
mkdir -p "$OUT"
cp -f "$WORK/build/src/libmanifold.so" "$OUT/libmanifold.so"
# The library target's PUBLIC compile definitions do not reach a hand-compiled
# consumer, so MANIFOLD_PAR is passed here to match the library it links.
# -static-libstdc++: the bare NDK wrapper would otherwise make the executable
# need libc++_shared.so, and one more .so to push alongside it.
"$CXX" -std=c++17 -O2 -fexceptions -frtti -static-libstdc++ \
  -DMANIFOLD_PAR=-1 \
  -I"$SRC/include" -I"$WORK/build/include" \
  -Wl,-rpath,'$ORIGIN' \
  -o "$OUT/manifold_spike" "$SPIKE_SRC" \
  -L"$OUT" -lmanifold
"$STRIP" --strip-all "$OUT/manifold_spike"
"$STRIP" --strip-unneeded "$OUT/libmanifold.so" -o "$OUT/libmanifold.stripped.so"

step "[5/6] build the static library the JNI shim links in"
# Same source and options, one difference: a static archive instead of a shared
# object. It is what makes libmanifold_jni.so self-contained.
cmake -S "$SRC" -B "$WORK/build-static" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE="$TC" \
  -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="android-$API" -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF \
  -DMANIFOLD_DOWNLOADS=OFF \
  -DMANIFOLD_CROSS_SECTION=OFF \
  -DMANIFOLD_CBIND=OFF \
  -DMANIFOLD_PAR=OFF \
  -DMANIFOLD_TEST=OFF \
  -DMANIFOLD_PYBIND=OFF \
  -DMANIFOLD_STRICT=OFF \
  2>&1 | tail -5
ninja -C "$WORK/build-static" manifold
STATIC_LIB="$WORK/build-static/src/libmanifold.a"
[ -f "$STATIC_LIB" ] || { echo "FATAL: no static Manifold archive at $STATIC_LIB" >&2; exit 1; }

step "[6/6] build libmanifold_jni.so and stage it into $STAGE_DIR"
# Same hand-compiled-consumer caveats as the spike: MANIFOLD_PAR must match the
# library it links (-1 = serial), and -static-libstdc++ keeps the library free of
# a libc++_shared dependency so nothing else has to be shipped beside it.
"$CXX" -std=c++17 -O2 -fexceptions -frtti -fPIC -shared -static-libstdc++ \
  -DMANIFOLD_PAR=-1 \
  -I"$SRC/include" -I"$WORK/build/include" \
  -o "$OUT/libmanifold_jni.so" "$JNI_SRC" \
  "$STATIC_LIB"
"$STRIP" --strip-unneeded "$OUT/libmanifold_jni.so" -o "$OUT/libmanifold_jni.stripped.so"
mkdir -p "$STAGE_DIR"
cp -f "$OUT/libmanifold_jni.stripped.so" "$STAGE_DIR/libmanifold_jni.so"

echo
echo "== artifacts =="
ls -la "$OUT"
echo "== dynamic dependencies =="
readelf -d "$OUT/manifold_spike" | grep -E 'NEEDED|RUNPATH|RPATH' || true
readelf -d "$OUT/libmanifold.stripped.so" | grep -E 'NEEDED|SONAME' || true
readelf -d "$STAGE_DIR/libmanifold_jni.so" | grep -E 'NEEDED|SONAME' || true
echo "== JNI entry points (all must be present) =="
# The symbols are read into a file first: grep -q would leave nm on a broken
# pipe, and pipefail would then call a successful match a failure.
nm -D "$STAGE_DIR/libmanifold_jni.so" > "$WORK/jni-symbols.txt"
for ENTRY in nativeVersion nativeLoadMesh nativeBox nativeUnion nativeSubtract nativeIntersect \
             nativeStatus nativeIsClosed nativeReadMesh nativeVolume nativeLiveHandles nativeRelease; do
  grep -q " T Java_com_tomppi_enderslicer_viewer_MeshBoolean_$ENTRY$" "$WORK/jni-symbols.txt" &&
    echo "  ok  $ENTRY" || { echo "  MISSING $ENTRY" >&2; exit 1; }
done
echo MANIFOLD-SPIKE-READY

if [ "${1:-}" = "--run" ]; then
  step "[run] push and execute on the dev phone"
  SERIAL="${ANDROID_SERIAL:-984bd59e}"
  ADB=(adb -s "$SERIAL")
  DEV_DIR=/data/local/tmp/manifold-spike
  PUB_DIR=/sdcard/Download/dsh-agent
  SMALL_STL="${MANIFOLD_SPIKE_STL:-$ROOT/docs/examples/nonplanar_test.stl}"
  "${ADB[@]}" shell mkdir -p "$DEV_DIR"
  "${ADB[@]}" push "$OUT/manifold_spike" "$DEV_DIR/manifold_spike"
  "${ADB[@]}" push "$OUT/libmanifold.stripped.so" "$DEV_DIR/libmanifold.so"
  "${ADB[@]}" shell mkdir -p "$PUB_DIR"
  "${ADB[@]}" push "$SMALL_STL" "$PUB_DIR/manifold-spike-input.stl"
  "${ADB[@]}" shell chmod 755 "$DEV_DIR/manifold_spike"
  for MODE in "large $PUB_DIR/manifold-spike-large" "small $PUB_DIR/manifold-spike-input.stl $PUB_DIR/manifold-spike"; do
    echo "--- manifold_spike $MODE"
    "${ADB[@]}" shell "su -c 'cd $DEV_DIR && LD_LIBRARY_PATH=$DEV_DIR ./manifold_spike $MODE'" || true
  done
  echo "--- published files"
  "${ADB[@]}" shell ls -la "$PUB_DIR"
fi
