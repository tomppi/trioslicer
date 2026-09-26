#!/usr/bin/env bash
# Verifies that klippy plans a real G-code file and generates steps for it, with no
# printer and no micro-controller at all.
#
# klippy's batch mode takes a dictionary of the micro-controller's commands - the file
# a firmware build produces - in place of a serial port, and writes the step stream it
# would have sent to a file instead. That exercises the whole motion pipeline:
# config, kinematics, the C helper, step generation. It is the check to run before
# blaming hardware, and the one to run on the phone to prove the payload works there
# without waiting for a printer.
#
#   PYTHON:  interpreter that can run klippy. On the phone this is the payload's
#            libklipper_exec.so; on a host it is a python with klippy's dependencies.
#   KLIPPY:  path to klippy.py.
#   DICT:    the micro-controller dictionary.
#   CONFIG:  a printer config. Default: the app's asset, with the two placeholders
#            substituted for scratch paths.
#
# Usage:
#   scripts/verify-klipper-batch.sh
#   PYTHON=... KLIPPY=... DICT=... scripts/verify-klipper-batch.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON="${PYTHON:-$ROOT/.build/klipper-python/py-host/bin/python3.11}"
KLIPPY="${KLIPPY:-$ROOT/.build/klipper-src/klippy/klippy.py}"
DICT="${DICT:-$ROOT/.build/klipper-src/out/klipper.dict}"
CONFIG="${CONFIG:-}"
GCODE="${GCODE:-$ROOT/native/klipper-pty/batch-motion.gcode}"

WORK="${WORK:-$(mktemp -d)}"
mkdir -p "$WORK/gcodes"

for f in "$PYTHON" "$KLIPPY" "$DICT" "$GCODE"; do
  [ -e "$f" ] || { echo "missing $f (set PYTHON/KLIPPY/DICT/GCODE)" >&2; exit 2; }
done

if [ -z "$CONFIG" ]; then
  # The app's own config, with the placeholders its service fills in at run time.
  # Batch mode never opens the serial port, so the value only has to be a path.
  CONFIG="$WORK/printer.cfg"
  sed -e "s|__SERIAL__|$WORK/batch|" -e "s|__GCODES__|$WORK/gcodes|" \
    "$ROOT/app/src/main/assets/klipper-host/printer.cfg" > "$CONFIG"
  # Added to this copy rather than to the shipped config: SET_KINEMATIC_POSITION is
  # what says where the axes are when there is no micro-controller to home against,
  # and it comes from the force_move module, which klippy loads only when a config
  # asks for it. It is a debugging aid and has no place in a printer's configuration.
  # enable_force_move is what registers SET_KINEMATIC_POSITION: the module is loaded
  # by the section, but the command only exists when the option asks for it.
  printf '\n[force_move]\nenable_force_move: True\n' >> "$CONFIG"
fi

echo "interpreter: $PYTHON"
echo "klippy:      $KLIPPY"
echo "dictionary:  $DICT"
echo "config:      $CONFIG"
echo "gcode:       $GCODE"
echo "work:        $WORK"

OUTPUT="$WORK/steps.bin"
LOG="$WORK/klippy.log"
set +e
# Timed, because this is the feasibility question in miniature: how long the host
# needs to plan a file and generate the steps for it. The same number from the phone
# is the one that matters, and the same command produces it.
STARTED=$(date +%s.%N)
"$PYTHON" "$KLIPPY" -i "$GCODE" -o "$OUTPUT" -d "$DICT" -l "$LOG" "$CONFIG"
STATUS=$?
FINISHED=$(date +%s.%N)
set -e

ELAPSED=$(echo "$FINISHED - $STARTED" | bc)
MOVES=$(grep -c "^G1" "$GCODE" || true)
echo
echo "klippy exited with $STATUS"
STEPS=$(stat -c%s "$OUTPUT" 2>/dev/null || echo 0)
echo "step stream: $STEPS bytes"
# Wall clock for the whole run, which includes starting the interpreter and loading
# klippy. It is a sanity figure rather than a rate: a meaningful rate needs a file the
# size of a print, and the number that answers the feasibility question is the link's
# own stats during a real one, which the app shows.
echo "wall clock:  ${ELAPSED}s (${MOVES} moves, interpreter start included)"
grep -c . "$LOG" | sed 's/^/log lines: /'
echo "--- timed section of the log, if klippy reported one ---"
grep -E "^Stats|print_time=" "$LOG" | tail -2 || true
echo "--- what klippy said ---"
grep -E "Loaded MCU|Configured MCU|shutdown|Error|error" "$LOG" | tail -8 || true

if [ "$STATUS" -ne 0 ] || [ "$STEPS" -le 0 ]; then
  echo >&2
  echo "FAILED: klippy did not complete a batch run with steps" >&2
  tail -20 "$LOG" >&2 || true
  exit 1
fi
echo "OK: planned and stepped the file with no hardware"
