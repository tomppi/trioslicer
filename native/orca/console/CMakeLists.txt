# Headless OrcaSlicer console for the Android port.
#
# Links libslic3r and nothing else: no libslic3r_gui, no wxWidgets, no OpenGL, no GLFW.
# The upstream orca-slicer executable cannot be built that way, which is why this target
# exists - see orca_console.cpp.
# nanosvg_impl.cpp carries the NanoSVG parser implementation, which the GUI build compiles
# and libslic3r calls into; without it the console links with nsvgDelete undefined.
add_executable(orca-console orca_console.cpp nanosvg_impl.cpp)

target_link_libraries(orca-console
    libslic3r
    boost_headeronly
    cereal::cereal
    nanosvg
)

if (NOT WIN32 AND NOT APPLE)
    target_link_libraries(orca-console ${CMAKE_DL_LIBS})
endif ()

set_target_properties(orca-console PROPERTIES OUTPUT_NAME "orca-console")
