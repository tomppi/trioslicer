#!/usr/bin/env bash
# Builds the CPython that klippy runs on, for Android arm64.
#
# Klippy needs cffi and greenlet, both C extensions, and the interpreter that
# Blender ships in this app has no headers to build them against. So klippy gets
# its own interpreter here, and Blender keeps its own untouched.
#
# Two builds, because CPython refuses to cross-compile without a matching host
# interpreter to run its build steps: "configure: error: Cross compiling requires
# --with-build-python". The host build is 3.11.4 to match the version being
# cross-built, since the flag requires the same minor version - the system Python
# (3.13 on this box) is not a substitute.
#
#   PY_SRC:    where the source and both builds live. Default .build/klipper-python
#   PY_PREFIX: where the Android interpreter is installed. Default .build/py311-android
#
# Usage: scripts/build-klipper-python-android.sh
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${PY_SRC:-$ROOT/.build/klipper-python}"
PREFIX="${PY_PREFIX:-$ROOT/.build/py311-android}"
HOST="$WORK/py-host"
VERSION=3.11.4
NDK="${ANDROID_NDK_HOME:-$(ls -d /opt/android-sdk/ndk/* 2>/dev/null | tail -1)}"
CC_DIR="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/bin | head -1)"

mkdir -p "$WORK"
cd "$WORK"
if [ ! -f "Python-$VERSION.tgz" ]; then
  curl -sSL -o "Python-$VERSION.tgz" "https://www.python.org/ftp/python/$VERSION/Python-$VERSION.tgz"
fi
[ -d "Python-$VERSION" ] || tar xf "Python-$VERSION.tgz"

echo "=== host interpreter $VERSION ==="
rm -rf "$WORK/host-build" && mkdir "$WORK/host-build" && cd "$WORK/host-build"
"$WORK/Python-$VERSION/configure" --prefix="$HOST" --without-ensurepip
make -j4
make install
"$HOST/bin/python3.11" -c "import sys; print('host python', sys.version.split()[0])"

echo "=== cross build for aarch64-android ==="
cd "$WORK/Python-$VERSION"
export CC="$CC_DIR/aarch64-linux-android24-clang" CXX="$CC_DIR/aarch64-linux-android24-clang++"
# -fPIC is forced rather than left to --enable-shared. Without it the core objects
# come out non-position-independent and the shared library cannot be linked:
#   ld.lld: error: relocation R_AARCH64_ADR_PREL_PG_HI21 cannot be used against
#   symbol '_Py_HashSecret'; recompile with -fPIC
export CFLAGS="-fPIC -O2"
export AR="$CC_DIR/llvm-ar" RANLIB="$CC_DIR/llvm-ranlib" STRIP="$CC_DIR/llvm-strip" READELF="$CC_DIR/llvm-readelf"
make distclean >/dev/null 2>&1 || true
./configure --host=aarch64-linux-android --build=x86_64-pc-linux-gnu \
  --with-build-python="$HOST/bin/python3.11" \
  --enable-shared --without-ensurepip --prefix="$PREFIX" \
  ac_cv_file__dev_ptmx=yes ac_cv_file__dev_ptc=no ac_cv_func_wcsftime=no \
  ac_cv_func_ftime=no ac_cv_func_faccessat=no ac_cv_func_link=no ac_cv_func_linkat=no \
  ac_cv_buggy_getaddrinfo=no ac_cv_little_endian_double=yes \
  ac_cv_posix_semaphores_enabled=yes ac_cv_func_sem_open=yes \
  ac_cv_func_sem_timedwait=yes ac_cv_func_sem_getvalue=yes
# Two passes, in this order. The extension modules link against
# libpython3.11.so, so that library has to exist before they are built; under a
# parallel make they race and the modules fail with "unable to find library
# -lpython3.11" - one problem reported dozens of times.
make libpython3.11.so
make -j4
make install
ls -l "$PREFIX/lib/libpython3.11.so" "$PREFIX/include/python3.11/Python.h"
