// Copyright (c) 2026 Tomas Kald and enderslicercura contributors
// Derived from the arc-overhang technique by Steven McCulloch and the
// SuperPleccer Multiplex implementation by rvmn and contributors.
// Distributed under GNU AGPL-3.0-or-later with CuraEngine.

#include "ArcOverhang.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <numbers>

#include "geometry/ClosedPolyline.h"
#include "geometry/LinesSet.h"
#include "geometry/OpenPolyline.h"
#include "geometry/SingleShape.h"
#include "utils/AABB.h"

namespace cura
{
namespace
{

constexpr double SQUARE_MICRONS_PER_SQUARE_MM = 1'000'000.0;
constexpr size_t MIN_CIRCLE_SEGMENTS = 24;
constexpr size_t MAX_CIRCLE_SEGMENTS = 720;
constexpr size_t MAX_RADIAL_STEPS = 2'000;

coord_t distanceTo(const Point2LL& a, const Point2LL& b)
{
    return vSize(a - b);
}

Point2LL safeAnchor(const SingleShape& island, const Shape& supported_region, const coord_t line_spacing)
{
    // Expand the supported material slightly so a merely touching edge becomes
    // a printable overlap, then keep the candidate inside this skin island.
    const Shape anchor_zone = island.intersection(supported_region.offset(std::max<coord_t>(line_spacing * 2, 10)));
    if (anchor_zone.empty())
    {
        return no_point;
    }

    Point2LL candidate = AABB(anchor_zone).getMiddle();
    if (island.inside(candidate, true))
    {
        return candidate;
    }

    // Concave anchor zones may have an AABB centre outside the actual polygon.
    // A real polygon vertex is still a valid boundary anchor.
    for (const Polygon& polygon : anchor_zone)
    {
        if (! polygon.empty())
        {
            return polygon.front();
        }
    }
    return no_point;
}

size_t circleSegments(const coord_t radius, const coord_t resolution)
{
    if (radius <= 0)
    {
        return MIN_CIRCLE_SEGMENTS;
    }
    const double ratio = std::clamp(
        1.0 - static_cast<double>(std::max<coord_t>(resolution, 1)) / static_cast<double>(radius),
        -1.0,
        1.0);
    const double angular_step = std::max(0.01, 2.0 * std::acos(ratio));
    const size_t calculated = static_cast<size_t>(std::ceil(2.0 * std::numbers::pi / angular_step));
    return std::clamp(calculated, MIN_CIRCLE_SEGMENTS, MAX_CIRCLE_SEGMENTS);
}

ClosedPolyline makeCircle(const Point2LL& centre, const coord_t radius, const coord_t resolution, const double start_angle)
{
    const size_t segments = circleSegments(radius, resolution);
    ClipperLib::Path points;
    points.reserve(segments);
    for (size_t index = 0; index < segments; ++index)
    {
        const double angle = start_angle + 2.0 * std::numbers::pi * static_cast<double>(index) / static_cast<double>(segments);
        points.emplace_back(
            centre.X + static_cast<coord_t>(std::llround(static_cast<double>(radius) * std::cos(angle))),
            centre.Y + static_cast<coord_t>(std::llround(static_cast<double>(radius) * std::sin(angle))));
    }
    return ClosedPolyline(std::move(points), false);
}

coord_t requiredRadius(const AABB& bounds, const Point2LL& centre)
{
    const std::array<Point2LL, 4> corners = {
        Point2LL(bounds.min_.X, bounds.min_.Y),
        Point2LL(bounds.min_.X, bounds.max_.Y),
        Point2LL(bounds.max_.X, bounds.min_.Y),
        Point2LL(bounds.max_.X, bounds.max_.Y),
    };
    coord_t result = 0;
    for (const Point2LL& corner : corners)
    {
        result = std::max(result, distanceTo(centre, corner));
    }
    return result;
}

bool appendIsland(
    const SingleShape& island,
    const Shape& supported_region,
    const ArcOverhangParameters& parameters,
    OpenLinesSet& output)
{
    if (island.empty() || island.area() / SQUARE_MICRONS_PER_SQUARE_MM > parameters.max_area_mm2)
    {
        return false;
    }

    const Shape unsupported = island.difference(supported_region);
    if (unsupported.empty())
    {
        return false;
    }

    const Point2LL centre = safeAnchor(island, supported_region, parameters.line_spacing);
    if (centre == no_point)
    {
        return false;
    }

    const Point2LL island_middle = AABB(island).getMiddle();
    Point2LL direction = island_middle - centre;
    if (vSize(direction) < 1)
    {
        direction = Point2LL(1, 0);
    }
    const double direction_length = std::max(1.0, std::sqrt(vSize2f(direction)));
    const double direction_x = static_cast<double>(direction.X) / direction_length;
    const double direction_y = static_cast<double>(direction.Y) / direction_length;
    const double start_angle = std::atan2(direction_y, direction_x);

    // A short, clipped pedestal seeds the first arc on supported material.
    const coord_t pedestal_length = std::max(parameters.min_radius, parameters.line_spacing * 2);
    ClipperLib::Path pedestal_points;
    pedestal_points.emplace_back(centre);
    pedestal_points.emplace_back(
        centre.X + static_cast<coord_t>(std::llround(direction_x * pedestal_length)),
        centre.Y + static_cast<coord_t>(std::llround(direction_y * pedestal_length)));
    OpenLinesSet pedestal_source;
    pedestal_source.push_back(OpenPolyline(std::move(pedestal_points)), CheckNonEmptyParam::OnlyIfValid);
    OpenLinesSet pedestal = island.intersection(pedestal_source);
    for (OpenPolyline& line : pedestal.getLines())
    {
        output.push_back(std::move(line), CheckNonEmptyParam::OnlyIfValid);
    }

    const coord_t required_radius = requiredRadius(AABB(island), centre) + parameters.line_spacing;
    const coord_t maximum_radius = std::min(parameters.max_radius, required_radius);
    const coord_t first_radius = std::max(parameters.min_radius, parameters.line_spacing);
    if (maximum_radius < first_radius || required_radius > parameters.max_radius)
    {
        // Do not partially convert an island. Normal Cura bridge lines are safer.
        return false;
    }

    const size_t radial_steps = static_cast<size_t>((maximum_radius - first_radius) / parameters.line_spacing) + 1;
    if (radial_steps == 0 || radial_steps > MAX_RADIAL_STEPS)
    {
        return false;
    }

    size_t generated_segments = 0;
    for (coord_t radius = first_radius; radius <= maximum_radius; radius += parameters.line_spacing)
    {
        LinesSet<ClosedPolyline> circle;
        circle.push_back(makeCircle(centre, radius, parameters.resolution, start_angle), CheckNonEmptyParam::OnlyIfValid);
        OpenLinesSet clipped = island.intersection(circle);
        for (OpenPolyline& line : clipped.getLines())
        {
            if (line.isValid())
            {
                output.push_back(std::move(line), CheckNonEmptyParam::OnlyIfValid);
                ++generated_segments;
            }
        }
        if (maximum_radius - radius < parameters.line_spacing)
        {
            break; // avoids coord_t overflow in radius += spacing
        }
    }

    return generated_segments >= 2;
}

} // namespace

bool ArcOverhangGenerator::generate(
    const Shape& area,
    const Shape& supported_region,
    const ArcOverhangParameters& parameters,
    OpenLinesSet& output)
{
    output.getLines().clear();
    if (area.empty() || supported_region.empty() || parameters.line_spacing <= 0 || parameters.min_radius <= 0
        || parameters.max_radius < parameters.min_radius || parameters.resolution <= 0 || parameters.max_area_mm2 <= 0.0)
    {
        return false;
    }

    OpenLinesSet generated;
    for (const SingleShape& island : area.splitIntoParts())
    {
        if (! appendIsland(island, supported_region, parameters, generated))
        {
            return false;
        }
    }
    if (generated.empty())
    {
        return false;
    }
    output.getLines() = std::move(generated.getLines());
    return true;
}

} // namespace cura
