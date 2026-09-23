# Companion to FindOpenGL.cmake: the GUI target asks for glfw3, the console never
# links it. The imported target name matches what the upstream GUI targets expect.
set(glfw3_FOUND TRUE)
set(GLFW3_FOUND TRUE)
set(GLFW_FOUND TRUE)
set(glfw3_LIBRARIES "")
set(GLFW_LIBRARIES "")

if (NOT TARGET glfw)
    add_library(glfw INTERFACE IMPORTED)
endif ()
