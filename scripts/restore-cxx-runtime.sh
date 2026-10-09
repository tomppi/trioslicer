#!/usr/bin/env bash
# Put a complete libc++_shared.so back into jniLibs.
#
# The Blender engine package ships its own jniLibs/ with 120 runtime libraries, and the
# libc++_shared.so among them has been stripped of dynamic symbols - 2195 against the
# NDK's 2340. Among the 145 that go is the vtable for std::ostringstream, which OCP
# needs, so a release built from a fresh checkout failed every CAD command with
#
#   build123d import failed: ImportError('dlopen failed: cannot locate symbol
#   "_ZTVNSt6__ndk119basic_ostringstreamIcNS_11char_traitsIcEENS_9allocatorIcEEEE"
#   referenced by "/data/data/com.tom...
#
# while a developer's tree, whose jniLibs already held an intact copy from an earlier
# staging, worked. That is why it looked like debug-versus-release and was not: it was
# staged-versus-not.
#
# This is deliberately NOT inside fetch-blender-engine-android.sh. That script stages
# assets as well as libraries, and an earlier attempt to fold this in there broke the
# asset staging in a way that took two runs to notice. A repair belongs on its own.
#
# Run it after the engine fetches and before Gradle.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET="${ROOT}/app/src/main/jniLibs/arm64-v8a/libc++_shared.so"
SYMBOL='_ZTVNSt6__ndk119basic_ostringstreamIcNS_11char_traitsIcEENS_9allocatorIcEEEE'

if [ ! -f "${TARGET}" ]; then
  echo "no libc++_shared.so staged; nothing to restore" >&2
  exit 1
fi

find_ndk_file() {
  local name="$1" root
  for root in "${ANDROID_NDK_HOME:-}" "${ANDROID_HOME:-}/ndk" /opt/android-sdk/ndk; do
    [ -n "${root}" ] || continue
    [ -d "${root}" ] || continue
    local hit
    hit="$(ls -1 "${root}"/*/"$2" 2>/dev/null | tail -1)"
    [ -n "${hit}" ] && { echo "${hit}"; return 0; }
  done
  return 1
}

SRC="$(find_ndk_file libc++_shared.so \
  toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so || true)"
if [ -z "${SRC}" ] || [ ! -f "${SRC}" ]; then
  echo "no NDK libc++_shared.so found; the staged one is left as it is" >&2
  exit 1
fi

cp -f "${SRC}" "${TARGET}"

# --strip-unneeded keeps the dynamic symbol table. --strip-all is what removed the
# vtables in the first place, so it must not be used on this file.
STRIP="$(find_ndk_file llvm-strip \
  toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip || true)"
if [ -n "${STRIP}" ]; then
  "${STRIP}" --strip-unneeded "${TARGET}"
else
  echo "no llvm-strip found; the restored runtime stays unstripped" >&2
fi

NM="$(find_ndk_file llvm-nm toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm || true)"
if [ -n "${NM}" ]; then
  # Written to a file rather than piped into grep -q: grep exits at the first match,
  # nm takes SIGPIPE, and under pipefail that reads as a failure. It did, on the first
  # run of this script, reporting the symbol missing when it had just been restored.
  SYMBOLS="$(mktemp)"
  trap 'rm -f "${SYMBOLS}"' EXIT
  "${NM}" -D --defined-only "${TARGET}" > "${SYMBOLS}" 2>/dev/null || true
  if ! grep -q "${SYMBOL}" "${SYMBOLS}"; then
    echo "libc++_shared.so still lacks ${SYMBOL} after restoring it" >&2
    exit 1
  fi
fi

echo "restored libc++_shared.so ($(stat -c%s "${TARGET}") bytes, vtable present)"
