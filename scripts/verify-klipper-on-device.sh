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
GCODE="${GCODE:-$ROOT/native/klipper-pty/batch-motion.gcode}"
STAGE="/data/local/tmp/klipper-batch"
# MOVES: generate a print's worth of moves instead of using the fixture. The planning
# rate only means something at that size, and this is the measurement the objective
# asks for - the same one verify-klipper-batch.sh takes on a host.
MOVES="${MOVES:-0}"

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
if [ "$MOVES" -gt 0 ]; then
  GCODE="/tmp/klipper-device-generated.gcode"
  awk -v n="$MOVES" 'BEGIN {
    print "G90"; print "G21"; print "M107"
    print "SET_KINEMATIC_POSITION X=0 Y=0 Z=10"
    for (i = 0; i < n; i++) {
      x = 10 + (i % 50) * 2
      y = 10 + int(i / 50) % 50
      printf "G1 X%d Y%d.%d E0.05 F3600\n", x, y, i % 10
    }
  }' > "$GCODE"
fi

$ADB shell "rm -rf $STAGE && mkdir -p $STAGE/gcodes"
sed -e "s|__SERIAL__|$STAGE/batch|" -e "s|__GCODES__|$STAGE/gcodes|" \
  "$ROOT/app/src/main/assets/klipper-host/printer.cfg" > /tmp/klipper-batch-config.cfg
printf '\n[force_move]\nenable_force_move: True\n' >> /tmp/klipper-batch-config.cfg
$ADB push /tmp/klipper-batch-config.cfg "$STAGE/printer.cfg" >/dev/null
$ADB push "$GCODE" "$STAGE/batch-motion.gcode" >/dev/null
$ADB push "$DICT" "$STAGE/klipper.dict" >/dev/null

echo
echo "=== running the payload's klippy in batch mode on the device ==="
# Timed around the run rather than by the script: the pushes above are this host's
# work, and the number that matters is how long the phone takes to plan. The adb
# round trip either side is about a tenth of a second.
STARTED=$(date +%s.%N)
set +e
$ADB shell "cd $PAYLOAD && PYTHONHOME=$PAYLOAD \
  LD_LIBRARY_PATH=$PAYLOAD/libexec:$NATIVE \
  $NATIVE/libklipper_exec.so $PAYLOAD/klippy/klippy.py \
  -i $STAGE/batch-motion.gcode -o $STAGE/steps.bin -d $STAGE/klipper.dict \
  -l $STAGE/klippy.log $STAGE/printer.cfg"
STATUS=$?
FINISHED=$(date +%s.%N)
set -e
ELAPSED=$(echo "$FINISHED - $STARTED" | bc)

STEPS=$($ADB shell "stat -c%s $STAGE/steps.bin 2>/dev/null || echo 0" | tr -d '\r' | head -1)
PLANNED=$(grep -c "^G1" "$GCODE" || true)
# Counted with awk rather than grep: grep exits 1 when the count is zero, and under
# this script's pipefail that ends the run before it prints its summary - which is
# exactly how a successful batch run came to look like a hang.
REFUSED=$($ADB shell "awk '/Must home axis first/{n++} END{print n+0}' $STAGE/klippy.log 2>/dev/null" \
  | tr -d '\r' | head -1)
REFUSED="${REFUSED:-0}"

echo
echo "klippy exited with  $STATUS"
echo "step stream:   $STEPS bytes"
echo "moves planned: $PLANNED (refused: $REFUSED)"
echo "wall clock:    ${ELAPSED}s on the phone"
if [ "${PLANNED:-0}" -ge 1000 ]; then
  echo "planning:      $(echo "scale=0; $PLANNED / $ELAPSED" | bc) moves/s"
fi
if [ "$STATUS" -ne 0 ] || [ "$STEPS" -le 0 ] || [ "$REFUSED" -ne 0 ]; then
  echo >&2
  echo "FAILED on the device; last of its log:" >&2
  $ADB shell "tail -20 $STAGE/klippy.log" >&2 || true
  exit 1
fi
echo "OK: the payload planned $PLANNED moves on the phone and wrote $STEPS bytes of steps"
