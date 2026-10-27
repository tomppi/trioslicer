#!/usr/bin/env bash
# Places the SolidCraft CAD engine into the app package.
#
#   libsolidcraft_exec.so   SolidCraft 0.22.1's headless command line, i.e. its stdio MCP
#                           server, cross-compiled for arm64-v8a. It is a PIE executable
#                           staged under a .so name so Android unpacks it into the app's
#                           native library directory, exactly as libocct_exec.so is.
#                           staged into app/src/main/jniLibs/arm64-v8a/
#
# Published as a release asset, because jniLibs/ is gitignored and building it needs a Rust
# cross toolchain with the aarch64-linux-android target and the NDK - not something CI
# carries today:
#
#   https://github.com/<repo>/releases/download/<SOLIDCRAFT_ENGINE_TAG>/solidcraft-engine-arm64-<version>.tar.gz
#
#   Default: downloads that asset (SOLIDCRAFT_ENGINE_TAG, default solidcraft-engine-v0.22.1).
#   SOLIDCRAFT_ENGINE_DIR:    use a local tree instead - a directory holding jniLibs/arm64-v8a,
#                            or one holding libsolidcraft_exec.so directly.
#   SOLIDCRAFT_ENGINE_SHA256: expected digest of the asset; set it when re-pinning.
#   SOLIDCRAFT_ENGINE_BASE_URL: where to download from. Default the release above; pointing it
#                            at a local http server exercises the download path.
#
# Rebuilding it from source is a separate job:
#
#   scripts/build-solidcraft-engine-android.sh --package
#
# which also prints the digest and the release command for a new pin.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="${GITHUB_REPOSITORY:-tomppi/trioslicer}"
VERSION="${SOLIDCRAFT_ENGINE_VERSION:-v0.22.1}"
TAG="${SOLIDCRAFT_ENGINE_TAG:-solidcraft-engine-${VERSION}}"
ASSET="solidcraft-engine-arm64-${VERSION}.tar.gz"
APP_JNILIBS="${APP_JNILIBS:-$ROOT/app/src/main/jniLibs/arm64-v8a}"
DOWNLOAD_DIR="$ROOT/.build/solidcraft-engine-download"

# The engine is executed by the app, so its digest is pinned: a deliberate re-pin sets
# SOLIDCRAFT_ENGINE_SHA256 to the digest of the new asset.
SOLIDCRAFT_ENGINE_SHA256_PINNED="0dc4703ed2ed658fec46fd169291b0706d86f098a9736063b60c4ae4800091be"

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
    else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# Not just "the file is there": an interrupted download leaves a file, and a placeholder
# leaves one that is not an engine. The ELF magic is the cheapest honest check, but it is not
# enough: the phone runs arm64-v8a only and a host x86-64 build once staged cleanly through this
# path (2026-10-27 audit), so the header's class/data and e_machine are checked as well.
check_engine() {
    local f="$1"
    [ -f "$f" ] || { echo "no $f" >&2; return 1; }
    local magic class machine
    magic="$(head -c 4 "$f" | od -An -tx1 | tr -d ' \n')"
    [ "$magic" = "7f454c46" ] || { echo "$f is not an ELF binary (magic $magic)" >&2; return 1; }
    # Bytes 4-5: class 2 = 64-bit, data 1 = little-endian.
    class="$(head -c 6 "$f" | tail -c 2 | od -An -tx1 | tr -d ' \n')"
    [ "$class" = "0201" ] || {
        echo "$f is not a 64-bit little-endian ELF (class/data $class)" >&2; return 1; }
    # Bytes 18-19: e_machine little-endian; 0xB7 is EM_AARCH64 (183).
    machine="$(head -c 20 "$f" | tail -c 2 | od -An -tx1 | tr -d ' \n')"
    [ "$machine" = "b700" ] || {
        echo "$f is not an AArch64 ELF (e_machine bytes $machine); the phone is arm64-v8a only" >&2
        return 1
    }
    [ "$(stat -c %s "$f")" -gt 10000000 ] || { echo "$f is only $(stat -c %s "$f") bytes" >&2; return 1; }
}

stage_engine() {
    local source="$1"
    mkdir -p "$APP_JNILIBS"
    install -m 755 "$source" "$APP_JNILIBS/libsolidcraft_exec.so"
    check_engine "$APP_JNILIBS/libsolidcraft_exec.so"
    echo "Staged libsolidcraft_exec.so ($(du -h "$APP_JNILIBS/libsolidcraft_exec.so" | cut -f1))"
}

if [ -n "${SOLIDCRAFT_ENGINE_DIR:-}" ]; then
    echo "Using local SolidCraft engine: $SOLIDCRAFT_ENGINE_DIR"
    if [ -f "$SOLIDCRAFT_ENGINE_DIR/jniLibs/arm64-v8a/libsolidcraft_exec.so" ]; then
        stage_engine "$SOLIDCRAFT_ENGINE_DIR/jniLibs/arm64-v8a/libsolidcraft_exec.so"
    elif [ -f "$SOLIDCRAFT_ENGINE_DIR/libsolidcraft_exec.so" ]; then
        stage_engine "$SOLIDCRAFT_ENGINE_DIR/libsolidcraft_exec.so"
    else
        echo "no libsolidcraft_exec.so under $SOLIDCRAFT_ENGINE_DIR" >&2
        exit 1
    fi
    exit 0
fi

BASE="${SOLIDCRAFT_ENGINE_BASE_URL:-https://github.com/${REPO}/releases/download/${TAG}}"
URL="$BASE/$ASSET"
mkdir -p "$DOWNLOAD_DIR"
echo "Downloading $ASSET"
if curl -fL --progress-bar "$URL" -o "$DOWNLOAD_DIR/payload.tar.gz"; then
    EXPECTED="${SOLIDCRAFT_ENGINE_SHA256:-$SOLIDCRAFT_ENGINE_SHA256_PINNED}"
    ACTUAL="$(sha256_of "$DOWNLOAD_DIR/payload.tar.gz")"
    if [ "$ACTUAL" != "$EXPECTED" ]; then
        echo "FATAL: $ASSET sha256 mismatch" >&2
        echo "  expected $EXPECTED" >&2
        echo "  actual   $ACTUAL" >&2
        exit 1
    fi
    rm -rf "$DOWNLOAD_DIR/unpacked"
    mkdir -p "$DOWNLOAD_DIR/unpacked"
    tar xzf "$DOWNLOAD_DIR/payload.tar.gz" -C "$DOWNLOAD_DIR/unpacked"
    stage_engine "$DOWNLOAD_DIR/unpacked/jniLibs/arm64-v8a/libsolidcraft_exec.so"
else
    cat >&2 <<EOF

Could not download $URL

The SolidCraft engine is published as a release asset so that a clone can build a complete
app without a Rust cross toolchain. Either that release is not published, or you are offline
- in which case build it locally:

  scripts/build-solidcraft-engine-android.sh

and run this script with SOLIDCRAFT_ENGINE_DIR=.build/solidcraft-engine/payload (or the
directory the build staged the .so into).
EOF
    exit 1
fi
