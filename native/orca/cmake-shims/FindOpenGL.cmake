# The Android engine is headless: there is no OpenGL and no GLFW, but OrcaSlicer's root
# CMakeLists calls find_package(OpenGL REQUIRED) unconditionally for the GUI target it
# still declares. Satisfy the call with empty imported targets so the console build
# configures; nothing links against them because libslic3r does not use OpenGL.
set(OPENGL_FOUND TRUE)
set(OpenGL_FOUND TRUE)
set(OPENGL_GL_FOUND TRUE)
set(OPENGL_LIBRARIES "")
set(OpenGL_LIBRARIES "")
set(OPENGL_INCLUDE_DIR "")

if (NOT TARGET GL::GL)
    add_library(GL::GL INTERFACE IMPORTED)
endif ()
if (NOT TARGET OpenGL::GL)
    add_library(OpenGL::GL INTERFACE IMPORTED)
endif ()
