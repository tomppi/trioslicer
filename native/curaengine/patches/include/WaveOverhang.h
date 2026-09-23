// Copyright (c) 2026 Tomas Kald and EnderSlicerCura contributors
// Algorithm adapted from the wavefront method documented by
// dennisklappe/OrcaSlicer-WaveOverhangs and stmcculloch/PrusaSlicer-WaveOverhangs.
// Distributed under GNU AGPL-3.0-or-later with CuraEngine.

#ifndef ENDERSLICER_WAVE_OVERHANG_H
#define ENDERSLICER_WAVE_OVERHANG_H

#include "geometry/OpenLinesSet.h"
#include "geometry/Shape.h"
#include "utils/Coord_t.h"

#include <cstddef>
#include <string>

namespace cura
{

struct WaveOverhangParameters
{
    coord_t line_spacing{};
    coord_t perimeter_overlap{};
    coord_t minimum_width{};
    size_t max_iterations{};
    std::string pattern{ "smart" };
    bool reverse_direction{};
};

/**
 * Generate clipped, expanding wavefronts from model-supported material into an
 * unsupported bottom-skin island. False means the caller must retain Cura's
 * normal bridge generator for the complete region.
 */
class WaveOverhangGenerator
{
public:
    static bool generate(
        const Shape& area,
        const Shape& supported_region,
        const WaveOverhangParameters& parameters,
        OpenLinesSet& output);
};

} // namespace cura

#endif
