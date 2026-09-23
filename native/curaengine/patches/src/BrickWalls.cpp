// Copyright (c) 2026 EnderSlicerCura contributors
// Brick-wall overhangs (see BrickWalls.h).
// Distributed under GNU AGPL-3.0-or-later with CuraEngine.

#include "BrickWalls.h"

#include "geometry/ClosedPolyline.h"
#include "geometry/LinesSet.h"
#include "geometry/SingleShape.h"

#include <algorithm>
#include <cmath>
#include <vector>

namespace cura
{
namespace
{

//! Below this area an unsupported region is treated as triangulation noise.
constexpr double MIN_ISLAND_AREA_UM2 = 10000.0; // 0.01 mm^2
//! The angle-driven course floor applies from 45 degrees overhang (h / t >= 1).
constexpr double MIN_STEEP_THICKNESS_FACTOR = 1.0;

OpenLinesSet contoursOf(const Shape& shape, const Shape& clip)
{
    LinesSet<ClosedPolyline> closed;
    for (const Polygon& polygon : shape)
    {
        if (polygon.size() < 3)
        {
            continue;
        }
        ClipperLib::Path points(polygon.begin(), polygon.end());
        closed.push_back(ClosedPolyline(std::move(points), false), CheckNonEmptyParam::OnlyIfValid);
    }
    return clip.intersection(closed);
}

double polygonArea(const Polygon& polygon)
{
    return std::abs(polygon.area());
}

double polygonPerimeter(const Polygon& polygon)
{
    double perimeter = 0.0;
    for (size_t i = 0; i < polygon.size(); ++i)
    {
        const Point2LL delta = polygon[(i + 1) % polygon.size()] - polygon[i];
        perimeter += std::hypot(static_cast<double>(delta.X), static_cast<double>(delta.Y));
    }
    return perimeter;
}

//! Point on the polyline at arc length s.
Point2LL pointAt(const OpenPolyline& line, const std::vector<double>& cumulative, double s)
{
    const double total = cumulative[line.size() - 1];
    if (s <= 0.0)
    {
        return line[0];
    }
    if (s >= total)
    {
        return line[line.size() - 1];
    }
    for (size_t i = 0; i + 1 < line.size(); ++i)
    {
        if (s <= cumulative[i + 1])
        {
            const double segment = cumulative[i + 1] - cumulative[i];
            const double t = segment > 0.0 ? (s - cumulative[i]) / segment : 0.0;
            return line[i] + (line[i + 1] - line[i]) * t;
        }
    }
    return line[line.size() - 1];
}

//! Emit the slice of a course polyline between arc lengths s0 and s1.
void emitSlice(const OpenPolyline& line, const std::vector<double>& cumulative, double s0, double s1, OpenLinesSet& out)
{
    if (s1 - s0 < 1.0) // degenerate slice (microns)
    {
        return;
    }
    OpenPolyline brick;
    brick.push_back(pointAt(line, cumulative, s0));
    for (size_t i = 0; i < line.size(); ++i)
    {
        const double s = cumulative[i];
        if (s > s0 && s < s1)
        {
            brick.push_back(line[i]);
        }
    }
    brick.push_back(pointAt(line, cumulative, s1));
    if (brick.size() >= 2)
    {
        out.push_back(std::move(brick), CheckNonEmptyParam::OnlyIfValid);
    }
}

//! Cut a course polyline into staggered bricks. Brick seams sit at
//! phase + k * brick_length; the piece count is distributed over the course
//! length so no sliver bricks remain. A non-positive brick_length keeps the
//! course continuous.
void splitIntoBricks(OpenPolyline line, coord_t brick_length, coord_t phase, OpenLinesSet& out)
{
    const size_t point_count = line.size();
    if (point_count < 2)
    {
        return;
    }
    if (brick_length <= 0)
    {
        out.push_back(std::move(line), CheckNonEmptyParam::OnlyIfValid);
        return;
    }
    std::vector<double> cumulative(point_count, 0.0);
    for (size_t i = 1; i < point_count; ++i)
    {
        const Point2LL delta = line[i] - line[i - 1];
        cumulative[i] = cumulative[i - 1] + std::hypot(static_cast<double>(delta.X), static_cast<double>(delta.Y));
    }
    const double total = cumulative[point_count - 1];
    if (total <= 0.0)
    {
        return;
    }
    if (total < 0.75 * brick_length)
    {
        out.push_back(std::move(line), CheckNonEmptyParam::OnlyIfValid);
        return;
    }
    const size_t brick_count = std::max<size_t>(1, static_cast<size_t>(std::llround(total / brick_length)));
    if (brick_count == 1)
    {
        out.push_back(std::move(line), CheckNonEmptyParam::OnlyIfValid);
        return;
    }
    const double adjusted = total / brick_count;
    const double start = std::fmod(static_cast<double>(phase), adjusted);
    std::vector<double> cuts;
    for (double cut = start; cut < total - 1.0; cut += adjusted)
    {
        cuts.push_back(cut);
    }
    double previous = 0.0;
    for (const double cut : cuts)
    {
        emitSlice(line, cumulative, previous, cut, out);
        previous = cut;
    }
    emitSlice(line, cumulative, previous, total, out);
}

} // namespace

bool BrickWallsGenerator::generate(
    const Shape& outline,
    const Shape& supported_region,
    const BrickWallsParameters& parameters,
    OpenLinesSet& output)
{
    output.getLines().clear();
    if (outline.empty() || supported_region.empty() || parameters.brick_width <= 0 || parameters.max_iterations < 1)
    {
        return false;
    }

    const Shape unsupported = outline.difference(supported_region);
    if (unsupported.empty())
    {
        // Fully supported layer: ordinary walls are the right tool.
        return false;
    }

    const Shape supported = outline.intersection(supported_region);
    if (supported.empty())
    {
        // Nothing of the outline overlaps the layer below: nowhere to anchor.
        return false;
    }

    bool any_region = false;
    for (const Polygon& island_polygon : unsupported)
    {
        const Shape island_shape(island_polygon);
        const double area = polygonArea(island_polygon);
        if (area < MIN_ISLAND_AREA_UM2)
        {
            continue; // triangulation sliver
        }
        const Shape zone = island_shape.offset(2 * parameters.brick_width);

        // Gentle-and-thin gate: a band thinner than one line width at an
        // overhang angle below 45 degrees prints fine with ordinary walls.
        // tan(theta) = layer_height / thickness, theta < 45 <=> h < t.
        const double thickness_gate = 2.0 * area / polygonPerimeter(island_polygon);
        if (parameters.layer_height < MIN_STEEP_THICKNESS_FACTOR * thickness_gate && thickness_gate < parameters.brick_width)
        {
            continue;
        }

        // Conformal staircase: expand from the supported region outward until
        // the island is covered. Collect the anchor edge of each step: the
        // boundary segment of the step band that lies on the previous front,
        // i.e. the course that rests on the layer below.
        Shape current = supported;
        std::vector<OpenLinesSet> anchor_edges;
        bool covered = false;
        for (size_t step = 1; step <= parameters.max_iterations; ++step)
        {
            const Shape next = outline.intersection(current.offset(parameters.brick_width));
            const Shape band = next.difference(current);
            if (band.empty())
            {
                break; // offset stalled: cannot expand any further
            }
            LinesSet<ClosedPolyline> band_closed;
            for (const Polygon& polygon : band)
            {
                if (polygon.size() < 3)
                {
                    continue;
                }
                ClipperLib::Path points(polygon.begin(), polygon.end());
                band_closed.push_back(ClosedPolyline(std::move(points), false), CheckNonEmptyParam::OnlyIfValid);
            }
            anchor_edges.push_back(current.offset(1).intersection(band_closed));
            if (island_shape.difference(next).empty())
            {
                covered = true;
                break;
            }
            current = next;
        }
        if (! covered)
        {
            continue; // fail-closed: the region is too wide for the cap
        }

        // Angle-driven course floor: with the local overhang angle theta,
        // tan(theta) = layer_height / thickness and thickness ~= 2 * A / P.
        // Courses n = 1 + ceil(tan(theta) / 2), applied from 45 degrees up:
        // 45-60 deg -> 2 courses, 70 deg -> 3, 80 deg -> 4.
        const double thickness = 2.0 * area / polygonPerimeter(island_polygon);
        size_t angle_courses = 0;
        if (parameters.layer_height >= MIN_STEEP_THICKNESS_FACTOR * thickness)
        {
            angle_courses = 1 + static_cast<size_t>(std::ceil(static_cast<double>(parameters.layer_height) / (2.0 * thickness)));
        }
        const size_t total_courses = std::max(anchor_edges.size() + 1, angle_courses);
        const size_t anchor_count = total_courses > anchor_edges.size() + 1 ? total_courses - anchor_edges.size() - 1 : 0;
        if (anchor_count > parameters.max_iterations)
        {
            continue; // fail-closed cap
        }

        const bool odd_layer = parameters.stagger_odd_layers;
        size_t ring_index = 0;
        auto emitBrickLines = [&](OpenLinesSet lines)
        {
            const bool shift = (ring_index % 2) == (odd_layer ? 1 : 0);
            const coord_t phase = shift ? parameters.brick_length / 2 : 0;
            for (OpenPolyline& line : lines.getLines())
            {
                if (! line.isValid() || line.length() < 50)
                {
                    continue; // drop clipping stubs below 0.05 mm
                }
                splitIntoBricks(std::move(line), parameters.brick_length, phase, output);
            }
            ++ring_index;
        };

        // Innermost anchor courses: outline insets sitting on the supported
        // region, carrying the load the outer courses transfer into the part.
        for (size_t j = 1; j <= anchor_count; ++j)
        {
            const Shape inset = outline.offset(-static_cast<coord_t>(j) * parameters.brick_width);
            if (inset.empty())
            {
                break;
            }
            emitBrickLines(contoursOf(inset, zone));
        }

        // Conformal staircase anchor edges from the supported region outward.
        for (const OpenLinesSet& edges : anchor_edges)
        {
            emitBrickLines(zone.intersection(edges));
        }

        // Outer course: the true outline, continuous for surface fidelity.
        OpenLinesSet outline_course = contoursOf(outline, zone);
        for (OpenPolyline& line : outline_course.getLines())
        {
            if (line.isValid())
            {
                output.push_back(std::move(line), CheckNonEmptyParam::OnlyIfValid);
            }
        }
        any_region = true;
    }

    return any_region && ! output.getLines().empty();
}

} // namespace cura
