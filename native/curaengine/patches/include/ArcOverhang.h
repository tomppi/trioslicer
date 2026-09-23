// Copyright (c) 2026 Tomas Kald and enderslicercura contributors
// Derived from the arc-overhang technique by Steven McCulloch and the
// SuperPleccer Multiplex implementation by rvmn and contributors.
// Distributed under GNU AGPL-3.0-or-later with CuraEngine.

#ifndef ENDERSLICER_ARC_OVERHANG_H
#define ENDERSLICER_ARC_OVERHANG_H

#include "geometry/OpenLinesSet.h"
#include "geometry/Shape.h"
#include "utils/Coord_t.h"

namespace cura
{

struct ArcOverhangParameters
{
    coord_t line_spacing{};
    coord_t min_radius{};
    coord_t max_radius{};
    coord_t resolution{};
    double max_area_mm2{};
};

/**
 * Generate support-free bottom-skin paths as expanding arcs.
 *
 * One centre is retained for the complete connected island (the Multiplex
 * principle). Every radius is clipped against the actual bridge-skin polygon.
 * The first path begins in a region that Cura identified as supported by the
 * previous layer. Returning false asks the caller to use normal bridge lines.
 */
class ArcOverhangGenerator
{
public:
    static bool generate(
        const Shape& area,
        const Shape& supported_region,
        const ArcOverhangParameters& parameters,
        OpenLinesSet& output);
};

} // namespace cura

#endif
