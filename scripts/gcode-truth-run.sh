#!/usr/bin/env bash
# Slice one configuration in the app and bring everything back for comparison.
#
# The app deletes its engine request directory after a slice, and only Cura's
# survives by accident. So the workspace is copied out while the slice runs, by
# polling from the host - a phone-side loop under su never started reliably.
#
# Usage: gcode-truth-run.sh <cura|prusa|orca> <on|off> [tag]
set -euo pipefail

SERIAL="${SERIAL:-984bd59e}"
ENGINE="${1:?engine: cura|prusa|orca}"
SUPPORTS="${2:?supports: on|off}"
TAG="${3:-$ENGINE-supports-$SUPPORTS}"

REPO="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$REPO/.build/gcode-truth/runs/$TAG"
PKG=com.tomppi.enderslicercura
ADB=(adb -s "$SERIAL")

case "$ENGINE" in
  cura)  SETTINGS_KEY=settings-json;       ELEMENT=CURA ;;
  prusa) SETTINGS_KEY=prusa-settings-json; ELEMENT=PRUSA ;;
  orca)  SETTINGS_KEY=orca-settings-json;  ELEMENT=ORCA ;;
  *) echo "unknown engine $ENGINE" >&2; exit 2 ;;
esac

case "$ENGINE:$SUPPORTS" in
  cura:on)   SUPPORT_KEY=supportsEnabled; SUPPORT_VALUE=true ;;
  cura:off)  SUPPORT_KEY=supportsEnabled; SUPPORT_VALUE=false ;;
  prusa:on)  SUPPORT_KEY=supportMaterial; SUPPORT_VALUE=true ;;
  prusa:off) SUPPORT_KEY=supportMaterial; SUPPORT_VALUE=false ;;
  orca:on)   SUPPORT_KEY=supportEnabled;  SUPPORT_VALUE=true ;;
  orca:off)  SUPPORT_KEY=supportEnabled;  SUPPORT_VALUE=false ;;
esac

mkdir -p "$OUT"
echo "== $TAG: engine=$ENGINE supports=$SUPPORTS =="

"${ADB[@]}" shell am force-stop "$PKG"
sleep 2
STATE="$OUT/enderslicer-state.xml"
"${ADB[@]}" shell su -c "cat /data/data/$PKG/shared_prefs/enderslicer-state.xml" > "$STATE"
python3 "$REPO/scripts/set-app-setting.py" "$STATE" "$SETTINGS_KEY" "$SUPPORT_KEY" "$SUPPORT_VALUE"
"${ADB[@]}" push "$STATE" /sdcard/Download/dsh-agent/gcode-truth/state.xml >/dev/null
"${ADB[@]}" shell su -c "cp /sdcard/Download/dsh-agent/gcode-truth/state.xml /data/data/$PKG/shared_prefs/enderslicer-state.xml"
# Written host-side and copied in. An inline printf with nested quotes ran as the
# shell user and was refused, which aborted the run before it ever sliced.
printf '<map>\n    <string name="engine">%s</string>\n</map>\n' "$ELEMENT" > "$OUT/slicer-engine.xml"
"${ADB[@]}" push "$OUT/slicer-engine.xml" /sdcard/Download/dsh-agent/gcode-truth/engine.xml >/dev/null
"${ADB[@]}" shell su -c "cp /sdcard/Download/dsh-agent/gcode-truth/engine.xml /data/data/$PKG/shared_prefs/slicer-engine.xml"
echo "   settings written"

"${ADB[@]}" shell su -c 'input keyevent 224' || true
"${ADB[@]}" shell su -c 'wm dismiss-keyguard' || true
sleep 1
"${ADB[@]}" shell cmd statusbar collapse || true
"${ADB[@]}" shell am start -n "$PKG/com.tomppi.enderslicer.MainActivity" >/dev/null
sleep 18
"${ADB[@]}" shell su -c 'input tap 675 2290' || true
sleep 4

( for i in $(seq 1 400); do
    for eng in curaengine prusaengine orcaengine; do
      "${ADB[@]}" shell su -c "for d in /data/user/0/$PKG/cache/$eng/requests/*; do n=$(basename $d); t=/sdcard/Download/dsh-agent/gcode-truth/captured/$eng-$n; if [ ! -f $t/.done ] && [ -f $d/model.stl ] && [ -f $d/output.gcode ]; then mkdir -p $t; cp -r $d/. $t/ 2>/dev/null; touch $t/.done; fi; done" 2>/dev/null || true
    done
    sleep 1
  done ) &
WATCHER=$!

"${ADB[@]}" shell su -c 'input tap 243 1889' || true
echo "   slice started"
DONE=""
for i in $(seq 1 40); do
  sleep 10
  DONE="$("${ADB[@]}" shell su -c "ls -t /data/data/$PKG/files/slice-results/ 2>/dev/null | head -1" | tr -d '\r')"
  echo "   $((i*10))s: newest result $DONE"
  [ -n "$DONE" ] && break
done
kill $WATCHER 2>/dev/null || true

"${ADB[@]}" shell su -c "tar -cf /sdcard/Download/dsh-agent/gcode-truth/result.tar -C /data/data/$PKG/files/slice-results $DONE" 2>/dev/null || true
"${ADB[@]}" pull /sdcard/Download/dsh-agent/gcode-truth/result.tar "$OUT/" >/dev/null 2>&1 || true
mkdir -p "$OUT/result" && tar -xf "$OUT/result.tar" -C "$OUT/result" 2>/dev/null || true
LOGNAME="$("${ADB[@]}" shell su -c "ls -t /data/data/$PKG/files/logs/ 2>/dev/null | head -1" | tr -d '\r')"
"${ADB[@]}" shell su -c "cat /data/data/$PKG/files/logs/$LOGNAME" > "$OUT/slice.log" 2>/dev/null || true
rm -rf "$OUT/captured"
"${ADB[@]}" pull /sdcard/Download/dsh-agent/gcode-truth/captured "$OUT/captured" >/dev/null 2>&1 || true
find "$OUT" \( -name '*.gcode' -o -name '*.ini' -o -name 'model.stl' \) | head -20
echo "== $TAG captured under $OUT =="
