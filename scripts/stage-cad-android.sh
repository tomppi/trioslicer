#!/usr/bin/env bash
#
# Stage the CAD engine's Python payload into app/src/main/assets/cad/.
#
# The tree is 533 MB across 10,700 files and is gitignored, like the Klipper payload
# beside it. This script is what puts it back.
#
# It stages from a built archive rather than from the cross-compile outputs, and that is
# deliberate. Those outputs are spread over a dozen build trees under /root/occt-port (OCCT
# plus the generated bindings, three Meson cross-builds, a CMake build, a Chaquopy wheel,
# and two directories of wheels unpacked by hand), and at least two packages - pycparser and
# pyserial - were installed straight into the tree with no surviving source at all.
# Assembling from those parts was tried and produced a payload missing build123d, which is
# the one package the whole feature exists for.
#
# The archive is the build output, in the same sense as the Blender and Klipper payloads:
#
#   /root/occt-port/cad-assets.tar   (548 MB; the tree below, plus what is pruned here)
#
# Two things are done to it. The test suites are removed - 2,353 files, 41 MB - and that is
# not tidiness: joblib, numpy and sklearn ship .gz files under their test data that are
# deliberately corrupt, because they test error handling, and AGP validates .gz assets.
# Left in, mergeReleaseAssets fails with "Not in GZIP format" and the APK does not build.
# And the symlinks are removed, because they point at the install directory of whatever
# machine built the archive.
set -euo pipefail

ARCHIVE="${CAD_ARCHIVE:-/root/occt-port/cad-assets.tar}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/cad"
SITE="$DEST/lib/python3.11/site-packages"
LIBEXEC="$DEST/libexec"

[ -f "$ARCHIVE" ] || { echo "no payload archive at $ARCHIVE" >&2; exit 1; }

# Staged into a sibling and swapped in at the end. An earlier version of this script cleared
# the destination first and only then found a package missing, which left the tree
# half-built and the app unable to start; it was recoverable only because the archive was
# still there. Validate before replacing.
STAGE="$(mktemp -d "${TMPDIR:-/tmp}/cad-stage.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT

echo "unstaging $ARCHIVE"
tar xf "$ARCHIVE" -C "$STAGE"

echo "pruning test suites"
find "$STAGE" -type d \( -name test -o -name tests \) -prune -exec rm -rf {} + 2>/dev/null || true

echo "removing symlinks"
find "$STAGE" -type l -delete

# --- checks, before anything in the tree is touched --------------------------
stage_site="$STAGE/lib/python3.11/site-packages"
stage_libexec="$STAGE/libexec"

[ -f "$STAGE/cad_mcp_slim.py" ] || { echo "the archive has no engine script" >&2; exit 1; }
for pkg in OCP.cpython-311.so build123d numpy scipy sklearn lib3mf PIL _ctypes.cpython-311.so; do
  [ -e "$stage_site/$pkg" ] || { echo "the archive has no $pkg" >&2; exit 1; }
done
for lib in libopenblas.so libgfortran.so.3 libjpeg_chaquopy.so libpng16.so libomp.so; do
  [ -f "$stage_libexec/$lib" ] || { echo "the archive has no $lib" >&2; exit 1; }
done
[ "$(find "$STAGE" -type l | wc -l)" -eq 0 ] || { echo "symlinks survived" >&2; exit 1; }

# Three, and only three: real sklearn datasets. Anything more means test fixtures are back.
gz="$(find "$STAGE" -name '*.gz' | wc -l)"
[ "$gz" -le 3 ] || {
  echo "$gz .gz files; corrupt test fixtures are back and mergeReleaseAssets will fail" >&2
  exit 1
}

# --- only now replace it -----------------------------------------------------
# The engine script is source, not payload: it is committed. It is taken from the archive
# only when the checkout has none, so staging never silently regresses the engine to an
# older copy.
rm -rf "$SITE" "$LIBEXEC"
# The parents, not just the destinations. `cp -a src dst` creates dst but not the directory
# it goes in, and on a fresh checkout neither app/src/main/assets/cad/lib/python3.11 nor
# app/src/main/assets/cad exists at all - so this worked on a machine that had staged before
# and failed on the first CI run with "cannot create directory ...: No such file or directory".
mkdir -p "$(dirname "$SITE")" "$(dirname "$LIBEXEC")"
cp -a "$stage_site" "$SITE"
cp -a "$stage_libexec" "$LIBEXEC"
[ -f "$DEST/cad_mcp_slim.py" ] || cp -a "$STAGE/cad_mcp_slim.py" "$DEST/cad_mcp_slim.py"

echo "staged: $(ls "$SITE" | wc -l) packages, $(ls "$LIBEXEC" | wc -l) libraries,"
echo "        $(find "$DEST" -type f | wc -l) files, $(du -sh "$DEST" | cut -f1)"
