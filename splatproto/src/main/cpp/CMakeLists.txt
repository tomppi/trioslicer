cmake_minimum_required(VERSION 3.22)
# OpenCV's Prefab package (libopencv_java4) is built against the shared C++ runtime.
set(ANDROID_STL c++_shared)
project(splatcore CXX C)

set(CMAKE_CXX_STANDARD 17)

# Compile GLSL to SPIR-V with the NDK's glslc, then embed it as a C array.
set(GLSLC "${CMAKE_ANDROID_NDK}/shader-tools/linux-x86_64/glslc")
set(SHADER_DIR "${CMAKE_CURRENT_SOURCE_DIR}/shaders")
set(GENERATED_DIR "${CMAKE_CURRENT_BINARY_DIR}/generated")
file(MAKE_DIRECTORY "${GENERATED_DIR}")

set(EMBED_PY "${CMAKE_CURRENT_SOURCE_DIR}/embed_spirv.py")

function(embed_shader name out_var)
    set(spv "${GENERATED_DIR}/${name}.spv")
    set(hdr "${GENERATED_DIR}/${name}_spv.h")
    add_custom_command(
        OUTPUT "${hdr}"
        COMMAND "${GLSLC}" -I"${SHADER_DIR}" "${SHADER_DIR}/${name}.comp" -o "${spv}"
        COMMAND python3 "${EMBED_PY}" "${spv}" "${hdr}" "${name}_spv"
        DEPENDS "${SHADER_DIR}/${name}.comp" "${SHADER_DIR}/shared_camera.glsl" "${EMBED_PY}"
        COMMENT "Embedding shader ${name}"
        VERBATIM
    )
    set(${out_var} "${hdr}" PARENT_SCOPE)
endfunction()

embed_shader(smoke SMOKE_HDR)
embed_shader(splat_clear SPLAT_CLEAR_HDR)
embed_shader(splat_project SPLAT_PROJECT_HDR)
embed_shader(splat_sort SPLAT_SORT_HDR)
embed_shader(splat_composite SPLAT_COMPOSITE_HDR)
embed_shader(splat_train_forward SPLAT_TRAIN_FORWARD_HDR)
embed_shader(splat_train_backward SPLAT_TRAIN_BACKWARD_HDR)
embed_shader(splat_adam SPLAT_ADAM_HDR)
embed_shader(splat_cam_adam SPLAT_CAM_ADAM_HDR)

add_custom_target(shaders DEPENDS
    "${SMOKE_HDR}" "${SPLAT_CLEAR_HDR}" "${SPLAT_PROJECT_HDR}" "${SPLAT_SORT_HDR}" "${SPLAT_COMPOSITE_HDR}"
    "${SPLAT_TRAIN_FORWARD_HDR}" "${SPLAT_TRAIN_BACKWARD_HDR}" "${SPLAT_ADAM_HDR}" "${SPLAT_CAM_ADAM_HDR}")

# ---------------------------------------------------------------------------
# Vendored Parallax SfM core (GPL-3.0-or-later). OpenCV comes from the AAR's
# Prefab package; the JNI bridge above is ours.
# ---------------------------------------------------------------------------
find_package(OpenCV REQUIRED CONFIG)

add_library(parallaxcore SHARED
    live.cpp
    probe.cpp
    jni_bridge.cpp
    parallax/core/features.cpp
    parallax/core/geometry.cpp
    parallax/core/ba.cpp
    parallax/core/sfm.cpp
    parallax/core/dense.cpp
    parallax/core/io.cpp)
target_include_directories(parallaxcore PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${CMAKE_CURRENT_SOURCE_DIR}/parallax"
    "${CMAKE_CURRENT_SOURCE_DIR}/parallax/core")
target_compile_options(parallaxcore PRIVATE -O3 -fvisibility=hidden -Wno-unused-parameter)
target_link_libraries(parallaxcore OpenCV::opencv_java4 android log jnigraphics)

