#!/usr/bin/env bash
# Check, and where possible bring up, everything the app talks to.
#
#   ./scripts/env-setup.sh
#
# The app itself builds with scripts/setup.sh. This is the layer around it: the
# phone it drives, the harness that runs the AI sessions, and the GPU box that
# generates models. None of it is bundled - all three are yours - so this
# reports what is reachable and what is missing, and never guesses.
#
# Configuration comes from the environment, because none of these addresses
# belong in a repository:
#
#   PHONE_TAILNET   phone's tailnet address, host:port  (preferred - works anywhere)
#   PHONE_LAN       phone's LAN address, host:port      (fallback - same WiFi only)
#   HARNESS_ORIGIN  the DeepSeek harness, default http://127.0.0.1:3080
#   GPU_BOX         ssh target for the GPU box, optional, e.g. <user>@<host>
#
# Nothing here is destructive: it connects, reads and reports. The one thing it
# changes is an adb forward, which is how the engine's socket is reached.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

PHONE_TAILNET="${PHONE_TAILNET:-}"
PHONE_LAN="${PHONE_LAN:-}"
HARNESS_ORIGIN="${HARNESS_ORIGIN:-http://127.0.0.1:3080}"
GPU_BOX="${GPU_BOX:-}"
ADB="${ADB:-adb}"
PORT="${BLENDER_MCP_PORT:-9876}"

RESULTS=()
note() { RESULTS+=("$1|$2|$3"); printf '  %-9s %-26s %s\n' "$1" "$2" "$3"; }
have() { command -v "$1" >/dev/null 2>&1; }
ok()   { note "ok"       "$1" "$2"; }
warn() { note "warn"     "$1" "$2"; }
bad()  { note "MISSING"  "$1" "$2"; }

echo "== build toolchain =="
if have java; then
  JV="$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
  [ "${JV}" = "17" ] && ok "java" "$(java -version 2>&1 | head -1)" \
                      || warn "java" "found ${JV}, the build targets 17"
else bad "java" "install JDK 17"; fi

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -z "${SDK}" ] && [ -f local.properties ] && SDK="$(sed -n 's/^sdk.dir=//p' local.properties | sed 's/\\\\/\//g')"
if [ -n "${SDK}" ] && [ -d "${SDK}" ]; then
  ok "android sdk" "${SDK}"
  [ -d "${SDK}/ndk/28.2.13676358" ] && ok "ndk 28.2" "present" \
    || warn "ndk 28.2" "not found; the Cura and Prusa builds need it"
else bad "android sdk" "set ANDROID_HOME or write sdk.dir in local.properties"; fi

for tool in git curl unzip; do
  have "$tool" && ok "$tool" "$(command -v $tool)" || bad "$tool" "required by the staging scripts"
done

echo
echo "== the phone =="
if ! have "${ADB}"; then
  bad "adb" "install platform-tools, or set ADB to its path"
else
  ok "adb" "$(${ADB} version 2>/dev/null | head -1)"
  TARGET=""
  # Tailnet first: it works wherever the tailnet is up, which the LAN address
  # does not - that one needs both machines on the same WiFi.
  for candidate in "${PHONE_TAILNET}" "${PHONE_LAN}"; do
    [ -n "${candidate}" ] || continue
    if "${ADB}" connect "${candidate}" >/dev/null 2>&1; then
      if "${ADB}" -s "${candidate}" get-state >/dev/null 2>&1; then
        ok "phone" "${candidate}"
        TARGET="${candidate}"
        break
      fi
    fi
    warn "phone" "${candidate} did not answer"
  done

  if [ -z "${TARGET}" ]; then
    if [ -z "${PHONE_TAILNET}" ] && [ -z "${PHONE_LAN}" ]; then
      bad "phone" "set PHONE_TAILNET (preferred) and/or PHONE_LAN"
    else
      bad "phone" "no address answered; is adb enabled on the device?"
    fi
  else
    # The engine's socket binds loopback inside the app, so it is reached by
    # forwarding, not by connecting to the phone's own address.
    if "${ADB}" -s "${TARGET}" forward "tcp:${PORT}" "tcp:${PORT}" >/dev/null 2>&1; then
      ok "engine socket" "tcp:${PORT} forwarded - the engine listens on the device"
    else
      warn "engine socket" "could not forward tcp:${PORT}"
    fi
    APP="${APP_ID:-com.tomppi.enderslicercura}"
    if "${ADB}" -s "${TARGET}" shell pm path "${APP}" >/dev/null 2>&1; then
      ok "app installed" "${APP}"
    else
      warn "app installed" "${APP} not found; install the APK or run ./scripts/setup.sh"
    fi
  fi
fi

echo
echo "== the harness =="
if ! have curl; then
  warn "harness" "curl is unavailable, cannot check"
else
  # 401 is the harness saying "sign in": the launch token is what signs you in,
  # and it is printed at startup rather than published (a ?token= URL in the
  # served dist would be a sign-in for anything that could reach the port).
  CODE="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 "${HARNESS_ORIGIN}/" 2>/dev/null || true)"
  if [ -z "${CODE}" ] || [ "${CODE}" = "000" ]; then
    warn "harness" "nothing at ${HARNESS_ORIGIN}; start it or set HARNESS_ORIGIN"
  elif [ "${CODE}" = "401" ]; then
    ok "harness" "${HARNESS_ORIGIN} is up and asking for a sign-in"
    if have dsh-url.sh; then
      echo
      echo "  Paste one of these into the app's chat setup - it carries the launch token:"
      dsh-url.sh 2>/dev/null | sed 's/^/    /' || true
    else
      echo "  Get the ?token= URL from the launcher's console (or dsh-url.sh) and paste it in."
    fi
  else
    ok "harness" "${HARNESS_ORIGIN} answered HTTP ${CODE}"
  fi
fi

echo
echo "== the GPU box =="
if [ -z "${GPU_BOX}" ]; then
  warn "gpu box" "GPU_BOX not set; photo-to-3D needs it, modelling does not"
elif ! have ssh; then
  warn "gpu box" "ssh is unavailable, cannot check"
else
  if ssh -o BatchMode=yes -o ConnectTimeout=5 "${GPU_BOX}" true >/dev/null 2>&1; then
    ok "gpu box" "${GPU_BOX} reachable over ssh"
  else
    warn "gpu box" "${GPU_BOX} did not answer; it hibernates, see the gpu-box-power skill"
  fi
fi

echo
echo "== summary =="
MISSING="$(printf '%s\n' "${RESULTS[@]:-}" | grep -c '^MISSING' || true)"
WARNED="$(printf '%s\n' "${RESULTS[@]:-}" | grep -c '^warn' || true)"
echo "  ${#RESULTS[@]} checks: $(( ${#RESULTS[@]} - MISSING - WARNED )) ok, ${WARNED} warnings, ${MISSING} missing"
if [ "${MISSING}" -gt 0 ]; then
  echo
  printf '%s\n' "${RESULTS[@]}" | grep '^MISSING' | while IFS='|' read -r _ what detail; do
    echo "  - ${what}: ${detail}"
  done
  exit 1
fi
echo "  Ready. Build the app with ./scripts/setup.sh."
