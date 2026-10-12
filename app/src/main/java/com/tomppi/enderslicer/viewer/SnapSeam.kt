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
