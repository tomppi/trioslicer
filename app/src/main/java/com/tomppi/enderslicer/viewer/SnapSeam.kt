package com.tomppi.enderslicer.viewer

import kotlin.math.abs

/**
 * The seam's own edge, as the closed loop a moved joint slides along.
 *
 * The loop is the outline the split leaves on a half's mating face - the same
 * contour the wall lip's ring is swept around - held in the halves' OWN
 * coordinates, the frame every joint's anchor is read in. A point dropped on it
 * is therefore on the mating plane, on the edge of the part and on material the
 * two halves share, whatever the plate's packer has done with them since.
 *
 * The nearest point is taken on the EDGES, not at the corners: a finger that
 * lands a third of the way along an edge gets a third of the way along, so a
 * slide is continuous rather than a jump from vertex to vertex. The outline is
 * already thinned to its corners by [SnapFit.capOutlines], which is what makes
 * that the difference between a slide and a stutter.
 */
class SnapSeamContour(private val loop: List<Vec3>) {
    /** How long the closed loop is: the seam's own length around, in millimetres. */
    val perimeterMm: Float = perimeterOf(loop)

    /** True when there is a real closed edge here to slide along. */
    val isUsable: Boolean get() = loop.size >= 3 && perimeterMm > 0f

    /**
     * The point of the loop nearest [pointMm], measured along its edges.
     *
     * Every edge is read, so the answer follows the finger proportionally along
     * a straight run rather than at the corners; the loop is closed, so the last
     * edge runs back to the first point like every other.
     */
    fun nearest(pointMm: Vec3): Vec3 = nearest(pointMm, null)

    /**
     * The same, with [fromMm] - where the joint already is - settling which edge
     * wins when the finger is nearly as far from two of them.
     *
     * A face's own middle is equidistant from opposite edges, and a plain
     * nearest-point answer flips between them as the finger crosses it: the hook
     * would jump the width of the part. The tie is therefore settled towards
     * where the joint stands, by a weight small enough that a finger ON the seam
     * is followed to the millimetre, and large enough that the branch only
     * changes once the finger has really left it.
     */
    fun nearest(pointMm: Vec3, fromMm: Vec3?): Vec3 {
        var best = loop.first()
        var bestCost = Float.MAX_VALUE
        for (at in loop.indices) {
            val here = loop[at]
            val next = loop[(at + 1) % loop.size]
            val edge = next - here
            val lengthSquared = edge.dot(edge)
            val along = if (lengthSquared <= 0f) {
                0f
            } else {
                ((pointMm - here).dot(edge) / lengthSquared).coerceIn(0f, 1f)
            }
            val on = here + edge * along
            var cost = (pointMm - on).length()
            if (fromMm != null) cost += CONTINUITY_WEIGHT * (on - fromMm).length()
            if (cost < bestCost) {
                bestCost = cost
                best = on
            }
        }
        return best
    }

    /** How far [pointMm] is from the loop, in millimetres: zero when it is on it. */
    fun distanceTo(pointMm: Vec3): Float = (pointMm - nearest(pointMm)).length()

    private companion object {
        /**
         * What a millimetre of travel along the seam costs against a millimetre of
         * distance from the finger. Small: at a hand's width from the edge it lags
         * the perpendicular foot by well under half a millimetre.
         */
        const val CONTINUITY_WEIGHT = 0.02f

        fun perimeterOf(loop: List<Vec3>): Float {
            if (loop.size < 2) return 0f
            var total = 0f
            for (at in loop.indices) total += (loop[(at + 1) % loop.size] - loop[at]).length()
            return total
        }
    }
}

/**
 * What a place on the seam gives a shape that has to sit on it.
 *
 * [alongMm] is the seam's own straight run through the anchor: how far the edge
 * goes either way before it bends, corner to corner. [acrossMm] is the face's
 * own depth across the seam, measured inboard of the rim until the part's own
 * edge or a feature ends it - the band a shape standing on the rim has to land
 * on. Both are the material's numbers, not the shape's, and both are read in
 * the halves' own coordinates.
 */
data class SeamRoom(
    val alongMm: Float,
    val acrossMm: Float,
    /** The seam's own direction there, in the halves' coordinates. */
    val alongDirection: Vec3,
    /** The way the material lies from the rim: the inward normal, in the same coordinates. */
    val inboardDirection: Vec3,
) {
    val isUsable: Boolean get() = alongMm > 0f && acrossMm > 0f
}

/**
 * Reads the seam's own edge off a half, in that half's own coordinates.
 *
 * This is the contour the wall lip's ring follows, read for the joint that is
 * being moved: the cap the split left on the half's mating face, its outline,
 * and the largest closed loop of it - the outer edge, when a hollow section
 * leaves the cap in several pieces.
 *
 * [anchorMm] is only the frame's origin on the mating plane: it keeps the
 * arithmetic in small numbers the way every other build here does, and the
 * anchor's own depth into the part is deliberately not part of where the edge
 * runs. Null when the half has no cap at the seam to read an edge from, or when
 * the edge it leaves is too small to be one.
 */
object SnapSeam {
    fun contour(half: SnapFitHalf, direction: Vec3, anchorMm: Vec3): SnapSeamContour? {
        if (!anchorMm.isFinite()) return null
        val unit = direction.normalized() ?: return null
        val (axis, side, rise) = SnapFit.frameAxes(unit) ?: return null
        if (half.mesh.triangleCount <= 0) return null
        val origin = anchorMm - axis * (anchorMm.dot(axis) - half.faceMm)
        val cap = SnapFit.capTriangles(half, axis, side, rise, half.faceMm, origin)
        if (cap.isEmpty()) return null
        val loop = SnapFit.capOutlines(cap)
            .filter { it.size >= 3 }
            .maxByOrNull { abs(areaOf(it)) }
            ?.map { (x, y) -> origin + side * x + rise * y }
            ?: return null
        return SnapSeamContour(loop).takeIf { it.isUsable }
    }

    /**
     * The room the seam gives at [anchorMm] on [half]: the straight run of the
     * edge either side of it, and the face's depth inboard of the rim there.
     *
     * Read off the same cap outline - and so the same [SnapFit.capOutlines] -
     * the contour and every other feature of the seam is read off, in the
     * half's OWN coordinates, so the numbers a fit check compares are the
     * numbers the material itself gives. Null when the half has no cap at the
     * seam to measure: there is no room there either.
     */
    fun room(half: SnapFitHalf, direction: Vec3, anchorMm: Vec3): SeamRoom? {
        if (!anchorMm.isFinite()) return null
        val unit = direction.normalized() ?: return null
        val (axis, side, rise) = SnapFit.frameAxes(unit) ?: return null
        if (half.mesh.triangleCount <= 0) return null
        val origin = anchorMm - axis * (anchorMm.dot(axis) - half.faceMm)
        val cap = SnapFit.capTriangles(half, axis, side, rise, half.faceMm, origin)
        if (cap.isEmpty()) return null
        val loop = SnapFit.capOutlines(cap)
            .filter { it.size >= 3 }
            .maxByOrNull { abs(areaOf(it)) }
            ?: return null
        // The frame's own origin is the anchor dropped onto the mating plane, so
        // the measurement is taken at (0, 0) in the outline's own coordinates.
        val nearest = nearestOnLoop(loop) ?: return null
        val along = edgeDirection(loop, nearest.edge)
        // The run the place gives, corner to corner. Not halved at the shorter
        // side: the anchor is where the user put the joint, and a face exactly
        // the size of the shape has to be placeable on it by hand rather than
        // only by a bullseye.
        val back = runFrom(loop, nearest.edge, nearest.along, forward = false)
        val forward = runFrom(loop, nearest.edge, nearest.along, forward = true)
        val alongMm = back + forward
        // The two ways across the seam at the anchor: one of them is into the
        // material, and a probe a twentieth of a millimetre along it says which.
        val normal = P2(-along.y, along.x)
        val inward = if (inCap(cap, nearest.point + normal * ROOM_PROBE_MM)) {
            normal
        } else {
            P2(-normal.x, -normal.y)
        }
        var acrossMm = 0f
        var step = 0f
        while (step < ROOM_MAX_MM) {
            step += ROOM_STEP_MM
            if (!inCap(cap, nearest.point + inward * step)) break
            acrossMm = step
        }
        if (alongMm <= 0f || acrossMm <= 0f) return null
        return SeamRoom(
            alongMm = alongMm,
            acrossMm = acrossMm,
            alongDirection = (side * along.x + rise * along.y).normalized() ?: side,
            inboardDirection = (side * inward.x + rise * inward.y).normalized() ?: rise,
        )
    }

    /** Where the anchor is on the outline: the edge it is nearest, and how far along it. */
    private class OnLoop(val edge: Int, val along: Float, val point: P2)

    private fun nearestOnLoop(loop: List<Pair<Float, Float>>): OnLoop? {
        var best: OnLoop? = null
        var bestDistance = Float.MAX_VALUE
        for (at in loop.indices) {
            val here = loop[at]
            val next = loop[(at + 1) % loop.size]
            val edge = P2(next.first - here.first, next.second - here.second)
            val length = edge.length()
            if (length < ROOM_WELD_MM) continue
            val along = ((-here.first * edge.x) + (-here.second * edge.y)) / (length * length)
            val clamped = along.coerceIn(0f, 1f)
            val point = P2(here.first + edge.x * clamped, here.second + edge.y * clamped)
            val distance = point.length()
            if (distance < bestDistance) {
                bestDistance = distance
                best = OnLoop(at, clamped, point)
            }
        }
        return best
    }

    /** The unit direction of one edge of the outline. */
    private fun edgeDirection(loop: List<Pair<Float, Float>>, edge: Int): P2 {
        val here = loop[edge]
        val next = loop[(edge + 1) % loop.size]
        return P2(next.first - here.first, next.second - here.second).unit()
    }

    /**
     * How far the outline runs straight from the anchor along one edge and then
     * through every edge that continues it. A boolean engine's outline carries
     * slivers - a vertex halfway along a straight run - so "straight" is the
     * TURN at the shared vertex, not the absence of one, exactly as the wall
     * lip's own bend test reads it.
     */
    private fun runFrom(
        loop: List<Pair<Float, Float>>,
        edge: Int,
        along: Float,
        forward: Boolean,
    ): Float {
        var total = if (forward) (1f - along) * edgeLength(loop, edge) else along * edgeLength(loop, edge)
        var current = edge
        var guard = 0
        while (guard++ <= loop.size) {
            val next = if (forward) (current + 1) % loop.size else (current - 1 + loop.size) % loop.size
            if (next == edge) break
            if (!continuesStraight(loop, current, next)) break
            total += edgeLength(loop, next)
            current = next
        }
        return total
    }

    /**
     * True when the two edges meet without turning: the vertex between them is a
     * sliver of the boolean engine's own outline rather than a corner of the
     * seam. The turn is the same either way round, so the walk direction does
     * not enter into it.
     */
    private fun continuesStraight(
        loop: List<Pair<Float, Float>>,
        edge: Int,
        next: Int,
    ): Boolean {
        val first = edgeDirection(loop, edge)
        val second = edgeDirection(loop, next)
        return first.x * second.x + first.y * second.y >= ROOM_STRAIGHT_COS
    }

    private fun edgeLength(loop: List<Pair<Float, Float>>, edge: Int): Float {
        val here = loop[edge]
        val next = loop[(edge + 1) % loop.size]
        return kotlin.math.hypot(next.first - here.first, next.second - here.second)
    }

    /** True when [point] is on any of the cap's own triangles: material, not air. */
    private fun inCap(cap: List<SnapFit.CapTriangle>, point: P2): Boolean =
        cap.any { it.contains(point.x, point.y) }

    /** A point in the seam plane's own two axes; the outline's own coordinates. */
    private class P2(val x: Float, val y: Float) {
        fun length(): Float = kotlin.math.hypot(x, y)
        fun unit(): P2 {
            val size = length()
            return if (size <= 0f) P2(1f, 0f) else P2(x / size, y / size)
        }
        operator fun plus(other: P2): P2 = P2(x + other.x, y + other.y)
        operator fun times(factor: Float): P2 = P2(x * factor, y * factor)
    }

    /** How fine the inboard march is, and how far it will look before giving up. */
    private const val ROOM_STEP_MM = 0.25f
    private const val ROOM_PROBE_MM = 0.05f
    private const val ROOM_MAX_MM = 400f
    /** Two edges this well aligned are one straight run: about five degrees. */
    private const val ROOM_STRAIGHT_COS = 0.996f
    /** Two outline corners this close are the same corner. */
    private const val ROOM_WELD_MM = 1e-4f

    /** The shoelace area of an outline loop; the sign is the way it is wound. */
    private fun areaOf(points: List<Pair<Float, Float>>): Float {
        var sum = 0f
        for (at in points.indices) {
            val here = points[at]
            val next = points[(at + 1) % points.size]
            sum += here.first * next.second - next.first * here.second
        }
        return sum * 0.5f
    }
}
