package com.tomppi.enderslicer.viewer

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot

/** One point in the seam's own plane, in millimetres from the rim's origin. */
data class SeamPoint(val x: Float, val y: Float)

/**
 * The room one joint takes about its own anchor, in the seam's plane: how far
 * the beam reaches on one side and how far the key reaches on the other.
 */
data class AnchorRoom(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float,
)

/**
 * The grid the rim was read from: which cells of the shared cross-section are
 * material, and the rectangle they were sampled in.
 *
 * It is what lets a joint's anchor be put INSIDE the outline rather than on it.
 * The rim is the edge of the material, and an anchor on the edge spends half
 * the joint's own footprint on air - on a small part the key visibly hangs over
 * the face. The cells are what says how far inside a point really is.
 */
data class RimGrid(
    val minX: Float,
    val minY: Float,
    val widthMm: Float,
    val heightMm: Float,
    /** Cells per side: the grid is square, the cells are not. */
    val samples: Int,
    /** Row-major, [samples] squared: true where both halves have material. */
    val shared: BooleanArray,
) {
    val cellWidthMm: Float get() = widthMm / samples
    val cellHeightMm: Float get() = heightMm / samples

    /** The widest a cell is, which is the resolution everything here is read at. */
    val widestCellMm: Float get() = maxOf(cellWidthMm, cellHeightMm)

    fun isShared(column: Int, row: Int): Boolean =
        column in 0 until samples && row in 0 until samples && shared[row * samples + column]

    /** The cell a plane point falls in. May be outside the grid. */
    fun columnOf(x: Float): Int = floor((x - minX) / cellWidthMm).toInt()
    fun rowOf(y: Float): Int = floor((y - minY) / cellHeightMm).toInt()

    fun centreX(column: Int): Float = minX + widthMm * (column + 0.5f) / samples
    fun centreY(row: Int): Float = minY + heightMm * (row + 0.5f) / samples

    /**
     * How far a cell's centre is from the nearest cell that is not shared, in
     * millimetres, looking no further than [reach] cells away. A cell with
     * nothing but material within [reach] is reported as [Float.MAX_VALUE]:
     * the caller only compares against a distance it asked for.
     */
    fun distanceToEdge(column: Int, row: Int, reach: Int): Float {
        var best = Float.MAX_VALUE
        for (otherRow in row - reach..row + reach) {
            for (otherColumn in column - reach..column + reach) {
                if (isShared(otherColumn, otherRow)) continue
                val dx = (otherColumn - column) * cellWidthMm
                val dy = (otherRow - row) * cellHeightMm
                val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                if (distance < best) best = distance
            }
        }
        return best
    }
}

/**
 * The rim of the cross-section the two halves share at their mating faces: the
 * material both of them have, sampled and reduced to its own outline.
 *
 * [rimPoints] are the cells of that material which have air on at least one
 * side, in the seam's plane about [originMm]. They are the outline itself, and
 * [grid] is the material behind them, which is what an anchor gets inset
 * against: a point on the rim is a point where the joint has both something to
 * root in and something to cut, and an even spread along it puts the joints
 * where the material is rather than where the bounding box is.
 */
data class SeamRim(
    val originMm: Vec3,
    val side: Vec3,
    val rise: Vec3,
    val widthMm: Float,
    val heightMm: Float,
    val rimPoints: List<SeamPoint>,
    /** Null for a rim that was built by hand: there is nothing to measure inset against. */
    val grid: RimGrid? = null,
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
     * How far inside the seam's own outline an automatic anchor sits: half the
     * key's own width.
     *
     * The rim is the EDGE of the shared material, so a joint anchored exactly
     * on it spends half its key on air - which is what a 20 mm cube showed.
     * This is the floor the anchor keeps whatever else is measured; the key
     * itself sits BESIDE the anchor, a whole key away from it, and
     * [anchorRoomFor] carries that distance.
     */
    fun anchorInsetFor(joint: SnapFitJoint): Float = joint.dimensions.keySizeMm * 0.5f

    /**
     * The room one joint takes about its own anchor, in the seam's own plane:
     * the beam on one side of the anchor and the key on the other, as the joint
     * was really built.
     *
     * The key is not centred on the anchor - it sits clear of the beam, so its
     * whole width stands off to one side - and that is why half a key width
     * alone is not enough to keep it on the face. The pad is deliberately NOT
     * part of the room, exactly as the spacing leaves it out: a whole-seam pad
     * IS the seam's own cross-section.
     */
    fun anchorRoomFor(joint: SnapFitJoint): AnchorRoom {
        val dimensions = joint.dimensions
        val half = dimensions.beamThicknessMm * 0.5f
        // The tooth's own reach, not half of whatever happens to be the widest
        // parameter: the hook stands a whole lip proud of the beam on ONE side -
        // the side it faces - and the material has to be there for it. A room
        // centred on the beam let the spread anchor joints whose barb hung over
        // the edge of the seam.
        val reach = half + dimensions.lipDepthMm
        val up = if (dimensions.facing == SnapFacing.SAME) reach else half
        val down = if (dimensions.facing == SnapFacing.SAME) half else reach
        return AnchorRoom(
            minX = -dimensions.beamWidthMm * 0.5f,
            maxX = dimensions.keyOffsetMm + dimensions.keySizeMm * 0.5f,
            minY = -down,
            maxY = up,
        )
    }

    /**
     * The same room read in the seam's own axes, which is the frame [rim]'s grid
     * is in.
     *
     * A joint built for the half the chosen one refused walks the assembly
     * direction the other way, and its own side axis turns round with it, so the
     * room has to be mirrored across the anchor before it is laid over the grid:
     * the spread was measuring where the key would land on the other side of the
     * joint entirely.
     */
    fun anchorRoomIn(rim: SeamRim, joint: SnapFitJoint): AnchorRoom {
        val room = anchorRoomFor(joint)
        if (joint.frame.side.dot(rim.side) >= 0f) return room
        return AnchorRoom(minX = -room.maxX, maxX = -room.minX, minY = room.minY, maxY = room.maxY)
    }

    /**
     * The points the spread may anchor to: [rim]'s own outline, moved inside
     * the material far enough that the joint standing there is on the face.
     *
     * Two things are measured per cell: that the whole room the joint takes
     * about its anchor is material both halves share - so the key cannot hang
     * over the edge, which is what a 20 mm face showed - and that the anchor is
     * at least half the key's own width inside the outline. The anchors are the
     * rim of what is left, so they still follow the seam's own contour and
     * still trace a hollow seam's walls. A seam with no cell that can hold the
     * whole joint falls back to its own middle line rather than losing every
     * anchor, which is the most room the material has to offer. A rim with no
     * grid to measure against - one built by hand - is returned as it is.
     */
    fun anchorsFor(rim: SeamRim, joint: SnapFitJoint): List<SeamPoint> {
        val grid = rim.grid ?: return rim.rimPoints
        val inset = anchorInsetFor(joint)
        if (inset <= 0f) return rim.rimPoints
        val reach = ceil(inset / minOf(grid.cellWidthMm, grid.cellHeightMm)).toInt() + 1
        val size = grid.samples
        val distance = FloatArray(size * size) { -1f }
        var deepest = 0f
        for (row in 0 until size) {
            for (column in 0 until size) {
                if (!grid.isShared(column, row)) continue
                val far = grid.distanceToEdge(column, row, reach)
                // Nothing but material within reach: deeper than anything the
                // inset can ask for, and only ever compared against it.
                val value = if (far == Float.MAX_VALUE) inset + grid.widestCellMm else far
                distance[row * size + column] = value
                if (value > deepest) deepest = value
            }
        }
        if (deepest <= 0f) return rim.rimPoints
        val room = anchorRoomIn(rim, joint)
        // The room is rounded OUTWARD to whole cells: a cell that only part of
        // the joint stands on is a cell the joint needs, and rounding the other
        // way left the key's far corner in a cell nobody had checked.
        val roomColumns = floor(room.minX / grid.cellWidthMm).toInt()..ceil(room.maxX / grid.cellWidthMm).toInt()
        val roomRows = floor(room.minY / grid.cellHeightMm).toInt()..ceil(room.maxY / grid.cellHeightMm).toInt()
        val holds = BooleanArray(size * size) { index ->
            val column = index % size
            val row = index / size
            distance[index] >= inset - 1e-4f &&
                roomColumns.all { across ->
                    roomRows.all { up -> grid.isShared(column + across, row + up) }
                }
        }
        // The material's own thinnest place is the floor: a seam that cannot
        // hold the whole joint keeps its middle line rather than losing every
        // anchor, and says what it is when the joints are built.
        val kept = if (holds.any { it }) {
            holds
        } else {
            val threshold = minOf(inset, deepest)
            BooleanArray(size * size) { index -> distance[index] >= threshold - 1e-4f }
        }
        val anchors = ArrayList<SeamPoint>()
        for (row in 0 until size) {
            for (column in 0 until size) {
                val index = row * size + column
                if (!kept[index]) continue
                val edge = !kept.getOrElse(index - 1) { false } || !kept.getOrElse(index + 1) { false } ||
                    !kept.getOrElse(index - size) { false } || !kept.getOrElse(index + size) { false }
                if (edge) anchors += SeamPoint(grid.centreX(column), grid.centreY(row))
            }
        }
        return anchors.takeIf { it.isNotEmpty() } ?: rim.rimPoints
    }

    /**
     * Up to [maxCount] anchors on [rim], at least [spacingFor] apart from one
     * another and from anything in [occupied].
     *
     * The points are the rim INSET by [anchorInsetFor], so the joint's key sits
     * on the face rather than over its edge, and they are chosen by
     * farthest-point sampling: the first is the point nearest the middle of the
     * rim, and each next is the point furthest from everything chosen so far.
     * On a rim that is a band - a hull's cross-section - that walks the band's
     * own length and spreads the joints evenly along it rather than clustering
     * them where the sampling happened to start.
     */
    fun plan(
        rim: SeamRim,
        joint: SnapFitJoint,
        occupied: List<SeamPoint> = emptyList(),
        maxCount: Int = MAX_JOINTS,
    ): Plan {
        val points = anchorsFor(rim, joint)
        if (points.isEmpty()) {
            return Plan.Refused("the two halves share no material at this seam, so there is nowhere to spread a joint")
        }
        val spacing = spacingFor(joint)
        // The room the joint takes about its own anchor, and the wall that has
        // to stand between two of them - the layout guard's own two numbers.
        // Sampling by anything looser proposes placements the guard then
        // refuses: two anchors a joint's own diameter apart can still have
        // their pockets cross when they sit diagonally, and the user is left
        // holding a spread they cannot join.
        // The packing runs on the guard's own rule, in the SEAM's own axes: the
        // joint's real footprint - what the guard will compare - carried over
        // from the joint's frame, which can be turned round from the rim's. A
        // spread sampled on anything smaller proposes placements the guard then
        // refuses, which is what a spread the user cannot join looks like.
        val footprint = SnapLayout.footprintOf(joint).rect
        val room = if (joint.frame.side.dot(rim.side) >= 0f) {
            footprint
        } else {
            SnapLayout.SeamRect(-footprint.maxX, -footprint.minX, footprint.minY, footprint.maxY)
        }
        val wall = joint.dimensions.beamThicknessMm
        // The rim's own middle: the first joint goes as near it as the rim
        // allows, which is a point that exists by construction - and as near it
        // as the joints ALREADY on the seam allow, which is what the seed used
        // to skip. A hand-placed joint near the middle of the seam then had the
        // spread's first joint land on top of it, and the pair could not be
        // joined at all: the device found exactly that, 0.98 mm of pocket
        // crossing, after the whole spread had been accepted into the panel.
        val centreX = (points.minOf { it.x } + points.maxOf { it.x }) * 0.5f
        val centreY = (points.minOf { it.y } + points.maxOf { it.y }) * 0.5f
        val chosen = ArrayList<SeamPoint>()
        val seed = points
            .filter { point -> occupied.all { roomGap(room, point, it) >= wall } }
            .minByOrNull { hypot((it.x - centreX).toDouble(), (it.y - centreY).toDouble()) }
            ?: return Plan.Refused(
                "the joints already on this seam leave no room for another at the " +
                    millimetres(spacing) + " mm spacing a joint this size needs" +
                    "; move one, remove one, or make the joints smaller",
            )
        chosen += seed
        while (chosen.size < maxCount) {
            var best: SeamPoint? = null
            var bestGap = -1f
            for (point in points) {
                var nearest = Float.MAX_VALUE
                for (taken in chosen) nearest = minOf(nearest, roomGap(room, point, taken))
                for (taken in occupied) nearest = minOf(nearest, roomGap(room, point, taken))
                if (nearest > bestGap + 1e-4f) {
                    bestGap = nearest
                    best = point
                }
            }
            if (best == null || bestGap < wall) break
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

    /**
     * How much room is left between two joints whose anchors are [one] and
     * [other], measured the way [SnapLayout.conflicts] measures it: their
     * footprints, in the seam's plane, on both axes at once. Negative when the
     * two rectangles cross, which is the pocket cutting into its neighbour.
     *
     * Both joints are taken to be [room], the joint that will stand there: the
     * spread is deciding whether a place is free, and the shape of the joint
     * does not change with the place.
     */
    private fun roomGap(room: SnapLayout.SeamRect, one: SeamPoint, other: SeamPoint): Float {
        val dx = other.x - one.x
        val dy = other.y - one.y
        val gapX = maxOf(room.minX, room.minX + dx) - minOf(room.maxX, room.maxX + dx)
        val gapY = maxOf(room.minY, room.minY + dy) - minOf(room.maxY, room.maxY + dy)
        // Crossing: report the overlap as a negative gap, so it can never pass
        // a check written as "at least a wall apart".
        if (gapX <= 0f && gapY <= 0f) return maxOf(gapX, gapY)
        return hypot(maxOf(gapX, 0f).toDouble(), maxOf(gapY, 0f).toDouble()).toFloat()
    }

    private fun distance(first: SeamPoint, second: SeamPoint): Float =
        hypot((first.x - second.x).toDouble(), (first.y - second.y).toDouble()).toFloat()

    /** Two decimals, locale-independent: this text lands in the panel. */
    private fun millimetres(value: Float): String = String.format(Locale.ROOT, "%.2f", value)
}
