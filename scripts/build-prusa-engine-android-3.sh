#!/usr/bin/env bash
# Cross-compile PrusaSlicer 3.0.0-alpha11 console (slic3r-app-launcher) for Android.
# Builds the full dependency chain through the upstream deps/ ExternalProject
# system, then the console-only PrusaSlicer. ANDROID_ABI selects the target.
set -euo pipefail
trap 'rc=$?; echo "::error::prusa3 engine build failed (exit $rc)"; if [ -f /tmp/prusa3-ninja.log ]; then echo "::error::--- ninja errors ---"; grep -E "fatal error|error generated|FAILED:|undefined reference|ld: error" /tmp/prusa3-ninja.log | head -60 | sed "s/^/::error::/"; echo "::error::--- ninja tail ---"; tail -25 /tmp/prusa3-ninja.log | sed "s/^/::error::/"; fi; if [ -f /tmp/prusa3-depbuild.log ]; then echo "::error::--- deps tail ---"; tail -20 /tmp/prusa3-depbuild.log | sed "s/^/::error::/"; fi; exit $rc' ERR

ABI="${ANDROID_ABI:-arm64-v8a}"
TAG="version_3.0.0-alpha11"
VER="3.0.0-alpha11"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
NDK="${ANDROID_NDK_HOME:-$SDK/ndk/28.2.13676358}"
TC=$NDK/build/cmake/android.toolchain.cmake
[ -f "$TC" ] || { echo "FATAL: NDK toolchain missing at $TC ($ABI)"; exit 1; }

PREFIX=$PWD/prusa3-build
SRC=$PREFIX/PrusaSlicer
BUILD=$PREFIX/build
OUT=$PREFIX/out
DEST=$BUILD/deps/destdir/usr/local

step () { echo; echo "===== $* ====="; }

step "[1/5] fetch PrusaSlicer $TAG"
if [ ! -d "$SRC/.git" ]; then
  for i in 1 2 3; do git clone --depth 1 --branch "$TAG" https://github.com/prusa3d/PrusaSlicer.git "$SRC" && break; rm -rf "$SRC"; done
fi

# The restored deps cache may carry a source tree with our patches applied (or an
# older revision of them): reset to the pristine checkout before patching again.
if [ -d "$SRC/.git" ]; then
  git -C "$SRC" checkout -- .
  git -C "$SRC" clean -fdq
fi

step "[2/5] patch deps system for Android"
python3 - "$SRC" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1])

def rep(p, old, new, label):
    t = p.read_text()
    if new in t:
        print(label, 'already patched'); return
    if old not in t:
        raise SystemExit('patch context not found for ' + label)
    p.write_text(t.replace(old, new, 1))
    print(label, 'patched')

# On Android there is no system ZLIB: build it from source.
rep(root / 'deps/CMakeLists.txt',
    'if (UNIX)\n    # On UNIX systems (including Apple) ZLIB should be available\n    list(APPEND SYSTEM_PROVIDED_PACKAGES ZLIB)\nendif ()',
    'if (UNIX AND NOT ANDROID)\n    # On UNIX systems (including Apple) ZLIB should be available\n    list(APPEND SYSTEM_PROVIDED_PACKAGES ZLIB)\nendif ()',
    'deps: ZLIB is source-built on Android')
# AddCMakeProject forwards only a fixed argument list to every dependency's
# ExternalProject configure; the NDK toolchain would then default to
# armeabi-v7a. Forward the ABI/platform/STL via DEP_CMAKE_OPTS.
rep(root / 'deps/CMakeLists.txt',
    '    set(DEP_CMAKE_OPTS "-DCMAKE_POSITION_INDEPENDENT_CODE=ON")',
    '    set(DEP_CMAKE_OPTS "-DCMAKE_POSITION_INDEPENDENT_CODE=ON;-DANDROID_ABI=${ANDROID_ABI};-DANDROID_PLATFORM=${ANDROID_PLATFORM};-DANDROID_STL=${ANDROID_STL};-DCMAKE_FIND_ROOT_PATH=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}")',
    'deps: forward ANDROID_ABI to dep projects')

# Lua's makefile takes CC from the environment. The bare NDK clang defaults to
# the host target, so hand it the arch specific NDK wrapper instead.
rep(root / 'deps/+Lua/Lua.cmake',
    '    list(APPEND LUA_ENV "CC=${CMAKE_C_COMPILER}")',
    '''    if (ANDROID)
        get_filename_component(_lua_ndk_bin "${CMAKE_C_COMPILER}" DIRECTORY)
        if (ANDROID_ABI STREQUAL "arm64-v8a")
            set(_lua_cc "${_lua_ndk_bin}/aarch64-linux-android24-clang")
        elseif (ANDROID_ABI STREQUAL "x86_64")
            set(_lua_cc "${_lua_ndk_bin}/x86_64-linux-android24-clang")
        elseif (ANDROID_ABI STREQUAL "armeabi-v7a")
            set(_lua_cc "${_lua_ndk_bin}/armv7a-linux-androideabi24-clang")
        endif ()
    else ()
        set(_lua_cc "${CMAKE_C_COMPILER}")
    endif ()
    list(APPEND LUA_ENV "CC=${_lua_cc}")''',
    'lua: android cc wrapper')

# gmplib.org / mpfr.org are unreachable from GitHub runners; mirror on ftp.gnu.org.
rep(root / 'deps/+GMP/GMP.cmake',
    'URL https://gmplib.org/download/gmp/gmp-6.2.1.tar.bz2',
    'URL https://ftp.gnu.org/gnu/gmp/gmp-6.2.1.tar.bz2',
    'gmp: ftp.gnu.org mirror')
rep(root / 'deps/+MPFR/MPFR.cmake',
    'URL https://www.mpfr.org/mpfr-4.2.1/mpfr-4.2.1.tar.bz2',
    'URL https://ftp.gnu.org/gnu/mpfr/mpfr-4.2.1.tar.bz2',
    'mpfr: ftp.gnu.org mirror')

# GMP/MPFR autotools builds default to the host compiler; target Android via the
# NDK clang wrapper + the android-* host triplet.
rep(root / 'deps/+GMP/GMP.cmake',
    '    set(_cross_compile_arg "")\n    if (APPLE)',
    '    set(_cross_compile_arg "")\n    if (ANDROID)\n        if (ANDROID_ABI STREQUAL "arm64-v8a")\n            set(_cross_compile_arg --host=aarch64-linux-android)\n        elseif (ANDROID_ABI STREQUAL "x86_64")\n            set(_cross_compile_arg --host=x86_64-linux-android)\n        endif ()\n        set(_gmp_build_tgt "")\n        get_filename_component(_ndk_bin ${CMAKE_C_COMPILER} DIRECTORY)\n        if (ANDROID_ABI STREQUAL "arm64-v8a")\n            set(_ndk_cc_name aarch64-linux-android24-clang)\n            set(_ndk_cxx_name aarch64-linux-android24-clang++)\n        elseif (ANDROID_ABI STREQUAL "x86_64")\n            set(_ndk_cc_name x86_64-linux-android24-clang)\n            set(_ndk_cxx_name x86_64-linux-android24-clang++)\n        endif ()\n    elseif (APPLE)',
    'gmp: android host triplet')
rep(root / 'deps/+GMP/GMP.cmake',
    '    elseif (CMAKE_CROSSCOMPILING)\n        # TOOLCHAIN_PREFIX should be defined in the toolchain file\n        set(_cross_compile_arg --host=${TOOLCHAIN_PREFIX})',
    '    elseif (CMAKE_CROSSCOMPILING AND NOT ANDROID)\n        # TOOLCHAIN_PREFIX should be defined in the toolchain file\n        set(_cross_compile_arg --host=${TOOLCHAIN_PREFIX})',
    'gmp: keep android host triplet')
rep(root / 'deps/+GMP/GMP.cmake',
        '    set(_cross_compile_arg "")\n    if (ANDROID)\n        if (ANDROID_ABI STREQUAL "arm64-v8a")\n            set(_cross_compile_arg --host=aarch64-linux-android)\n        elseif (ANDROID_ABI STREQUAL "x86_64")\n            set(_cross_compile_arg --host=x86_64-linux-android)\n        endif ()\n        set(_gmp_build_tgt "")\n        get_filename_component(_ndk_bin ${CMAKE_C_COMPILER} DIRECTORY)\n        if (ANDROID_ABI STREQUAL "arm64-v8a")\n            set(_ndk_cc_name aarch64-linux-android24-clang)\n            set(_ndk_cxx_name aarch64-linux-android24-clang++)\n        elseif (ANDROID_ABI STREQUAL "x86_64")\n            set(_ndk_cc_name x86_64-linux-android24-clang)\n            set(_ndk_cxx_name x86_64-linux-android24-clang++)\n        endif ()\n    elseif (APPLE)',
    '    set(_cross_compile_arg "")\n    if (ANDROID)\n        if (ANDROID_ABI STREQUAL "arm64-v8a")\n            set(_cross_compile_arg --host=aarch64-linux-android)\n        elseif (ANDROID_ABI STREQUAL "x86_64")\n            set(_cross_compile_arg --host=x86_64-linux-android)\n        endif ()\n        set(_gmp_build_tgt "")\n        get_filename_component(_ndk_bin ${CMAKE_C_COMPILER} DIRECTORY)\n        if (ANDROID_ABI STREQUAL "arm64-v8a")\n            set(_ndk_cc_name aarch64-linux-android24-clang)\n            set(_ndk_cxx_name aarch64-linux-android24-clang++)\n        elseif (ANDROID_ABI STREQUAL "x86_64")\n            set(_ndk_cc_name x86_64-linux-android24-clang)\n            set(_ndk_cxx_name x86_64-linux-android24-clang++)\n        endif ()\n        if (ANDROID_ABI STREQUAL "arm64-v8a")\n            set(_gmp_ccflags "${_gmp_ccflags} --target=aarch64-linux-android24 --sysroot=${CMAKE_SYSROOT}")\n        elseif (ANDROID_ABI STREQUAL "x86_64")\n            set(_gmp_ccflags "${_gmp_ccflags} --target=x86_64-linux-android24 --sysroot=${CMAKE_SYSROOT}")\n        endif ()\n    elseif (APPLE)',
    'gmp: android target flags')
rep(root / 'deps/+GMP/GMP.cmake',
    '        set(_cfg_cmd env "CFLAGS=${_gmp_ccflags}" "CXXFLAGS=${_gmp_ccflags}" ./configure',
    '        set(_cfg_cmd env "CC=${_ndk_bin}/${_ndk_cc_name}" "CXX=${_ndk_bin}/${_ndk_cxx_name}" "CFLAGS=${_gmp_ccflags}" "CXXFLAGS=${_gmp_ccflags} -std=gnu++17" ./configure',
    'gmp: android clang wrapper')

rep(root / 'deps/+MPFR/MPFR.cmake',
    '    if (EMSCRIPTEN)\n        set(_cross_compile_arg --host=wasm32)\n    endif ()',
    '    if (ANDROID)\n        if (ANDROID_ABI STREQUAL "arm64-v8a")\n            set(_cross_compile_arg --host=aarch64-linux-android)\n        elseif (ANDROID_ABI STREQUAL "x86_64")\n            set(_cross_compile_arg --host=x86_64-linux-android)\n        endif ()\n        get_filename_component(_ndk_bin ${CMAKE_C_COMPILER} DIRECTORY)\n        if (ANDROID_ABI STREQUAL "arm64-v8a")\n            set(_ndk_cc_name aarch64-linux-android24-clang)\n        elseif (ANDROID_ABI STREQUAL "x86_64")\n            set(_ndk_cc_name x86_64-linux-android24-clang)\n        endif ()\n        if (ANDROID_ABI STREQUAL "arm64-v8a")\n            set(_gmp_ccflags "${_gmp_ccflags} --target=aarch64-linux-android24 --sysroot=${CMAKE_SYSROOT}")\n        elseif (ANDROID_ABI STREQUAL "x86_64")\n            set(_gmp_ccflags "${_gmp_ccflags} --target=x86_64-linux-android24 --sysroot=${CMAKE_SYSROOT}")\n        endif ()\n    elseif (EMSCRIPTEN)\n        set(_cross_compile_arg --host=wasm32)\n    endif ()',
    'mpfr: android host triplet + target flags')
rep(root / 'deps/+Imath/Imath.cmake',
    '-DLIBDEFLATE_BUILD_SHARED_LIB=OFF\n        -DLIBDEFLATE_BUILD_GZIP=OFF',
    '-DLIBDEFLATE_BUILD_SHARED_LIB=OFF\n        -DLIBDEFLATE_BUILD_GZIP=OFF\n        -DBUILD_TESTING=OFF',
    'imath: tests off')
rep(root / 'deps/+cpptrace/cpptrace.cmake',
    '        -DCPPTRACE_USE_EXTERNAL_LIBDWARF=ON',
    '        -DCPPTRACE_USE_EXTERNAL_LIBDWARF=OFF',
    'cpptrace: bundled libdwarf')
rep(root / 'deps/+cpptrace/cpptrace.cmake',
    'PATCH_COMMAND ${PATCH_CMD} ${CMAKE_CURRENT_LIST_DIR}/cpptrace.patch',
    'PATCH_COMMAND ${PATCH_CMD} ${CMAKE_CURRENT_LIST_DIR}/cpptrace.patch && python3 ${CMAKE_CURRENT_LIST_DIR}/strip_config_installs.py',
    'cpptrace: drop own config install (shims win)')
rep(root / 'deps/+OpenSSL/OpenSSL.cmake',
    '    BUILD_COMMAND make depend && make "-j${NPROC}"',
    '    BUILD_COMMAND make depend && make "-j${NPROC}" build_libs',
    'openssl: capture make log')
rep(root / 'deps/+Boost/Boost.cmake',
    'set(_excluded_libs contract|fiber|numpy|stacktrace|wave|test|log)',
    'set(_excluded_libs contract|fiber|numpy|stacktrace|wave|test|log|process)',
    'boost: exclude process lib')
rep(root / 'deps/+Boost/Boost.cmake',
    'add_cmake_project(Boost',
    'add_cmake_project(Boost\n    PATCH_COMMAND python3 ${CMAKE_CURRENT_LIST_DIR}/make_regex_static.py',
    'boost: static regex build hook')

rep(root / 'deps/+OpenVDB/OpenVDB.cmake',
    '        -DOPENVDB_BUILD_VDB_PRINT=OFF',
    '        -DOPENVDB_BUILD_VDB_PRINT=OFF\n        -DBoost_INCLUDE_DIR=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}/include\n        -DBoost_LIBRARY_DIR=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}/lib\n        -DBoost_USE_STATIC_LIBS=ON\n        -DBoost_USE_MULTITHREADED=OFF\n        -DTBB_DIR=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}/lib/cmake/TBB\n        -DTBB_ROOT=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}\n        -DImath_DIR=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}/lib/cmake/Imath\n        -DBlosc_INCLUDE_DIR=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}/include\n        -DBlosc_LIBRARY=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}/lib/libblosc.a\n        -DLog4cplus_INCLUDE_DIR=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}/include\n        -DLog4cplus_LIBRARY=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}/lib/liblog4cplus.a\n        -Dzstd_DIR=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}/lib/cmake/zstd',
    'openvdb: explicit Boost include dir (FindBoost module skips CMAKE_PREFIX_PATH)')
rep(root / 'cmake/modules/FindBlosc.cmake',
    '  find_package(zstd REQUIRED)',
    '  # zstd cross-build config-version rejects empty-version requests; resolve via -Dzstd_DIR\n  set(zstd_FOUND TRUE)\n  if(NOT TARGET zstd::libzstd)\n    add_library(zstd::libzstd INTERFACE IMPORTED)\n    set_target_properties(zstd::libzstd PROPERTIES INTERFACE_INCLUDE_DIRECTORIES "${Blosc_INCLUDE_DIR}")\n  message(STATUS "DBG-BLOSC INCDIR=[${Blosc_INCLUDE_DIR}] INCDIRS=[${Blosc_INCLUDE_DIRS}] PC=[${PC_Blosc_INCLUDE_DIRS}] PCO=[${PC_Blosc_CFLAGS_OTHER}] PRFX=[${CMAKE_INSTALL_PREFIX}]")\n  if(NOT EXISTS "${Blosc_INCLUDE_DIR}/blosc.h" AND DEFINED CMAKE_INSTALL_PREFIX)\n    set(Blosc_INCLUDE_DIR "${CMAKE_INSTALL_PREFIX}/include")\n    set(Blosc_INCLUDE_DIRS "${Blosc_INCLUDE_DIR}")\n  endif()\n  endif()',
    'FindBlosc: zstd shim + cross include clamp')

# PNG is only source-built by the deps bundle on MSVC/APPLE/Emscripten; Android
# has no system libpng, so include it in the source build as well.
rep(root / 'deps/+PNG/PNG.cmake',
    'if (MSVC OR APPLE OR EMSCRIPTEN)',
    'if (MSVC OR APPLE OR EMSCRIPTEN OR ANDROID)',
    'png: build from source on Android')

# Sol2 v3.5.0 calls find_package(Lua 5.4 EXACT); module-mode FindLua ignores
# CMAKE_PREFIX_PATH (same issue as Boost/Blosc), so point it at the stage dir.
rep(root / 'deps/+Sol2/Sol2.cmake',
    '            -DSOL2_BUILD_LUA=OFF',
    '            -DSOL2_BUILD_LUA=OFF\n            -DLUA_INCLUDE_DIR=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}/include\n            -DLUA_LIBRARY=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}/lib/liblua.a',
    'sol2: explicit Lua paths')
# Bionic has no wordexp(), so Boost.Process cannot be built for Android. Two
# engine sources use it (post-processing scripts and drive ejection); stub them
# out for the console build.
rep(root / 'src/libslic3r/src/libslic3r/GCode/PostProcessor.cpp',
    '#include <cstdlib>   // getenv()\n#include <sstream>\n#include <boost/process.hpp>\n\nnamespace process = boost::process;\n\nstatic int run_script(const std::string &script, const std::string &gcode, std::string &std_err)\n{',
    '#include <cstdlib>   // getenv()\n#include <sstream>\n\n#if defined(__ANDROID__)\n// Post-processing scripts are unavailable without Boost.Process.\nstatic int run_script(const std::string &, const std::string &, std::string &std_err)\n{\n    std_err = "Post-processing scripts are not supported by the Android engine.";\n    return -1;\n}\n#else\n#include <boost/process.hpp>\n\nnamespace process = boost::process;\n\nstatic int run_script(const std::string &script, const std::string &gcode, std::string &std_err)\n{',
    'postprocessor: no boost.process on Android')
rep(root / 'src/libslic3r/src/libslic3r/GCode/PostProcessor.cpp',
    '    child.wait();\n    return child.exit_code();\n}\n\n#endif',
    '    child.wait();\n    return child.exit_code();\n}\n#endif // !__ANDROID__\n\n#endif',
    'postprocessor: close the Android guard')
rep(root / 'src/slic3r-shared/src/Slic3r/Biz/RemovableDrive/RemovableDriveServiceLinux.cpp',
    '#include <boost/process.hpp>',
    '#if !defined(__ANDROID__)\n#include <boost/process.hpp>\n#endif',
    'removable drive: no boost.process on Android')
rep(root / 'src/slic3r-shared/src/Slic3r/Biz/RemovableDrive/RemovableDriveServiceLinux.cpp',
    'namespace {\nbool eject_inner(const boost::filesystem::path& path)\n{',
    'namespace {\n#if defined(__ANDROID__)\nbool eject_inner(const boost::filesystem::path&)\n{\n    return true;\n}\n#else\nbool eject_inner(const boost::filesystem::path& path)\n{',
    'removable drive: stub eject on Android')
rep(root / 'src/slic3r-shared/src/Slic3r/Biz/RemovableDrive/RemovableDriveServiceLinux.cpp',
    '    return true;\n}\n} // namespace',
    '    return true;\n}\n#endif // __ANDROID__\n} // namespace',
    'removable drive: close the Android guard')
# The monitor's worker blocks in wait_for on m_thread_stop_condition, but the
# members are declared before m_thread, so the condition variable and its mutex
# are destroyed while the worker is still waking up: bionic then aborts with
# "pthread_mutex_lock called on a destroyed mutex" as the process exits. Join
# while every member is still alive.
rep(root / 'src/slic3r-shared/src/Slic3r/Biz/RemovableDrive/RemovableDriveMonitorLinux.hpp',
    '        if (m_thread.joinable()) {\n            m_thread.request_stop();\n            m_thread_stop_condition.notify_all();\n        }',
    '        if (m_thread.joinable()) {\n            m_thread.request_stop();\n            m_thread_stop_condition.notify_all();\n            // The worker waits in wait_for on the condition variable, and the members\n            // above it are destroyed first: join before that happens.\n            m_thread.join();\n        }',
    'removable drive: join the monitor worker on teardown')
# PrusaSlicer 3.0 builds its CLI only when SLIC3R_GUI is on (slic3r-app-cli links
# the ImGui/Plater slic3r-shared library). Build a headless console instead that
# links the GUI-free engine layers; the platform layer is GUI-free apart from the
# OpenGL render canvas.
rep(root / 'src/CMakeLists.txt',
    'if (SLIC3R_GUI)\n    # TODO: The following two are GUI only for now, before we can build these',
    'add_subdirectory(slic3r-platform)\n\nif (SLIC3R_GUI)\n    # TODO: The following two are GUI only for now, before we can build these',
    'src: platform layer outside the GUI gate')
rep(root / 'src/CMakeLists.txt',
    '    add_subdirectory(slic3r-render)\n    add_subdirectory(slic3r-platform)\n    add_subdirectory(libvgcode)',
    '    add_subdirectory(slic3r-render)\n    add_subdirectory(libvgcode)',
    'src: drop duplicate platform subdir')
rep(root / 'src/CMakeLists.txt',
    'else()\n    message(FATAL_ERROR "Non-GUI build is not supported yet.")\nendif ()',
    'else()\n    add_subdirectory(slic3r-console-headless)\nendif ()',
    'src: headless console target')
rep(root / 'src/slic3r-platform/CMakeLists.txt',
    'add_library(slic3r-platform STATIC ${SLIC3R_PLATFORM_FILES})',
    'if (NOT SLIC3R_GUI)\n    # The render canvas needs OpenGL/ImGui: only for GUI builds.\n    list(REMOVE_ITEM SLIC3R_PLATFORM_FILES src/Slic3r/App/Platform/AbstractRenderCanvas.cpp)\nendif ()\n\nadd_library(slic3r-platform STATIC ${SLIC3R_PLATFORM_FILES})',
    'platform: no OpenGL canvas when headless')
rep(root / 'src/slic3r-platform/CMakeLists.txt',
    'target_link_libraries(slic3r-platform PUBLIC slic3r-render slic3r-jthread)',
    'target_link_libraries(slic3r-platform PUBLIC slic3r-jthread)\n\nif (SLIC3R_GUI)\n    target_link_libraries(slic3r-platform PUBLIC slic3r-render)\nelse ()\n    # Headless: slic3r-render is GUI-only, but the platform layer still includes\n    # its headers (ScreenInfo) plus the base/domain layers it used to pull in.\n    target_link_libraries(slic3r-platform PUBLIC slic3r-base slic3r-domain)\n    target_include_directories(slic3r-platform PUBLIC ${CMAKE_SOURCE_DIR}/src/slic3r-render/include)\nendif ()',
    'platform: render link only for GUI')
# OpenSSL ships no generic CMake config: its ./Configure must target android-*.
rep(root / 'deps/+OpenSSL/OpenSSL.cmake',
    'set(_conf_cmd "./config")\nset(_cross_arch "")\nset(_cross_comp_prefix_line "")\nset(_apple_target_flags "")',
    'set(_conf_cmd "./config")\nset(_cross_arch "")\nset(_cross_comp_prefix_line "")\nset(_apple_target_flags "")\nset(_openssl_tgt "")',
    'openssl: tgt var')
rep(root / 'deps/+OpenSSL/OpenSSL.cmake',
    'elseif (CMAKE_CROSSCOMPILING)',
    'elseif (ANDROID)\n    set(_conf_cmd "./Configure")\n    if (ANDROID_ABI STREQUAL "arm64-v8a")\n        set(_cross_arch "linux-aarch64")\n        set(_openssl_tgt "--target=aarch64-linux-android24")\n    elseif (ANDROID_ABI STREQUAL "x86_64")\n        set(_cross_arch "linux-x86_64")\n        set(_openssl_tgt "--target=x86_64-linux-android24")\n    else ()\n        message(FATAL_ERROR "OpenSSL: unsupported Android ABI: ${ANDROID_ABI}")\n    endif ()\n    get_filename_component(_ndk_bin ${CMAKE_C_COMPILER} DIRECTORY)\n    if (ANDROID_ABI STREQUAL "arm64-v8a")\n        set(_ndk_cc_name aarch64-linux-android24-clang)\n        set(_ndk_cxx_name aarch64-linux-android24-clang++)\n    elseif (ANDROID_ABI STREQUAL "x86_64")\n        set(_ndk_cc_name x86_64-linux-android24-clang)\n        set(_ndk_cxx_name x86_64-linux-android24-clang++)\n    endif ()\nelseif (CMAKE_CROSSCOMPILING)',
    'openssl: android configure target')
rep(root / 'deps/+OpenSSL/OpenSSL.cmake',
    '    CONFIGURE_COMMAND ${_conf_cmd} ${_cross_arch}',
    '    CONFIGURE_COMMAND env "CC=${_ndk_bin}/${_ndk_cc_name}" "CXX=${_ndk_bin}/${_ndk_cxx_name}" ${_conf_cmd} ${_cross_arch}',
    'openssl: clang wrapper')
rep(root / 'deps/+OpenSSL/OpenSSL.cmake',
    '        "--prefix=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}"\n        ${_cross_comp_prefix_line}',
    '        "--prefix=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}"\n        --libdir=lib\n        ${_cross_comp_prefix_line}',
    'openssl: libdir lib')
rep(root / 'deps/+CURL/CURL.cmake',
    '  -DHTTP_ONLY=ON',
    '  -DHTTP_ONLY=ON\n  -DOPENSSL_ROOT_DIR=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}\n  -DOPENSSL_CRYPTO_LIBRARY=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}/lib/libcrypto.a\n  -DOPENSSL_SSL_LIBRARY=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}/lib/libssl.a\n  -DOPENSSL_INCLUDE_DIR=${${PROJECT_NAME}_DEP_INSTALL_PREFIX}/include',
    'curl: explicit openssl paths')
rep(root / 'deps/+LibAssert/LibAssert.cmake',
    '            -DLIBASSERT_USE_EXTERNAL_CPPTRACE=ON',
    '            -DLIBASSERT_USE_EXTERNAL_CPPTRACE=OFF',
    'libassert: bundled cpptrace (no find_package)')

# cpptrace installs its own <pkg>-config.cmake which demands find_dependency(libdwarf);
# our shims provide the config, so neutralize cpptrace's config/version/targets
# installs by shipping a second patch that removes those install() blocks.

# cpptrace's own install emits a config that demands find_dependency(libdwarf),
# which the cross-build cannot satisfy; strip the config/version/targets
# install() blocks so the shim configs (write_shims) are authoritative.
(root / 'deps/+cpptrace' / 'strip_config_installs.py').write_text('''
import pathlib
p = pathlib.Path('cmake/InstallRules.cmake')
s = p.read_text(encoding='utf-8')
i = s.index('# copy config file for find_package to find')
j = s.index('# support packaging library')
s = s[:i].rstrip() + chr(10) * 2 + s[j:]
p.write_text(s, encoding='utf-8')
print('cpptrace: config installs stripped')
''')


# Boost.Regex ships only a header-only CMakeLists in 1.86 (INTERFACE lib, so no
# libboost_regex.a is produced), but CMake 3.31's FindBoost module hard-links
# Boost::iostreams -> Boost::regex and looks for a real static archive. Replace
# libs/regex/CMakeLists.txt with a static build of the b2 sources so that
# libboost_regex.a is built and installed into the deps stage dir.
(root / 'deps/+Boost' / 'make_regex_static.py').write_text('''
from pathlib import Path

cmake = '\\n'.join([
    'cmake_minimum_required(VERSION 3.5...3.16)',
    '',
    'project(boost_regex VERSION "${BOOST_SUPERPROJECT_VERSION}" LANGUAGES CXX)',
    '',
    'add_library(boost_regex',
    '  src/posix_api.cpp',
    '  src/regex.cpp',
    '  src/regex_debug.cpp',
    '  src/static_mutex.cpp',
    '  src/wide_posix_api.cpp',
    ')',
    '',
    'add_library(Boost::regex ALIAS boost_regex)',
    '',
    'target_include_directories(boost_regex PUBLIC include)',
    '',
    'target_link_libraries(boost_regex',
    '  PUBLIC',
    '    Boost::assert',
    '    Boost::config',
    '    Boost::core',
    '    Boost::static_assert',
    '    Boost::predef',
    '    Boost::throw_exception',
    ')',
    '',
    'target_compile_definitions(boost_regex PUBLIC BOOST_REGEX_NO_LIB)',
    '',
    'if(BUILD_TESTING AND EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/test/CMakeLists.txt")',
    '  add_subdirectory(test)',
    'endif()',
]);
Path('libs/regex/CMakeLists.txt').write_text(cmake + '\\n', encoding='utf-8')
print('boost: static regex CMakeLists written')
''')


PY

# Headless console sources (see src/slic3r-console-headless/CMakeLists.txt for why
# they are not part of the upstream tree).
CONSOLE_DIR="$SRC/src/slic3r-console-headless"
mkdir -p "$CONSOLE_DIR"
cat > "$CONSOLE_DIR/main.cpp" <<'CEOF'
// Headless slicing console for the Android bundle.
//
// PrusaSlicer 3.0 gates its stock CLI behind SLIC3R_GUI (slic3r-app-cli links the
// ImGui/Plater library), so this front-end drives the GUI-free engine layers
// (Slic3r::Domain / Slic3r::Biz) directly. It accepts the same invocation the
// app uses:
//
//   prusa-slicer --datadir DIR --load config.json --export-gcode -o out.gcode model.stl
//
// The configuration file is the PrusaSlicer "--save" format ({preset, configuration}).

#include <chrono>
#include <cstdlib>
#include <fstream>
#include <functional>
#include <future>
#include <iostream>
#include <memory>
#include <optional>
#include <string>
#include <thread>
#include <vector>

#include <boost/filesystem.hpp>
#include <nlohmann/json.hpp>

#include "Slic3r/App/Platform/StdMainThreadDispatcher.hpp"
#include "Slic3r/App/DisplayStrings.hpp"
#include "console_services.hpp"
#include "Slic3r/Biz/AppInstance/AppInstanceMessageHandlerFactory.hpp"
#include "Slic3r/Biz/Config/ConfigLoad.hpp"
#include "Slic3r/Biz/FileLoadingLogic.hpp"
#include "Slic3r/Biz/Platform/JobManager/JobManager.hpp"
#include "Slic3r/Biz/Platform/PlatformServices.hpp"
#include "Slic3r/Biz/Preset/IO/BundlePaths.hpp"
#include "Slic3r/Biz/ProjectInteractor.hpp"
#include "Slic3r/Biz/SecretStoreDummy.hpp"
#include "Slic3r/Biz/StatusCache.hpp"
#include "Slic3r/Directories.hpp"
#include "Slic3r/Domain/JobStatus.hpp"
#include "Slic3r/Domain/Workbench.hpp"
#include "libslic3r/IThumbnailImageGenerator.hpp"

using namespace Slic3r;
using namespace Slic3r::Biz;

namespace {

/// Thumbnails are a GUI feature; the console reports none.
class StubThumbnailGenerator final : public Slicing::IThumbnailImageGenerator
{
public:
    std::future<Slicing::ThumbnailImageResults> enqueue_thumbnail_requests(
        const Slicing::ThumbnailImageRequests&
    ) override
    {
        std::promise<Slicing::ThumbnailImageResults> promise;
        promise.set_value(Slicing::ThumbnailImageResults{});
        return promise.get_future();
    }

    void handle_enqueued_requests() override {}
};

/// The interactor's background stores keep using the main thread dispatcher, so
/// it has to be closed before the interactor is destroyed - the same order the
/// reference CLI uses in its destructor. Declare the guard after the interactor
/// so it is destroyed first.
class DispatcherGuard
{
public:
    explicit DispatcherGuard(Platform::PlatformServices& services) : m_services(services) {}
    ~DispatcherGuard() { m_services.main_thread_dispatcher().close(); }

    DispatcherGuard(const DispatcherGuard&)            = delete;
    DispatcherGuard& operator=(const DispatcherGuard&) = delete;

private:
    Platform::PlatformServices& m_services;
};

/// Watches the export job so the console can block until the G-code was written.
class ExportFinishedListener final :
    public Platform::JobManager::IJobManagerStatusChangedListener
{
public:
    bool finished{false};
    bool failed{false};
    std::string error;

    void on_job_manager_status_changed(
        const Platform::JobManager::JobManagerStatus& job_manager_status
    ) override
    {
        for (const auto& [job_name, job_progress] : job_manager_status) {
            if (job_name.rfind("printhost", 0) != 0) {
                continue;
            }

            if (job_progress.status == Domain::JobStatus::Failed) {
                failed   = true;
                finished = true;
            } else if (job_progress.status == Domain::JobStatus::Finished) {
                finished = true;
            }
        }
    }
};

/// Reports slicing progress the way the CLI does ("NN => stage"), which is what
/// the Android app parses for its progress bar. The GUI reads the same cache.
class SlicingProgressListener final : public IStatusCacheChangedListener
{
public:
    explicit SlicingProgressListener(StatusCache& status_cache) :
        m_status_cache(status_cache)
    {}

    void watch(const Domain::SlicingId id)
    {
        m_slicing_id    = id;
        m_last_percent  = -1;
    }

    void on_status_cache_progress_changed(const Domain::SlicingId id) override
    {
        if (id != m_slicing_id) {
            return;
        }

        const std::optional<Slicing::Status> status = m_status_cache.get_status(id);
        if (!status.has_value() || !status->progress.has_value()) {
            return;
        }

        const int percent = static_cast<int>(status->progress->progress.value);
        if (percent == m_last_percent) {
            return;
        }
        m_last_percent = percent;

        std::cout << percent << " => "
                  << App::to_display_string(status->progress->progress_info)
                  << std::endl;
    }

private:
    StatusCache&           m_status_cache;
    Domain::SlicingId      m_slicing_id;
    int                    m_last_percent{-1};
};

/// Mirrors Slic3r::App::init_paths() so the engine finds the bundled presets.
void prepare_datadirs(const std::string& datadir)
{
    namespace fs = boost::filesystem;
    const fs::path data_dir(datadir);
    for (const fs::path& sub : {
             data_dir,
             data_dir / "cache",
             data_dir / "update_sync",
             data_dir / "shared_runtime",
             data_dir / "local_repositories",
             data_dir / "snapshots",
             data_dir / "presets",
             data_dir / "presets" / "local",
             data_dir / "presets" / "user",
             data_dir / "shapes",
             data_dir / "lua",
             data_dir / "authorized_authors",
         })
    {
        if (!fs::exists(sub)) {
            fs::create_directories(sub);
        }
    }
}

std::string usage()
{
    return "usage: prusa-slicer --datadir DIR --load config.json --export-gcode "
           "-o out.gcode model.stl\n";
}

} // namespace

int main(int argc, char** argv)
{
    std::string datadir;
    std::string config_path;
    std::string output_path;
    std::string model_path;

    for (int i = 1; i < argc; ++i) {
        const std::string arg = argv[i];
        if (arg == "--datadir" && i + 1 < argc) {
            datadir = argv[++i];
        } else if (arg == "--load" && i + 1 < argc) {
            config_path = argv[++i];
        } else if (arg == "--export-gcode") {
            // Slicing always exports G-code in this console.
        } else if ((arg == "-o" || arg == "--output") && i + 1 < argc) {
            output_path = argv[++i];
        } else if (arg == "--help" || arg == "-h") {
            std::cout << usage();
            return EXIT_SUCCESS;
        } else if (!arg.empty() && arg[0] != '-') {
            model_path = arg;
        }
    }

    if (model_path.empty() || config_path.empty() || output_path.empty()) {
        std::cerr << usage();
        return EXIT_FAILURE;
    }

    if (!datadir.empty()) {
        Slic3r::set_data_dir(datadir);
        Slic3r::set_cache_dir((boost::filesystem::path(datadir) / "cache").string());
        Slic3r::set_resources_dir(datadir);
        Slic3r::set_var_dir((boost::filesystem::path(datadir) / "icons").string());
        prepare_datadirs(datadir);
    }

    Platform::PlatformServices& platform_services = Platform::PlatformServices::instance();
    platform_services.set_secret_store(std::make_unique<SecretStoreDummy>());
    // Setting the dispatcher also creates the timer queue the engine expects.
    platform_services.set_main_thread_dispatcher(
        std::make_unique<App::Platform::StdMainThreadDispatcher>()
    );
    platform_services.set_job_manager(
        std::make_unique<Platform::JobManager::JobManager>(
            platform_services.main_thread_dispatcher()
        )
    );
    platform_services.set_app_instance_message_handler(
        AppInstance::create_app_instance_message_handler(
            platform_services.main_thread_dispatcher()
        )
    );
    platform_services.set_single_instance_checker(Console::create_single_instance_checker());
    platform_services.set_app_config_provider(Console::create_app_config_provider());
    platform_services.set_render_request_handler(Console::create_render_request_handler());

    const auto wait_until = [&platform_services](const std::function<bool()>& predicate)
    {
        while (true) {
            platform_services.main_thread_dispatcher().dispatch_enqueued();
            if (predicate()) {
                return;
            }

            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
    };

    StubThumbnailGenerator thumbnail_generator;
    Domain::Workbench workbench;
    ProjectInteractor project_interactor(
        workbench,
        platform_services.main_thread_dispatcher(),
        thumbnail_generator
    );

    // Load the vendor bundles from the datadir. Without them the workbench has no
    // vendor configs and loading the selected preset dereferences an empty bundle.
    project_interactor.preset_interactor().load_preset_bundle(
        Preset::IO::BundlePaths::make_standard_runtime()
    );

    DispatcherGuard dispatcher_guard(platform_services);

    // The app passes a full "--save" document; the bundles above resolve the
    // vendor and printer it refers to.
    nlohmann::ordered_json config_document;
    try {
        std::ifstream config_stream(config_path);
        if (!config_stream.is_open()) {
            std::cerr << "Cannot open configuration " << config_path << "\n";
            return EXIT_FAILURE;
        }
        config_stream >> config_document;
    } catch (const std::exception& exception) {
        std::cerr << "Invalid configuration " << config_path << ": " << exception.what() << "\n";
        return EXIT_FAILURE;
    }

    tl::expected<Config::PresetAndConfig, std::string> preset_and_config =
        Config::load_preset_and_config(config_document);
    if (!preset_and_config.has_value()) {
        std::cerr << config_path << ": " << preset_and_config.error() << "\n";
        return EXIT_FAILURE;
    }

    tl::expected<Domain::SelectionId, std::string> project_id =
        project_interactor.new_project_with_preset(
            preset_and_config->preset_metadata,
            preset_and_config->config_pack
        );
    if (!project_id.has_value()) {
        std::cerr << project_id.error() << "\n";
        return EXIT_FAILURE;
    }

    tl::expected<Domain::Model, std::string> model =
        FileLoadingLogic::read_model_from_file(model_path, nullptr);
    if (!model.has_value()) {
        std::cerr << model_path << ": " << model.error() << "\n";
        return EXIT_FAILURE;
    }
    if (model->objects.empty()) {
        std::cerr << model_path << ": no objects in the model\n";
        return EXIT_FAILURE;
    }

    project_interactor.scene_interactor().add_new_objects(std::move(model->objects));

    Slicing::SlicingInteractor& slicing_interactor = project_interactor.slicing_interactor();
    StatusCache& status_cache                      = project_interactor.status_cache();
    const Domain::SlicingId slicing_id             = project_interactor.selected_bed_slicing_id();

    SlicingProgressListener progress_listener(status_cache);
    progress_listener.watch(slicing_id);
    status_cache.add_listener<IStatusCacheChangedListener>(&progress_listener);

    Domain::Project& project                        = project_interactor.selected_project();
    const Domain::ConfigContainer& config_container = project_interactor.selected_config_container();

    const Domain::BedInstance* bed_instance =
        project.find_bed_instance_by_id(slicing_id.bed_instance_id);
    if (bed_instance == nullptr) {
        std::cerr << "No print bed selected\n";
        return EXIT_FAILURE;
    }

    slicing_interactor.update_process(
        project.model(),
        project.metadata(),
        config_container.selected_preset().metadata(),
        config_container.build_print_config(),
        *bed_instance
    );
    slicing_interactor.slice_bed(slicing_id);

    wait_until(
        [&status_cache, &slicing_id]()
        {
            const std::optional<Slicing::Status> status = status_cache.get_status(slicing_id);
            return status.has_value()
                && (status->code == Slicing::StatusCode::Empty
                    || status->code == Slicing::StatusCode::Removed
                    || status->code == Slicing::StatusCode::Finished
                    || status->code == Slicing::StatusCode::InvalidData);
        }
    );

    const std::optional<Slicing::Status> slicing_status = status_cache.get_status(slicing_id);
    if (!slicing_status.has_value() || slicing_status->code != Slicing::StatusCode::Finished) {
        std::cerr << "Slicing failed";
        if (slicing_status.has_value()) {
            for (const Slicing::Error& error : slicing_status->errors) {
                std::cerr << ": " << error;
            }
        }
        std::cerr << "\n";
        return EXIT_FAILURE;
    }

    ExportFinishedListener export_listener;
    platform_services.job_manager()
        .add_listener<Platform::JobManager::IJobManagerStatusChangedListener>(&export_listener);
    project_interactor.do_result_export(
        slicing_id,
        boost::filesystem::path(output_path)
    );
    wait_until([&export_listener]() { return export_listener.finished; });
    platform_services.job_manager()
        .remove_listener<Platform::JobManager::IJobManagerStatusChangedListener>(&export_listener);

    if (export_listener.failed) {
        std::cerr << "Export failed\n";
        return EXIT_FAILURE;
    }

    std::cout << "Slicing result exported to " << output_path << "\n";
    return EXIT_SUCCESS;
}
CEOF
cat > "$CONSOLE_DIR/console_services.hpp" <<'CEOF'
#pragma once

// Services the desktop app provides through its window / IPC layer. The console
// supplies console-only implementations of them.

#include "Slic3r/Biz/Platform/IAppConfigProvider.hpp"
#include "Slic3r/Biz/Platform/IRenderRequestHandler.hpp"
#include "Slic3r/Biz/Platform/ISingleInstanceChecker.hpp"

#include <memory>

namespace Slic3r::Console {

std::unique_ptr<Biz::Platform::IAppConfigProvider> create_app_config_provider();
std::unique_ptr<Biz::Platform::ISingleInstanceChecker> create_single_instance_checker();

/// Owned by the process; the platform services only borrow it.
Biz::Platform::IRenderRequestHandler* create_render_request_handler();

} // namespace Slic3r::Console
CEOF

cat > "$CONSOLE_DIR/console_services.cpp" <<'CEOF'
// The platform services hold a message handler, a config provider, a single
// instance checker and a render handler. Their desktop implementations need
// dbus / Win32 messages, a lock file and a window, and the accessors assert
// when they are missing, so the console provides no-op counterparts that are
// real objects. The engine calls into the message handler while it handles
// projects and backups.
#include "console_services.hpp"

#include "Slic3r/Biz/AppInstance/AppInstanceMessageHandlerFactory.hpp"
#include "Slic3r/Directories.hpp"

#include <boost/filesystem.hpp>
#include <boost/system/error_code.hpp>

namespace Slic3r::Biz::AppInstance {

namespace {

class NullAppInstanceMessageSender final : public AbstractAppInstanceMessageSender
{
public:
    void multicast_message(const std::string&, const std::string&, size_t, void*) override {}
    void broadcast_message(const std::string&, const std::string&, size_t, void*) override {}
};

class NullAppInstanceMessageHandler final : public AbstractAppInstanceMessageHandler
{
public:
    using AbstractAppInstanceMessageHandler::AbstractAppInstanceMessageHandler;

    void init(void*) override {}
    void multicast_message(const std::string&, const std::string&) override {}
    void on_becoming_primary_instance() override {}
};

} // namespace

std::unique_ptr<AbstractAppInstanceMessageSender> create_app_instance_message_sender()
{
    return std::make_unique<NullAppInstanceMessageSender>();
}

std::unique_ptr<AbstractAppInstanceMessageHandler> create_app_instance_message_handler(
    Platform::IMainThreadDispatcher& dispatcher
)
{
    return std::make_unique<NullAppInstanceMessageHandler>(dispatcher);
}

} // namespace Slic3r::Biz::AppInstance

namespace Slic3r::Console {

namespace {

/// The desktop implementation is backed by AppServices, which the console does
/// not start. Only STEP import reads these values, so the schema defaults from
/// AppConfig.cpp are reported and the setter side is a no-op.
class ConsoleAppConfigProvider final : public Biz::Platform::IAppConfigProvider
{
public:
    boost::filesystem::path download_dir() const override
    {
        namespace fs = boost::filesystem;
        const fs::path dir = fs::path(Slic3r::data_dir()) / "downloads";
        boost::system::error_code ec;
        fs::create_directories(dir, ec);
        return dir;
    }

    bool get_show_step_import_parameters() const override { return true; }
    void set_show_step_import_parameters(bool) override {}
    double get_step_linear_precision() const override { return 0.005; }
    void set_step_linear_precision(double) override {}
    double get_step_angle_precision() const override { return 1.; }
    void set_step_angle_precision(double) override {}
};

/// Nothing is rendered, so nothing has to be repainted.
class NullRenderRequestHandler final : public Biz::Platform::IRenderRequestHandler
{
public:
    void request_render() override {}
};

/// One process is never competing with another instance of itself.
class ConsoleSingleInstanceChecker final : public Biz::Platform::ISingleInstanceChecker
{
public:
    bool is_another_running() override { return false; }
    bool is_primary_instance() override { return true; }
};

} // namespace

std::unique_ptr<Biz::Platform::IAppConfigProvider> create_app_config_provider()
{
    return std::make_unique<ConsoleAppConfigProvider>();
}

std::unique_ptr<Biz::Platform::ISingleInstanceChecker> create_single_instance_checker()
{
    return std::make_unique<ConsoleSingleInstanceChecker>();
}

Biz::Platform::IRenderRequestHandler* create_render_request_handler()
{
    static NullRenderRequestHandler handler;
    return &handler;
}

} // namespace Slic3r::Console
CEOF

cat > "$CONSOLE_DIR/CMakeLists.txt" <<'CEOF'
# Headless console for the Android bundle.
#
# PrusaSlicer 3.0 builds its CLI only with the GUI (slic3r-app-cli links the
# ImGui/Plater library, see src/CMakeLists.txt). This target links the GUI-free
# engine layers instead, so the console can be cross-compiled for Android.

set(_headless_sources main.cpp console_services.cpp)

# The business-logic layer (and the app services it uses) lives in slic3r-shared,
# whose library target also carries the ImGui/Plater code. Compile the GUI-free
# part of that target's source list directly into this target instead.
#
# The list is read from the real target instead of globbing the directory: the
# tree also holds sources upstream does not compile (disabled by commenting them
# out) and variant specific ones that only build in their own configuration.
file(READ "${CMAKE_SOURCE_DIR}/src/slic3r-shared/CMakeLists.txt" _shared_cmake)
string(REGEX REPLACE "#[^\n]*" "" _shared_cmake "${_shared_cmake}")
string(REGEX MATCHALL "src/Slic3r/[A-Za-z0-9_./+-]+\\.cpp" _headless_candidates "${_shared_cmake}")
list(REMOVE_DUPLICATES _headless_candidates)
list(TRANSFORM _headless_candidates PREPEND "${CMAKE_SOURCE_DIR}/src/slic3r-shared/")

foreach(_candidate ${_headless_candidates})
    # Windows/macOS implementations and the desktop IPC factories are not part of
    # this build. The Linux implementations are __ANDROID__ aware and compile as
    # they are, so they stay in.
    if (_candidate MATCHES "(Win32|Mac)\\.cpp$")
        continue()
    endif ()
    if (_candidate MATCHES "AppInstanceMessageHandler(Factory)?(Linux|Win32|Mac)\\.cpp$")
        continue()
    endif ()

    # The shared library picks exactly one YAML adapter via SLIC3R_YAML.
    if (_candidate MATCHES "YamlAdapter(Libfyaml|YamlCpp)\\.cpp$")
        continue()
    endif ()

    # Sources that include a GUI toolkit or a desktop only library cannot be part
    # of this target. Only include directives are inspected: source text may
    # mention e.g. "GLES" or "imgui" inside strings and comments.
    file(STRINGS "${_candidate}" _gui_includes REGEX "^[ \\t]*#[ \\t]*include.*(<GL/|<GLES/|<wx/|<Windows[.]h|<dbus/|libvgcode|Slic3r/App/(Render|Plater|Yoga|Imgui|Preview|View|ToolBar|Browser|Scene|Undo)/)")
    if (_gui_includes)
        continue()
    endif ()

    list(APPEND _headless_sources "${_candidate}")
endforeach()

# Third-party dependencies of the business logic; the GUI libraries normally pull
# these in, so this target resolves them itself.
foreach(_package nlohmann_json magic_enum pugixml cereal expat CURL PNG JPEG TBB Boost ZLIB libdeflate)
    find_package(${_package} QUIET)
endforeach()

# yoga ships a config package only (used by the Yoga UI headers).
find_package(yoga CONFIG QUIET)

# libassert (through slic3r-base) links cpptrace, which resolves DWARF symbols
# from libdwarf. Both provide config packages; ask for them here so the imported
# targets exist in this directory too.
find_package(cpptrace CONFIG QUIET)
# libdwarf's link interface references zstd::libzstd_static.
find_package(zstd CONFIG QUIET)
find_package(libdwarf CONFIG QUIET)

# imgui headers are needed by Theme.hpp. Adding the bundled target would drag
# glfw3/SDL2/OpenGL finds into this GUI-free configure, so expose the vendored
# headers as an interface target instead (the console links no imgui symbols).
set(_imgui_root "${CMAKE_SOURCE_DIR}/bundled_deps/imgui")
if (EXISTS "${_imgui_root}/imgui/imgui.h" AND NOT TARGET imgui)
    add_library(imgui INTERFACE IMPORTED)
    set_target_properties(imgui PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES "${_imgui_root};${_imgui_root}/imgui")
endif ()

add_executable(slic3r-console-headless ${_headless_sources})

target_include_directories(slic3r-console-headless PRIVATE
    "${CMAKE_SOURCE_DIR}/bundled_deps/imgui/imgui"
    "${CMAKE_SOURCE_DIR}/src/slic3r-shared/include"
    "${CMAKE_SOURCE_DIR}/src/slic3r-shared/src"
    "${CMAKE_SOURCE_DIR}/src/slic3r-platform/include"
    "${CMAKE_SOURCE_DIR}/src/slic3r-render/include"
    "$<TARGET_PROPERTY:libslic3r,SOURCE_DIR>/src"
)

# The shared library selects its YAML backend at configure time and passes the
# matching define to its sources; this target compiles those sources itself.
if ("${SLIC3R_YAML}" STREQUAL "ryml")
    find_package(ryml CONFIG REQUIRED)
    target_link_libraries(slic3r-console-headless PRIVATE ryml::ryml)
    target_compile_definitions(slic3r-console-headless PRIVATE SLIC3R_YAML_RYML)
else ()
    message(FATAL_ERROR "The headless console expects SLIC3R_YAML=ryml, got '${SLIC3R_YAML}'.")
endif ()

# Option driven defines the shared library sets for the sources compiled here.
if (SLIC3R_ENABLE_FORMAT_STEP)
    target_compile_definitions(slic3r-console-headless PRIVATE SLIC3R_ENABLE_FORMAT_STEP=1)
    get_target_property(OCCT_TYPE OCCTWrapper TYPE)
    if (OCCT_TYPE STREQUAL "MODULE_LIBRARY")
        add_dependencies(slic3r-console-headless OCCTWrapper)
        # A MODULE library cannot be linked; the include directory is enough.
        target_include_directories(slic3r-console-headless PRIVATE "${CMAKE_SOURCE_DIR}/src/occt_wrapper")
    else ()
        target_link_libraries(slic3r-console-headless PRIVATE OCCTWrapper)
    endif ()
endif ()

if (SLIC3R_ENABLE_WIN10_MESH_REPAIR)
    target_compile_definitions(slic3r-console-headless PRIVATE SLIC3R_ENABLE_WIN10_MESH_REPAIR=1)
endif ()

if (SLIC3R_DEBUG_PRESET_CACHE)
    target_compile_definitions(slic3r-console-headless PRIVATE SLIC3R_DEBUG_PRESET_CACHE=1)
endif ()

slic3r_add_tracy(slic3r-console-headless)

# Link everything this configuration provides; GUI-only targets are skipped.
function(_headless_link)
    foreach(_dep IN LISTS ARGN)
        if (TARGET ${_dep})
            target_link_libraries(slic3r-console-headless PRIVATE ${_dep})
        else ()
            message(STATUS "headless console: target ${_dep} unavailable, skipping")
        endif ()
    endforeach()
endfunction()

_headless_link(
    slic3r-domain
    slic3r-base
    slic3r-biz-algorithms
    slic3r-biz-arrange
    slic3r-biz-crypto
    slic3r-biz-parser
    slic3r-biz-lua
    slic3r-platform
    slic3r-gcode-reader
    libpgcode
    slic3r-jthread
    libslic3r
    imgui
    fastfloat
    yoga::yogacore
    fmt::fmt
    nlohmann_json::nlohmann_json
    magic_enum::magic_enum
    expat::expat
    libexpat
    pugixml::pugixml
    range-v3::range-v3
    libcereal
    libdeflate::libdeflate_static
    cpptrace::cpptrace
    libdwarf::dwarf
    TBB::tbb
    TBB::tbbmalloc
    ZLIB::ZLIB
    PNG::PNG
    JPEG::JPEG
    CURL::libcurl
    Boost::filesystem
    Boost::thread
)
CEOF
step "[2b/5] headless console sources written"


# OpenSSL's android configuration still looks for NDK <triple>-gcc names;
# the NDK ships clang wrappers only, so provide the classic symlinks.
NDKBIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
export ANDROID_API=24
for TRIPLE in aarch64-linux-android x86_64-linux-android; do
  ln -sf clang "$NDKBIN/$TRIPLE${ANDROID_API}-gcc"
  ln -sf clang++ "$NDKBIN/$TRIPLE${ANDROID_API}-g++"
  ln -sf clang "$NDKBIN/$TRIPLE-gcc"
  ln -sf clang++ "$NDKBIN/$TRIPLE-g++"
done

# Autotools-based deps (GMP/MPFR/OpenSSL/Lua) inherit the NDK wrappers through
# the exported CC/CXX; the NDK clang wrapper configures target + sysroot itself.
TOOLBIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
if [ "$ABI" = "arm64-v8a" ]; then
  export CC=$TOOLBIN/aarch64-linux-android24-clang
  export CXX=$TOOLBIN/aarch64-linux-android24-clang++
elif [ "$ABI" = "x86_64" ]; then
  export CC=$TOOLBIN/x86_64-linux-android24-clang
  export CXX=$TOOLBIN/x86_64-linux-android24-clang++
fi

# cpptrace finds libdwarf via find_package, but the cross-installed configs are
# not self-sufficient for the bare find_package(cpptrace) that libassert issues.
# Write known-good self-contained configs BEFORE the deps build (deps may
# configure early) and AFTER it (the real installs may clobber them).
write_shims () {
  LIBDWARF_PREFIX=$BUILD/deps/destdir/usr/local
  mkdir -p "$LIBDWARF_PREFIX/lib/cmake/libdwarf" "$LIBDWARF_PREFIX/lib/cmake/cpptrace"
  cat > "$LIBDWARF_PREFIX/lib/cmake/libdwarf/libdwarfConfig.cmake" <<'CEO'
include("${CMAKE_CURRENT_LIST_DIR}/libdwarf-targets.cmake" OPTIONAL)
set(libdwarf_FOUND TRUE)
if(NOT TARGET libdwarf::dwarf AND EXISTS "${CMAKE_CURRENT_LIST_DIR}/../../../lib/libdwarf.a")
  add_library(libdwarf::dwarf STATIC IMPORTED)
  set_target_properties(libdwarf::dwarf PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_LIST_DIR}/../../../lib/libdwarf.a"
    INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_CURRENT_LIST_DIR}/../../../include")
endif()
CEO
  cat > "$LIBDWARF_PREFIX/lib/cmake/libdwarf/libdwarfConfigVersion.cmake" <<'CEO'
set(PACKAGE_VERSION 0.11.1)
set(PACKAGE_VERSION_COMPATIBLE TRUE)
CEO
  # The installed cpptrace targets file references libdwarf::dwarf, which the
  # cross build does not export; provide the interface target here instead (the
  # archive is self contained: libdwarf is bundled into it).
  cat > "$LIBDWARF_PREFIX/lib/cmake/cpptrace/cpptraceConfig.cmake" <<'CEO'
set(cpptrace_FOUND TRUE)
if(NOT TARGET cpptrace::cpptrace)
  add_library(cpptrace::cpptrace INTERFACE IMPORTED)
  set_target_properties(cpptrace::cpptrace PROPERTIES
    INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_CURRENT_LIST_DIR}/../../..;${CMAKE_CURRENT_LIST_DIR}/../../../include")
  if(EXISTS "${CMAKE_CURRENT_LIST_DIR}/../../../lib/libcpptrace.a")
    set_target_properties(cpptrace::cpptrace PROPERTIES
      INTERFACE_LINK_LIBRARIES "${CMAKE_CURRENT_LIST_DIR}/../../../lib/libcpptrace.a")
  endif()
endif()
if(NOT TARGET cpptrace)
  add_library(cpptrace INTERFACE IMPORTED)
  set_target_properties(cpptrace PROPERTIES INTERFACE_LINK_LIBRARIES cpptrace::cpptrace)
endif()
CEO
  cat > "$LIBDWARF_PREFIX/lib/cmake/cpptrace/cpptraceConfigVersion.cmake" <<'CEO'
set(PACKAGE_VERSION 1.0.4)
set(PACKAGE_VERSION_COMPATIBLE TRUE)
CEO
  echo "SHIM-BEGIN"; ls -R "$LIBDWARF_PREFIX/lib/cmake" 2>&1 | head -40 || true; echo "SHIM-END"
  mkdir -p "$LIBDWARF_PREFIX/lib/cmake/zstd"
  cat > "$LIBDWARF_PREFIX/lib/cmake/zstd/zstdConfig.cmake" <<'CEO'
include("${CMAKE_CURRENT_LIST_DIR}/zstd-targets.cmake" OPTIONAL)
include("${CMAKE_CURRENT_LIST_DIR}/zstdTargets.cmake" OPTIONAL)
if(NOT TARGET zstd::libzstd)
  add_library(zstd::libzstd INTERFACE IMPORTED)
  set_target_properties(zstd::libzstd PROPERTIES
    INTERFACE_INCLUDE_DIRECTORIES "${CMAKE_CURRENT_LIST_DIR}/../../..;${CMAKE_CURRENT_LIST_DIR}/../../../include")
endif()
set(zstd_FOUND TRUE)
CEO
  cat > "$LIBDWARF_PREFIX/lib/cmake/zstd/zstdConfigVersion.cmake" <<'CEO'
set(PACKAGE_VERSION 1.5.6)
set(PACKAGE_VERSION_COMPATIBLE TRUE)
set(PACKAGE_VERSION_UNSUITABLE FALSE)
CEO
}
write_shims



step "[3/5] dependency bundle (deps/ ExternalProject chain)"
mkdir -p "$PREFIX"
cmake -S "$SRC/deps" -B "$BUILD/deps" -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$TC \
  -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-24 -DANDROID_STL=c++_shared \
  -DCMAKE_BUILD_TYPE=Release \
  -DPrusaSlicer_deps_PACKAGE_EXCLUDES='wxWidgets|GLEW|GLFW|SDL2|SDL|OpenCSG|WebView2|Trumpeloeil|libfyaml|sentry' \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON 2>&1 | tee /tmp/prusa3-depconf.log
cmake --build "$BUILD/deps" -j 1 2>&1 | tee /tmp/prusa3-depbuild.log

# Re-write shims after the real installs (the deps chain may have clobbered them).
write_shims

step "[4/5] console-only PrusaSlicer ($ABI)"
# ccache keeps the long main-build compiles across CI runs (the checkout always
# has fresh timestamps, so ninja alone would rebuild everything).
CCACHE_ARGS=""
if command -v ccache >/dev/null 2>&1; then
  ccache --max-size=4G >/dev/null 2>&1 || true
  CCACHE_ARGS="-DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache"
  echo "ccache enabled: $(ccache --version | head -1)"
fi
cmake -S "$SRC" -B "$BUILD/main" -G Ninja \
  $CCACHE_ARGS \
  -DCMAKE_TOOLCHAIN_FILE=$TC \
  -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DSLIC3R_GUI=OFF -DSLIC3R_STATIC=ON -DSLIC3R_RELEASE_DEBUG_SYMBOLS=OFF \
  -DOPENSSL_ROOT_DIR=$DEST \
  -DOPENSSL_CRYPTO_LIBRARY=$DEST/lib/libcrypto.a \
  -DOPENSSL_SSL_LIBRARY=$DEST/lib/libssl.a \
  -DOPENSSL_INCLUDE_DIR=$DEST/include \
  -DBUILD_TESTING=OFF -DSLIC3R_BUILD_TESTS=OFF \
  -DCMAKE_PREFIX_PATH=$DEST -DCMAKE_FIND_ROOT_PATH=$DEST \
  "-DCMAKE_CXX_FLAGS=-isystem $DEST/include" \
  "-DCMAKE_EXE_LINKER_FLAGS=-nostdlib++ -Wl,-Bstatic -lz -lc++_static -lc++abi -Wl,-Bdynamic" \
  2>&1 | tee /tmp/prusa3-mainconf.log
ninja -C "$BUILD/main" -k 0 slic3r-console-headless -j8 2>&1 | tee /tmp/prusa3-ninja.log
# Link hygiene: the NDK sysroot provides libstdc++.so / libz.so stubs; the console
# must not depend on them. Drop -lstdc++ (static libc++ is used) and force -lz to
# resolve statically. The FIRST ninja invocation regenerates build.ninja.
BIN="$BUILD/main/build.ninja"
sed -i 's/ -lstdc++ /  /g' "$BIN"
sed -i 's/ -lz / -Wl,-Bstatic -lz -Wl,-Bdynamic /g' "$BIN"
sed -i "s#${DEST}/lib/libz.so##g" "$BIN"
rm -f "$BUILD/main/src/slic3r-console-headless/slic3r-console-headless"
ninja -C "$BUILD/main" -k 0 slic3r-console-headless -j8 2>&1 | tee /tmp/prusa3-ninja.log

step "[5/5] package $ABI"
mkdir -p "$OUT"
cp -v "$BUILD/main/src/slic3r-console-headless/slic3r-console-headless" "$OUT/prusa-slicer"
STRIP="${ANDROID_NDK_HOME:-$SDK/ndk/28.2.13676358}/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip"
if [ -x "$STRIP" ]; then "$STRIP" -s "$OUT/prusa-slicer"; fi
echo "== dynamic dependency verification =="
readelf -d "$OUT/prusa-slicer" | grep NEEDED | tee "$OUT/needed.txt" || echo "no dynamic dependencies beyond the interpreter"
if grep -qE 'libc\+\+_shared|libz\.so|libz3\.so|libTK|libstdc\+\+\.so' "$OUT/needed.txt"; then
  echo "::error::engine binary has undesirable NEEDED entries:"
  sed 's/^/::error:: /' "$OUT/needed.txt"
  exit 1
fi
rm -rf "$OUT/resources"
mkdir -p "$OUT/resources"
# Slicing needs the profiles; the lua trees back the plugin API. Fonts, icons,
# shaders, localizations, test data and the web UI belong to the GUI (the app
# extracts the presets only, so shipping the rest would bloat every download).
for _resource in presets lua lua_template; do
  if [ -d "$SRC/resources/$_resource" ]; then
    cp -r "$SRC/resources/$_resource" "$OUT/resources/"
  fi
done
rm -f "$OUT/resources.tar.gz"
tar -czf "$OUT/resources.tar.gz" -C "$OUT" resources
echo "== resources =="
ls "$OUT/resources" | tr '\n' ' '
echo
echo PRUSA-ENGINE-3-READY
file "$OUT/prusa-slicer" | head -1 || true