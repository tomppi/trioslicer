#!/usr/bin/env bash
# Places the Klipper host payload into the app package.
#
# The host is two things, and both have to be here for the app's built-in printer to
# work at all:
#
#   the payload   klippy, the Android CPython standard library it runs on, the extensions
#                 staged beside them, and the compiled C helper it generates motion with
#                 (957 files, staged into app/src/main/assets/klipper/)
#   the libraries the interpreter itself: libklipper_exec.so, the CPython built for bionic
#                 that runs klippy; libpython3.11.so, the shared library it loads; and
#                 libklipper_pty.so, which gives klippy the pty it talks to the printer
#                 through (staged into app/src/main/jniLibs/arm64-v8a/)
#
# Both are published as one release asset, the way the five slicer engines are, so that a
# clone and CI can stage them without the cross-compiled interpreter they were built from:
#
#   https://github.com/<repo>/releases/download/<KLIPPER_HOST_TAG>/klipper-host-arm64-<version>.zip
#
#   Default: downloads that asset (KLIPPER_HOST_TAG, default klipper-host-v0.13.0).
#   KLIPPER_HOST_DIR:   use a local tree instead - either the repository root, or the
#                       staged app/src/main/assets/klipper directory with its
#                       ../../jniLibs/arm64-v8a beside it.
#   KLIPPER_HOST_SHA256: expected digest of the asset; set it when re-pinning.
#   KLIPPER_HOST_BASE_URL: where to download from. Default the release above; pointing
#                       it at a local http server exercises the download path.
#   APP_ASSETS:         where the payload is staged. Default app/src/main/assets/klipper
#
# Outputs:
#   app/src/main/assets/klipper/...            (957 files; gitignored)
#   app/src/main/jniLibs/arm64-v8a/libklipper_exec.so, libklipper_pty.so, libpython3.11.so
#
# Building the payload itself is a separate and much longer job, because it needs an
# interpreter cross-compiled for bionic:
#
#   scripts/build-klipper-python-android.sh
#   scripts/build-klipper-extensions-android.sh
#   scripts/build-klipper-pty-android.sh
#   scripts/stage-klipper-android.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="${GITHUB_REPOSITORY:-tomppi/trioslicer}"
VERSION="${KLIPPER_VERSION:-v0.13.0}"
TAG="${KLIPPER_HOST_TAG:-klipper-host-${VERSION}}"
ASSET="klipper-host-arm64-${VERSION}.zip"
APP_ASSETS="${APP_ASSETS:-$ROOT/app/src/main/assets/klipper}"
APP_JNILIBS="$ROOT/app/src/main/jniLibs/arm64-v8a"
DOWNLOAD_DIR="$ROOT/.build/klipper-host-download"

# The payload is executed by the app, so its digest is pinned: a deliberate re-pin sets
# KLIPPER_HOST_SHA256 to the digest of the new asset.
KLIPPER_HOST_SHA256_PINNED="555d178dfff4399923faa1680bc3762702a0f7de3c95c9972f5257cb20e84c1c"

# Files whose absence means the payload is not usable. The same three the service checks
# before starting a host, so a truncated download fails here rather than on a phone.
PAYLOAD_SENTINELS=(
  klippy/klippy.py
  klippy/chelper/c_helper.so
  lib/python3.11/encodings/__init__.py
)

# And the executables around it. Without these the payload is a library of Python that
# nothing on the phone can run, which builds an app whose printer screens can only report
# that the host would not start.
HOST_LIBRARIES=(
  libklipper_exec.so
  libklipper_pty.so
  libpython3.11.so
)

sha256_of () {  # $1 = file
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

stage_payload () {  # $1 = directory holding the payload tree
  local src="$1"
  # A directory that holds the payload one level down - which is how the release asset is
  # packed, as a `klipper/` root - is used as it is rather than rejected.
  [ -d "$src/klipper/klippy" ] && src="$src/klipper"
  local missing=0
  for f in "${PAYLOAD_SENTINELS[@]}"; do
    [ -f "$src/$f" ] || { echo "FATAL: payload is missing $f" >&2; missing=1; }
  done
  [ "$missing" = 0 ] || { echo "FATAL: $src is not a staged Klipper payload" >&2; exit 1; }
  # The payload is normally already here when KLIPPER_HOST_DIR points at this repository,
  # which is the local case for the machine that built it. That is not an error: it is the
  # same tree, and the sentinels above have just checked it. What would be an error is a
  # source *inside* the destination, because clearing the destination to copy into it
  # would delete the source on the way.
  if [ -e "$APP_ASSETS" ]; then
    local src_real app_real
    src_real="$(cd "$src" && pwd -P)"
    app_real="$(cd "$APP_ASSETS" && pwd -P)"
    if [ "$src_real" = "$app_real" ]; then
      echo "payload already staged at ${APP_ASSETS#"$ROOT"/} ($(find "$APP_ASSETS" -type f | wc -l | tr -d ' ') files)"
      return 0
    fi
    if [[ "$src_real" == "$app_real"/* ]]; then
      echo "FATAL: refusing to stage the payload onto itself ($src_real)" >&2
      exit 1
    fi
  fi
  rm -rf "$APP_ASSETS"
  mkdir -p "$(dirname "$APP_ASSETS")"
  cp -a "$src" "$APP_ASSETS"
  echo "staged $(find "$APP_ASSETS" -type f | wc -l | tr -d ' ') payload files into ${APP_ASSETS#"$ROOT"/}"
}

stage_libraries () {  # $1 = directory holding the host's own shared libraries
  local src="$1"
  local missing=0
  for lib in "${HOST_LIBRARIES[@]}"; do
    [ -f "$src/$lib" ] || { echo "FATAL: payload is missing $lib" >&2; missing=1; }
  done
  [ "$missing" = 0 ] || {
    echo "FATAL: $src does not hold the Klipper host's libraries - the payload alone" >&2
    echo "       cannot run, because nothing on the phone can execute it" >&2
    exit 1
  }
  mkdir -p "$APP_JNILIBS"
  for lib in "${HOST_LIBRARIES[@]}"; do
    # The same file, when the payload is this repository: nothing to copy.
    if [ "$(readlink -f "$src/$lib")" = "$(readlink -f "$APP_JNILIBS/$lib" 2>/dev/null)" ]; then
      continue
    fi
    cp -a "$src/$lib" "$APP_JNILIBS/$lib"
  done
  echo "staged ${#HOST_LIBRARIES[@]} host libraries into ${APP_JNILIBS#"$ROOT"/}"
}

unpack () {  # $1 = zip
  rm -rf "$DOWNLOAD_DIR/unpacked"
  mkdir -p "$DOWNLOAD_DIR/unpacked"
  python3 -c "
import zipfile
zipfile.ZipFile('$1').extractall('$DOWNLOAD_DIR/unpacked')
"
}

if [ -n "${KLIPPER_HOST_DIR:-}" ]; then
  echo "Using local Klipper payload: $KLIPPER_HOST_DIR"
  if [ -d "$KLIPPER_HOST_DIR/app/src/main/assets/klipper" ]; then
    stage_payload "$KLIPPER_HOST_DIR/app/src/main/assets/klipper"
    stage_libraries "$KLIPPER_HOST_DIR/app/src/main/jniLibs/arm64-v8a"
  else
    stage_payload "$KLIPPER_HOST_DIR"
    stage_libraries "$KLIPPER_HOST_DIR/../../jniLibs/arm64-v8a"
  fi
  exit 0
fi

BASE="${KLIPPER_HOST_BASE_URL:-https://github.com/${REPO}/releases/download/${TAG}}"
URL="$BASE/$ASSET"
mkdir -p "$DOWNLOAD_DIR"
echo "Downloading $ASSET"
if curl -fL --progress-bar "$URL" -o "$DOWNLOAD_DIR/payload.zip"; then
  EXPECTED="${KLIPPER_HOST_SHA256:-$KLIPPER_HOST_SHA256_PINNED}"
  ACTUAL="$(sha256_of "$DOWNLOAD_DIR/payload.zip")"
  if [ "$ACTUAL" != "$EXPECTED" ]; then
    echo "FATAL: $ASSET sha256 mismatch" >&2
    echo "  expected $EXPECTED" >&2
    echo "  actual   $ACTUAL" >&2
    exit 1
  fi
  unpack "$DOWNLOAD_DIR/payload.zip"
  stage_payload "$DOWNLOAD_DIR/unpacked"
  stage_libraries "$DOWNLOAD_DIR/unpacked/jniLibs/arm64-v8a"
else
  cat >&2 <<EOF

Could not download $URL

The host payload is published as a release asset so that a clone can build a complete
app without a cross-compiled interpreter. Either that release is not published, or you
are offline - in which case build it locally:

  scripts/build-klipper-python-android.sh
  scripts/build-klipper-extensions-android.sh
  scripts/build-klipper-pty-android.sh
  scripts/stage-klipper-android.sh

and run this script with KLIPPER_HOST_DIR pointing at this repository.
EOF
  exit 1
fi
