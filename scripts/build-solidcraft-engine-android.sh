#!/usr/bin/env bash
# Build the SolidCraft CAD engine for Android arm64-v8a and stage it into jniLibs.
#
# SolidCraft (https://github.com/tangency-labs/solidcraft, MIT OR Apache-2.0, 0.22.1) is a
# pure-Rust parametric CAD engine. This builds its headless command line, `solidcraft-cli`,
# which is the MCP server the app talks to over stdio: a child process that owns the design
# document, exactly as the OCCT console is a child process that owns a conversion.
#
# It is a PIE executable, not a JNI library, and it is staged with the `.so` name jniLibs
# requires so Android unpacks it into the app's native library directory - where the app can
# execute it. libocct_exec.so is the precedent.
#
# The one Android-specific detail is the linker: Cargo has no NDK integration, so the target's
# linker is handed to it through CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER. The API level in
# that linker's name (24) is the app's minSdk.
#
# Usage: scripts/build-solidcraft-engine-android.sh [--clean] [--package]
#
#   --clean     remove the target directory for this ABI first
#   --package   also write the release tarball the fetch script downloads, print its SHA-256
#               and the `gh release create` line that publishes it
#
# Requires: cargo and the aarch64-linux-android Rust target (rustup target add
# aarch64-linux-android), Android NDK 28.2.13676358, git and a checkout - the script clones
# one at the pinned commit when SOLIDCRAFT_SRC does not point at an existing tree.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SOLIDCRAFT_VERSION="0.22.1"
# The commit the 0.22.1 artifacts were cut from: a checkout of a newer main is not this engine.
SOLIDCRAFT_COMMIT="1ee65cf70a10140f8d8658f53a07481a1fa3996d"
SOLIDCRAFT_REPO="https://github.com/tangency-labs/solidcraft.git"

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-/opt/android-sdk/ndk/28.2.13676358}}"
TARGET_ABI=arm64-v8a
TARGET=aarch64-linux-android
TARGET_API=24
TOOLCHAIN="${NDK}/toolchains/llvm/prebuilt/linux-x86_64"
CLANG="${TOOLCHAIN}/bin/${TARGET}${TARGET_API}-clang"
STRIP="${TOOLCHAIN}/bin/llvm-strip"
SRC="${SOLIDCRAFT_SRC:-/tmp/solidcraft-probe}"
OUT="${REPO_ROOT}/app/src/main/jniLibs/${TARGET_ABI}/libsolidcraft_exec.so"
PACKAGE_DIR="${REPO_ROOT}/.build/solidcraft-engine"
PACKAGE="${PACKAGE_DIR}/solidcraft-engine-arm64-v${SOLIDCRAFT_VERSION}.tar.gz"

CLEAN=0
PACKAGE_ASSET=0
for arg in "$@"; do
    case "$arg" in
        --clean) CLEAN=1 ;;
        --package) PACKAGE_ASSET=1 ;;
        *) echo "unknown argument: $arg" >&2; exit 2 ;;
    esac
done

command -v cargo >/dev/null || { echo "no cargo on PATH" >&2; exit 1; }
[ -x "${CLANG}" ] || { echo "no NDK clang at ${CLANG}" >&2; exit 1; }
[ -x "${STRIP}" ] || { echo "no NDK llvm-strip at ${STRIP}" >&2; exit 1; }
cargo target list --installed 2>/dev/null | grep -qx "${TARGET}" || {
    echo "the ${TARGET} Rust target is not installed; run: rustup target add ${TARGET}" >&2
    exit 1
}

# 1. The source. A checkout is used as it is when it is one - the probe tree the first build
#    was measured in - and cloned at the pinned commit otherwise, so a clean machine can
#    rebuild the same engine without being handed a tree.
if [ ! -f "${SRC}/Cargo.toml" ]; then
    echo "== cloning SolidCraft ${SOLIDCRAFT_VERSION} (${SOLIDCRAFT_COMMIT:0:7}) into ${SRC}"
    rm -rf "${SRC}"
    git clone --quiet "${SOLIDCRAFT_REPO}" "${SRC}"
    git -C "${SRC}" checkout --quiet "${SOLIDCRAFT_COMMIT}"
fi
ACTUAL_COMMIT="$(git -C "${SRC}" rev-parse HEAD 2>/dev/null || echo unknown)"
echo "== building SolidCraft ${SOLIDCRAFT_VERSION} from ${SRC} (${ACTUAL_COMMIT:0:7})"

if [ "${CLEAN}" = 1 ]; then
    rm -rf "${SRC}/target/${TARGET}"
fi

# 2. Cross-compile. --no-default-features keeps the GUI, the GPU render host and the file
#    pickers out: an MCP server needs none of them, and leaving them in is what makes the
#    unstripped binary half a gigabyte. This is the same command the phone spike verified.
echo "== cargo build (release, ${TARGET})"
(
    cd "${SRC}"
    CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="${CLANG}" \
        cargo build --release -p solidcraft-cli --no-default-features --target "${TARGET}"
)

BUILT="${SRC}/target/${TARGET}/release/solidcraft-cli"
[ -s "${BUILT}" ] || { echo "cargo did not produce ${BUILT}" >&2; exit 1; }

# 3. Strip and stage. The unstripped binary is 447 MB; the phone runs the 63 MB stripped one.
mkdir -p "$(dirname "${OUT}")"
cp "${BUILT}" "${OUT}.unstripped"
"${STRIP}" --strip-all -o "${OUT}" "${OUT}.unstripped"
rm -f "${OUT}.unstripped"
chmod 755 "${OUT}"

magic="$(head -c 4 "${OUT}" | od -An -tx1 | tr -d ' \n')"
[ "${magic}" = "7f454c46" ] || { echo "${OUT} is not an ELF binary (magic ${magic})" >&2; exit 1; }
[ "$(stat -c %s "${OUT}")" -gt 10000000 ] || { echo "${OUT} is suspiciously small" >&2; exit 1; }
echo "== staged ${OUT} ($(( $(stat -c%s "${OUT}") / 1048576 )) MB)"

# 4. Optionally the release asset the fetch script downloads. The tarball holds the file
#    under the jniLibs path, like the other engines' assets, so the fetch script can lift it
#    out with tar rather than knowing this repository's layout.
if [ "${PACKAGE_ASSET}" = 1 ]; then
    mkdir -p "${PACKAGE_DIR}/payload/jniLibs/${TARGET_ABI}"
    cp "${OUT}" "${PACKAGE_DIR}/payload/jniLibs/${TARGET_ABI}/libsolidcraft_exec.so"
    rm -f "${PACKAGE}"
    tar czf "${PACKAGE}" -C "${PACKAGE_DIR}/payload" "jniLibs/${TARGET_ABI}/libsolidcraft_exec.so"
    DIGEST="$(sha256sum "${PACKAGE}" | cut -d' ' -f1)"
    echo "== packaged ${PACKAGE}"
    echo "   sha256 ${DIGEST}"
    echo "   publish it, then pin that digest in scripts/fetch-solidcraft-engine-android.sh:"
    echo "   gh release create solidcraft-engine-v${SOLIDCRAFT_VERSION} "${PACKAGE}" --title ..."
fi
