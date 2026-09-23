# native/blender -- CMake for libblender_exec.so (enderslicercura engine wrapper).
# Pattern: same as the CuraEngine/Prusa exec wrappers.
# Engine artifacts from the DSH-built package (PrintShare/blender-engine-arm64):
#   libs/*.a      -> 148 static libs, arm64-v8a, Blender 3.6
#   jniLibs/*.so  -> runtime shared libs (Alembic, OIIO, cpython, ...)
# Source headers: point BLENDER_SRC_DIR at the Blender source root used for the
# build (the epai 3.6 tree; needed to compile the creator sources).
cmake_minimum_required(VERSION 3.10.2)
project(blender_exec)

set(BLENDER_ENGINE_LIBS "$ENV{BLENDER_ENGINE_LIBS}" CACHE PATH "dir with lib*.a")
if(NOT BLENDER_ENGINE_LIBS)
  set(BLENDER_ENGINE_LIBS "${CMAKE_CURRENT_SOURCE_DIR}/blender-libs")
endif()
if(NOT EXISTS "${BLENDER_ENGINE_LIBS}/libbf_blenkernel.a")
  message(FATAL_ERROR "Blender engine libs not found at ${BLENDER_ENGINE_LIBS}; "
          "set BLENDER_ENGINE_LIBS or vendor blender-libs/ (see README.md)")
endif()

set(BLENDER_SO_DIR "$ENV{BLENDER_SO_DIR}" CACHE PATH "dir with engine .so set")
if(NOT BLENDER_SO_DIR)
  set(BLENDER_SO_DIR "${CMAKE_CURRENT_SOURCE_DIR}/blender-jniLibs")
endif()

set(BLENDER_LIBDIR "$ENV{BLENDER_LIBDIR}" CACHE PATH "lib-android_arm64 root")
set(BLENDER_SRC_DIR "$ENV{BLENDER_SRC_DIR}" CACHE PATH "Blender source root")
if(NOT BLENDER_SRC_DIR)
  set(BLENDER_SRC_DIR "${CMAKE_CURRENT_SOURCE_DIR}/blender-src")
endif()
set(BSRC "${BLENDER_SRC_DIR}")

add_definitions(
  -DWITH_PYTHON -DWITH_PYTHON_SECURITY -DWITH_BUILDINFO_HEADER -DWITH_BUILDINFO
  -DWITH_FREESTYLE -DWITH_AUDASPACE -DWITH_ALEMBIC -DWITH_OPENCOLLADA
  -DWITH_OPENCOLORIO -DWITH_OPENIMAGEDENOISE -DWITH_OPENSUBDIV
  -DWITH_USD -DWITH_DRACO -DWITH_OPENAL -DWITH_JACK -DWITH_PULSEAUDIO
  -DWITH_SDL -DWITH_TBB -DWITH_GMP -DWITH_LZMA -DWITH_LIBMV -DWITH_CYCLES
  -DWITH_CYCLES_LOGGING -DWITH_MEM_JEMALLOC -DWITH_CODEC_FFMPEG -DWITH_IMAGE_OPENEXR
  -DWITH_IMAGE_CINEON -DWITH_IMAGE_WEBP -DWITH_IMAGE_OPENJPEG -DWITH_IO_STL
  -DWITH_IO_PLY -DWITH_IO_WAVEFRONT_OBJ -DWITH_IO_GPENCIL -DWITH_CODEC_AVI
  -DWITH_OPENGL -DWITH_XR_OPENXR -DWITH_IK_SOLVER -DWITH_IK_ITASC
  -DWITH_QUADRIFLOW -DWITH_MOD_FLUID -DWITH_MOD_OCEANSIM -DWITH_BULLET
  -DWITH_COMPOSITOR_CPU -DWITH_INTERNATIONAL -DWITH_INPUT_NDOF -DWITH_POTRACE
  -DWITH_ASSERT_ABORT -DWITH_MEM_JEMALLOC
)

set(CREATOR_SRC
  blender_exec.cpp
  creator/creator.cc
  creator/creator_args.cc
  creator/creator_signals.cc
  creator/buildinfo.c
)

add_library(blender_exec SHARED ${CREATOR_SRC})
add_compile_options(-Wno-enum-constexpr-conversion -Wno-deprecated-declarations -Wno-unknown-warning-option)

add_definitions(
  -DNDEBUG -DWITH_ASSERT_ABORT -DWITH_BUILDINFO -DWITH_BUILDINFO_HEADER
  -DWITH_CYCLES_LOGGING -DWITH_FFMPEG -DWITH_FREESTYLE -DWITH_GMP -DWITH_LIBMV
  -DWITH_OCIO -DWITH_OPENGL -DWITH_PYTHON -DWITH_PYTHON_SECURITY -DWITH_SDL
  -DWITH_SSE2NEON -DWITH_TBB -DWITH_USD
  -D_FILE_OFFSET_BITS=64 -D_LARGEFILE64_SOURCE -D_LARGEFILE_SOURCE -D__LITTLE_ENDIAN__
)

file(GLOB BLENDER_LIB_INC_DIRS "${BLENDER_LIBDIR}/*/include")
list(APPEND BLENDER_LIB_INC_DIRS "${BLENDER_LIBDIR}/boost/boost_1_71_0")
set(BLENDER_INCS
  ${BLENDER_LIB_INC_DIRS}
  ${BSRC}/intern/clog
  ${BSRC}/intern/guardedalloc
  ${BSRC}/source/blender/blenkernel
  ${BSRC}/source/blender/blenlib
  ${BSRC}/source/blender/depsgraph
  ${BSRC}/source/blender/editors/include
  ${BSRC}/source/blender/gpu
  ${BSRC}/source/blender/imbuf
  ${BSRC}/source/blender/io/usd
  ${BSRC}/source/blender/bmesh
  ${BSRC}/source/blender/makesdna
  ${BSRC}/source/blender/makesrna
  ${BSRC}/source/blender/render
  ${BSRC}/source/blender/windowmanager
  ${BSRC}/source/blender/python
  ${BSRC}/intern/cycles/blender
  ${BSRC}/intern/libmv
  ${BSRC}/source/blender/freestyle
  ${BSRC}/source/blender/python
  ${BSRC}/intern/mikktspace/.
  ${BSRC}/intern
  ${BSRC}/intern/ghost
  ${BSRC}/source/blender/compositor/realtime_compositor
  ${BSRC}/source/blender/draw
  ${BSRC}/intern/opencolorio
  ${BSRC}/extern/audaspace/bindings/C
  ${BSRC}/extern/audaspace/include
  ${BLENDER_LIBDIR}/Tbb/include/tbb-master/include
  ${BLENDER_LIBDIR}/GMP/include
  ${BLENDER_LIBDIR}/Embree/common/simd/arm
  ${BSRC}/extern/binreloc/include
  ${BLENDER_LIBDIR}/OpenVDB/include
  ${BLENDER_LIBDIR}/OpenVDB/openvdb
  
  # generated DNA/RNA headers used by creator sources
  ${CMAKE_CURRENT_SOURCE_DIR}/blender-gensrc
)
target_include_directories(blender_exec PRIVATE ${BLENDER_INCS})

# Python headers (matching the engine's cpython 3.11):
set(PYTHON_INCLUDE_DIR "$ENV{BLENDER_PYTHON_INCLUDE}" CACHE PATH "cpython include")
if(PYTHON_INCLUDE_DIR)
  target_include_directories(blender_exec PRIVATE ${PYTHON_INCLUDE_DIR})
endif()

file(GLOB BLENDER_LIBS "${BLENDER_ENGINE_LIBS}/lib*.a")
file(GLOB BLENDER_EXT_LIBS "${BLENDER_LIBDIR}/*/lib/*.a")
file(GLOB BLENDER_SO_SET "${BLENDER_SO_DIR}/*.so")

target_link_libraries(blender_exec
  -Wl,--start-group
  ${BLENDER_LIBS}
  ${BLENDER_EXT_LIBS}
  -Wl,--end-group
  ${BLENDER_SO_SET}
  ${BLENDER_LIBDIR}/Epoxy/lib/libepoxy.a
  ${BLENDER_LIBDIR}/JPEG/lib/libjpeg.a
  android log EGL GLESv2 z m dl
)

set_target_properties(blender_exec PROPERTIES
  POSITION_INDEPENDENT_CODE ON
  CXX_STANDARD 17
  CXX_STANDARD_REQUIRED ON
  CXX_EXTENSIONS ON)
