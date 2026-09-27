#!/usr/bin/env bash
# Places the Klipper host payload into the app package.
#
# The payload is klippy, the Android CPython standard library it runs on, its compiled C
# helper and the extensions staged beside them: 957 files that must be present for the
# app's built-in host to start. It is published as a release asset, the way the five
# slicer engines are, so that a clone and CI can stage it without the cross-compiled
# interpreter it was built from:
#
#   https://github.com/<repo>/releases/download/<KLIPPER_HOST_TAG>/klipper-host-arm64-<version>.zip
#
#   Default: downloads that asset (KLIPPER_HOST_TAG, default klipper-host-v0.13.0).
#   KLIPPER_HOST_DIR:   use a local staged tree instead - a directory holding
#                       klippy/klippy.py, klippy/chelper/c_helper.so and
#                       lib/python3.11/encodings/__init__.py, which is what
#                       scripts/stage-klipper-android.sh produces.
#   KLIPPER_HOST_SHA256: expected digest of the asset; set it when re-pinning.
#   KLIPPER_HOST_BASE_URL: where to download from. Default the release above; pointing
#                       it at a local http server exercises the download path.
#   APP_ASSETS:         where to stage. Default app/src/main/assets/klipper
#
# Outputs:
#   app/src/main/assets/klipper/...   (957 files; gitignored)
#
# Building the payload itself is a separate and much longer job, because it needs an
# interpreter cross-compiled for bionic:
#
#   scripts/build-klipper-python-android.sh
#   scripts/build-klipper-extensions-android.sh
#   scripts/stage-klipper-android.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="${GITHUB_REPOSITORY:-tomppi/trioslicer}"
VERSION="${KLIPPER_VERSION:-v0.13.0}"
TAG="${KLIPPER_HOST_TAG:-klipper-host-${VERSION}}"
ASSET="klipper-host-arm64-${VERSION}.zip"
APP_ASSETS="${APP_ASSETS:-$ROOT/app/src/main/assets/klipper}"
DOWNLOAD_DIR="$ROOT/.build/klipper-host-download"

# The payload is executed by the app, so its digest is pinned: a deliberate re-pin sets
# KLIPPER_HOST_SHA256 to the digest of the new asset.
KLIPPER_HOST_SHA256_PINNED="20ef691831b669760109ed532c46cebe08049447bf289b46dfd97af45dc88e5e"

# Files whose absence means the payload is not usable. The same three the service checks
# before starting a host, so a truncated download fails here rather than on a phone.
SENTINELS=(
  klippy/klippy.py
  klippy/chelper/c_helper.so
  lib/python3.11/encodings/__init__.py
)

sha256_of () {  # $1 = file
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

stage_from () {  # $1 = directory holding the payload tree
  local src="$1"
  # A directory that holds the payload one level down - which is how the release asset
  # is packed, as a `klipper/` root - is used as it is rather than rejected.
  [ -d "$src/klipper/klippy" ] && src="$src/klipper"
  local missing=0
  for f in "${SENTINELS[@]}"; do
    [ -f "$src/$f" ] || { echo "FATAL: payload is missing $f" >&2; missing=1; }
  done
  [ "$missing" = 0 ] || { echo "FATAL: $src is not a staged Klipper payload" >&2; exit 1; }
  # Staging onto itself would delete the source before it was copied, and pointing
  # KLIPPER_HOST_DIR at the app's own asset directory is the obvious way to try.
  if [ -e "$APP_ASSETS" ]; then
    local src_real app_real
    src_real="$(cd "$src" && pwd -P)"
    app_real="$(cd "$APP_ASSETS" && pwd -P)"
    if [ "$src_real" = "$app_real" ] || [[ "$src_real" == "$app_real"/* ]]; then
      echo "FATAL: refusing to stage the payload onto itself ($src_real)" >&2
      exit 1
    fi
  fi
  rm -rf "$APP_ASSETS"
  mkdir -p "$(dirname "$APP_ASSETS")"
  cp -a "$src" "$APP_ASSETS"
  echo "staged $(find "$APP_ASSETS" -type f | wc -l | tr -d ' ') files into ${APP_ASSETS#"$ROOT"/}"
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
  stage_from "$KLIPPER_HOST_DIR"
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
  stage_from "$DOWNLOAD_DIR/unpacked"
else
  cat >&2 <<EOF

Could not download $URL

The host payload is published as a release asset so that a clone can build a complete
app without a cross-compiled interpreter. Either that release is not published, or you
are offline - in which case build it locally:

  scripts/build-klipper-python-android.sh
  scripts/build-klipper-extensions-android.sh
  scripts/stage-klipper-android.sh

and run this script with KLIPPER_HOST_DIR pointing at
${APP_ASSETS:-app/src/main/assets/klipper}.
EOF
  exit 1
fi
