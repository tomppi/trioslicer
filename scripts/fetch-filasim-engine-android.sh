#!/usr/bin/env bash
# Places the native filaSim engine (libfilasim_jni.so) into the app package, the
# way the other engines are staged.
#
#   Local build:  FILASIM_ENGINE_DIR=<dir>, a directory holding
#                 libfilasim_jni.so. scripts/build-filasim-engine-android.sh
#                 leaves one in
#                 .build/filasim-engine-android/target/aarch64-linux-android/release.
#   Release:      FILASIM_ENGINE_TAG=<tag> downloads
#                 https://github.com/<repo>/releases/download/<tag>/filasim-engine-arm64-<tag>.zip
#                 FILASIM_ENGINE_SHA256 pins that asset's digest; set it when
#                 re-pinning a tag. This needs no token.
#   CI artifact:  the newest successful filasim-engine-android workflow run, or
#                 FILASIM_ENGINE_RUN_ID to pin one. The artifact is verified
#                 against the digest the artifacts API reports, so this needs a
#                 GITHUB_TOKEN with actions:read.
#
# Output:
#   app/src/main/jniLibs/arm64-v8a/libfilasim_jni.so
set -euo pipefail

REPO="${GITHUB_REPOSITORY:-tomppi/trioslicer}"
WORKFLOW="filasim-engine-android.yml"
ARTIFACT_NAME="FilaSimEngine-android-arm64-v8a"
ENGINE_NAME="libfilasim_jni.so"
APP_JNILIBS="app/src/main/jniLibs/arm64-v8a"
DOWNLOAD_DIR=".build/filasim-engine-download"

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

find_engine () {  # $1 = directory to search
  find "$1" -name "$ENGINE_NAME" -type f | head -1
}

if [ -n "${FILASIM_ENGINE_DIR:-}" ]; then
  echo "Using local filaSim engine directory: $FILASIM_ENGINE_DIR"
  SOURCE="$(find_engine "$FILASIM_ENGINE_DIR")"
  if [ -z "$SOURCE" ]; then
    echo "::error::$FILASIM_ENGINE_DIR does not contain $ENGINE_NAME" >&2
    exit 1
  fi
elif [ -n "${FILASIM_ENGINE_TAG:-}" ]; then
  ASSET="filasim-engine-arm64-${FILASIM_ENGINE_TAG}.zip"
  URL="https://github.com/$REPO/releases/download/${FILASIM_ENGINE_TAG}/$ASSET"
  rm -rf "$DOWNLOAD_DIR"
  mkdir -p "$DOWNLOAD_DIR/unpacked"
  echo "Downloading $ASSET"
  curl -fsSL "$URL" -o "$DOWNLOAD_DIR/engine.zip"
  ACTUAL_SHA="$(sha256_of "$DOWNLOAD_DIR/engine.zip")"
  echo "$ASSET has sha256 $ACTUAL_SHA"
  if [ -n "${FILASIM_ENGINE_SHA256:-}" ] && [ "$ACTUAL_SHA" != "$FILASIM_ENGINE_SHA256" ]; then
    echo "::error::$ASSET does not match FILASIM_ENGINE_SHA256" >&2
    echo "::error::  expected $FILASIM_ENGINE_SHA256" >&2
    echo "::error::  actual   $ACTUAL_SHA" >&2
    exit 1
  fi
  unzip -q -o "$DOWNLOAD_DIR/engine.zip" -d "$DOWNLOAD_DIR/unpacked"
  SOURCE="$(find_engine "$DOWNLOAD_DIR/unpacked")"
  if [ -z "$SOURCE" ]; then
    echo "::error::$ASSET does not contain $ENGINE_NAME" >&2
    exit 1
  fi
else
  if [ -n "${FILASIM_ENGINE_RUN_ID:-}" ]; then
    RUN_ID="$FILASIM_ENGINE_RUN_ID"
  else
    echo "Fetching the newest successful $ARTIFACT_NAME of $WORKFLOW"
    RUN_ID=$(api "https://api.github.com/repos/$REPO/actions/workflows/$WORKFLOW/runs?status=success&per_page=1" \
      | python3 -c 'import json,sys; runs=json.load(sys.stdin)["workflow_runs"]; print(runs[0]["id"] if runs else "")')
    if [ -z "$RUN_ID" ]; then
      echo "::error::no successful $WORKFLOW run found" >&2
      exit 1
    fi
  fi
  if ! RUN_JSON=$(api "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID"); then
    echo "::error::no $WORKFLOW run with id $RUN_ID in $REPO" >&2
    exit 1
  fi
  read -r RUN_BRANCH RUN_SHA <<<"$(printf '%s' "$RUN_JSON" | python3 -c 'import json,sys; run=json.load(sys.stdin); print(run["head_branch"], run["head_sha"])')"
  echo "Engine source: $WORKFLOW run $RUN_ID on branch $RUN_BRANCH at $RUN_SHA"

  read -r ARTIFACT_ID ARTIFACT_DIGEST <<<"$(api "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID/artifacts" \
    | python3 -c 'import json,sys; want=sys.argv[1]; matches=[a for a in json.load(sys.stdin)["artifacts"] if a["name"]==want]; print(matches[0]["id"], matches[0].get("digest") or "") if matches else None' "$ARTIFACT_NAME")"
  if [ -z "$ARTIFACT_ID" ]; then
    echo "::error::run $RUN_ID has no artifact named $ARTIFACT_NAME" >&2
    exit 1
  fi

  rm -rf "$DOWNLOAD_DIR"
  mkdir -p "$DOWNLOAD_DIR/unpacked"
  api -o "$DOWNLOAD_DIR/engine.zip" "https://api.github.com/repos/$REPO/actions/artifacts/$ARTIFACT_ID/zip"
  ACTUAL_SHA="$(sha256_of "$DOWNLOAD_DIR/engine.zip")"
  ARTIFACT_SHA="${ARTIFACT_DIGEST#sha256:}"
  if [ -z "$ARTIFACT_SHA" ]; then
    echo "::error::the artifacts API reports no digest for $ARTIFACT_NAME; use FILASIM_ENGINE_DIR or a release tag instead" >&2
    exit 1
  fi
  if [ "$ACTUAL_SHA" != "$ARTIFACT_SHA" ]; then
    echo "::error::$ARTIFACT_NAME sha256 does not match the digest the artifacts API reports" >&2
    echo "::error::  expected $ARTIFACT_SHA" >&2
    echo "::error::  actual   $ACTUAL_SHA" >&2
    exit 1
  fi
  # The engine is unpacked as code into the app package, so nothing is unpacked
  # until the digest check above passed.
  unzip -q -o "$DOWNLOAD_DIR/engine.zip" -d "$DOWNLOAD_DIR/unpacked"
  SOURCE="$(find_engine "$DOWNLOAD_DIR/unpacked")"
  if [ -z "$SOURCE" ]; then
    echo "::error::artifact $ARTIFACT_NAME does not contain $ENGINE_NAME" >&2
    find "$DOWNLOAD_DIR/unpacked" -maxdepth 4 | head -40 >&2
    exit 1
  fi
fi

# The engine is a JNI library: JNI resolves its entry points by name, so the
# dynamic symbol table has to survive whatever stripped the build.
if ! grep -qa 'Java_com_tomppi_enderslicer_smartinfill_FilaSimNative_createSession' "$SOURCE"; then
  echo "::error::$SOURCE does not export the filaSim JNI entry points" >&2
  exit 1
fi

mkdir -p "$APP_JNILIBS"
cp "$SOURCE" "$APP_JNILIBS/$ENGINE_NAME"
echo "Staged $APP_JNILIBS/$ENGINE_NAME ($(stat -c%s "$APP_JNILIBS/$ENGINE_NAME" 2>/dev/null || stat -f%z "$APP_JNILIBS/$ENGINE_NAME") bytes, sha256 $(sha256_of "$APP_JNILIBS/$ENGINE_NAME"))"
