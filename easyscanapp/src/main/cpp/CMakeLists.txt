cmake_minimum_required(VERSION 3.22)
project(sptflite C CXX)

set(TFLITE_LIB ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a/libtensorflowlite_jni.so)
set(QNN_DELEGATE_LIB ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a/libQnnTFLiteDelegate.so)

add_library(sptflite SHARED sp_tflite.cpp npu_matmul.cpp)
target_include_directories(sptflite PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/tflite_headers ${CMAKE_CURRENT_SOURCE_DIR}/qnn_tflite)
target_compile_options(sptflite PRIVATE -O3 -ffast-math -std=c++17)
target_link_libraries(sptflite ${TFLITE_LIB} ${QNN_DELEGATE_LIB} log android dl)

add_library(lgtflite SHARED lg_tflite.cpp)
target_include_directories(lgtflite PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/tflite_headers ${CMAKE_CURRENT_SOURCE_DIR}/qnn_tflite)
target_compile_options(lgtflite PRIVATE -O3 -ffast-math -std=c++17)
target_link_libraries(lgtflite ${TFLITE_LIB} ${QNN_DELEGATE_LIB} log android dl)

set(CMAKE_CXX_STANDARD 17)

# ---------------- SuperVINS VIO (SuperPoint + LightGlue front-end + Ceres sliding-window backend) ----------------
set(SUPERVINS ${CMAKE_CURRENT_SOURCE_DIR}/supervins)
set(THIRDPARTY ${CMAKE_CURRENT_SOURCE_DIR}/openvins/thirdparty)
if(NOT DEFINED CERES_ANDROID_DIR)
  if(DEFINED ENV{CERES_ANDROID_DIR})
    set(CERES_ANDROID_DIR $ENV{CERES_ANDROID_DIR})
  elseif(EXISTS ${THIRDPARTY}/ceres/lib/libceres.a)
    set(CERES_ANDROID_DIR ${THIRDPARTY}/ceres)
  else()
    set(CERES_ANDROID_DIR /root/src/ceres-android-src/install)
  endif()
endif()

file(GLOB SUPERVINS_SRC
  ${SUPERVINS}/estimator/*.cpp
  ${SUPERVINS}/factor/*.cpp
  ${SUPERVINS}/initial/*.cpp
  ${SUPERVINS}/utility/*.cpp
)

add_library(supervins_core STATIC ${SUPERVINS_SRC})
set_target_properties(supervins_core PROPERTIES CXX_STANDARD 14 CXX_STANDARD_REQUIRED ON)
target_compile_definitions(supervins_core PRIVATE __ANDROID__)
target_compile_options(supervins_core PRIVATE
  -O3 -fexceptions -frtti -Wno-unused-parameter
  -include ${SUPERVINS}/estimator/ros_stub.h
)
target_include_directories(supervins_core PRIVATE
  ${SUPERVINS}/estimator
  ${SUPERVINS}/utility
  ${SUPERVINS}/initial
  ${SUPERVINS}/factor
  ${THIRDPARTY}
  ${THIRDPARTY}/Eigen
  ${THIRDPARTY}/opencv_include
  ${CERES_ANDROID_DIR}/include
  ${CERES_ANDROID_DIR}/include/ceres/internal/miniglog
)

file(GLOB DBOW3_SRC ${CMAKE_CURRENT_SOURCE_DIR}/dbow3/*.cpp)
list(APPEND DBOW3_SRC ${CMAKE_CURRENT_SOURCE_DIR}/dbow3/quicklz.c)
set_source_files_properties(${CMAKE_CURRENT_SOURCE_DIR}/dbow3/quicklz.c PROPERTIES LANGUAGE C)

add_library(supervins_jni SHARED
  ${DBOW3_SRC}
  ${CMAKE_CURRENT_SOURCE_DIR}/supervins_frontend.cpp
  ${CMAKE_CURRENT_SOURCE_DIR}/vio_jni.cpp
  ${CMAKE_CURRENT_SOURCE_DIR}/loop_db_jni.cpp
)
set_target_properties(supervins_jni PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
target_compile_definitions(supervins_jni PRIVATE __ANDROID__)
target_compile_options(supervins_jni PRIVATE
  -O3 -Wno-unused-parameter
  $<$<COMPILE_LANGUAGE:CXX>:-fexceptions>
  $<$<COMPILE_LANGUAGE:CXX>:-frtti>
  $<$<COMPILE_LANGUAGE:CXX>:-include>
  $<$<COMPILE_LANGUAGE:CXX>:${SUPERVINS}/estimator/ros_stub.h>
)
target_include_directories(supervins_jni PRIVATE
  ${CMAKE_CURRENT_SOURCE_DIR}
  ${CMAKE_CURRENT_SOURCE_DIR}/dbow3
  ${SUPERVINS}
  ${SUPERVINS}/estimator
  ${SUPERVINS}/utility
  ${SUPERVINS}/initial
  ${SUPERVINS}/factor
  ${THIRDPARTY}
  ${THIRDPARTY}/Eigen
  ${THIRDPARTY}/opencv_include
  ${CERES_ANDROID_DIR}/include
  ${CERES_ANDROID_DIR}/include/ceres/internal/miniglog
)

add_library(ceres_android STATIC IMPORTED)
set_target_properties(ceres_android PROPERTIES IMPORTED_LOCATION ${CERES_ANDROID_DIR}/lib/libceres.a)

add_library(lib_opencv SHARED IMPORTED)
set_target_properties(lib_opencv PROPERTIES IMPORTED_LOCATION ${THIRDPARTY}/opencv_lib/arm64-v8a/libopencv_vio4.so)

target_link_libraries(supervins_jni
  supervins_core
  lib_opencv
  ceres_android
  sptflite
  lgtflite
  log
  android
  dl
)

# ---------------- Off-heap native TSDF + Marching Cubes + Direct GPU VBO upload ----------------
add_library(livemesh_jni SHARED live_tsdf_jni.cpp)
target_compile_options(livemesh_jni PRIVATE -O3 -std=c++17)
target_link_libraries(livemesh_jni GLESv3 log android)
