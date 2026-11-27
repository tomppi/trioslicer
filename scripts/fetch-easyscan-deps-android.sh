#!/usr/bin/env bash
# Stages the EasyScan SuperVINS native build dependencies and multi-SoC QNN libraries:
#
#   easyscanapp/src/main/cpp/openvins/thirdparty/{Eigen,unsupported,opencv_include,opencv_lib,ceres}
#   easyscanapp/src/main/jniLibs/arm64-v8a/{libopencv_vio4.so,libQnnGpu.so,libQnnHtpV{68,69,75,79,81}{Skel,Stub}.so}
#
# Published as a release asset so CI and local clones build the exact same APK without keeping
# ~110 MB of prebuilt native libraries and headers in Git.
#
#   scripts/fetch-easyscan-deps-android.sh
#       Downloads the pinned package (EASYSCAN_DEPS_TAG / EASYSCAN_DEPS_VERSION) from this
#       repository's releases and verifies its SHA-256 before unpacking.
#
#   EASYSCAN_DEPS_DIR=/path/to/stage
#       Uses a local unpacked stage directory instead.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="${GITHUB_REPOSITORY:-tomppi/trioslicer}"
VERSION="${EASYSCAN_DEPS_VERSION:-v1}"
TAG="${EASYSCAN_DEPS_TAG:-easyscan-deps-$VERSION}"
ASSET="easyscan-deps-arm64-$VERSION.tar.gz"
BASE_URL="${EASYSCAN_DEPS_BASE_URL:-https://github.com/$REPO/releases/download/$TAG}"
DOWNLOAD_DIR="$ROOT/.build/easyscan-deps-download"
TP_DST="$ROOT/easyscanapp/src/main/cpp/openvins/thirdparty"
JNI_DST="$ROOT/easyscanapp/src/main/jniLibs/arm64-v8a"

EASYSCAN_DEPS_SHA256_PINNED="4bff9f50a7137419397647e7ffa2a69ea40ab8f2b2e87da962e0ce66f9fa099b"

sha256_of () {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

stage () {
  local dir="$1"
  [ -s "$dir/thirdparty/ceres/lib/libceres.a" ] || { echo "missing ceres in $dir" >&2; exit 1; }
  [ -s "$dir/thirdparty/opencv_lib/arm64-v8a/libopencv_vio4.so" ] || { echo "missing libopencv_vio4.so in $dir" >&2; exit 1; }
  [ -d "$dir/thirdparty/Eigen" ] || { echo "missing Eigen in $dir" >&2; exit 1; }

  mkdir -p "$TP_DST" "$JNI_DST"
  rm -rf "$TP_DST/Eigen" "$TP_DST/unsupported" "$TP_DST/opencv_include" "$TP_DST/opencv_lib" "$TP_DST/ceres"
  cp -r "$dir/thirdparty/"* "$TP_DST/"
  cp -r "$dir/jniLibs/arm64-v8a/"* "$JNI_DST/"
  echo "staged EasyScan SuperVINS thirdparty ($(du -sh "$TP_DST" | cut -f1)) and multi-SoC QNN libs"
}

if [ -n "${EASYSCAN_DEPS_DIR:-}" ]; then
  echo "Using local EasyScan deps: $EASYSCAN_DEPS_DIR"
  stage "$EASYSCAN_DEPS_DIR"
  exit 0
fi

EXPECTED="${EASYSCAN_DEPS_SHA256:-$EASYSCAN_DEPS_SHA256_PINNED}"
rm -rf "$DOWNLOAD_DIR"
mkdir -p "$DOWNLOAD_DIR/unpacked"
echo "Downloading $BASE_URL/$ASSET"
curl -fsSL "$BASE_URL/$ASSET" -o "$DOWNLOAD_DIR/$ASSET"
ACTUAL="$(sha256_of "$DOWNLOAD_DIR/$ASSET")"
if [ "$ACTUAL" != "$EXPECTED" ]; then
  echo "::error::$ASSET does not match the pinned digest" >&2
  echo "::error::  expected $EXPECTED" >&2
  echo "::error::  actual   $ACTUAL" >&2
  exit 1
fi
tar -C "$DOWNLOAD_DIR/unpacked" -xzf "$DOWNLOAD_DIR/$ASSET"
if [ -f "$DOWNLOAD_DIR/unpacked/SHA256SUMS" ]; then
  ( cd "$DOWNLOAD_DIR/unpacked" && sha256sum --quiet -c SHA256SUMS >/dev/null )
fi
stage "$DOWNLOAD_DIR/unpacked"
