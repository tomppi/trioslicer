#!/usr/bin/env bash
# Stage the embedded Blender engine into the app.
#
# The engine is not in this repository and cannot be: libblender_exec.so is
# ~125 MB and the runtime assets (CPython stdlib, Blender's scripts, datafiles)
# are another ~400 MB, all of them built rather than source. Nothing in Gradle
# refuses to assemble without them: a plain assembleDebug packages whatever is
# staged and builds either way. What fails is the content check - the
# verifyDebugApkEngines / verifyReleaseApkEngines tasks reject an APK that is
# missing the engine, its runtime libraries or its assets - and CI runs that
# check, staging this package from the release asset before it assembles.
#
# Two ways to provide it:
#
#   BLENDER_ENGINE_DIR=/path/to/blender-engine-arm64   ./scripts/fetch-blender-engine-android.sh
#   BLENDER_ENGINE_TAG=v1.2.0                          ./scripts/fetch-blender-engine-android.sh
#
# The directory form expects the layout native/blender/README.md describes:
#
#   <dir>/blender                      the arm64 ELF the build links from
#   <dir>/jniLibs/*.so                 the 120 runtime libraries it loads
#   <dir>/libs/*.a                     the 148 static libraries
#   <dir>/python/                      CPython 3.11.4 stdlib
#   <dir>/scripts/                     Blender scripts, including our MCP addon
#   <dir>/3.6/config/datafiles/        OCIO and locale datafiles
#   <dir>/licenses/                    GPL and third-party license texts
#
# The tag form downloads that package from this repository's releases
# (blender-engine-arm64-<tag>.zip); set BLENDER_ENGINE_TAG to pick another tag, and
# BLENDER_ENGINE_SHA256 to the new asset's digest when re-pinning.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI="${ROOT}/app/src/main/jniLibs/arm64-v8a"
ASSETS="${ROOT}/app/src/main/assets/blender"
# The release asset is unpacked as code into the app package, so its digest is pinned. A
# deliberate re-pin sets BLENDER_ENGINE_SHA256 to the digest of the new asset.
BLENDER_ENGINE_SHA256_PINNED="24299aba31b91973766812ae8d371b27ed7d250706b62b328b441c2cd82a2bfb"

sha256_of () {  # $1 = file
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

stage() {
  local dir="$1"
  [ -d "$dir" ] || { echo "no such directory: $dir" >&2; exit 1; }

  mkdir -p "${JNI}" "${ASSETS}"

  if [ -f "${dir}/blender" ]; then
    cp "${dir}/blender" "${JNI}/libblender_exec.so"
    echo "staged libblender_exec.so ($(du -h "${JNI}/libblender_exec.so" | cut -f1))"
  elif [ -f "${dir}/libblender_exec.so" ]; then
    cp "${dir}/libblender_exec.so" "${JNI}/libblender_exec.so"
    echo "staged libblender_exec.so"
  else
    echo "no engine binary in $dir (expected blender or libblender_exec.so)" >&2
    exit 1
  fi

  # The engine is one shared library among 121: libblender_exec.so needs the
  # bundled cpython, ffmpeg, OpenVDB, USD and OpenImageDenoise next to it, so
  # the app cannot load it without them. The package carries them in jniLibs/,
  # and native/blender/blender-jniLibs is the tracked-in-place copy for engines
  # built on this machine.
  local libs_src=""
  if [ -d "${dir}/jniLibs" ]; then
    libs_src="${dir}/jniLibs"
  elif [ -d "${ROOT}/native/blender/blender-jniLibs" ]; then
    libs_src="${ROOT}/native/blender/blender-jniLibs"
  fi
  if [ -z "${libs_src}" ]; then
    echo "no engine runtime libraries: neither ${dir}/jniLibs nor native/blender/blender-jniLibs exists" >&2
    exit 1
  fi
  local libs_count=0
  for lib in "${libs_src}"/*.so; do
    [ -f "${lib}" ] || continue
    cp "${lib}" "${JNI}/"
    libs_count=$((libs_count + 1))
  done
  echo "staged ${libs_count} engine runtime libraries"

  for part in python scripts; do
    [ -d "${dir}/${part}" ] && cp -R "${dir}/${part}" "${ASSETS}/"
  done
  [ -d "${dir}/3.6" ] && { mkdir -p "${ASSETS}/3.6"; cp -R "${dir}/3.6/." "${ASSETS}/3.6/"; }

  # The license texts ship with the binary: the GPL requires them to travel with
  # it. The engine package carries a copy, and native/blender/assets/licenses is
  # the tracked copy for engines built from a tree that has not staged them.
  local license_src=""
  if [ -d "${dir}/licenses" ]; then
    license_src="${dir}/licenses"
  elif [ -d "${ROOT}/native/blender/assets/licenses" ]; then
    license_src="${ROOT}/native/blender/assets/licenses"
  fi
  if [ -z "${license_src}" ]; then
    echo "no license texts: neither ${dir}/licenses nor native/blender/assets/licenses exists" >&2
    exit 1
  fi
  mkdir -p "${ASSETS}/licenses"
  cp -R "${license_src}/." "${ASSETS}/licenses/"

  # The startup scripts are OURS, not the engine build's: the serve loop that
  # keeps the app process alive, the token check on the socket and the request
  # framing all live in them. The tracked copies under native/blender/assets/
  # are the source of truth, so they are laid over whatever the package shipped -
  # otherwise a clone (and CI) would run the package's older addon.
  if [ -d "${ROOT}/native/blender/assets/startup" ]; then
    mkdir -p "${ASSETS}/scripts/startup"
    # *.py only: a __pycache__ left in that directory by a local test run would
    # otherwise be copied into the APK as delivery.
    cp "${ROOT}/native/blender/assets/startup"/*.py "${ASSETS}/scripts/startup/"
    echo "staged startup scripts from native/blender/assets/startup"
  fi

  echo "staged assets: $(du -sh "${ASSETS}" | cut -f1)"
  echo
  echo "Next: ./gradlew :app:assembleDebug"
  echo "Note: app/src/main/assets/blender is gitignored and is delivery, not source."
  echo "      Anything you edit there must be copied back to native/blender/."
}

if [ -n "${BLENDER_ENGINE_DIR:-}" ]; then
  stage "${BLENDER_ENGINE_DIR}"
  exit 0
fi

TAG="${BLENDER_ENGINE_TAG:-v1.2.0}"
ASSET="blender-engine-arm64-${TAG}.zip"
URL="https://github.com/tomppi/trioslicer/releases/download/${TAG}/${ASSET}"

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT

echo "downloading ${ASSET} (${TAG})"
if ! curl -fL --progress-bar "${URL}" -o "${WORK}/engine.zip"; then
  cat >&2 <<'EOF'

Could not download the engine package.

It is published as a release asset so that a clone can build without owning a
built engine. If the download fails, either the tag has no asset (check the
release page), or you have no network - in which case build the engine locally
per native/blender/README.md and run this script with BLENDER_ENGINE_DIR.
EOF
  exit 1
fi

EXPECTED="${BLENDER_ENGINE_SHA256:-$BLENDER_ENGINE_SHA256_PINNED}"
ACTUAL="$(sha256_of "${WORK}/engine.zip")"
if [ "$ACTUAL" != "$EXPECTED" ]; then
  echo "FATAL: ${ASSET} sha256 mismatch" >&2
  echo "  expected $EXPECTED" >&2
  echo "  actual   $ACTUAL" >&2
  exit 1
fi

if command -v unzip >/dev/null 2>&1; then
  unzip -q "${WORK}/engine.zip" -d "${WORK}"
else
  # bsdtar, shipped with Windows 10+ and macOS, reads zip.
  tar -xf "${WORK}/engine.zip" -C "${WORK}"
fi

stage "${WORK}/blender-engine-arm64"
