#!/usr/bin/env bash
# Builds the native filaSim engine (libfilasim_jni.so) for Android arm64 and
# stages it into the app package, the way the other four engines are built.
#
# The engine source is upstream CNCKitchen/smartInfillGenerator at the commit
# the app pins, with the same Enderslicer patch chain the WebAssembly build
# uses — so the native and browser engines are built from one source tree.
#
#   FILASIM_SRC:  the source tree to build from. Default, in order:
#                 the newest .build/filasim-android/<commit>-format*/ that
#                 holds crates/filasim-core - what
#                 scripts/prepare-filasim-assets.py plus the filasim-*.py patch
#                 chain produces - and otherwise a clone of the pinned commit
#                 itself, fetched into .build/filasim-native/. Either is
#                 correct: the patch chain only adds the thermal module and its
#                 crate-root declaration, so the structural engine is the same
#                 code in both trees.
#   OUTPUT_ROOT:  where the cargo target directory lives
#                 (default: .build/filasim-engine-android).
#   APP_JNILIBS:  destination for the shared library
#                 (default: app/src/main/jniLibs).
#
# Usage:
#   scripts/build-filasim-engine-android.sh            # Android arm64 library
#   scripts/build-filasim-engine-android.sh --host     # host smoke binary
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FILASIM_COMMIT="e7485ec22d4ebe8baca04190404fbb877c90e031"
OUTPUT_ROOT="${OUTPUT_ROOT:-$ROOT/.build/filasim-engine-android}"
APP_JNILIBS="${APP_JNILIBS:-$ROOT/app/src/main/jniLibs}"
JNI_CRATE_SOURCE="$ROOT/native/filasim/jni"
MODE="android"

if [[ "${1:-}" == "--host" ]]; then
  MODE="host"
elif [[ -n "${1:-}" ]]; then
  echo "Unknown argument: $1 (expected --host or nothing)" >&2
  exit 2
fi

# --- the patched upstream tree -------------------------------------------------
PREPARED_ROOT="${FILASIM_PREPARED_ROOT:-$ROOT/.build/filasim-android}"
if [[ -z "${FILASIM_SRC:-}" ]]; then
  for candidate in $(ls -d "$PREPARED_ROOT"/*-format* 2>/dev/null | sort -r); do
    if [[ -f "$candidate/crates/filasim-core/Cargo.toml" ]]; then
      FILASIM_SRC="$candidate"
      break
    fi
  done
fi
if [[ -z "${FILASIM_SRC:-}" || ! -f "$FILASIM_SRC/crates/filasim-core/Cargo.toml" ]]; then
  # Nothing prepared locally: fetch the pinned upstream commit itself. The
  # Enderslicer patch chain only adds the thermal module (thermal.rs and the
  # crate-root declaration), which the structural engine does not use, so this
  # tree builds the same session code the WebAssembly workspace does.
  NATIVE_SRC_ROOT="${FILASIM_NATIVE_SRC_ROOT:-$ROOT/.build/filasim-native}"
  FILASIM_SRC="$NATIVE_SRC_ROOT/$FILASIM_COMMIT"
  if [[ ! -f "$FILASIM_SRC/crates/filasim-core/Cargo.toml" ]]; then
    mkdir -p "$NATIVE_SRC_ROOT"
    echo "Fetching pinned filaSim source $FILASIM_COMMIT"
    curl -fsSL "https://github.com/CNCKitchen/smartInfillGenerator/archive/$FILASIM_COMMIT.zip"       -o "$NATIVE_SRC_ROOT/$FILASIM_COMMIT.zip"
    unzip -q -o "$NATIVE_SRC_ROOT/$FILASIM_COMMIT.zip" -d "$NATIVE_SRC_ROOT"
    rm -rf "$FILASIM_SRC"
    mv "$NATIVE_SRC_ROOT/smartInfillGenerator-$FILASIM_COMMIT" "$FILASIM_SRC"
  fi
  if [[ ! -f "$FILASIM_SRC/crates/filasim-core/Cargo.toml" ]]; then
    echo "Unable to stage the pinned filaSim source at $FILASIM_SRC" >&2
    exit 2
  fi
fi

# The JNI crate is authored in this repository and built inside the upstream
# workspace, so its path dependency on filasim-core resolves to the pinned tree.
rm -rf "$FILASIM_SRC/crates/filasim-jni"
cp -r "$JNI_CRATE_SOURCE" "$FILASIM_SRC/crates/filasim-jni"
python3 - "$FILASIM_SRC" <<'PY'
import pathlib
import sys

manifest = pathlib.Path(sys.argv[1]) / "Cargo.toml"
text = manifest.read_text()
member = '"crates/filasim-jni"'
marker = 'members = ['
if member not in text:
    start = text.index(marker)
    end = text.index(']', start)
    text = text[:end] + ', ' + member + text[end:]
    manifest.write_text(text)
start = text.index(marker)
print("workspace members:", text[start:text.index(']', start) + 1])
PY

# --- build ---------------------------------------------------------------------
export CARGO_TARGET_DIR="$OUTPUT_ROOT/target"
mkdir -p "$OUTPUT_ROOT"

if [[ "$MODE" == "host" ]]; then
  echo "Building the host smoke binary from $FILASIM_SRC"
  (cd "$FILASIM_SRC" && cargo build --release -p filasim-jni --bin filasim-smoke)
  echo "Binary: $CARGO_TARGET_DIR/release/filasim-smoke"
  exit 0
fi

NDK_PATH="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK_PATH" ]]; then
  echo "ANDROID_NDK_HOME or ANDROID_NDK_ROOT must point to Android NDK 28.2.13676358" >&2
  exit 2
fi
if [[ ! -d "$NDK_PATH/toolchains/llvm/prebuilt" ]]; then
  echo "Invalid Android NDK path: $NDK_PATH" >&2
  exit 2
fi

TOOLCHAIN="$(echo "$NDK_PATH"/toolchains/llvm/prebuilt/*/bin)"
if [[ ! -d "$TOOLCHAIN" ]]; then
  echo "No LLVM toolchain under $NDK_PATH/toolchains/llvm/prebuilt" >&2
  exit 2
fi

# API 29 matches the app's minSdk.
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$TOOLCHAIN/aarch64-linux-android29-clang"
export CC_aarch64_linux_android="$TOOLCHAIN/aarch64-linux-android29-clang"
export AR_aarch64_linux_android="$TOOLCHAIN/llvm-ar"
export CXX_aarch64_linux_android="$TOOLCHAIN/aarch64-linux-android29-clang++"

echo "Building libfilasim_jni.so (arm64-v8a) from $FILASIM_SRC"
(cd "$FILASIM_SRC" && cargo build --release -p filasim-jni --target aarch64-linux-android)

LIBRARY="$CARGO_TARGET_DIR/aarch64-linux-android/release/libfilasim_jni.so"
if [[ ! -f "$LIBRARY" ]]; then
  echo "cargo reported success but $LIBRARY is missing" >&2
  exit 1
fi

mkdir -p "$APP_JNILIBS/arm64-v8a"
cp "$LIBRARY" "$APP_JNILIBS/arm64-v8a/libfilasim_jni.so"
echo "Staged $APP_JNILIBS/arm64-v8a/libfilasim_jni.so ($(stat -c%s "$APP_JNILIBS/arm64-v8a/libfilasim_jni.so") bytes)"
echo "Upstream commit: $FILASIM_COMMIT"
if command -v sha256sum >/dev/null 2>&1; then
  echo "sha256: $(sha256sum "$APP_JNILIBS/arm64-v8a/libfilasim_jni.so" | cut -d' ' -f1)"
fi
