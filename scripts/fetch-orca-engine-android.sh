#!/usr/bin/env bash
# Places the OrcaSlicer 2.4.2 ARM64 console and its resources into the app package.
#
# The console is published as a release asset, the way the Blender engine is, so a clone and CI
# can stage it without owning a token and without waiting for an engine workflow run:
#
#   https://github.com/<repo>/releases/download/<ORCA_ENGINE_TAG>/orca-engine-arm64-<tag>.zip
#
#   Default: downloads that asset (ORCA_ENGINE_TAG, default v1.3.0).
#   ORCA_ENGINE_DIR:     use a local build instead - a directory holding
#                        <dir>/liborca_console_exec.so (or orca-console) and <dir>/resources.
#   ORCA_ENGINE_RUN_ID:  download one specific orca-engine-android workflow run instead, which
#                        needs GITHUB_TOKEN with actions:read. Without a run id, a missing asset
#                        falls back to the newest successful run when a token is present.
#   ORCA_ENGINE_SHA256:  expected digest of the release asset; set it when re-pinning a new tag.
#
# Outputs:
#   app/src/main/jniLibs/arm64-v8a/liborca_console_exec.so
#   app/src/main/assets/orca/resources/...
set -euo pipefail

REPO="${GITHUB_REPOSITORY:-tomppi/trioslicer}"
WORKFLOW="orca-engine-android.yml"
ARTIFACT_NAME="OrcaSlicer-2.4.2-android-arm64-v8a"
APP_JNILIBS="app/src/main/jniLibs/arm64-v8a"
APP_ASSETS="app/src/main/assets/orca"
DOWNLOAD_DIR=".build/orca-engine-download"
# The release asset is unpacked as code into the app package, so its digest is pinned. A
# deliberate re-pin sets ORCA_ENGINE_SHA256 to the digest of the new asset.
ORCA_ENGINE_SHA256_PINNED="0e2112257ae4ee052d78a4fdd8d3376bd2e0ed576372f03a352f757e5a7f51cc"

sha256_of () {  # $1 = file
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d' ' -f1
  else
    shasum -a 256 "$1" | cut -d' ' -f1
  fi
}

api () {
  curl -fsSL -H "Accept: application/vnd.github+json" \
    -H "Authorization: Bearer ${GITHUB_TOKEN:?GITHUB_TOKEN is required to download the engine artifact}" "$@"
}

unpack () {  # $1 = zip
  rm -rf "$DOWNLOAD_DIR/unpacked"
  mkdir -p "$DOWNLOAD_DIR/unpacked"
  python3 -c "
import zipfile
zipfile.ZipFile('$1').extractall('$DOWNLOAD_DIR/unpacked')
"
  SRC_DIR="$DOWNLOAD_DIR/unpacked"
}

download_artifact () {  # $1 = run id
  mkdir -p "$DOWNLOAD_DIR"
  api "https://api.github.com/repos/$REPO/actions/runs/$1/artifacts" \
    | python3 -c "
import json, sys
name = '$ARTIFACT_NAME'
for artifact in json.load(sys.stdin)['artifacts']:
    if artifact['name'] == name:
        print(artifact['archive_download_url'])
        break
" > "$DOWNLOAD_DIR/url.txt"
  local url
  url=$(cat "$DOWNLOAD_DIR/url.txt")
  [ -n "$url" ] || { echo "FATAL: run $1 has no $ARTIFACT_NAME artifact" >&2; exit 1; }
  api -L -o "$DOWNLOAD_DIR/engine.zip" "$url"
  unpack "$DOWNLOAD_DIR/engine.zip"
}

newest_successful_run () {
  api "https://api.github.com/repos/$REPO/actions/workflows/$WORKFLOW/runs?status=success&per_page=1" \
    | python3 -c 'import json,sys; runs=json.load(sys.stdin)["workflow_runs"]; print(runs[0]["id"] if runs else "")'
}

if [ -n "${ORCA_ENGINE_DIR:-}" ]; then
  echo "Using local Orca engine directory: $ORCA_ENGINE_DIR"
  SRC_DIR="$ORCA_ENGINE_DIR"
elif [ -n "${ORCA_ENGINE_RUN_ID:-}" ]; then
  echo "Downloading $ARTIFACT_NAME from workflow run $ORCA_ENGINE_RUN_ID"
  download_artifact "$ORCA_ENGINE_RUN_ID"
else
  TAG="${ORCA_ENGINE_TAG:-v1.3.0}"
  ASSET="orca-engine-arm64-${TAG}.zip"
  URL="https://github.com/${REPO}/releases/download/${TAG}/${ASSET}"
  mkdir -p "$DOWNLOAD_DIR"
  echo "Downloading $ASSET ($TAG)"
  if curl -fL --progress-bar "$URL" -o "$DOWNLOAD_DIR/engine.zip"; then
    EXPECTED="${ORCA_ENGINE_SHA256:-$ORCA_ENGINE_SHA256_PINNED}"
    ACTUAL="$(sha256_of "$DOWNLOAD_DIR/engine.zip")"
    if [ "$ACTUAL" != "$EXPECTED" ]; then
      echo "FATAL: $ASSET sha256 mismatch" >&2
      echo "  expected $EXPECTED" >&2
      echo "  actual   $ACTUAL" >&2
      exit 1
    fi
    unpack "$DOWNLOAD_DIR/engine.zip"
  elif [ -n "${GITHUB_TOKEN:-}" ]; then
    echo "No $ASSET on $TAG; falling back to the newest successful $WORKFLOW run"
    RUN_ID="$(newest_successful_run)"
    [ -n "$RUN_ID" ] || { echo "FATAL: no successful $WORKFLOW run found" >&2; exit 1; }
    download_artifact "$RUN_ID"
  else
    cat >&2 <<EOF

Could not download $URL

The console is published as a release asset so that a clone can build without
owning a built engine. Either the tag has no asset (check the release page), or
you are offline - in which case build it locally with
scripts/build-orca-deps-android.sh then scripts/build-orca-engine-android.sh and
run this script with ORCA_ENGINE_DIR pointing at the result.
EOF
    exit 1
  fi
fi

BIN=""
# The workflow artifact is unpacked at its least common ancestor, so it holds jniLibs/ and
# assets/ at the root rather than the app's path shape; both layouts are accepted.
for candidate in "$SRC_DIR/liborca_console_exec.so" "$SRC_DIR/orca-console" "$SRC_DIR/app/src/main/jniLibs/arm64-v8a/liborca_console_exec.so" "$SRC_DIR/jniLibs/arm64-v8a/liborca_console_exec.so"; do
  [ -f "$candidate" ] && { BIN="$candidate"; break; }
done
[ -n "$BIN" ] || { echo "FATAL: no console binary under $SRC_DIR" >&2; exit 1; }
RES="$SRC_DIR/resources"
[ -d "$RES" ] || RES="$SRC_DIR/app/src/main/assets/orca/resources"
[ -d "$RES" ] || RES="$SRC_DIR/assets/orca/resources"
[ -d "$RES" ] || { echo "FATAL: no resources/ under $SRC_DIR" >&2; exit 1; }

mkdir -p "$APP_JNILIBS" "$APP_ASSETS"
cp "$BIN" "$APP_JNILIBS/liborca_console_exec.so"
chmod +x "$APP_JNILIBS/liborca_console_exec.so"
rm -rf "$APP_ASSETS/resources"
cp -r "$RES" "$APP_ASSETS/resources"
echo "installed $(ls -la "$APP_JNILIBS/liborca_console_exec.so" | awk '{print $5}') byte console and $(du -sh "$APP_ASSETS/resources" | cut -f1) of resources"
