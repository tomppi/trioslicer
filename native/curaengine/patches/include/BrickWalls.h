// Copyright (c) 2026 EnderSlicerCura contributors
// Brick-wall overhangs: interlocking staircase wall courses for steep step-out
// regions. Inner staggered brick courses expand from the supported region and
// the outer course follows the true part outline, so the visible wall surface
// is exact. The course count is per-region and angle-driven with a fail-closed
// cap; ordinary walls are used wherever a region cannot be covered safely.
// Distributed under GNU AGPL-3.0-or-later with CuraEngine.

#pragma once

#include "geometry/OpenLinesSet.h"
#include "utils/Coord_t.h"

#include <cstddef>

namespace cura
{

struct BrickWallsParameters
{
    coord_t brick_width{ 400 };   // extrusion width / staircase step (microns)
    coord_t brick_length{ 1600 }; // brick segment length; <= 0 keeps courses continuous
    coord_t layer_height{ 200 };  // layer height for the angle -> course count mapping
    size_t max_iterations{ 60 };  // fail-closed cap on courses per region
    bool stagger_odd_layers{ false }; // shift brick seams by half a brick on odd layers
};

class BrickWallsGenerator
{
public:
    // Generates conformal brick courses for every unsupported region of the
    // outline: an inner staircase of staggered brick courses anchored on the
    // supported region, plus the true outline as the continuous outer course.
    // Returns false when the layer is fully supported (ordinary walls
    // suffice) or when no region can be covered within max_iterations.
    static bool generate(
        const Shape& outline,
        const Shape& supported_region,
        const BrickWallsParameters& parameters,
        OpenLinesSet& output);
};

} // namespace cura
