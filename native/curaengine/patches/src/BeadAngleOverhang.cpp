// Copyright (c) 2026 EnderSlicerCura contributors
// Masonry-bonded walls (see BeadAngleOverhang.h).
// Distributed under GNU AGPL-3.0-or-later with CuraEngine.

#include "BeadAngleOverhang.h"

#include "geometry/ClosedPolyline.h"
#include "geometry/LinesSet.h"
#include "geometry/SingleShape.h"

#include <vector>

namespace cura
{
namespace
{

//! Split a contour into the runs that stay outside the replacement region.
void clipOutside(const OpenPolyline& line, const Shape& region, OpenLinesSet& output)
{
    if (region.empty())
    {
        output.push_back(line, CheckNonEmptyParam::OnlyIfValid);
        return;
    }
    OpenPolyline run;
    for (const Point2LL& point : line)
    {
        if (region.inside(point))
        {
            if (run.size() >= 2)
            {
                output.push_back(std::move(run), CheckNonEmptyParam::OnlyIfValid);
            }
            run.clear();
        }
        else
        {
            run.push_back(point);
        }
    }
    if (run.size() >= 2)
    {
        output.push_back(std::move(run), CheckNonEmptyParam::OnlyIfValid);
    }
}

} // namespace

void BeadAngleGenerator::generateBaseWalls(
    const Shape& outline,
    const BeadAngleParameters& parameters,
    const Shape& replacement,
    OpenLinesSet& output)
{
    for (size_t i = 0; i < parameters.base_wall_count; ++i)
    {
        // The outer wall follows the raw outline (offset(0) can collapse
        // very thin parts); the inner walls nest inward and stop as soon as
        // the inset no longer fits.
        const Shape inset = (i == 0) ? outline : outline.offset(-static_cast<coord_t>(i) * parameters.line_width);
        if (inset.empty())
        {
            break;
        }
        // The insets lie inside the outline by construction, so they need no
        // clipping: emit each boundary directly (Clipper polygon intersection
        // would drop closed rings when extracting open paths).
        for (const Polygon& polygon : inset)
        {
            if (polygon.size() < 3)
            {
                continue;
            }
            OpenPolyline line;
            line.reserve(polygon.size() + 1);
            for (const Point2LL& point : polygon)
            {
                line.push_back(point);
            }
            line.push_back(polygon.front());
            clipOutside(line, replacement, output);
        }
    }
}

void BeadAngleGenerator::generateMasonryWalls(
    const Shape& outline,
    const BeadAngleParameters& parameters,
    const coord_t lean,
    OpenLinesSet& output)
{
    for (size_t i = 0; i < parameters.base_wall_count; ++i)
    {
        const coord_t offset = lean - static_cast<coord_t>(i) * parameters.line_width;
        const Shape inset = (offset == 0) ? outline : outline.offset(offset);
        if (inset.empty())
        {
            continue;
        }
        for (const Polygon& polygon : inset)
        {
            if (polygon.size() < 3)
            {
                continue;
            }
            OpenPolyline line;
            line.reserve(polygon.size() + 1);
            for (const Point2LL& point : polygon)
            {
                line.push_back(point);
            }
            line.push_back(polygon.front());
            output.push_back(std::move(line), CheckNonEmptyParam::OnlyIfValid);
        }
    }
}

} // namespace cura
