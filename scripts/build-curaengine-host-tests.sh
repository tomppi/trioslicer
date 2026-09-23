#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENGINE_ROOT="${ENGINE_ROOT:-$ROOT/.build/CuraEngine}"
OUTPUT_ROOT="${OUTPUT_ROOT:-$ROOT/.build/curaengine-host-tests}"
ARTIFACT_ROOT="$OUTPUT_ROOT/artifacts"
ARTIFACT="$ARTIFACT_ROOT/CuraEngine"
RUNTIME_ENV="$ARTIFACT_ROOT/runtime.env"

if [[ ! -d "$ENGINE_ROOT/.git" ]]; then
  echo "Patched CuraEngine source is missing. Run scripts/build-curaengine-android.sh first." >&2
  exit 2
fi

conan profile detect --force --name default
rm -rf "$OUTPUT_ROOT"
mkdir -p "$OUTPUT_ROOT"

COMMON_ARGS=(
  -pr:h default
  -pr:b default
  -of "$OUTPUT_ROOT"
  --build=missing
  -s build_type=Release
  -s compiler.cppstd=gnu20
  -c tools.build:skip_test=True
  -o '&:enable_arcus=False'
  -o '&:enable_plugins=False'
  -o '&:enable_benchmarks=False'
  -o '&:enable_extensive_warnings=False'
  -o '&:with_cura_resources=False'
  -o 'boost/*:header_only=True'
  -o '*:shared=False'
  -o 'hwloc/*:shared=True'
)

pushd "$ENGINE_ROOT" >/dev/null
conan install . "${COMMON_ARGS[@]}"
conan build . "${COMMON_ARGS[@]}"
popd >/dev/null

ENGINE_BINARY="$(find "$OUTPUT_ROOT" -type f -name CuraEngine -perm -u+x | head -n 1 || true)"
if [[ -z "$ENGINE_BINARY" ]]; then
  echo "Host CuraEngine build completed without an executable" >&2
  find "$OUTPUT_ROOT" -maxdepth 6 -type f | sort >&2
  exit 3
fi

CONAN_RUN_ENV="$(find "$OUTPUT_ROOT" -type f -path '*/generators/conanrun.sh' | head -n 1 || true)"
if [[ -z "$CONAN_RUN_ENV" ]]; then
  echo "Conan did not generate a runtime environment for host CuraEngine" >&2
  exit 4
fi

mkdir -p "$ARTIFACT_ROOT"
cp -v "$ENGINE_BINARY" "$ARTIFACT"
chmod 755 "$ARTIFACT"

# oneTBB intentionally remains a runtime library in the host fixture. Source
# Conan's generated environment, verify the executable, and persist the exact
# path for the Gradle process that launches the real-engine tests.
# shellcheck disable=SC1090
source "$CONAN_RUN_ENV"
: "${LD_LIBRARY_PATH:?Conan runtime environment did not define LD_LIBRARY_PATH}"
printf 'LD_LIBRARY_PATH=%s\n' "$LD_LIBRARY_PATH" > "$RUNTIME_ENV"
if [[ -n "${GITHUB_ENV:-}" ]]; then
  cat "$RUNTIME_ENV" >> "$GITHUB_ENV"
fi

file "$ARTIFACT"
ldd "$ARTIFACT"
# awk reads ldd to the end. grep -q closes the pipe on the first match, and with pipefail
# ldd's broken-pipe exit then inverts this check: a missing library would pass.
! ldd "$ARTIFACT" | awk '/not found/ { missing = 1 } END { exit !missing }'
"$ARTIFACT" help >/dev/null
printf '%s\n' "$ARTIFACT"
