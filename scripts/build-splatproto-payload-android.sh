#!/usr/bin/env bash
# Builds the Splat Proto payload package: Brush's JNI trainer library and the U2Net NPU
# masker assets, packaged the way scripts/fetch-blender-engine-android.sh expects.
#
# The package is not repository content: libbrush_jni.so is ~158 MB and the masker payload is
# 75 MB of Qualcomm AI Runtime object code plus the compiled model. Both are published as one
# release asset; scripts/fetch-splatproto-payload-android.sh stages it into :splatproto.
#
#   scripts/build-splatproto-payload-android.sh              build the library, package the payload
#   scripts/build-splatproto-payload-android.sh --no-package  build the library only
#
# Requirements: Rust stable with the aarch64-linux-android target, cargo-ndk, NDK
# 28.2.13676358 (ANDROID_NDK_HOME or ANDROID_HOME/ndk/...), git, tar.
#
# Inputs:
#   BRUSH_SRC              a checkout of ArthurBrussee/brush at the pinned commit to reuse
#                          instead of cloning into .build/splatproto-brush/
#   SPLATPROTO_MASKER_DIR  the masker payload directory (default: the staged module assets;
#                          rebuild it as native/splatproto/masker/SOURCE.md describes)
#   OUTPUT_ROOT            build directory (default: .build/splatproto-payload)
#
# Output:
#   .build/splatproto-payload/splatproto-payload-arm64-v1.tar.gz
#   and the SHA-256 plus the "gh release" command that publishes it.
set -eo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BRUSH_REPO="https://github.com/ArthurBrussee/brush.git"
BRUSH_COMMIT="1388f74c6fe0236f68ee4915564bf00e9d2e3747"
JNI_CRATE_SRC="$ROOT/native/splatproto/brush-jni"
JNI_PATCH="$ROOT/native/splatproto/brush-android.patch"
MASKER_SRC="$SPLATPROTO_MASKER_DIR"
if [ -z "$MASKER_SRC" ]; then MASKER_SRC="$ROOT/splatproto/src/main/assets/masker"; fi
OUTPUT_ROOT="$OUTPUT_ROOT"
if [ -z "$OUTPUT_ROOT" ]; then OUTPUT_ROOT="$ROOT/.build/splatproto-payload"; fi
VERSION="v1"
TAG="splatproto-payload-$VERSION"
ASSET="splatproto-payload-arm64-$VERSION.tar.gz"
MODE=""
if [ "$#" -gt 0 ]; then MODE="$1"; fi
PACKAGE=1
if [ "$MODE" = "--no-package" ]; then PACKAGE=0; fi
if [ -n "$MODE" ] && [ "$MODE" != "--no-package" ] && [ "$MODE" != "--package" ]; then
  echo "Unknown argument: $MODE (expected --package or --no-package)" >&2
  exit 2
fi

# --- toolchain ----------------------------------------------------------------
if [ -z "$ANDROID_HOME" ]; then ANDROID_HOME="/opt/android-sdk"; fi
NDK="$ANDROID_NDK_HOME"
if [ -z "$NDK" ]; then NDK="$ANDROID_NDK_ROOT"; fi
if [ -z "$NDK" ]; then NDK="$ANDROID_HOME/ndk/28.2.13676358"; fi
if [ -z "$NDK" ]; then NDK="/opt/android-sdk/ndk/28.2.13676358"; fi
if [ ! -d "$NDK" ]; then echo "Android NDK not found at $NDK (set ANDROID_NDK_HOME)" >&2; exit 1; fi
export ANDROID_NDK_HOME="$NDK"
export ANDROID_NDK_ROOT="$NDK"
for tool in cargo git tar sha256sum file; do
  if ! command -v "$tool" >/dev/null 2>&1; then echo "$tool is required" >&2; exit 1; fi
done
if ! cargo ndk --version >/dev/null 2>&1; then
  echo "Installing cargo-ndk (pinned 4.1.2)"
  cargo install cargo-ndk --locked --version 4.1.2
fi
cargo ndk --version

sha256_of () { sha256sum "$1" | cut -d' ' -f1; }

# --- Brush source tree --------------------------------------------------------
mkdir -p "$OUTPUT_ROOT"
BRUSH_DIR="$OUTPUT_ROOT/brush/$BRUSH_COMMIT"
if [ -n "$BRUSH_SRC" ]; then
  BRUSH_DIR="$BRUSH_SRC"
  echo "Using Brush source: $BRUSH_DIR"
elif [ ! -f "$BRUSH_DIR/Cargo.toml" ]; then
  echo "Fetching ArthurBrussee/brush $BRUSH_COMMIT"
  rm -rf "$BRUSH_DIR"
  mkdir -p "$BRUSH_DIR"
  git -C "$BRUSH_DIR" init -q
  git -C "$BRUSH_DIR" remote add origin "$BRUSH_REPO"
  git -C "$BRUSH_DIR" fetch -q --depth 1 origin "$BRUSH_COMMIT"
  git -C "$BRUSH_DIR" checkout -q FETCH_HEAD
fi

# The patch adds the JNI crate to the workspace, pins one JNI version for the whole binary and
# makes Brush's GPU kernels portable on Adreno (no SUBGROUP capability). See
# native/splatproto/README.md.
if ! grep -q 'apps/brush-jni' "$BRUSH_DIR/Cargo.toml"; then
  echo "Applying native/splatproto/brush-android.patch"
  git -C "$BRUSH_DIR" apply --whitespace=nowarn "$JNI_PATCH"
fi
rm -rf "$BRUSH_DIR/apps/brush-jni"
cp -a "$JNI_CRATE_SRC" "$BRUSH_DIR/apps/brush-jni"

# --- build --------------------------------------------------------------------
echo "Building libbrush_jni.so (arm64-v8a, API 29)"
( cd "$BRUSH_DIR" && CARGO_TARGET_DIR="$OUTPUT_ROOT/target" \
    cargo ndk -t arm64-v8a -P 29 build --release -p brush-jni )
BUILT="$OUTPUT_ROOT/target/aarch64-linux-android/release/libbrush_jni.so"
if [ ! -s "$BUILT" ]; then echo "cargo did not produce $BUILT" >&2; exit 1; fi
# A host x86-64 build must never be packaged as the phone's engine.
file "$BUILT" | grep -q 'ARM aarch64' || { echo "$BUILT is not an AArch64 ELF" >&2; exit 1; }
echo "built libbrush_jni.so ($(du -h "$BUILT" | cut -f1))"

if [ "$PACKAGE" = "0" ]; then
  mkdir -p "$ROOT/splatproto/src/main/jniLibs/arm64-v8a"
  cp "$BUILT" "$ROOT/splatproto/src/main/jniLibs/arm64-v8a/libbrush_jni.so"
  exit 0
fi

# --- package ------------------------------------------------------------------
if [ ! -d "$MASKER_SRC" ]; then echo "masker payload not found: $MASKER_SRC" >&2; exit 1; fi
STAGE="$OUTPUT_ROOT/stage"
rm -rf "$STAGE"
mkdir -p "$STAGE/jniLibs/arm64-v8a" "$STAGE/assets/masker" "$STAGE/LICENSES"
cp "$BUILT" "$STAGE/jniLibs/arm64-v8a/libbrush_jni.so"
cp -a "$MASKER_SRC/." "$STAGE/assets/masker/"
rm -rf "$STAGE/assets/masker/LICENSES" "$STAGE/assets/masker/SHA256SUMS"
cp -a "$ROOT/native/splatproto/masker/SOURCE.md" "$STAGE/assets/masker/SOURCE.md"
cp "$ROOT/native/splatproto/masker/LICENSE-Apache-2.0.txt" "$STAGE/LICENSES/"
cp "$ROOT/native/splatproto/masker/LICENSE-AI-Hub-Models-BSD-3.txt" "$STAGE/LICENSES/"
cp "$ROOT/native/splatproto/masker/LICENSE-Qualcomm-AI-Stack.pdf" "$STAGE/LICENSES/"
cp "$ROOT/native/splatproto/masker/NOTICE-Qualcomm.txt" "$STAGE/LICENSES/"
cp "$STAGE/assets/masker/SOURCE.md" "$STAGE/SOURCE.md"
# The app ships the notice beside the object code, so the same licence set goes into the
# packaged assets as well (a few hundred kB; NpuMasker.kt does not stage it onto the device).
mkdir -p "$STAGE/assets/masker/LICENSES"
cp "$STAGE/LICENSES/"* "$STAGE/assets/masker/LICENSES/"

( cd "$STAGE" && find . -type f ! -name SHA256SUMS -exec sha256sum {} + > SHA256SUMS )
cp "$STAGE/SHA256SUMS" "$STAGE/assets/masker/SHA256SUMS"

TARBALL="$OUTPUT_ROOT/$ASSET"
tar -C "$STAGE" -czf "$TARBALL" .
DIGEST="$(sha256_of "$TARBALL")"
echo
echo "packaged $TARBALL ($(du -h "$TARBALL" | cut -f1))"
echo "sha256: $DIGEST"
echo
echo "Publish it with:"
echo "  gh release create $TAG --repo tomppi/trioslicer --title 'Splat Proto payload v1 (Brush JNI + U2Net NPU masker)' --notes-file $ROOT/native/splatproto/PAYLOAD_RELEASE_NOTES.md $TARBALL"
echo
echo "Then set SPLATPROTO_PAYLOAD_SHA256_PINNED in scripts/fetch-splatproto-payload-android.sh"
echo "to $DIGEST and stage it with: scripts/fetch-splatproto-payload-android.sh"
