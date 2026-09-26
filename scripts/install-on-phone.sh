#!/usr/bin/env bash
# Puts the current build on the phone, over the network, and says what landed.
#
# The objective asks for installation "with the same package and signing key", and this
# is that step in one command: it checks the APK really is signed by this repository's
# key before installing, because the failure that matters is an install that silently
# replaces the app with one signed by something else - or refuses and leaves the old one
# with the payload and gcode already extracted.
#
# The link is a phone on a network, and this is the part that used to cost cycles:
# adb over the network drops, adbd loses root across a restart, and a stale server
# answers for a device that is gone. So it reconnects, re-roots and retries rather than
# reporting the first refusal as the truth.
#
#   SERIAL:   the device. Default 100.72.208.101:5555.
#   APK:      what to install. Default the release build, which is signed.
#   SKIP_BUILD=1 to install whatever is already there.
#
# Usage: scripts/install-on-phone.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERIAL="${SERIAL:-100.72.208.101:5555}"
APK="${APK:-$ROOT/app/build/outputs/apk/release/app-release.apk}"
APP_ID=com.tomppi.enderslicercura

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  echo "building the release APK"
  (cd "$ROOT" && ./gradlew :app:assembleRelease --console=plain -q)
fi
[ -f "$APK" ] || { echo "no APK at $APK" >&2; exit 2; }

# The signer, before anything is installed over the user's data.
BUILD_TOOLS="$(ls -d ${ANDROID_HOME:-/root/Android/Sdk}/build-tools/*/ \
  /opt/android-sdk/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
APK_CERT=$("$BUILD_TOOLS/apksigner" verify --print-certs "$APK" 2>/dev/null \
  | sed -n 's/.*SHA-256 digest: //p' | head -1 | tr -d ':' | tr 'a-f' 'A-F')
ALIAS=$(sed -n 's/^keyAlias=//p' "$ROOT/keystore.properties" 2>/dev/null | tr -d '\r')
PASS=$(sed -n 's/^storePassword=//p' "$ROOT/keystore.properties" 2>/dev/null | tr -d '\r')
KEY_CERT=$(keytool -list -v -keystore "$ROOT/keystore/release.jks" -storepass "$PASS" \
  -alias "$ALIAS" 2>/dev/null | sed -n 's/.*SHA256: //p' | head -1 | tr -d ':' | tr 'a-f' 'A-F')
if [ -z "$APK_CERT" ] || [ "$APK_CERT" != "$KEY_CERT" ]; then
  echo "$APK is not signed by this repository's key; it would not update the install" >&2
  exit 2
fi
echo "signer:  ${APK_CERT:0:16}... (matches keystore/release.jks)"

connect() {
  adb connect "$SERIAL" >/dev/null 2>&1 || true
  adb devices | awk -v s="$SERIAL" '$1 == s && $2 == "device"' | grep -q .
}

# A stale server answers for a device that is gone, and adbd that lost root across a
# restart makes every read of the app's files fail with a message about permissions.
for attempt in 1 2 3; do
  connect && break
  echo "no device at $SERIAL (attempt $attempt); restarting the server"
  adb kill-server >/dev/null 2>&1 || true
  sleep 2
done
connect || { echo "no device at $SERIAL - is adb enabled and the network up?" >&2; exit 2; }

ADB="adb -s $SERIAL"
BEFORE=$($ADB shell "dumpsys package $APP_ID 2>/dev/null | sed -n 's/.*versionName=//p' | head -1" | tr -d '\r')
echo "on the phone now: ${BEFORE:-not installed}"

$ADB root >/dev/null 2>&1 || true
sleep 2
$ADB install -r "$APK"
AFTER=$($ADB shell "dumpsys package $APP_ID | sed -n 's/.*versionName=//p' | head -1" | tr -d '\r')
echo "now:             $AFTER"
echo "payload:         $($ADB shell "ls $APP_ID/files 2>/dev/null | head -3" | tr -d '\r' | paste -sd' ' -)"

# The printer screen starts the host itself; starting it here would need the activity
# in front and the printer attached, and both are the runbook's business, not this
# script's.
echo
echo "installed. The runbook for bringing the printer up is in docs/KLIPPER_ANDROID.md."
