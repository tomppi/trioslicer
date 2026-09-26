#!/usr/bin/env bash
# Builds the Python extensions klippy needs, for Android arm64.
#
# Run scripts/build-klipper-python-android.sh first: this builds against the
# interpreter it installs. Every flag here was found by a failure, so the comments
# say which one - they are not decoration.
#
#   PREFIX: the Android interpreter from the CPython script.
#           Default .build/py311-android
#
# Output: matching .so files under PREFIX/lib/python3.11/site-packages, named with
# the target's EXT_SUFFIX (.cpython-311.so - not the host's platform tag, which is
# what setuptools produces and what the target will refuse to import).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PREFIX="${PREFIX:-$ROOT/.build/py311-android}"
HOST="${HOST_PYTHON:-$ROOT/.build/klipper-python/py-host/bin/python3.11}"
WORK="$ROOT/.build/klipper-deps"
NDK="${ANDROID_NDK_HOME:-$(ls -d /opt/android-sdk/ndk/* 2>/dev/null | tail -1)}"
TC="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)"

export CC="$TC/aarch64-linux-android24-clang" CXX="$TC/aarch64-linux-android24-clang++"
export AR="$TC/llvm-ar" RANLIB="$TC/llvm-ranlib" STRIP="$TC/llvm-strip"
export _PYTHON_HOST_PLATFORM=linux-aarch64
export LDSHARED="$CC -shared"

mkdir -p "$WORK" && cd "$WORK"

# setuptools is needed to build anything, and pip cannot be used here: our host
# interpreter was built --without-ensurepip and has no ssl module, so pip cannot
# reach PyPI. A wheel is just a zip, so unpacking it is enough.
SITE="$("$HOST" -c 'import site; print(site.getsitepackages()[0])')"
if ! "$HOST" -c "import setuptools" 2>/dev/null; then
  WHEEL="$(curl -sSL https://pypi.org/simple/setuptools/ | grep -oE 'https://files[^"]*setuptools-[0-9.]+-py3-none-any.whl' | tail -1)"
  curl -sSL -o setuptools.whl "$WHEEL"
  "$HOST" -m zipfile -e setuptools.whl "$SITE"
fi

# libffi, static. Needed by _cffi_backend, and bionic has no libffi.so. 3.4.4
# does not build on Android (tramp.c uses open_temp_exec_file undeclared); 3.4.6
# does. MAKEINFO=true skips a docs step that needs texinfo.
if [ ! -f "$PREFIX/lib/libffi.a" ]; then
  [ -d libffi-3.4.6 ] || { curl -sSL -o libffi-3.4.6.tar.gz https://github.com/libffi/libffi/releases/download/v3.4.6/libffi-3.4.6.tar.gz; tar xzf libffi-3.4.6.tar.gz; }
  cd libffi-3.4.6
  ./configure --host=aarch64-linux-android --prefix="$PREFIX" --enable-static --disable-shared CFLAGS="-fPIC -O2" > /dev/null
  make -j4 MAKEINFO=true && make install MAKEINFO=true
  cd "$WORK"
fi

# greenlet. It links libpython so its Python symbols resolve at load time (without
# -lpython3.11 it fails on PyContext_Type). -fno-emulated-tls is required: the
# default pulls in __emutls_get_address, which bionic does not provide. libc++_shared.so
# must be present at runtime for the same reason.
build_extension() {
  local name="$1" url="$2" extra_cflags="${3:-}"
  [ -d "$name" ] || { curl -sSL -o "$name.tar.gz" "$url"; tar xzf "$name.tar.gz"; }
  cd "$name"
  rm -rf build *.egg-info
  CFLAGS="-fPIC -fno-emulated-tls -I$PREFIX/include/python3.11 -I$PREFIX/include $extra_cflags" \
  CXXFLAGS="-fPIC -fno-emulated-tls" \
  LDFLAGS="-L$PREFIX/lib -lpython3.11" \
  "$HOST" setup.py build_ext --inplace
  local built
  built="$(find . -name "_*.so" -newermt '-5 minutes' | head -1)"
  # The target's own EXT_SUFFIX, read from its sysconfig - setuptools names the file
  # after the host interpreter, and the target refuses that name.
  local suffix
  suffix="$(grep -rhoE "EXT_SUFFIX[^,]*" "$PREFIX"/lib/python3.11/_sysconfigdata_*.py | head -1 | sed "s/.*: *'//; s/'//")"
  cp "$built" "$PREFIX/lib/python3.11/site-packages/$(basename "${built%%$suffix*}")$suffix"
  # Read the binary rather than trust the build's exit code.
  "$TC/llvm-readelf" -h "$built" | grep -q AArch64 || { echo "$name is not AArch64" >&2; exit 1; }
  "$TC/llvm-readelf" -d "$built" | grep -q "libpython3.11.so" || { echo "$name does not link libpython" >&2; exit 1; }
  cd "$WORK"
}

build_extension cffi-1.14.6 https://files.pythonhosted.org/packages/source/c/cffi/cffi-1.14.6.tar.gz
build_extension greenlet-2.0.2 https://files.pythonhosted.org/packages/source/g/greenlet/greenlet-2.0.2.tar.gz

# Pure Python: no compilation, so they are unpacked rather than built.
for pkg in "pycparser-2.21 p/pycparser" "pyserial-3.5 p/pyserial" "jinja2-3.1.4 j/jinja2"; do
  set -- $pkg
  [ -d "$1" ] || { curl -sSL -o "$1.tar.gz" "https://files.pythonhosted.org/packages/source/$2/$1.tar.gz"; tar xzf "$1.tar.gz"; }
done
cp -a pycparser-2.21/pycparser pyserial-3.5/serial jinja2-3.1.4/src/jinja2 "$PREFIX/lib/python3.11/site-packages/"
# MarkupSafe: capital letters, because PyPI serves it as MarkupSafe-2.1.5.tar.gz and
# a lowercase request returns a 404 page that tar fails on silently.
[ -d MarkupSafe-2.1.5 ] || { curl -sSL -o MarkupSafe-2.1.5.tar.gz https://files.pythonhosted.org/packages/source/M/MarkupSafe/MarkupSafe-2.1.5.tar.gz; tar xzf MarkupSafe-2.1.5.tar.gz; }
cp -a MarkupSafe-2.1.5/src/markupsafe "$PREFIX/lib/python3.11/site-packages/"

echo "extensions built for $PREFIX"
echo "remember: libc++_shared.so must be alongside the runtime for greenlet"
