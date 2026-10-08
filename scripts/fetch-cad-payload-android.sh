#!/usr/bin/env bash
# Places the CAD engine's Python payload into the app package.
#
#   the payload   OCP 7.9.3.1 - the official Python bindings for OpenCASCADE 7.9.3 - with
#                 build123d and their dependencies. 533 MB across 10,700 files, staged into
#                 app/src/main/assets/cad/
#
# Published as a release asset, the way the Klipper host payload is, because it is the output
# of a long cross-compile and app/src/main/assets/cad/lib is gitignored - without it a build
# of the checkout produces an APK with no CAD engine in it at all:
#
#   https://github.com/<repo>/releases/download/<CAD_PAYLOAD_TAG>/cad-payload-arm64-<version>.tar.gz
#
#   Default: downloads that asset (CAD_PAYLOAD_TAG, default cad-payload-v1.0.0).
#   CAD_ARCHIVE:        use a local archive instead and skip the download. stage-cad-android.sh
#                       reads the same variable.
#   CAD_PAYLOAD_SHA256: expected digest of the asset; set it when re-pinning.
#   CAD_PAYLOAD_BASE_URL: where to download from. Default the release above.
#
# The archive is not the staged tree: it is the raw output of the cross-compiles, and
# stage-cad-android.sh prunes the test suites and strips the symlinks on the way in. Both
# matter - joblib, numpy and sklearn ship deliberately corrupt .gz fixtures that AGP refuses,
# so the APK does not build with them present.
#
# Rebuilding the payload from source is a much longer job:
#
#   scripts/build-occt-engine-android.sh          (OCCT + the bindings)
#   scripts/stage-cad-android.sh                  (assembles the tree)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="${GITHUB_REPOSITORY:-tomppi/trioslicer}"
VERSION="${CAD_PAYLOAD_VERSION:-v1.1.0}"
TAG="${CAD_PAYLOAD_TAG:-cad-payload-${VERSION}}"
ASSET="cad-payload-arm64-${VERSION}.tar.gz"
DOWNLOAD_DIR="$ROOT/.build/cad-payload-download"

# The payload is imported by the app, so its digest is pinned: a deliberate re-pin sets
# CAD_PAYLOAD_SHA256 to the digest of the new asset.
CAD_PAYLOAD_SHA256_PINNED="9b4cadc3e92e61cf2aefbc629900896a2bcc848824f9f0900e7f8e358bd901dd"

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
    else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# Already staged? Then there is nothing to do, which is what makes this cheap to call from
# setup and from CI on every run.
if [ -z "${CAD_ARCHIVE:-}" ] && [ -z "${CAD_PAYLOAD_FORCE:-}" ]; then
    if [ -f "$ROOT/app/src/main/assets/cad/lib/python3.11/site-packages/OCP.cpython-311.so" ] &&
       [ -d "$ROOT/app/src/main/assets/cad/libexec" ]; then
        echo "CAD payload already staged"
        exit 0
    fi
fi

if [ -n "${CAD_ARCHIVE:-}" ]; then
    echo "Using local CAD archive: $CAD_ARCHIVE"
    exec "$ROOT/scripts/stage-cad-android.sh"
fi

BASE="${CAD_PAYLOAD_BASE_URL:-https://github.com/${REPO}/releases/download/${TAG}}"
URL="$BASE/$ASSET"
mkdir -p "$DOWNLOAD_DIR"
echo "Downloading $ASSET"
if curl -fL --progress-bar "$URL" -o "$DOWNLOAD_DIR/payload.tar.gz"; then
    EXPECTED="${CAD_PAYLOAD_SHA256:-$CAD_PAYLOAD_SHA256_PINNED}"
    ACTUAL="$(sha256_of "$DOWNLOAD_DIR/payload.tar.gz")"
    if [ "$ACTUAL" != "$EXPECTED" ]; then
        echo "FATAL: $ASSET sha256 mismatch" >&2
        echo "  expected $EXPECTED" >&2
        echo "  actual   $ACTUAL" >&2
        exit 1
    fi
    # stage-cad-android.sh reads a plain tar, and gunzipping once here keeps the staging
    # script free of having to care how the asset was compressed.
    rm -f "$DOWNLOAD_DIR/cad-assets.tar"
    gunzip -c "$DOWNLOAD_DIR/payload.tar.gz" > "$DOWNLOAD_DIR/cad-assets.tar"
    CAD_ARCHIVE="$DOWNLOAD_DIR/cad-assets.tar" "$ROOT/scripts/stage-cad-android.sh"
else
    cat >&2 <<EOF

Could not download $URL

The CAD payload is published as a release asset so that a clone can build a complete app
without re-running the cross-compiles behind it. Either that release is not published, or
you are offline - in which case build it locally and point CAD_ARCHIVE at the archive:

  scripts/build-occt-engine-android.sh
  CAD_ARCHIVE=/path/to/cad-assets.tar scripts/stage-cad-android.sh
EOF
    exit 1
fi
