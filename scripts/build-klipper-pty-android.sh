#!/usr/bin/env bash
# Builds libklipper_pty.so for Android arm64 and stages it into the app package,
# the way the other native pieces are built.
#
# It exists for one reason: the printer is bridged to klippy through a pty, and
# Android has no pty API. android.system.Os offers open and readlink but no ioctl,
# and ioctlInt is a hidden API an app targeting 29 or later may not call. A
# terminal emulator on this platform carries a native library for the same reason.
#
#   APP_JNILIBS:  destination for the shared library. Default app/src/main/jniLibs
#
# Usage: scripts/build-klipper-pty-android.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/native/klipper-pty"
APP_JNILIBS="${APP_JNILIBS:-$ROOT/app/src/main/jniLibs}"
OUT="$APP_JNILIBS/arm64-v8a/libklipper_pty.so"

NDK="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK" ]; then
  NDK="$(ls -d /opt/android-sdk/ndk/* 2>/dev/null | tail -1 || true)"
fi
[ -n "$NDK" ] && [ -d "$NDK" ] || { echo "No Android NDK found: set ANDROID_NDK_HOME." >&2; exit 1; }
TC="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)"
CLANG="$TC/aarch64-linux-android24-clang"
[ -x "$CLANG" ] || { echo "No aarch64 clang under $NDK" >&2; exit 1; }

mkdir -p "$(dirname "$OUT")"
# -llog is deliberately absent: nothing here logs, it returns null and the caller
# says what that means.
"$CLANG" -shared -fPIC -O2 -o "$OUT" "$SRC/klipper_pty.c"

# Read the library back rather than trust the compiler's exit code.
"$TC/llvm-readelf" -h "$OUT" | grep -q AArch64 || { echo "$OUT is not AArch64" >&2; exit 1; }
"$TC/llvm-readelf" --dyn-syms "$OUT" | grep -q "Java_com_tomppi_enderslicer_printer_KlipperPty_nativeOpenPty" \
  || { echo "$OUT does not export the pty entry point" >&2; exit 1; }
echo "staged $(basename "$OUT"): $(stat -c%s "$OUT") bytes"
if ! git -C "$ROOT" check-ignore -q "$OUT" 2>/dev/null; then
  echo "warning: $OUT is not gitignored" >&2
fi
