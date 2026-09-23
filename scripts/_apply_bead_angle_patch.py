# Bead-angle/masonry engine support patch (applied after the main script).
# Only installs the generator sources (masonry-bonded walls live there) and
# the CMake entry - no preview markers, no settings flags.

def apply(root, arc_patch_root, replace):
    cmake = root / "CMakeLists.txt"

    (root / "include" / "BeadAngleOverhang.h").write_text((arc_patch_root / "include" / "BeadAngleOverhang.h").read_text())
    (root / "src" / "BeadAngleOverhang.cpp").write_text((arc_patch_root / "src" / "BeadAngleOverhang.cpp").read_text())

    replace(
        cmake,
        "        src/BrickWalls.cpp\n",
        "        src/BrickWalls.cpp\n        src/BeadAngleOverhang.cpp\n",
    )
