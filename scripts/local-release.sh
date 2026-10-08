#!/usr/bin/env bash
# Build the release APK locally, signed the way the published one is, and put it on a phone.
#
# This is the loop to prefer while changing something. It is a few minutes rather than the twenty
# a CI run takes, and the APK it produces has the same identity as the one on the releases page -
# so it installs *over* a released build, keeping the app's data, instead of needing an uninstall
# first.
#
# The signature is the point, which is why it is checked here against the same certificate digest
# the workflow pins. A build that had quietly fallen back to the SDK's debug key would still flash
# onto a phone that had never had the app and fail later, in a way that looks like anything but
# signing.
#
# Usage:
#   scripts/local-release.sh                     build and verify
#   scripts/local-release.sh --install           ... then install to the one attached device
#   scripts/local-release.sh --install <serial>  ... or to the named one
#
# This does not publish anything. Cutting the release is a separate, deliberate step; the commands
# are printed at the end, and explained in "Working locally" in docs/TECHNICAL.md.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
# What the workflow pins, and what keystore/README.md documents.
CERT_RELEASE="e4d88ac927ecb945e256e783ae431254785fd110fa9c78559f89d832128ea5d7"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
BUILD_TOOLS_DIR="$SDK/build-tools"

INSTALL=0
SERIAL=""
while [ $# -gt 0 ]; do
    case "$1" in
        --install)
            INSTALL=1
            shift
            if [ $# -gt 0 ] && [ "${1#--}" = "$1" ]; then SERIAL="$1"; shift; fi
            ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

APKSIGNER="$(ls "$BUILD_TOOLS_DIR"/*/apksigner 2>/dev/null | sort -V | tail -1)"
AAPT2="$(ls "$BUILD_TOOLS_DIR"/*/aapt2 2>/dev/null | sort -V | tail -1)"
[ -n "$APKSIGNER" ] || { echo "no apksigner under $BUILD_TOOLS_DIR" >&2; exit 1; }
[ -f "$ROOT/keystore.properties" ] || {
    echo "no keystore.properties: a release build cannot be signed here" >&2; exit 1; }

cd "$ROOT"

echo "== building the release APK"
./gradlew :app:assembleRelease --console=plain | tail -3

[ -f "$APK" ] || { echo "no APK at $APK" >&2; exit 1; }

echo "== signing"
SIGNER_LOG="$(mktemp)"
"$APKSIGNER" verify --print-certs "$APK" > "$SIGNER_LOG"
if grep -q 'CN=Android Debug' "$SIGNER_LOG"; then
    echo "the APK is signed with an Android debug key, which a release build must not be" >&2
    exit 1
fi
if ! grep -q "$CERT_RELEASE" "$SIGNER_LOG"; then
    echo "the APK is NOT signed with the release key:" >&2
    echo "it will not install over a released build, and the release must not carry it." >&2
    grep 'SHA-256 digest' "$SIGNER_LOG" >&2 || true
    exit 1
fi
# awk rather than grep|head: under `set -o pipefail` a reader that exits early sends SIGPIPE to the
# writer, and the pipeline reports failure for output that was there. This repository has been
# bitten by that before, in restore-cxx-runtime.sh.
awk '/SHA-256 digest/ { print; exit }' "$SIGNER_LOG"

if [ -n "$AAPT2" ]; then
    BADGING="$(mktemp)"
    "$AAPT2" dump badging "$APK" > "$BADGING" 2>/dev/null || true
    awk 'NR == 1 { print; exit }' "$BADGING"
fi
ls -la "$APK" | awk '{ printf "  %s bytes\n", $5 }'
sha256sum "$APK" | awk '{ print "  sha256 " $1 }'

if [ "$INSTALL" = 1 ]; then
    if [ -z "$SERIAL" ]; then
        SERIAL="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')"
    fi
    [ -n "$SERIAL" ] || { echo "no attached device to install to" >&2; exit 1; }
    echo "== installing on $SERIAL"
    adb -s "$SERIAL" install -r "$APK"
fi

echo
echo "This APK carries the release signature, so it installs over a released build."
echo "It is for the phone in front of you. The release carries the artifact CI built, from the run"
echo "for the tag - not this file - so that what is published can be traced to the tag:"
echo "  git push origin main && git tag v<version> && git push origin v<version>"
echo "  gh run list --limit 5                            # the run whose ref is v<version>"
echo "  gh run download <run-id> --name enderslicercura-apk --dir /tmp/ci-apk"
echo "  gh release create v<version> --verify-tag --title 'TrioSlicer <version>' \\"
echo "      --notes-file <notes> /tmp/ci-apk/app-release.apk#TrioSlicer-<version>.apk"
echo "A payload change also needs its asset published and its digest pinned first:"
echo "  docs/CAD_PAYLOAD_ANDROID.md"
