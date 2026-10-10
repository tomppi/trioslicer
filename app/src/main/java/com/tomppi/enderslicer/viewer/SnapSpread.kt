package com.tomppi.enderslicer.viewer

import java.util.Locale
import kotlin.math.abs
import kotlin.math.hypot

/** One point in the seam's own plane, in millimetres from the rim's origin. */
data class SeamPoint(val x: Float, val y: Float)

/**
 * The rim of the cross-section the two halves share at their mating faces: the
 * material both of them have, sampled and reduced to its own outline.
 *
 * [rimPoints] are the cells of that material which have air on at least one
 * side, in the seam's plane about [originMm]. They are what the automatic
 * spread walks, because a point on the rim is a point where the joint has both
 * something to root in and something to cut, and an even spread along it puts
 * the joints where the material is rather than where the bounding box is.
 */
data class SeamRim(
    val originMm: Vec3,
    val side: Vec3,
    val rise: Vec3,
    val widthMm: Float,
    val heightMm: Float,
    val rimPoints: List<SeamPoint>,
) {
    /** A plane point as a point in the halves' own coordinates. */
    fun model(point: SeamPoint): Vec3 = originMm + side * point.x + rise * point.y

    /** A point in the halves' own coordinates, in this rim's plane coordinates. */
    fun plane(pointMm: Vec3): SeamPoint {
        val delta = pointMm - originMm
        return SeamPoint(delta.dot(side), delta.dot(rise))
    }
}

/**
 * The automatic spread: where along the rim a set of joints can go.
 *
 * Proposes, never decides. Every point it returns is on the rim of the shared
 * cross-section and at least [spacingFor] away from every other point and from
 * any joint already placed, and it says how many the rim had room for and why,
 * when that is fewer than asked.
 *
 * The spacing is the joint's own footprint across the seam plus one beam
 * thickness: two pockets closer than that would leave a wall thinner than the
 * hook is thick between them, which is a wall that prints as a smear. The
 * footprint is read off the joint that was really built, not off the
 * parameters, so a joint the material clamped short takes proportionally less
 * room.
 *
 * Pure: mesh arithmetic, no engine, no state, no I/O.
 */
object SnapSpread {
    /** How many joints the automatic spread will place at most. */
    const val MAX_JOINTS = 4

    /** Fewer than this and it is a placement, not a spread, and the panel says so. */
    const val MIN_JOINTS = 2

    sealed interface Plan {
        data class Proposed(
            /** The joints' anchors, in the halves' own coordinates. */
            val anchors: List<Vec3>,
            /** The centre-to-centre spacing the proposal keeps, in millimetres. */
            val spacingMm: Float,
            /** Null when the rim had room for everything that was asked for. */
            val note: String?,
        ) : Plan

        data class Refused(val reason: String) : Plan
    }

    /**
     * How far apart two joints' anchors have to be on this seam, from the joint
     * that was built: its own footprint across the seam plus a wall of its own
     * thickness between neighbours.
     */
    fun spacingFor(joint: SnapFitJoint): Float =
        footprintDiameterMm(joint) + joint.dimensions.beamThicknessMm

    /**
     * Up to [maxCount] anchors on [rim], at least [spacingFor] apart from one
     * another and from anything in [occupied].
     *
     * The points are chosen by farthest-point sampling: the first is the rim
     * point nearest the middle of the rim, and each next is the point furthest
     * from everything chosen so far. On a rim that is a band - a hull's
     * cross-section - that walks the band's own length and spreads the joints
     * evenly along it rather than clustering them where the sampling happened
     * to start.
     */
    fun plan(
        rim: SeamRim,
        joint: SnapFitJoint,
        occupied: List<SeamPoint> = emptyList(),
        maxCount: Int = MAX_JOINTS,
    ): Plan {
        val points = rim.rimPoints
        if (points.isEmpty()) {
            return Plan.Refused("the two halves share no material at this seam, so there is nowhere to spread a joint")
        }
        val spacing = spacingFor(joint)
        // The rim's own middle: the first joint goes as near it as the rim
        // allows, which is a point that exists by construction.
        val centreX = (points.minOf { it.x } + points.maxOf { it.x }) * 0.5f
        val centreY = (points.minOf { it.y } + points.maxOf { it.y }) * 0.5f
        val chosen = ArrayList<SeamPoint>()
        points.minByOrNull { hypot((it.x - centreX).toDouble(), (it.y - centreY).toDouble()) }?.let { chosen += it }
        while (chosen.size < maxCount) {
            var best: SeamPoint? = null
            var bestGap = -1f
            for (point in points) {
                var nearest = Float.MAX_VALUE
                for (taken in chosen) nearest = minOf(nearest, distance(point, taken))
                for (taken in occupied) nearest = minOf(nearest, distance(point, taken))
                if (nearest > bestGap + 1e-4f) {
                    bestGap = nearest
                    best = point
                }
            }
            if (best == null || bestGap < spacing) break
            chosen += best
        }
        // The joints already placed count towards the spacing but are not this
        // proposal's: what the user is being offered is the anchors above.
        if (chosen.size < MIN_JOINTS) {
            return Plan.Refused(
                "there is only room for " + chosen.size + " joint on this seam at the " +
                    millimetres(spacing) + " mm spacing a joint this size needs" +
                    (if (occupied.isEmpty()) "" else ", with the joints already placed") +
                    "; place it by hand, or make the joints smaller",
            )
        }
        val note = if (chosen.size < maxCount) {
            "the rim has room for " + chosen.size + " joints at the " + millimetres(spacing) +
                " mm spacing this joint needs"
        } else {
            null
        }
        return Plan.Proposed(chosen.map(rim::model), spacing, note)
    }

    /**
     * How much room one joint takes across the seam: the widest the beam, its
     * teeth and its key reach, either side of the joint's own anchor, doubled.
     *
     * The pad is deliberately not part of it, exactly as the layout guard's
     * footprint leaves it out: a whole-seam pad IS the seam's own cross-section,
     * so two full joints on one seam share one pad rather than competing for
     * the room - and what has to stay apart is the beam and the key.
     */
    private fun footprintDiameterMm(joint: SnapFitJoint): Float {
        // Each solid is measured in ITS OWN half's frame: the beam's in the beam
        // half's, the pocket's in the mate's. Both frames sit on the same seam
        // through the same anchor, so the two are the same place physically -
        // but the plate's packer moves the two halves apart after a split, and a
        // pocket measured in the beam half's frame would carry that separation
        // into the joint's own size.
        val beam = widestIn(joint.unionSolid, joint.frame)
        val pocket = widestIn(joint.subtractSolid, joint.socketFrame)
        return 2f * maxOf(beam, pocket)
    }

    /** How far [mesh] reaches across the seam, either side of [frame]'s anchor. */
    private fun widestIn(mesh: StlMesh, frame: SnapFitFrame): Float {
        var widest = 0f
        val vertices = mesh.interleavedVertices
        for (vertex in 0 until mesh.triangleCount * 3) {
            val base = vertex * MeshSolidBuilder.FLOATS_PER_VERTEX
            val local = frame.local(Vec3(vertices[base], vertices[base + 1], vertices[base + 2]))
            widest = maxOf(widest, abs(local.x), abs(local.y))
        }
        return widest
    }

    private fun distance(first: SeamPoint, second: SeamPoint): Float =
        hypot((first.x - second.x).toDouble(), (first.y - second.y).toDouble()).toFloat()

    /** Two decimals, locale-independent: this text lands in the panel. */
    private fun millimetres(value: Float): String = String.format(Locale.ROOT, "%.2f", value)
}
