#!/usr/bin/env bash
# Stages the Klipper host payload (klippy) for the Android app.
#
# Klipper is GPLv3, so it is fetched rather than vendored: this repository stays
# free of its sources, the same way the five slicer engines are fetched rather
# than committed. The staged tree goes to a gitignored asset directory.
#
#   KLIPPER_SRC:  a Klipper checkout to use.
#                 Default: .build/klipper-src, cloned at KLIPPER_TAG if missing.
#   KLIPPER_TAG:  the tag to clone. Default: v0.13.0, the current stable release.
#   APP_ASSETS:   where to stage. Default: app/src/main/assets/klipper
#
# Usage: scripts/stage-klipper-android.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="${KLIPPER_TAG:-v0.13.0}"
SRC="${KLIPPER_SRC:-$ROOT/.build/klipper-src}"
ASSETS="${APP_ASSETS:-$ROOT/app/src/main/assets/klipper}"

# The same NDK the other engines are built with.
NDK="${ANDROID_NDK_HOME:-}"
if [ -z "$NDK" ]; then
  NDK="$(ls -d /opt/android-sdk/ndk/* 2>/dev/null | tail -1 || true)"
fi
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  echo "No Android NDK found: set ANDROID_NDK_HOME." >&2
  exit 1
fi
CC_DIR="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin 2>/dev/null | head -1)"
CLANG="$CC_DIR/aarch64-linux-android24-clang"
[ -x "$CLANG" ] || { echo "No aarch64 clang under $NDK" >&2; exit 1; }
echo "ndk: $NDK"

if [ ! -d "$SRC/klippy" ]; then
  echo "cloning Klipper $TAG into $SRC"
  mkdir -p "$(dirname "$SRC")"
  git clone --quiet --depth 1 --branch "$TAG" https://github.com/Klipper3d/klipper.git "$SRC"
fi
echo "source: $SRC at $(cd "$SRC" && git describe --tags --always 2>/dev/null || echo unknown)"

# Build the C helper for bionic. -lpthread is deliberately absent: bionic keeps
# pthreads inside libc and the link fails if it is named.
cd "$SRC/klippy/chelper"
SOURCES="$(ls *.c | tr '\n' ' ')"
"$CLANG" -shared -fPIC -O2 -Wl,--no-undefined -o c_helper.so $SOURCES -lm
[ -s c_helper.so ] || { echo "chelper failed to build" >&2; exit 1; }
echo "chelper: $(stat -c%s c_helper.so) bytes"

# Stage the Python tree and the helper. The .c files are left behind on purpose:
# klippy compiles the helper when its sources are newer than the library, and a
# phone has no compiler, so the payload carries no sources to trigger that.
rm -rf "$ASSETS"
mkdir -p "$ASSETS"
cd "$SRC"
find klippy -name "*.py" | while read -r f; do
  mkdir -p "$ASSETS/$(dirname "$f")"
  cp "$f" "$ASSETS/$f"
done
cp "$SRC/klippy/chelper/c_helper.so" "$ASSETS/klippy/chelper/c_helper.so"
cat > "$ASSETS/MANIFEST.txt" <<TXT
Klipper host payload for the Android app.
Source: Klipper3d/klipper at $TAG
Contents: klippy Python tree (*.py only) plus a prebuilt bionic chelper.
The chelper C sources are deliberately absent: klippy compiles the helper when the
sources are newer than the library, and with no sources present there is nothing
to trigger that on a phone that has no compiler. Klipper's own sources are
available upstream and in the checkout this was staged from.
TXT

COUNT="$(find "$ASSETS" -name "*.py" | wc -l)"
SIZE="$(du -sh "$ASSETS" | cut -f1)"
[ "$COUNT" -ge 150 ] || { echo "expected the whole klippy tree, found $COUNT files" >&2; exit 1; }
echo "staged: $COUNT python files, $SIZE, helper $(sha256sum "$ASSETS/klippy/chelper/c_helper.so" | cut -c1-16)"
if ! git -C "$ROOT" check-ignore -q "$ASSETS" 2>/dev/null; then
  echo "warning: $ASSETS is not gitignored, so Klipper's sources would be committed" >&2
fi
