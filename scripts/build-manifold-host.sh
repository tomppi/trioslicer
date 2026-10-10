#!/usr/bin/env bash
# Build the Manifold JNI shim for the host, so the JVM tests can run the same
# boolean engine the phone runs.
#
# The shim (native/manifold-jni/manifold_jni.cpp) is platform-independent C++
# over JNI; only the library it links differs. Manifold itself is vendored into
# .build/manifold-src by scripts/build-manifold-android.sh and this script reuses
# that checkout, so a machine that has built the phone's engine already has
# everything this needs. A checkout without the source skips this build, and the
# JVM tests that need a real boolean skip with it.
#
# Output: .build/manifold-host/out/libmanifold_jni.so, the directory
# app/build.gradle.kts puts on the test JVM's java.library.path when it exists.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="${MANIFOLD_SRC:-$ROOT/.build/manifold-src}"
WORK="${MANIFOLD_HOST_BUILD:-$ROOT/.build/manifold-host}"
OUT="$WORK/out"
JNI_SRC="$ROOT/native/manifold-jni/manifold_jni.cpp"

[ -d "$SRC" ] || { echo "FATAL: no Manifold source at $SRC; run scripts/build-manifold-android.sh first" >&2; exit 1; }
[ -f "$JNI_SRC" ] || { echo "FATAL: $JNI_SRC is missing" >&2; exit 1; }

JAVAC="$(command -v javac || true)"
[ -n "$JAVAC" ] || { echo "FATAL: no javac on PATH to find jni.h" >&2; exit 1; }
JAVA_HOME="$(cd "$(dirname "$(readlink -f "$JAVAC")")/.." && pwd)"
[ -f "$JAVA_HOME/include/jni.h" ] || { echo "FATAL: no jni.h under $JAVA_HOME" >&2; exit 1; }

echo "===== [1/3] configure host Manifold (static, serial, no optional deps) ====="
cmake -S "$SRC" -B "$WORK/build" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_SHARED_LIBS=OFF \
  -DMANIFOLD_DOWNLOADS=OFF \
  -DMANIFOLD_CROSS_SECTION=OFF \
  -DMANIFOLD_CBIND=OFF \
  -DMANIFOLD_PAR=OFF \
  -DMANIFOLD_TEST=OFF \
  -DMANIFOLD_PYBIND=OFF \
  -DMANIFOLD_STRICT=OFF 2>&1 | tail -5

echo "===== [2/3] build libmanifold.a ====="
cmake --build "$WORK/build" --target manifold -j "$(nproc)"

STATIC_LIB="$WORK/build/src/libmanifold.a"
[ -f "$STATIC_LIB" ] || { echo "FATAL: no static Manifold archive at $STATIC_LIB" >&2; exit 1; }

echo "===== [3/3] link libmanifold_jni.so for the host ====="
mkdir -p "$OUT"
g++ -std=c++17 -O2 -fexceptions -frtti -fPIC -shared \
  -DMANIFOLD_PAR=-1 \
  -I"$SRC/include" -I"$WORK/build/include" \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/linux" \
  -o "$OUT/libmanifold_jni.so" "$JNI_SRC" "$STATIC_LIB" -pthread

nm -D "$OUT/libmanifold_jni.so" > "$WORK/jni-symbols.txt"
for ENTRY in nativeVersion nativeLoadMesh nativeBox nativeUnion nativeSubtract nativeIntersect \
             nativeStatus nativeIsClosed nativeReadMesh nativeVolume nativeLiveHandles nativeRelease; do
  grep -q " T Java_com_tomppi_enderslicer_viewer_MeshBoolean_${ENTRY}$" "$WORK/jni-symbols.txt" &&
    echo "  ok  $ENTRY" || { echo "  MISSING $ENTRY" >&2; exit 1; }
done
echo MANIFOLD-HOST-READY "$OUT/libmanifold_jni.so"
