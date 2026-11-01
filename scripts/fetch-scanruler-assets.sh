#!/usr/bin/env bash
# SPDX-License-Identifier: AGPL-3.0-only
# Fetches, builds and stages the pinned ScanRuler offline bundle (CNCKitchen/ScanRuler,
# AGPL-3.0-only) into app/src/main/assets/scanruler/ for the WebView host.
#
# The upstream repository is cloned at the pinned commit into a work directory (never into
# the app tree), its package-lock is installed with npm ci, and the static build is made with
# Vite's base set to /assets/scanruler/ - the path the app's WebViewAssetLoader serves. The
# unmodified dist/* and upstream LICENSE are staged; the committed SOURCE.md and
# .source-version pin are preserved/regenerated, then SHA256SUMS is refreshed by
# scripts/verify-scanruler-assets.py (which also runs as the check).
#
# Pinned toolchain: node 22.23.4 / npm 10.9.9 (the build embeds content hashes, so the
# bundle bytes depend on the toolchain; the script refuses other versions and the verifier
# pins the recorded ones).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets/scanruler"
WORK="${SCANRULER_WORK:-$ROOT/.build/scanruler-build}"
TAG="v0.9.4"
COMMIT="f183e9d074a2ce95d1059174a173b8150b3b71eb"
NODE_VERSION="22.23.4"
NPM_VERSION="10.9.9"

echo '=== toolchain ==='
actual_node="$(node --version | sed 's/^v//')"
actual_npm="$(npm --version)"
if [ "$actual_node" != "$NODE_VERSION" ] || [ "$actual_npm" != "$NPM_VERSION" ]; then
  echo "FATAL: ScanRuler was pinned with node $NODE_VERSION / npm $NPM_VERSION" >&2
  echo "       current: node $actual_node / npm $actual_npm" >&2
  echo "       re-pin NODE_VERSION/NPM_VERSION and the committed hashes if this is deliberate" >&2
  exit 1
fi
echo "node $actual_node / npm $actual_npm"

mkdir -p "$WORK"
rm -rf "$WORK/src"
echo "=== clone $TAG ($COMMIT) ==="
git clone --filter=blob:none https://github.com/CNCKitchen/scanruler.git "$WORK/src"
git -C "$WORK/src" checkout -q "$COMMIT"
head_commit="$(git -C "$WORK/src" rev-parse HEAD)"
if [ "$head_commit" != "$COMMIT" ]; then
  echo "FATAL: checked out $head_commit, expected $COMMIT" >&2
  exit 1
fi
if ! git -C "$WORK/src" tag --points-at HEAD | grep -qx "$TAG"; then
  echo "FATAL: commit $COMMIT is not tagged $TAG" >&2
  exit 1
fi

echo '=== npm ci ==='
( cd "$WORK/src" && npm ci --no-audit --no-fund )

echo '=== npm run build -- --base=/assets/scanruler/ ==='
( cd "$WORK/src" && npm run build -- --base=/assets/scanruler/ )

DIST="$WORK/src/dist"
[ -f "$DIST/index.html" ] || { echo "FATAL: build produced no dist/index.html" >&2; exit 1; }
if ! grep -q '/assets/scanruler/assets/' "$DIST/index.html"; then
  echo "FATAL: dist/index.html does not use the /assets/scanruler/ base" >&2
  exit 1
fi

echo '=== stage unmodified dist + upstream LICENSE + our docs ==='
KEEP="$WORK/SOURCE.md"
rm -f "$KEEP"
if [ -f "$ASSETS/SOURCE.md" ]; then cp "$ASSETS/SOURCE.md" "$KEEP"; fi
rm -rf "$ASSETS"
mkdir -p "$ASSETS"
cp -a "$DIST/." "$ASSETS/"
# The 4.2 MB built-in sample is used only by the upstream e2e test script and the page does
# not reference it; it is deliberately excluded from the APK (see SOURCE.md).
rm -rf "$ASSETS/samples"
if [ -e "$ASSETS/samples" ]; then echo "FATAL: ScanRuler samples were staged" >&2; exit 1; fi
cp "$WORK/src/LICENSE" "$ASSETS/LICENSE"
if [ -f "$KEEP" ]; then cp "$KEEP" "$ASSETS/SOURCE.md"; fi
[ -f "$ASSETS/SOURCE.md" ] || { echo "FATAL: assets/scanruler/SOURCE.md is missing from the checkout" >&2; exit 1; }

cat > "$ASSETS/.source-version" <<MARKER
format=1
project=CNCKitchen/ScanRuler
tag=$TAG
commit=$COMMIT
base=/assets/scanruler/
node=$NODE_VERSION
npm=$NPM_VERSION
build=npm ci && npm run build -- --base=/assets/scanruler/
sample=excluded
MARKER

echo '=== manifest ==='
rm -f "$ASSETS/SHA256SUMS"
python3 "$ROOT/scripts/verify-scanruler-assets.py" --root "$ASSETS" --write
python3 "$ROOT/scripts/verify-scanruler-assets.py" --root "$ASSETS" --check-only
echo "staged ScanRuler $TAG at $ASSETS ($(find "$ASSETS" -type f | wc -l) files, $(du -sh "$ASSETS" | cut -f1))"