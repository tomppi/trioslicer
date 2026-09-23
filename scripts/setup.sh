#!/usr/bin/env bash
# One-command setup: from a clean clone to a debug APK.
#
#   ./scripts/setup.sh
#
# Fails early, and says which script to run, rather than letting Gradle discover
# the problem several minutes in. Each engine is staged by its own script, in the
# same shape app/build.gradle.kts already expects:
#
#   OrcaSlicer    fetched from CI or built  scripts/fetch-orca-engine-android.sh
#   Cura resources  fetched from the pinned tag  scripts/fetch-cura-resources.sh
#   CuraEngine    built from source      scripts/build-curaengine-android.sh
#   PrusaSlicer   fetched or built       scripts/fetch-prusa-engine-android.sh
#   filaSim       built from source      scripts/build-filasim-engine-android.sh
#   Blender       staged from a package  scripts/fetch-blender-engine-android.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT}"

fail() { echo; echo "SETUP FAILED: $*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

echo "== toolchain =="
have java || fail "java not found; JDK 17 is required"
JAVA_MAJOR="$(java -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
[ "${JAVA_MAJOR}" = "17" ] || echo "  warning: found Java ${JAVA_MAJOR}, the build targets 17"
have git || fail "git not found"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "${SDK}" ] && [ -f local.properties ]; then
  SDK="$(sed -n 's/^sdk.dir=//p' local.properties | sed 's/\\\\/\//g')"
fi
[ -n "${SDK}" ] && [ -d "${SDK}" ] || fail "Android SDK not found; set ANDROID_HOME or write sdk.dir in local.properties"
echo "  SDK  ${SDK}"
[ -d "${SDK}/ndk/28.2.13676358" ] || echo "  warning: NDK 28.2.13676358 not found; the Cura and Prusa builds need it"

echo
echo "== engines =="
JNI="app/src/main/jniLibs/arm64-v8a"
mkdir -p "${JNI}" app/src/main/assets/blender

# build-curaengine-android.sh only copies what it builds into the app package
# when it is told where that package is; without the variable it leaves the
# engine in .build/ and the APK ships with no CuraEngine at all.
export APP_JNILIBS_DIR="${ROOT}/app/src/main/jniLibs"

# The Cura definitions are gitignored, so a clean clone has none, and an engine
# without them cannot resolve a single setting at slice time.
if [ -s app/src/main/assets/cura/definitions/creality_ender3.def.json ]; then
  echo "  Cura defs     already staged"
else
  echo "  Cura defs     fetching (scripts/fetch-cura-resources.sh)"
  ./scripts/fetch-cura-resources.sh
fi

if [ -s "${JNI}/libcuraengine_exec.so" ]; then
  echo "  CuraEngine    already staged"
else
  echo "  CuraEngine    building (scripts/build-curaengine-android.sh)"
  ./scripts/build-curaengine-android.sh
fi

if [ -s "${JNI}/libprusa_slicer_exec.so" ]; then
  echo "  PrusaSlicer   already staged"
else
  echo "  PrusaSlicer   fetching (scripts/fetch-prusa-engine-android.sh)"
  ./scripts/fetch-prusa-engine-android.sh
fi

if [ -s "${JNI}/liborca_console_exec.so" ]; then
  echo "  OrcaSlicer    already staged"
else
  echo "  OrcaSlicer    staging (scripts/fetch-orca-engine-android.sh)"
  ./scripts/fetch-orca-engine-android.sh
fi

# The native filaSim engine is a JNI library built from the pinned upstream
# source. A machine with Rust builds it in under a minute; without Rust the
# fetch script stages the CI artifact instead (that path needs GITHUB_TOKEN).
if [ -s "${JNI}/libfilasim_jni.so" ]; then
  echo "  filaSim       already staged"
elif have cargo; then
  echo "  filaSim       building (scripts/build-filasim-engine-android.sh)"
  ./scripts/build-filasim-engine-android.sh
else
  echo "  filaSim       fetching (scripts/fetch-filasim-engine-android.sh)"
  ./scripts/fetch-filasim-engine-android.sh
fi

# Not just the engine binary: libblender_exec.so loads the 120 libraries beside
# it, so a tree holding only the binary would sail past this check and fail in
# Gradle minutes later.
blender_staged() {
  [ -s "${JNI}/libblender_exec.so" ] || return 1
  [ "$(find "${JNI}" -maxdepth 1 -name '*.so' | wc -l)" -ge 121 ] || return 1
}

if blender_staged; then
  echo "  Blender       already staged"
else
  echo "  Blender       staging (scripts/fetch-blender-engine-android.sh)"
  ./scripts/fetch-blender-engine-android.sh
fi

echo
echo "== staged tree =="
missing=""
require_file() {
  if [ -s "$1" ]; then
    printf '  ok       %s\n' "$2"
  else
    printf '  MISSING  %s (%s)\n' "$2" "$1"
    missing="yes"
  fi
}
require_dir() {
  if [ -d "$1" ]; then
    printf '  ok       %s\n' "$2"
  else
    printf '  MISSING  %s (%s)\n' "$2" "$1"
    missing="yes"
  fi
}

# The APK is only as complete as this tree: CuraEngine needs its definitions and
# the shared formulae library it records as NEEDED, PrusaSlicer needs its
# resources, the native filaSim engine needs its JNI entry points, and Blender
# needs the 120 libraries beside its binary. Gradle would
# happily package whatever is present, so the check belongs here, before the
# build, where the message can still name the script to run.
for name in fdmprinter fdmextruder creality_base creality_base_extruder_0 creality_ender3; do
  require_file "app/src/main/assets/cura/definitions/$name.def.json" "Cura definition $name.def.json"
done
require_file "app/src/main/assets/cura/definitions/ultimaker_s5.def.json" "Cura catalogue machine"
require_file "${JNI}/libcuraengine_exec.so" "CuraEngine executable"
require_file "${JNI}/libcura-formulae-engine.so" "CuraEngine formulae library"
require_file "${JNI}/libprusa_slicer_exec.so" "PrusaSlicer executable"
require_file "app/src/main/assets/prusa/resources/presets/prusa-research-fff/PrusaResearch/vendor.yaml" "PrusaSlicer resources"
require_file "${JNI}/liborca_console_exec.so" "OrcaSlicer console"
require_file "${JNI}/libfilasim_jni.so" "filaSim engine"
require_dir "app/src/main/assets/orca/resources/profiles" "OrcaSlicer profile tree"
require_file "app/src/main/assets/blender/scripts/startup/blender_mcp_slim.py" "Blender MCP addon"
require_dir "app/src/main/assets/blender/python/lib/python3.11" "Blender CPython stdlib"
if blender_staged; then
  printf '  ok       %s\n' "Blender engine and its runtime libraries"
else
  printf '  MISSING  %s\n' "Blender engine and its runtime libraries"
  missing="yes"
fi
[ -z "${missing}" ] || fail "the staged tree is incomplete; the APK would ship without one of the five engines"

echo
echo "== build =="
./gradlew :app:assembleDebug

APK="app/build/outputs/apk/debug/app-debug.apk"
[ -f "${APK}" ] || fail "the build reported success but produced no APK"
echo
echo "APK   ${APK}  ($(du -h "${APK}" | cut -f1))"
echo "sha256 $(sha256sum "${APK}" | cut -d' ' -f1)"
