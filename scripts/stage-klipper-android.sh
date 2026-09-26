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

# Stage the Python tree, the helper, and klippy's own .cfg data files. The latter
# are not optional: the thermistor tables and the display menus live in them, so a
# payload of pure Python starts and then dies reading the printer's configuration
# with "Unable to open config file .../extras/temperature_sensors.cfg".
#
# The .c and .h files are left behind on purpose: klippy compiles the helper when
# its sources are newer than the library, and a phone has no compiler, so the
# payload carries no sources to trigger that.
# Checked before anything is removed. This deletes the staged payload and rebuilds it,
# and running it without the interpreter that supplies the standard library used to
# destroy a working payload and then fail - leaving a tree of pure klippy that builds,
# installs, and starts on the phone only to die importing encodings.
PY_LIB="$PY_PREFIX/lib/python3.11"
[ -d "$PY_LIB" ] || {
  echo "no interpreter under $PY_PREFIX: run build-klipper-python-android.sh first" >&2
  echo "(the default is .build/py311-android; set PY_PREFIX if it is elsewhere)" >&2
  exit 1
}

rm -rf "$ASSETS"
mkdir -p "$ASSETS"
cd "$SRC"
find klippy \( -name "*.py" -o -name "*.cfg" \) | while read -r f; do
  mkdir -p "$ASSETS/$(dirname "$f")"
  cp "$f" "$ASSETS/$f"
done
cp "$SRC/klippy/chelper/c_helper.so" "$ASSETS/klippy/chelper/c_helper.so"

# Klipper's own license, with Klipper's own code: the terms travel with the thing they
# cover. Nothing else in this repository is under them, and staging this file is not a
# statement about the rest - see docs/KLIPPER_VENDORING.md for what the boundary is and
# what publishing would involve.
cp "$SRC/COPYING" "$ASSETS/COPYING"

# The standard library and the extensions, taken from the interpreter built for the
# phone. PYTHONHOME points at the payload root on the device, so the tree has to
# keep the layout that interpreter was installed with.
#
# Pruned to what a klippy run reaches: the test suite alone is half the tree, and
# the development headers, IDLE, tkinter and the 2to3 tools are never imported.
# __pycache__ is this machine's bytecode - the phone would at best ignore it, and
# it doubles the payload.
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
Contents: the klippy Python tree with its .cfg data files, a prebuilt bionic
chelper, and the standard library and extensions of the interpreter built by
build-klipper-python-android.sh and build-klipper-extensions-android.sh.
The chelper C sources are deliberately absent: klippy compiles the helper when the
sources are newer than the library, and with no sources present there is nothing
to trigger that on a phone that has no compiler. Klipper's own sources are
available upstream and in the checkout this was staged from.

One line of klippy/util.py is patched against this file's own copy: its
create_pty() chmods a /dev/pts node, which Android does not allow an app to do.
The patch is in scripts/stage-klipper-android.sh, which is the whole diff.
TXT

# One patch to klippy, applied here so that what ships is what this script built.
#
# util.create_pty() chmods the pty node it just made, so that a client running as
# a different user (OctoPrint, say) can open it. Android does not allow that:
# SELinux grants appdomain devpts:chr_file { getattr read write ioctl } and offers
# no setattr, and the denial is dontaudited, so it reaches Python as a bare EACCES
# and klippy exits during startup. Nothing else opens this pty - the app owns the
# node and is its only reader - so skipping the chmod changes nothing but lets the
# host run. klippy is otherwise exactly upstream's code.
python3 - "$ASSETS/klippy/util.py" <<'PATCH'
import sys
path = sys.argv[1]
source = open(path).read()
old = "    os.chmod(filename, 0o660)\n"
new = ("    try:\n"
       "        os.chmod(filename, 0o660)\n"
       "    except OSError:\n"
       "        # Android: an app may not setattr a devpts node, and the denial is\n"
       "        # dontaudited. No other user opens this pty.\n"
       "        pass\n")
if old not in source:
    sys.exit("stage: util.py no longer chmods the pty; the patch needs rewriting")
open(path, "w").write(source.replace(old, new, 1))
PATCH
grep -q "No other user opens this pty" "$ASSETS/klippy/util.py" \
  || { echo "the util.py patch did not apply" >&2; exit 1; }

# The second patch, and the more interesting one.
#
# mcu.py decides how to reach the micro-controller: with a baud rate it uses
# connect_uart, which opens the port through pyserial with exclusive=True, and
# pyserial's lock is flock. Without a baud rate it uses connect_pipe, a plain
# os.open. Upstream already treats two kinds of path as "not a real UART" and
# leaves the baud rate unset for them - /dev/rpmsg_ and /tmp/klipper_host_, the
# Linux host MCU's pty. A pty is the same case and Android is where it matters:
# there is no /tmp to put one in, and flock on a devpts node is denied to an app
# (SELinux grants devpts:chr_file getattr read write ioctl and no lock, and
# dontaudits the denial), so connect_uart fails as a bare EACCES forever.
#
# The condition is exact rather than Android-specific: a pty has no baud rate to
# set on any platform, so a serial port that resolves into /dev/pts is a pipe
# connection wherever this runs. /dev/ttyUSB0 and /dev/serial/by-id/... are
# unaffected.
python3 - "$ASSETS/klippy/mcu.py" <<'PATCH'
import sys
path = sys.argv[1]
source = open(path).read()
old = """            if not (self._serialport.startswith("/dev/rpmsg_")
                    or self._serialport.startswith("/tmp/klipper_host_")):
                self._baud = config.getint('baud', 250000, minval=2400)
"""
new = """            if not (self._serialport.startswith("/dev/rpmsg_")
                    or self._serialport.startswith("/tmp/klipper_host_")
                    or os.path.realpath(self._serialport).startswith("/dev/pts/")):
                self._baud = config.getint('baud', 250000, minval=2400)
"""
if old not in source:
    sys.exit("stage: mcu.py no longer treats paths this way; the patch needs rewriting")
open(path, "w").write(source.replace(old, new, 1))
PATCH
grep -q 'realpath(self._serialport).startswith("/dev/pts/")' "$ASSETS/klippy/mcu.py" \
  || { echo "the mcu.py patch did not apply" >&2; exit 1; }

# The third patch, and the one that stops the printer.
#
# Android's libc has no getloadavg, so CPython was configured and built without
# os.getloadavg. The statistics module calls it every stats interval and does not
# catch it, so klippy reached "Loaded MCU" and "Configured MCU" and then died on
# this line, taking the printer to shutdown with it. The load average is reported
# in the log and read by nothing.
python3 - "$ASSETS/klippy/extras/statistics.py" <<'PATCH'
import sys
path = sys.argv[1]
source = open(path).read()
old = "        self.last_load_avg = os.getloadavg()[0]\n"
new = ("        try:\n"
       "            self.last_load_avg = os.getloadavg()[0]\n"
       "        except (AttributeError, OSError):\n"
       "            # Android: bionic has no getloadavg and this interpreter was\n"
       "            # built without it. Reported to the log and read by nothing.\n"
       "            self.last_load_avg = 0.\n")
if old not in source:
    sys.exit("stage: statistics.py no longer reads the load average; patch needs rewriting")
open(path, "w").write(source.replace(old, new, 1))
PATCH
grep -q "bionic has no getloadavg" "$ASSETS/klippy/extras/statistics.py" \
  || { echo "the statistics.py patch did not apply" >&2; exit 1; }

COUNT="$(find "$ASSETS" -name "*.py" | wc -l)"
SIZE="$(du -sh "$ASSETS" | cut -f1)"
[ "$COUNT" -ge 150 ] || { echo "expected the whole klippy tree, found $COUNT files" >&2; exit 1; }
[ -s "$ASSETS/lib/python3.11/os.py" ] || { echo "the standard library is missing" >&2; exit 1; }
echo "staged: $COUNT python files, $(find "$ASSETS" -type f | wc -l) files, $SIZE"
echo "helper $(sha256sum "$ASSETS/klippy/chelper/c_helper.so" | cut -c1-16)"
if ! git -C "$ROOT" check-ignore -q "$ASSETS" 2>/dev/null; then
  echo "warning: $ASSETS is not gitignored, so Klipper's sources would be committed" >&2
fi
