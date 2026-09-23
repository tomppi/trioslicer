// Copyright (c) 2026 EnderSlicerCura contributors
// Masonry-bonded walls: the wall stack leans alternately +/- half a bead per
// layer, so every bead rests on the shoulder of the bead beneath instead of
// stacking flat. Distributed under GNU AGPL-3.0-or-later with CuraEngine.

#pragma once

#include "geometry/OpenLinesSet.h"
#include "geometry/Shape.h"
#include "utils/Coord_t.h"

#include <cstddef>

namespace cura
{

struct BeadAngleParameters
{
    coord_t line_width{ 400 };        // extrusion width (microns)
    size_t base_wall_count{ 2 };      // the sliced wall_line_count
};

class BeadAngleGenerator
{
public:
    // Emits the ordinary wall insets (0 .. base_wall_count - 1) for the whole
    // layer, clipped out of the replacement region (if any).
    static void generateBaseWalls(
        const Shape& outline,
        const BeadAngleParameters& parameters,
        const Shape& replacement,
        OpenLinesSet& output);

    // Emits the wall insets (0 .. base_wall_count - 1) shifted sideways by
    // [lean] (signed microns). Masonry-bonded walls lean alternately +/- half
    // a bead per layer so every bead rests on the shoulder of the bead
    // beneath instead of stacking flat like Lego bricks.
    static void generateMasonryWalls(
        const Shape& outline,
        const BeadAngleParameters& parameters,
        coord_t lean,
        OpenLinesSet& output);
};

} // namespace cura
