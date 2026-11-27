#!/usr/bin/env bash
# Places the PrusaSlicer 3.0.0-alpha15 ARM64 engine (console + resources) into the app package.
#
#   Default: downloads the pinned release asset (PRUSA_ENGINE_TAG, default
#            prusa-engine-v3.0.0-alpha15) without requiring a GITHUB_TOKEN, and verifies its
#            pinned SHA-256 digest before unpacking.
#   PRUSA_ENGINE_DIR:    use a local build instead (<dir>/prusa-slicer and <dir>/resources).
#   PRUSA_ENGINE_RUN_ID: download one specific prusa-engine-3 workflow run instead (needs
#                        GITHUB_TOKEN with actions:read).
#   PRUSA_ENGINE_SHA256: expected digest of the release asset (for a deliberate re-pin).
#
# Outputs:
#   app/src/main/jniLibs/arm64-v8a/libprusa_slicer_exec.so  (stripped)
#   app/src/main/assets/prusa/resources/...
#   app/src/main/assets/prusa-presets.json
#   app/src/main/assets/prusa/all-settings.json  (derived all-settings catalogue)
set -euo pipefail

REPO="${GITHUB_REPOSITORY:-tomppi/trioslicer}"
WORKFLOW="prusa-engine-3.yml"
ARTIFACT_NAME="PrusaSlicer-3.0.0-alpha15-android-arm64-v8a"
APP_JNILIBS="app/src/main/jniLibs/arm64-v8a"
APP_ASSETS="app/src/main/assets/prusa"
DOWNLOAD_DIR=".build/prusa-engine-download"
# The release asset is unpacked as code into the app package, so its digest is pinned. A
# deliberate re-pin sets PRUSA_ENGINE_SHA256 to the digest of the new asset.
PRUSA_ENGINE_SHA256_PINNED="a32e5a1165f6a3f5d7acfcbb8bd470d9f117ce8e35e375dd6be401fbfe3a7613"

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

download_workflow_artifact () {  # $1 = run id (empty => newest successful)
  local RUN_ID="$1"
  if [ -z "$RUN_ID" ]; then
    echo "Fetching the newest successful $ARTIFACT_NAME artifact of $WORKFLOW"
    RUN_ID=$(api "https://api.github.com/repos/$REPO/actions/workflows/$WORKFLOW/runs?status=success&per_page=1" \
      | python3 -c 'import json,sys; runs=json.load(sys.stdin)["workflow_runs"]; print(runs[0]["id"] if runs else "")')
    if [ -z "$RUN_ID" ]; then
      echo "::error::no successful $WORKFLOW run found"
      exit 1
    fi
  else
    echo "Using pinned $WORKFLOW run $RUN_ID"
  fi

  local RUN_JSON RUN_BRANCH RUN_SHA ARTIFACT_ID ARTIFACT_DIGEST
  if ! RUN_JSON=$(api "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID"); then
    echo "::error::no $WORKFLOW run with id $RUN_ID in $REPO"
    exit 1
  fi
  read -r RUN_BRANCH RUN_SHA <<<"$(printf '%s' "$RUN_JSON" | python3 -c 'import json,sys; run=json.load(sys.stdin); print(run["head_branch"], run["head_sha"])')"
  echo "Engine source: $WORKFLOW run $RUN_ID on branch $RUN_BRANCH at $RUN_SHA"

  read -r ARTIFACT_ID ARTIFACT_DIGEST <<<"$(api "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID/artifacts" \
    | python3 -c 'import json,sys; want=sys.argv[1]; matches=[a for a in json.load(sys.stdin)["artifacts"] if a["name"]==want]; print(matches[0]["id"], matches[0].get("digest") or "") if matches else None' "$ARTIFACT_NAME")"
  if [ -z "$ARTIFACT_ID" ]; then
    echo "::error::run $RUN_ID has no artifact named $ARTIFACT_NAME"
    exit 1
  fi
  echo "Downloading artifact $ARTIFACT_ID"

  rm -rf "$DOWNLOAD_DIR"
  mkdir -p "$DOWNLOAD_DIR/unpacked"
  api -o "$DOWNLOAD_DIR/engine.zip" "https://api.github.com/repos/$REPO/actions/artifacts/$ARTIFACT_ID/zip"

  local ACTUAL_SHA ARTIFACT_SHA VERIFY_FAILED
  ACTUAL_SHA="$(sha256_of "$DOWNLOAD_DIR/engine.zip")"
  echo "Artifact $ARTIFACT_NAME of $WORKFLOW run $RUN_ID on branch $RUN_BRANCH at $RUN_SHA has sha256 $ACTUAL_SHA"
  ARTIFACT_SHA="${ARTIFACT_DIGEST#sha256:}"
  VERIFY_FAILED=0
  if [ -z "$ARTIFACT_SHA" ]; then
    if [ -n "${PRUSA_ENGINE_SHA256:-}" ]; then
      echo "note: the artifacts API reports no digest for $ARTIFACT_NAME; checked against PRUSA_ENGINE_SHA256 only"
    else
      echo "::error::the artifacts API reports no digest for $ARTIFACT_NAME; set PRUSA_ENGINE_SHA256 to pin it"
      VERIFY_FAILED=1
    fi
  elif [ "$ACTUAL_SHA" != "$ARTIFACT_SHA" ]; then
    echo "::error::$ARTIFACT_NAME sha256 does not match the digest the artifacts API reports"
    echo "::error::  expected $ARTIFACT_SHA"
    echo "::error::  actual   $ACTUAL_SHA"
    VERIFY_FAILED=1
  fi
  if [ -n "${PRUSA_ENGINE_SHA256:-}" ] && [ "$ACTUAL_SHA" != "$PRUSA_ENGINE_SHA256" ]; then
    echo "::error::$ARTIFACT_NAME sha256 does not match PRUSA_ENGINE_SHA256"
    echo "::error::  expected $PRUSA_ENGINE_SHA256"
    echo "::error::  actual   $ACTUAL_SHA"
    VERIFY_FAILED=1
  fi
  if [ "$VERIFY_FAILED" -ne 0 ]; then
    echo "::error::refusing to unpack $ARTIFACT_NAME, the artifact is not the one that was expected"
    exit 1
  fi

  unzip -q -o "$DOWNLOAD_DIR/engine.zip" -d "$DOWNLOAD_DIR/unpacked"
  SRC_DIR="$DOWNLOAD_DIR/unpacked/prusa3-build/out"
}

if [ -n "${PRUSA_ENGINE_DIR:-}" ]; then
  echo "Using local Prusa engine directory: $PRUSA_ENGINE_DIR"
  SRC_DIR="$PRUSA_ENGINE_DIR"
elif [ -n "${PRUSA_ENGINE_RUN_ID:-}" ]; then
  download_workflow_artifact "$PRUSA_ENGINE_RUN_ID"
else
  TAG="${PRUSA_ENGINE_TAG:-prusa-engine-v3.0.0-alpha15}"
  ASSET="prusa-engine-arm64-${TAG#prusa-engine-}.zip"
  URL="https://github.com/${REPO}/releases/download/${TAG}/${ASSET}"
  rm -rf "$DOWNLOAD_DIR"
  mkdir -p "$DOWNLOAD_DIR/unpacked"
  echo "Downloading $ASSET ($TAG)"
  if curl -fL --progress-bar "$URL" -o "$DOWNLOAD_DIR/engine.zip"; then
    EXPECTED="${PRUSA_ENGINE_SHA256:-$PRUSA_ENGINE_SHA256_PINNED}"
    ACTUAL="$(sha256_of "$DOWNLOAD_DIR/engine.zip")"
    if [ "$ACTUAL" != "$EXPECTED" ]; then
      echo "FATAL: $ASSET sha256 mismatch" >&2
      echo "  expected $EXPECTED" >&2
      echo "  actual   $ACTUAL" >&2
      exit 1
    fi
    unzip -q -o "$DOWNLOAD_DIR/engine.zip" -d "$DOWNLOAD_DIR/unpacked"
    SRC_DIR="$DOWNLOAD_DIR/unpacked/prusa3-build/out"
  elif [ -n "${GITHUB_TOKEN:-}" ]; then
    echo "No $ASSET on $TAG; falling back to the newest successful $WORKFLOW run"
    download_workflow_artifact ""
  else
    cat >&2 <<EOF

Could not download $URL

The console is published as a release asset so that a clone can build without
owning a GITHUB_TOKEN. Either the tag has no asset (check the release page), or
you are offline - in which case build it locally with
scripts/build-prusa-engine-android-3.sh and run this script with
PRUSA_ENGINE_DIR pointing at prusa3-build/out.
EOF
    exit 1
  fi
fi

if [ ! -f "$SRC_DIR/prusa-slicer" ]; then
  echo "::error::engine directory does not contain prusa-slicer: $SRC_DIR"
  find "$SRC_DIR" -maxdepth 3 2>/dev/null | head -40 || true
  exit 1
fi
echo "Engine published files at: $SRC_DIR"

mkdir -p "$APP_JNILIBS" "$APP_ASSETS"
rm -f "$APP_JNILIBS/libprusa_slicer_exec.so"
cp "$SRC_DIR/prusa-slicer" "$APP_JNILIBS/libprusa_slicer_exec.so"
chmod +x "$APP_JNILIBS/libprusa_slicer_exec.so"

# Strip with the NDK when available (CI installs it; local builds skip if missing).
for CAND in \
  "${ANDROID_NDK_HOME:-}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" \
  "${ANDROID_HOME:-}/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"; do
  if [ -x "$CAND" ]; then
    cp "$APP_JNILIBS/libprusa_slicer_exec.so" "$APP_JNILIBS/.prusa-unstripped"
    "$CAND" -s "$APP_JNILIBS/.prusa-unstripped" -o "$APP_JNILIBS/libprusa_slicer_exec.so"
    rm -f "$APP_JNILIBS/.prusa-unstripped"
    echo "stripped with $CAND"
    break
  fi
done

rm -rf "$APP_ASSETS/resources"
cp -r "$SRC_DIR/resources" "$APP_ASSETS/resources"
test -s "$APP_ASSETS/resources/presets/prusa-research-fff/PrusaResearch/vendor.yaml"

# The console only accepts an already-resolved configuration, so the app resolves the
# repository's hardware conditions itself and reads a JSON view of the same documents
# (scripts/prusa-presets-to-json.py). PyYAML parses them once, here, instead of shipping a
# YAML parser in the APK.
if ! python3 -c 'import yaml' 2>/dev/null; then
  # Ubuntu's interpreter is externally managed, so --user alone is refused (PEP 668).
  python3 -m pip install --user --quiet --break-system-packages pyyaml ||
    { echo "PyYAML is required to convert the PrusaSlicer preset repository" >&2; exit 1; }
fi
python3 scripts/prusa-presets-to-json.py app/src/main/assets
test -s app/src/main/assets/prusa-presets.json

# The 3.0 launcher has no settings dump - --dump-settings prints the usage stub -
# so the All-settings catalogue is derived from the resolved configuration and the
# preset values instead of being written by the engine. It is committed; this
# keeps it in step with the resources a fetch just staged.
python3 scripts/generate-prusa-all-settings.py app/src/main/assets
test -s app/src/main/assets/prusa/all-settings.json

echo "== packaged engine =="
ls -la "$APP_JNILIBS/libprusa_slicer_exec.so"
du -sh "$APP_ASSETS/resources" app/src/main/assets/prusa-presets.json app/src/main/assets/prusa/all-settings.json
