package com.tomppi.enderslicer.viewer

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The snap-fit joint, as geometry: given the split plane's normal, the point on
 * the model the user tapped, a scale and the two halves, this builds the two
 * solids a boolean can put in - the beam, barb and key to union into one half,
 * and the matching pocket to subtract from the other.
 *
 * The design is the agreed one (docs/snap-fit-notes.md): a cantilever lying
 * across the seam along the assembly direction, with a barb on its free end,
 * plus a square key beside it for registration. Nothing protrudes past the
 * outline of either part.
 *
 * The barb is one-way, and its shape is the whole trick:
 *
 *  - The leading face - the one the other part reaches first, facing back along
 *    the assembly direction - is a [SnapFitParameters.rampAngleDeg]-degree ramp.
 *    The descending half's pocket mouth rides that ramp and cams the beam
 *    aside, which is the only reason the parts can be pushed together at all. A
 *    square leading face cannot cam in, and a joint with one never assembles.
 *  - The back face is square, perpendicular to the assembly direction. It is
 *    the catch: once the barb has sprung out into its pocket the square faces
 *    meet and pulling the halves apart only presses them together.
 *
 * Clearances are separate and explicit, because most printed snap fits fail on
 * exactly this: [SnapFitParameters.keyClearanceMm] around the key,
 * [SnapFitParameters.lipClearanceMm] around the beam and barb, and
 * [SnapFitParameters.matingClearanceMm] as the relief of the socket's mouth in
 * the mate's mating face. All three default to a printable 0.2 mm and all three
 * scale with [SnapFitParameters] like every other dimension, so one slider
 * drives the whole joint.
 *
 * The beam is unioned into the half on the low side of the plane and the pocket
 * is cut from the half on the high side; flipping the axis swaps which half
 * carries the beam. The beam's length is clamped to the material the other half
 * actually has (leaving a wall at least a beam-thickness thick), and its root
 * to the material its own half has, so the joint cannot run out through the far
 * face; if what is left is too small to be a joint, nothing is produced.
 *
 * Pure geometry: no UI, no state, no I/O, and no dependency outside the JVM.
 */
object SnapFit {
    /**
     * Builds the joint, or returns null when these halves and this scale cannot
     * hold one: halves that do not straddle a plane in that order, a mate too
     * thin for the beam, a low half too thin to root it, or a scale or angle
     * that is not a positive number.
     */
    fun generate(
        axis: Vec3,
        anchorMm: Vec3,
        scale: Float,
        lowHalf: StlMesh,
        highHalf: StlMesh,
        parameters: SnapFitParameters = SnapFitParameters(),
    ): SnapFitJoint? {
        if (!axis.isFinite() || !anchorMm.isFinite() || !scale.isFinite() || scale <= 0f) return null
        if (!parameters.isUsable()) return null
        if (lowHalf.triangleCount <= 0 || highHalf.triangleCount <= 0) return null

        val direction = axis.normalized() ?: return null
        val low = extent(lowHalf, direction)
        val high = extent(highHalf, direction)
        if (low == null || high == null) return null
        // The halves must be on either side of one plane, in this order. A pair
        // the wrong way round overlaps by the whole model and is refused here.
        if (low.endInclusive > high.start + PLANE_TOLERANCE_MM) return null
        val plane = (low.endInclusive + high.start) * 0.5f

        val rampAngle = parameters.rampAngleDeg
        val keySize = parameters.keySizeMm * scale
        val keyHeight = parameters.keyHeightMm * scale
        val keyClearance = parameters.keyClearanceMm * scale
        val beamWidth = parameters.beamWidthMm * scale
        val beamThickness = parameters.beamThicknessMm * scale
        val lipDepth = parameters.lipDepthMm * scale
        val lipClearance = parameters.lipClearanceMm * scale
        val matingClearance = parameters.matingClearanceMm * scale
        val keyGap = parameters.keyGapMm * scale
        // The key's socket and the beam's pocket are separate solids; they may
        // not touch, or the pocket would be one shape with a step in it.
        if (keyGap <= keyClearance + lipClearance) return null

        // Leave a wall of at least a beam-thickness beyond the beam's tip, and
        // the same below its root.
        val wall = beamThickness
        val mateDepth = high.endInclusive - plane
        val ownDepth = plane - low.start
        val beamLength = minOf(parameters.beamLengthMm * scale, mateDepth - wall)
        val beamRoot = minOf(parameters.beamRootMm * scale, ownDepth - wall)
        if (beamLength <= 0f || beamRoot <= 0f) return null
        val lipRun = lipDepth / tan(rampAngle * DEGREES_TO_RADIANS)
        if (beamLength <= lipRun + lipClearance) return null

        val half = beamThickness * 0.5f
        val lipBack = beamLength - lipRun
        val keyCentre = beamWidth * 0.5f + keyGap + keySize * 0.5f

        // The frame's origin is the anchor dropped onto the mating plane, so the
        // beam sits where the user tapped and the plane is local z = 0.
        val alongAnchor = anchorMm.dot(direction)
        val origin = anchorMm - direction * (alongAnchor - plane)
        val side = perpendicularTo(direction)
        val rise = direction.cross(side)
        val frame = SnapFitFrame(origin, direction, side, rise, plane)

        val dimensions = SnapFitDimensions(
            scale = scale,
            keySizeMm = keySize,
            keyHeightMm = keyHeight,
            keyClearanceMm = keyClearance,
            keyOffsetMm = keyCentre,
            beamLengthMm = beamLength,
            beamWidthMm = beamWidth,
            beamThicknessMm = beamThickness,
            beamRootMm = beamRoot,
            lipDepthMm = lipDepth,
            lipRunMm = lipRun,
            lipClearanceMm = lipClearance,
            matingClearanceMm = matingClearance,
            rampAngleDeg = rampAngle,
            beamTipMm = beamLength,
            lipBackMm = lipBack,
        )

        // ------------------------------------------------------------- union
        val union = MeshSolidBuilder(UNION_NAME)
        // The beam and its barb are one prism: a cross-section in the (rise,
        // axis) plane, extruded across the beam's width. Building them as one
        // closed shell keeps the boolean from having to reconcile two shells
        // that share a face.
        addPrism(
            union,
            frame,
            listOf(
                Vec3(-beamWidth * 0.5f, -half, -beamRoot),
                Vec3(-beamWidth * 0.5f, -half, beamLength),
                Vec3(-beamWidth * 0.5f, half, beamLength),
                Vec3(-beamWidth * 0.5f, half + lipDepth, lipBack),
                Vec3(-beamWidth * 0.5f, half, lipBack),
                Vec3(-beamWidth * 0.5f, half, -beamRoot),
            ),
            Vec3(1f, 0f, 0f),
            beamWidth,
        )
        // The square key, disjoint from the beam by the key gap.
        addPrism(
            union,
            frame,
            listOf(
                Vec3(keyCentre - keySize * 0.5f, -keySize * 0.5f, -beamRoot),
                Vec3(keyCentre + keySize * 0.5f, -keySize * 0.5f, -beamRoot),
                Vec3(keyCentre + keySize * 0.5f, keySize * 0.5f, -beamRoot),
                Vec3(keyCentre - keySize * 0.5f, keySize * 0.5f, -beamRoot),
            ),
            Vec3(0f, 0f, 1f),
            keyHeight + beamRoot,
        )

        // ---------------------------------------------------------- subtract
        val socket = MeshSolidBuilder(SOCKET_NAME)
        // The beam and barb plus clearance, as one pocket. It starts below the
        // plane by the mating clearance, so the pocket is open at the mate's
        // mating face and that face is relieved around the joint instead of
        // bottoming out on the other half.
        val pocketSide = beamWidth * 0.5f + lipClearance
        addPrism(
            socket,
            frame,
            listOf(
                Vec3(-pocketSide, -half - lipClearance, -matingClearance),
                Vec3(-pocketSide, -half - lipClearance, beamLength + lipClearance),
                Vec3(-pocketSide, half + lipDepth + lipClearance, beamLength + lipClearance),
                Vec3(-pocketSide, half + lipDepth + lipClearance, lipBack - lipClearance),
                Vec3(-pocketSide, half + lipClearance, lipBack - lipClearance),
                Vec3(-pocketSide, half + lipClearance, -matingClearance),
            ),
            Vec3(1f, 0f, 0f),
            beamWidth + 2f * lipClearance,
        )
        // The key's socket, the key plus the key's own clearance.
        val keySide = keySize * 0.5f + keyClearance
        addPrism(
            socket,
            frame,
            listOf(
                Vec3(keyCentre - keySide, -keySide, -matingClearance),
                Vec3(keyCentre + keySide, -keySide, -matingClearance),
                Vec3(keyCentre + keySide, keySide, -matingClearance),
                Vec3(keyCentre - keySide, keySide, -matingClearance),
            ),
            Vec3(0f, 0f, 1f),
            keyHeight + keyClearance + matingClearance,
        )

        return SnapFitJoint(union.build(), socket.build(), frame, dimensions)
    }

    /**
     * Extrudes a planar polygon into a closed prism: caps triangulated, sides
     * quad by quad, every face wound outwards. The polygon is re-oriented if it
     * was handed over the wrong way round, so callers only have to list their
     * corners in order.
     */
    private fun addPrism(
        builder: MeshSolidBuilder,
        frame: SnapFitFrame,
        points: List<Vec3>,
        direction: Vec3,
        length: Float,
    ) {
        if (points.size < 3 || !(length > 0f)) return
        val ordered = if (polygonNormal(points).dot(direction) < 0f) points.reversed() else points
        val extrusion = direction.normalized() ?: return
        val origin = ordered[0]
        val basisU = perpendicularTo(extrusion)
        val basisV = extrusion.cross(basisU)
        val flat = ordered.map { point ->
            val delta = point - origin
            Vec2(delta.dot(basisU), delta.dot(basisV))
        }
        val triangles = Triangulator.triangulate(flat)
        val shift = extrusion * length
        for (triangle in triangles) {
            // The cap at the far end faces along the extrusion and keeps the
            // polygon's own winding; the near cap is the same triangle turned
            // round.
            val (first, second, third) = triangle
            builder.addFace(
                frame.model(ordered[first] + shift),
                frame.model(ordered[second] + shift),
                frame.model(ordered[third] + shift),
            )
            builder.addFace(
                frame.model(ordered[first]),
                frame.model(ordered[third]),
                frame.model(ordered[second]),
            )
        }
        for (edge in ordered.indices) {
            val from = ordered[edge]
            val to = ordered[(edge + 1) % ordered.size]
            val fromTop = from + shift
            val toTop = to + shift
            builder.addFace(frame.model(from), frame.model(to), frame.model(toTop))
            builder.addFace(frame.model(from), frame.model(toTop), frame.model(fromTop))
        }
    }

    /** One triangle of a feature, from three model-space corners. */
    private fun MeshSolidBuilder.addFace(a: Vec3, b: Vec3, c: Vec3) {
        addTriangle(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z)
    }

    /** The unnormalised normal of a polygon, by Newell's method. */
    private fun polygonNormal(points: List<Vec3>): Vec3 {
        var x = 0f
        var y = 0f
        var z = 0f
        for (index in points.indices) {
            val current = points[index]
            val next = points[(index + 1) % points.size]
            x += (current.y - next.y) * (current.z + next.z)
            y += (current.z - next.z) * (current.x + next.x)
            z += (current.x - next.x) * (current.y + next.y)
        }
        return Vec3(x, y, z)
    }

    /** Any unit vector perpendicular to [direction]. */
    private fun perpendicularTo(direction: Vec3): Vec3 {
        val reference = when {
            abs(direction.x) <= abs(direction.y) && abs(direction.x) <= abs(direction.z) -> Vec3(1f, 0f, 0f)
            abs(direction.y) <= abs(direction.z) -> Vec3(0f, 1f, 0f)
            else -> Vec3(0f, 0f, 1f)
        }
        return reference.cross(direction).normalized() ?: Vec3(1f, 0f, 0f)
    }

    /** The span of a mesh's vertices along [direction]. */
    private fun extent(mesh: StlMesh, direction: Vec3): ClosedFloatingPointRange<Float>? {
        val vertices = mesh.interleavedVertices
        val count = mesh.triangleCount * 3
        if (count <= 0) return null
        var low = Float.POSITIVE_INFINITY
        var high = Float.NEGATIVE_INFINITY
        for (vertex in 0 until count) {
            val base = vertex * MeshSolidBuilder.FLOATS_PER_VERTEX
            val along = vertices[base] * direction.x + vertices[base + 1] * direction.y + vertices[base + 2] * direction.z
            if (along < low) low = along
            if (along > high) high = along
        }
        return if (low <= high) low..high else null
    }

    /** A point in the plane of a prism's cross-section, for the triangulator. */
    private class Vec2(val x: Float, val y: Float)

    /**
     * Ear clipping for a simple polygon given counter-clockwise. The joints are
     * six-gons with one reflex corner, which a triangle fan cannot handle; the
     * fan is only the last-resort fallback for a polygon this rejects.
     */
    private object Triangulator {
        fun triangulate(points: List<Vec2>): List<Triple<Int, Int, Int>> {
            val count = points.size
            if (count < 3) return emptyList()
            val remaining = MutableList(count) { it }
            val triangles = ArrayList<Triple<Int, Int, Int>>(count - 2)
            var guard = 0
            while (remaining.size > 3 && guard < count * count) {
                guard++
                var clipped = false
                for (position in remaining.indices) {
                    val previous = remaining[(position + remaining.size - 1) % remaining.size]
                    val current = remaining[position]
                    val next = remaining[(position + 1) % remaining.size]
                    if (turn(points[previous], points[current], points[next]) <= EPSILON) continue
                    if (contains(points, remaining, previous, current, next)) continue
                    triangles.add(Triple(previous, current, next))
                    remaining.removeAt(position)
                    clipped = true
                    break
                }
                if (!clipped) return fan(count)
            }
            if (remaining.size == 3) triangles.add(Triple(remaining[0], remaining[1], remaining[2]))
            return triangles
        }

        private fun fan(count: Int): List<Triple<Int, Int, Int>> =
            (1 until count - 1).map { Triple(0, it, it + 1) }

        private fun turn(a: Vec2, b: Vec2, c: Vec2): Float =
            (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

        private fun contains(points: List<Vec2>, remaining: List<Int>, a: Int, b: Int, c: Int): Boolean {
            for (index in remaining) {
                if (index == a || index == b || index == c) continue
                val point = points[index]
                if (turn(points[a], points[b], point) >= -EPSILON &&
                    turn(points[b], points[c], point) >= -EPSILON &&
                    turn(points[c], points[a], point) >= -EPSILON
                ) {
                    return true
                }
            }
            return false
        }

        private const val EPSILON = 1e-6f
    }

    private const val UNION_NAME = "snap-fit joint"
    private const val SOCKET_NAME = "snap-fit socket"
    private const val PLANE_TOLERANCE_MM = 1e-3f
    private val DEGREES_TO_RADIANS = (Math.PI / 180.0).toFloat()
}

/**
 * The dimensions a joint was actually built with, in model millimetres: the
 * scaled parameters after the beam was clamped to the material available and
 * the lip's run was derived from the ramp angle.
 */
data class SnapFitDimensions(
    val scale: Float,
    val keySizeMm: Float,
    val keyHeightMm: Float,
    val keyClearanceMm: Float,
    /** The key's centre, along the in-plane side axis from the anchor. */
    val keyOffsetMm: Float,
    val beamLengthMm: Float,
    val beamWidthMm: Float,
    val beamThicknessMm: Float,
    /** How deep the beam roots into its own half, below the mating plane. */
    val beamRootMm: Float,
    val lipDepthMm: Float,
    /** The ramp's run along the assembly axis: the lip's depth at that angle. */
    val lipRunMm: Float,
    val lipClearanceMm: Float,
    val matingClearanceMm: Float,
    val rampAngleDeg: Float,
    /** The beam's free end, along the assembly axis from the mating plane. */
    val beamTipMm: Float,
    /** The lip's square catch face, along the assembly axis from the plane. */
    val lipBackMm: Float,
)

/**
 * Where the joint was put: the anchor dropped onto the mating plane, and the
 * right-handed frame the features are laid out in. Local x is the in-plane side
 * axis, local y the in-plane rise, local z the assembly axis with zero on the
 * mating plane; [model] and [local] convert either way.
 */
data class SnapFitFrame(
    val originMm: Vec3,
    /** The assembly direction, from the beam's half to the other one. */
    val axis: Vec3,
    val side: Vec3,
    val rise: Vec3,
    /** Where the mating plane sits along [axis], in model coordinates. */
    val planeOffsetMm: Float,
) {
    fun model(local: Vec3): Vec3 = originMm + side * local.x + rise * local.y + axis * local.z

    fun local(point: Vec3): Vec3 {
        val delta = point - originMm
        return Vec3(delta.dot(side), delta.dot(rise), delta.dot(axis))
    }
}

/** The two solids, ready for [MeshBoolean], and the geometry they were built to. */
data class SnapFitJoint(
    /** Union this into the half on the low side of the plane. */
    val unionSolid: StlMesh,
    /** Subtract this from the half on the high side. */
    val subtractSolid: StlMesh,
    val frame: SnapFitFrame,
    val dimensions: SnapFitDimensions,
)

/**
 * The joint's starting dimensions, before the scale. Every one of them is
 * multiplied by the caller's scale factor, clearances included, so the whole
 * joint follows one slider.
 */
data class SnapFitParameters(
    /** The square key's side. */
    val keySizeMm: Float = 5f,
    /** How far the key stands out of the mating face. */
    val keyHeightMm: Float = 2f,
    /** Clearance around the key in its socket. */
    val keyClearanceMm: Float = 0.2f,
    /** The beam's reach past the mating face, before clamping. */
    val beamLengthMm: Float = 8f,
    val beamWidthMm: Float = 3f,
    val beamThicknessMm: Float = 1.4f,
    /** How deep the beam roots inside its own half. */
    val beamRootMm: Float = 2f,
    /** How far the barb stands out sideways from the beam. */
    val lipDepthMm: Float = 0.8f,
    /** Clearance around the beam and barb in the pocket. */
    val lipClearanceMm: Float = 0.2f,
    /**
     * How far the pocket is sunk below the mating plane, which relieves the
     * mate's mating face at the joint's mouth.
     */
    val matingClearanceMm: Float = 0.2f,
    /** The material left between the beam and the key. */
    val keyGapMm: Float = 1f,
    /** The barb's lead-in angle; 45 degrees is the printable default. */
    val rampAngleDeg: Float = 45f,
) {
    internal fun isUsable(): Boolean {
        val lengths = listOf(
            keySizeMm, keyHeightMm, keyClearanceMm, beamLengthMm, beamWidthMm, beamThicknessMm,
            beamRootMm, lipDepthMm, lipClearanceMm, matingClearanceMm, keyGapMm,
        )
        return lengths.all { it.isFinite() && it > 0f } &&
            rampAngleDeg.isFinite() && rampAngleDeg > 1f && rampAngleDeg < 89f
    }
}

/** A point or direction in build-plate millimetres. */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

    fun dot(other: Vec3): Float = x * other.x + y * other.y + z * other.z

    fun cross(other: Vec3): Vec3 = Vec3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x,
    )

    fun length(): Float = sqrt(x * x + y * y + z * z)

    fun normalized(): Vec3? {
        val size = length()
        if (!size.isFinite() || size <= 0f) return null
        return Vec3(x / size, y / size, z / size)
    }

    operator fun plus(other: Vec3): Vec3 = Vec3(x + other.x, y + other.y, z + other.z)

    operator fun minus(other: Vec3): Vec3 = Vec3(x - other.x, y - other.y, z - other.z)

    operator fun times(factor: Float): Vec3 = Vec3(x * factor, y * factor, z * factor)
}
