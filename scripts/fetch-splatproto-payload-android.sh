#!/usr/bin/env bash
# Stages the Splat Proto payload into :splatproto:
#
#   splatproto/src/main/jniLibs/arm64-v8a/libcross3dgs_android.so
#   splatproto/src/main/assets/masker/
#
# The payload is published as a release asset, not kept in this repository: ~11 MB Cross3DGS
# Vulkan trainer plus U2Net/DA3 QNN HTP context models and Qualcomm AI Runtime object code.
# See native/splatproto/README.md for provenance.
#
#   scripts/fetch-splatproto-payload-android.sh
#       Downloads the pinned package (SPLATPROTO_PAYLOAD_TAG / SPLATPROTO_PAYLOAD_VERSION) from
#       this repository's releases and verifies its digest before unpacking.
#
#   SPLATPROTO_PAYLOAD_DIR=/path/to/package
#       Uses a local package directory instead (layout: jniLibs/arm64-v8a/libcross3dgs_android.so,
#       assets/masker/...).
#
#   SPLATPROTO_PAYLOAD_SHA256=<digest>
#       Overrides the pinned digest (a deliberate re-pin).
set -eo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="${GITHUB_REPOSITORY:-}"
if [ -z "$REPO" ]; then REPO="tomppi/trioslicer"; fi
VERSION="${SPLATPROTO_PAYLOAD_VERSION:-}"
if [ -z "$VERSION" ]; then VERSION="v2"; fi
TAG="${SPLATPROTO_PAYLOAD_TAG:-}"
if [ -z "$TAG" ]; then TAG="splatproto-payload-$VERSION"; fi
ASSET="splatproto-payload-arm64-$VERSION.tar.gz"
BASE_URL="${SPLATPROTO_PAYLOAD_BASE_URL:-}"
if [ -z "$BASE_URL" ]; then BASE_URL="https://github.com/$REPO/releases/download/$TAG"; fi
DOWNLOAD_DIR="$ROOT/.build/splatproto-payload-download"
JNI_DST="$ROOT/splatproto/src/main/jniLibs/arm64-v8a"
ASSET_DST="$ROOT/splatproto/src/main/assets/masker"

# The release asset becomes code in the APK, so its digest is pinned here.
SPLATPROTO_PAYLOAD_SHA256_PINNED="d584cdde3c054b02044b258966cf67ea3327cf5ab78eb84e3dee4383c7eace34"

sha256_of () {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

stage () {
  local dir="$1"
  local lib="$dir/jniLibs/arm64-v8a/libcross3dgs_android.so"
  if [ ! -s "$lib" ]; then echo "no JNI library in package: $lib" >&2; exit 1; fi
  if [ ! -s "$dir/assets/masker/models/u2net.bin" ]; then
    echo "no U2Net model in package: $dir/assets/masker/models/u2net.bin" >&2
    exit 1
  fi
  if [ ! -s "$dir/assets/masker/models/da3_depth_fp16.bin" ]; then
    echo "no DA3 model in package: $dir/assets/masker/models/da3_depth_fp16.bin" >&2
    exit 1
  fi

  mkdir -p "$JNI_DST"
  rm -f "$JNI_DST/libbrush_jni.so"
  cp "$lib" "$JNI_DST/libcross3dgs_android.so"

  rm -rf "$ASSET_DST"
  mkdir -p "$ASSET_DST"
  cp -r "$dir/assets/masker/"* "$ASSET_DST/"
  chmod 755 "$ASSET_DST/bin/qnn-net-run" "$ASSET_DST/bin/da3-worker" 2>/dev/null || true
  echo "staged libcross3dgs_android.so ($(du -h "$JNI_DST/libcross3dgs_android.so" | cut -f1))"
  echo "staged masker + DA3 payload ($(du -sh "$ASSET_DST" | cut -f1))"
}

if [ -n "${SPLATPROTO_PAYLOAD_DIR:-}" ]; then
  echo "Using local Splat Proto payload: $SPLATPROTO_PAYLOAD_DIR"
  stage "$SPLATPROTO_PAYLOAD_DIR"
  exit 0
fi

EXPECTED="${SPLATPROTO_PAYLOAD_SHA256:-}"
if [ -z "$EXPECTED" ]; then EXPECTED="$SPLATPROTO_PAYLOAD_SHA256_PINNED"; fi

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
# The package's own manifest is verified, so a truncated or edited extraction cannot pass.
if [ -f "$DOWNLOAD_DIR/unpacked/SHA256SUMS" ]; then
  ( cd "$DOWNLOAD_DIR/unpacked" && sha256sum --quiet -c SHA256SUMS >/dev/null )
fi
stage "$DOWNLOAD_DIR/unpacked"
