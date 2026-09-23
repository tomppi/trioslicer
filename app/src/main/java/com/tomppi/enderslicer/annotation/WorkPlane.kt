package com.tomppi.enderslicer.annotation

import kotlin.math.abs

/**
 * Placement geometry for annotation points.
 *
 * A tap resolves to a ray, and a ray alone does not say where along itself the
 * point belongs - which is why a point placed by eye lands at an arbitrary
 * depth, and why dragging one drifts in depth as the camera moves. Both are
 * solved by intersecting the ray with a plane instead:
 *
 *  - a **horizontal plane** at a chosen height for ordinary placement, so the
 *    point lands exactly under the finger at a height the user controls;
 *  - a **vertical plane** through an existing point for adjusting height alone,
 *    so a vertical drag changes Z and leaves X and Y where they were.
 *
 * Pure functions on plain vectors, so the behaviour is unit-testable without a
 * camera, a GL context or a mesh.
 */
object WorkPlane {

    /** How close to parallel a ray may be to a plane before the result is meaningless. */
    private const val PARALLEL_EPSILON = 1e-4f

    /**
     * Where a ray meets the horizontal plane at [planeZ], or null when the ray
     * runs parallel to it or would have to travel backwards.
     */
    fun intersectHorizontal(
        origin: Point3,
        direction: Point3,
        planeZ: Float,
    ): Point3? {
        val dz = direction.z
        if (abs(dz) < PARALLEL_EPSILON) return null
        val t = (planeZ - origin.z) / dz
        if (t <= 0f) return null
        return Point3(origin.x + direction.x * t, origin.y + direction.y * t, planeZ)
    }

    /**
     * The Z at which a ray crosses the vertical plane through [anchor].
     *
     * The plane faces the camera horizontally: its normal is the ray's own
     * direction flattened to XY, so the plane always presents its face to the
     * viewer and a vertical drag reads as pure height change. X and Y of the
     * result are deliberately not used - the caller keeps [anchor]'s, which is
     * what makes this adjust height *only*.
     */
    fun intersectVerticalForZ(
        origin: Point3,
        direction: Point3,
        anchor: Point3,
    ): Float? {
        val nx = direction.x
        val ny = direction.y
        val length = kotlin.math.sqrt(nx * nx + ny * ny)
        if (length < PARALLEL_EPSILON) return null
        val ux = nx / length
        val uy = ny / length
        val denominator = direction.x * ux + direction.y * uy
        if (abs(denominator) < PARALLEL_EPSILON) return null
        val numerator = (anchor.x - origin.x) * ux + (anchor.y - origin.y) * uy
        val t = numerator / denominator
        if (t <= 0f) return null
        return origin.z + direction.z * t
    }
}
