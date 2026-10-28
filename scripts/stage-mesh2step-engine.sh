#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Stages the Mesh-Mix-S2C (mesh -> STEP) engine into app/src/main/assets/mesh2step/.
#
# The engine is vendored as pure Python from an explicitly pinned upstream commit; the
# launcher and the OCP 7.9 compatibility module are TrioSlicer's and are never overwritten
# here. Run this only to (re)pin the engine:
#
#   scripts/stage-mesh2step-engine.sh
#
# The archive is pinned by digest as well as by commit, so a changed GitHub archive is
# noticed instead of silently staged; a deliberate re-pin sets TAG, COMMIT and
# MESH2STEP_ARCHIVE_SHA256 together.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TAG="v1.6.0"
COMMIT="4cf72ea5900f40d99116bda7877a8e7550ec604f"
# sha256 of https://github.com/STL2CURVES-MMM/Mesh-Mix-S2C/archive/$COMMIT.zip
MESH2STEP_ARCHIVE_SHA256="834b51ba4aa22f7cd595321fe3ee35bc5c7f3941c31d4712d81e75ed2fcef553"

DEST="$ROOT/app/src/main/assets/mesh2step"
DOWNLOAD_DIR="$ROOT/.build/mesh2step-download"
ARCHIVE="${MESH2STEP_ARCHIVE:-$DOWNLOAD_DIR/source.zip}"

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

mkdir -p "$DOWNLOAD_DIR"
if [ -z "${MESH2STEP_ARCHIVE:-}" ]; then
  echo "downloading Mesh-Mix-S2C $TAG ($COMMIT)"
  curl -fL --progress-bar \
    "https://github.com/STL2CURVES-MMM/Mesh-Mix-S2C/archive/$COMMIT.zip" \
    -o "$DOWNLOAD_DIR/source.zip" || {
      echo "FATAL: could not download the pinned Mesh-Mix-S2C archive" >&2
      exit 1
    }
fi

ACTUAL="$(sha256_of "$ARCHIVE")"
if [ "$ACTUAL" != "${MESH2STEP_ARCHIVE_SHA256_OVERRIDE:-$MESH2STEP_ARCHIVE_SHA256}" ]; then
  echo "FATAL: Mesh-Mix-S2C archive sha256 mismatch" >&2
  echo "  expected $MESH2STEP_ARCHIVE_SHA256" >&2
  echo "  actual   $ACTUAL" >&2
  echo "Re-pin TAG, COMMIT and MESH2STEP_ARCHIVE_SHA256 together if this is deliberate." >&2
  exit 1
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/mesh2step-stage.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
unzip -q "$ARCHIVE" -d "$WORK"
SRC="$(find "$WORK" -mindepth 1 -maxdepth 1 -type d | head -1)"
[ -n "$SRC" ] || { echo "archive has no top-level directory" >&2; exit 1; }
[ "$(cat "$SRC/VERSION")" = "1.6.0" ] || {
  echo "archive VERSION is $(cat "$SRC/VERSION"), expected 1.6.0" >&2
  exit 1
}

# Validate the upstream tree before replacing the staged one, so a half-download
# cannot leave the app with a truncated engine.
for module in __init__ cli pipeline meshio stepio cad; do
  [ -f "$SRC/engine/mmm/$module.py" ] || { echo "archive has no mmm/$module.py" >&2; exit 1; }
done
[ -f "$SRC/engine/mmm_engine.py" ] || { echo "archive has no mmm_engine.py" >&2; exit 1; }
[ -f "$SRC/LICENSE" ] || { echo "archive has no LICENSE" >&2; exit 1; }
[ -f "$SRC/THIRD_PARTY_NOTICES.md" ] || { echo "archive has no THIRD_PARTY_NOTICES.md" >&2; exit 1; }

# The vendored files are replaced byte-for-byte; launcher.py, ocp_compat.py, SOURCE.md
# and the pin marker are TrioSlicer's and stay as they are.
rm -rf "$DEST/mmm"
mkdir -p "$DEST/mmm"
cp "$SRC"/engine/mmm/*.py "$DEST/mmm/"
cp "$SRC/engine/mmm_engine.py" "$DEST/mmm_engine.py"
cp "$SRC/VERSION" "$DEST/VERSION"
cp "$SRC/LICENSE" "$DEST/LICENSE"
cp "$SRC/THIRD_PARTY_NOTICES.md" "$DEST/THIRD_PARTY_NOTICES.md"

cat > "$DEST/.source-version" <<MARKER
format=1
engine=1.6.0
source=STL2CURVES-MMM/Mesh-Mix-S2C
tag=$TAG
commit=$COMMIT
MARKER

# Never stage host bytecode: it is the wrong CPython tag and would be packaged.
find "$DEST" -name '__pycache__' -type d -prune -exec rm -rf {} + 2>/dev/null || true
find "$DEST" -name '*.pyc' -delete 2>/dev/null || true

rm -f "$DEST/SHA256SUMS"
python3 "$ROOT/scripts/verify-mesh2step-assets.py" --root "$DEST" --write
echo "staged Mesh-Mix-S2C $TAG at $DEST"