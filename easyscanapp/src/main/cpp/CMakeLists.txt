cmake_minimum_required(VERSION 3.22)
project(sptflite CXX)

set(TFLITE_LIB ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a/libtensorflowlite_jni.so)
set(QNN_DELEGATE_LIB ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/arm64-v8a/libQnnTFLiteDelegate.so)
add_library(sptflite SHARED sp_tflite.cpp)
target_include_directories(sptflite PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/tflite_headers
    ${CMAKE_CURRENT_SOURCE_DIR}/qnn_tflite)
target_compile_options(sptflite PRIVATE -O3 -ffast-math -std=c++17)
target_link_libraries(sptflite ${TFLITE_LIB} ${QNN_DELEGATE_LIB} log android dl)
set(CMAKE_CXX_STANDARD 17)
