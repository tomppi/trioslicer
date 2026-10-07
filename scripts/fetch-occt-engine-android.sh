#!/usr/bin/env bash
# Places the OCCT engine into the app package.
#
#   libocct_exec.so   OpenCASCADE 7.6.0 linked with FreeCAD's PlaneGCS constraint solver,
#                     cross-compiled for arm64-v8a. It converts STEP and IGES to a mesh on
#                     import, and solves the "sketch" command's constraints.
#                     staged into app/src/main/jniLibs/arm64-v8a/
#
# Published as a release asset, the way the other engines are, because jniLibs/ is gitignored
# and building it needs the OrcaSlicer dependency tree - 38 cross-compiled OCCT toolkits that
# CI does not have:
#
#   https://github.com/<repo>/releases/download/<OCCT_ENGINE_TAG>/occt-engine-arm64-<version>.tar.gz
#
#   Default: downloads that asset (OCCT_ENGINE_TAG, default occt-engine-v1.0.0).
#   OCCT_ENGINE_DIR:    use a local tree instead - a directory holding jniLibs/arm64-v8a,
#                       or one holding libocct_exec.so directly.
#   OCCT_ENGINE_SHA256: expected digest of the asset; set it when re-pinning.
#   OCCT_ENGINE_BASE_URL: where to download from. Default the release above; pointing it at
#                       a local http server exercises the download path.
#
# Rebuilding it from source is a separate job:
#
#   scripts/build-occt-engine-android.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="${GITHUB_REPOSITORY:-tomppi/trioslicer}"
VERSION="${OCCT_ENGINE_VERSION:-v1.0.0}"
TAG="${OCCT_ENGINE_TAG:-occt-engine-${VERSION}}"
ASSET="occt-engine-arm64-${VERSION}.tar.gz"
APP_JNILIBS="${APP_JNILIBS:-$ROOT/app/src/main/jniLibs/arm64-v8a}"
DOWNLOAD_DIR="$ROOT/.build/occt-engine-download"

# The engine is executed by the app, so its digest is pinned: a deliberate re-pin sets
# OCCT_ENGINE_SHA256 to the digest of the new asset.
OCCT_ENGINE_SHA256_PINNED="e874e23469dfbe0fbd13e8f17769e7eaca94ec205d70ac25b0903b11f48e498c"

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
    else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# Not just "the file is there": an interrupted download leaves a file, and a placeholder
# leaves one that is not an engine. The ELF magic is the cheapest honest check.
check_engine() {
    local f="$1"
    [ -f "$f" ] || { echo "no $f" >&2; return 1; }
    local magic
    magic="$(head -c 4 "$f" | od -An -tx1 | tr -d ' \n')"
    [ "$magic" = "7f454c46" ] || { echo "$f is not an ELF binary (magic $magic)" >&2; return 1; }
    [ "$(stat -c %s "$f")" -gt 10000000 ] || { echo "$f is only $(stat -c %s "$f") bytes" >&2; return 1; }
}

stage_engine() {
    local source="$1"
    mkdir -p "$APP_JNILIBS"
    install -m 755 "$source" "$APP_JNILIBS/libocct_exec.so"
    check_engine "$APP_JNILIBS/libocct_exec.so"
    echo "Staged libocct_exec.so ($(du -h "$APP_JNILIBS/libocct_exec.so" | cut -f1))"
}

if [ -n "${OCCT_ENGINE_DIR:-}" ]; then
    echo "Using local OCCT engine: $OCCT_ENGINE_DIR"
    if [ -f "$OCCT_ENGINE_DIR/jniLibs/arm64-v8a/libocct_exec.so" ]; then
        stage_engine "$OCCT_ENGINE_DIR/jniLibs/arm64-v8a/libocct_exec.so"
    elif [ -f "$OCCT_ENGINE_DIR/libocct_exec.so" ]; then
        stage_engine "$OCCT_ENGINE_DIR/libocct_exec.so"
    else
        echo "no libocct_exec.so under $OCCT_ENGINE_DIR" >&2
        exit 1
    fi
    exit 0
fi

BASE="${OCCT_ENGINE_BASE_URL:-https://github.com/${REPO}/releases/download/${TAG}}"
URL="$BASE/$ASSET"
mkdir -p "$DOWNLOAD_DIR"
echo "Downloading $ASSET"
if curl -fL --progress-bar "$URL" -o "$DOWNLOAD_DIR/payload.tar.gz"; then
    EXPECTED="${OCCT_ENGINE_SHA256:-$OCCT_ENGINE_SHA256_PINNED}"
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
    stage_engine "$DOWNLOAD_DIR/unpacked/jniLibs/arm64-v8a/libocct_exec.so"
else
    cat >&2 <<EOF

Could not download $URL

The OCCT engine is published as a release asset so that a clone can build a complete app
without the OrcaSlicer dependency tree it links against. Either that release is not
published, or you are offline - in which case build it locally:

  scripts/build-occt-engine-android.sh

and run this script with OCCT_ENGINE_DIR pointing at this repository.
EOF
    exit 1
fi
