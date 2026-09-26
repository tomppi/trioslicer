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
#   PY_PREFIX:    the Android interpreter to take the standard library and the
#                 extensions from. Default: .build/py311-android
#
# Usage: scripts/build-klipper-python-android.sh
#        scripts/build-klipper-extensions-android.sh
#        scripts/stage-klipper-android.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="${KLIPPER_TAG:-v0.13.0}"
SRC="${KLIPPER_SRC:-$ROOT/.build/klipper-src}"
ASSETS="${APP_ASSETS:-$ROOT/app/src/main/assets/klipper}"
PY_PREFIX="${PY_PREFIX:-$ROOT/.build/py311-android}"

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

# The standard library and the extensions, taken from the interpreter built for the
# phone. PYTHONHOME points at the payload root on the device, so the tree has to
# keep the layout that interpreter was installed with.
#
# Pruned to what a klippy run reaches: the test suite alone is half the tree, and
# the development headers, IDLE, tkinter and the 2to3 tools are never imported.
# __pycache__ is this machine's bytecode - the phone would at best ignore it, and
# it doubles the payload.
PY_LIB="$PY_PREFIX/lib/python3.11"
[ -d "$PY_LIB" ] || {
  echo "no interpreter under $PY_PREFIX: run build-klipper-python-android.sh first" >&2
  exit 1
}
mkdir -p "$ASSETS/lib"
tar -C "$(dirname "$PY_LIB")" -cf - \
  --exclude=test --exclude=tkinter --exclude=idlelib --exclude=lib2to3 \
  --exclude=ensurepip --exclude=turtledemo --exclude=__pycache__ \
  --exclude=config-3.11 \
  --exclude='*-x86_64-linux-gnu.so' \
  python3.11 | tar -C "$ASSETS/lib" -xf -

# klippy imports cffi, greenlet, serial and jinja2 before it reads a line of the
# printer's configuration, so a payload without them runs nowhere.
for pkg in cffi greenlet serial jinja2 markupsafe pycparser; do
  [ -e "$ASSETS/lib/python3.11/site-packages/$pkg" ] || {
    echo "payload is missing $pkg: run build-klipper-extensions-android.sh" >&2
    exit 1
  }
done
# greenlet is the one extension that resolves Python symbols only when libpython is
# named as a dependency, and the way a payload built without that fails on the phone
# is an ImportError from a file that is plainly there. Checked on the staged tree
# rather than on the source it came from.
for so in "$ASSETS"/lib/python3.11/site-packages/greenlet/_greenlet*.so; do
  "$CC_DIR/llvm-readelf" -d "$so" | grep -q libpython || {
    echo "staged greenlet does not link libpython: $so" >&2
    exit 1
  }
  "$CC_DIR/llvm-readelf" -h "$so" | grep -q AArch64 || {
    echo "staged greenlet is not AArch64: $so" >&2
    exit 1
  }
done

cat > "$ASSETS/MANIFEST.txt" <<TXT
Klipper host payload for the Android app.
Source: Klipper3d/klipper at $TAG
Contents: the klippy Python tree (*.py only), a prebuilt bionic chelper, and the
standard library and extensions of the interpreter built by
build-klipper-python-android.sh and build-klipper-extensions-android.sh.
The chelper C sources are deliberately absent: klippy compiles the helper when the
sources are newer than the library, and with no sources present there is nothing
to trigger that on a phone that has no compiler. Klipper's own sources are
available upstream and in the checkout this was staged from.
TXT

COUNT="$(find "$ASSETS" -name "*.py" | wc -l)"
SIZE="$(du -sh "$ASSETS" | cut -f1)"
[ "$COUNT" -ge 150 ] || { echo "expected the whole klippy tree, found $COUNT files" >&2; exit 1; }
[ -s "$ASSETS/lib/python3.11/os.py" ] || { echo "the standard library is missing" >&2; exit 1; }
echo "staged: $COUNT python files, $(find "$ASSETS" -type f | wc -l) files, $SIZE"
echo "helper $(sha256sum "$ASSETS/klippy/chelper/c_helper.so" | cut -c1-16)"
if ! git -C "$ROOT" check-ignore -q "$ASSETS" 2>/dev/null; then
  echo "warning: $ASSETS is not gitignored, so Klipper's sources would be committed" >&2
fi
