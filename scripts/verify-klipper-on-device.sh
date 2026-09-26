#!/usr/bin/env bash
# Runs klippy's batch mode on the phone, inside the app's own payload, with no printer.
#
# This is the check that the payload plans motion on the device rather than on the host
# it was built on: the interpreter, the standard library, cffi, the C helper and
# klippy's planner all have to work there, and none of that needs a micro-controller.
# Batch mode takes a dictionary of the micro-controller's commands in place of a serial
# port and writes the step stream to a file.
#
# The app has to have run at least once, so its payload is extracted.
#
#   SERIAL:  the device. Default 100.72.208.101:5555.
#   DICT:    the board's dictionary from a firmware build. See verify-klipper-batch.sh
#            for how to produce one - it has to be the board's build, not the host
#            MCU's, or the config's pins will not resolve.
#   APP_ID:  the package. Default com.tomppi.enderslicercura.
#
# Root is used to read the app's own payload, which is how every device check in this
# port has been done. The app itself never needs it: nothing here is part of its path.
#
# Usage: DICT=... scripts/verify-klipper-on-device.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERIAL="${SERIAL:-100.72.208.101:5555}"
APP_ID="${APP_ID:-com.tomppi.enderslicercura}"
DICT="${DICT:-$ROOT/.build/klipper-src/out/klipper.dict}"
GCODE="$ROOT/native/klipper-pty/batch-motion.gcode"
STAGE="/data/local/tmp/klipper-batch"

[ -e "$DICT" ] || { echo "no dictionary at $DICT - see scripts/verify-klipper-batch.sh" >&2; exit 2; }
adb connect "$SERIAL" >/dev/null 2>&1 || true
if ! adb devices | awk -v s="$SERIAL" '$1 == s && $2 == "device"' | grep -q .; then
  echo "no device at $SERIAL (is adb enabled, and is the network up?)" >&2
  exit 2
fi
ADB="adb -s $SERIAL"
$ADB root >/dev/null 2>&1 || true
sleep 2

FILES="/data/user/0/$APP_ID/files"
PAYLOAD="$FILES/klipper"
NATIVE=$($ADB shell "ls -d /data/app/*/$APP_ID*/lib/arm64 2>/dev/null | head -1" | tr -d '\r')
[ -n "$NATIVE" ] || { echo "no native library directory for $APP_ID" >&2; exit 2; }

for f in "klippy/klippy.py" "klippy/chelper/c_helper.so" "libexec/libpython3.11.so.1.0"; do
  $ADB shell "[ -e $PAYLOAD/$f ]" || {
    echo "the payload is missing $f - run the app once so it extracts" >&2; exit 2; }
done
echo "payload:  $PAYLOAD"
echo "native:   $NATIVE"

# The same batch config the host script builds: the app's own, with the two
# placeholders pointed at scratch paths, plus force_move because SET_KINEMATIC_POSITION
# - how axes are told where they are with no endstops to home against - exists only
# when enable_force_move is set.
$ADB shell "rm -rf $STAGE && mkdir -p $STAGE/gcodes"
sed -e "s|__SERIAL__|$STAGE/batch|" -e "s|__GCODES__|$STAGE/gcodes|" \
  "$ROOT/app/src/main/assets/klipper-host/printer.cfg" > /tmp/klipper-batch-config.cfg
printf '\n[force_move]\nenable_force_move: True\n' >> /tmp/klipper-batch-config.cfg
$ADB push /tmp/klipper-batch-config.cfg "$STAGE/printer.cfg" >/dev/null
$ADB push "$GCODE" "$STAGE/batch-motion.gcode" >/dev/null
$ADB push "$DICT" "$STAGE/klipper.dict" >/dev/null

echo
echo "=== running the payload's klippy in batch mode on the device ==="
set +e
$ADB shell "cd $PAYLOAD && PYTHONHOME=$PAYLOAD \
  LD_LIBRARY_PATH=$PAYLOAD/libexec:$NATIVE \
  $NATIVE/libklipper_exec.so $PAYLOAD/klippy/klippy.py \
  -i $STAGE/batch-motion.gcode -o $STAGE/steps.bin -d $STAGE/klipper.dict \
  -l $STAGE/klippy.log $STAGE/printer.cfg"
STATUS=$?
set -e

STEPS=$($ADB shell "stat -c%s $STAGE/steps.bin 2>/dev/null || echo 0" | tr -d '\r')
MOVES=$(grep -c "^G1" "$GCODE" || true)
REFUSED=$($ADB shell "grep -c 'Must home axis first' $STAGE/klippy.log 2>/dev/null || echo 0" | tr -d '\r')

echo
echo "klippy exited with $STATUS"
echo "step stream: $STEPS bytes"
echo "moves:       $MOVES (refused: $REFUSED)"
if [ "$STATUS" -ne 0 ] || [ "$STEPS" -le 0 ] || [ "$REFUSED" -ne 0 ]; then
  echo >&2
  echo "FAILED on the device; last of its log:" >&2
  $ADB shell "tail -20 $STAGE/klippy.log" >&2 || true
  exit 1
fi
echo "OK: the payload planned \$MOVES moves on the phone and wrote \$STEPS bytes of steps"
