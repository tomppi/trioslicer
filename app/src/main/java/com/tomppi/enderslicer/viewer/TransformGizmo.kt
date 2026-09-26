package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.annotation.Point3
import com.tomppi.enderslicer.model.ModelPlacement
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** What the on-model gizmo is doing right now. */
enum class TransformGizmoMode { NONE, MOVE, ROTATE, SCALE }

/** One coloured run of line segments: xyz pairs, ready for GL_LINES. */
class GizmoGroup(val color: FloatArray, val vertices: FloatArray)

/** Line geometry drawn over the model, in plate coordinates. */
class GizmoOverlay(val groups: List<GizmoGroup>) {
    val isEmpty: Boolean get() = groups.all { it.vertices.isEmpty() }
    val totalVertexCount: Int get() = groups.sumOf { it.vertices.size / 3 }
}

/** A rotation ring: the closed loop the finger grabs, and the axis it turns. */
class GizmoRing(val axis: ModelPlacement.Axis, val points: FloatArray)

/** A translation arrow: a shaft with a head, and the axis it moves along. */
class GizmoArrow(val axis: ModelPlacement.Axis, val vertices: FloatArray)

/**
 * The geometry of the on-model transform gizmo.
 *
 * Cura's colours, so a glance says which axis is which: X red, Y green, Z blue.
 * Rings sit in the plane their axis is normal to, arrows point along their axis,
 * and both are sized from the model so a 20 mm part gets a gizmo it can still be
 * touched on and a 300 mm one does not get a ring off the screen.
 */
object TransformGizmo {
    val X_COLOR = floatArrayOf(0.95f, 0.30f, 0.30f)
    val Y_COLOR = floatArrayOf(0.35f, 0.90f, 0.40f)
    val Z_COLOR = floatArrayOf(0.35f, 0.55f, 1f)

    private const val RING_SEGMENTS = 96
    private const val HEAD_SEGMENTS = 12

    fun colorFor(axis: ModelPlacement.Axis): FloatArray = when (axis) {
        ModelPlacement.Axis.X -> X_COLOR
        ModelPlacement.Axis.Y -> Y_COLOR
        ModelPlacement.Axis.Z -> Z_COLOR
    }

    /** Ring radius: wide enough to clear the model's footprint, never tiny. */
    fun ringRadiusMm(bounds: MeshBounds?): Float {
        if (bounds == null) return MIN_RING_MM
        val footprint = max(bounds.width, bounds.depth)
        if (!footprint.isFinite() || footprint <= 0f) return MIN_RING_MM
        return (footprint * 0.62f).coerceAtLeast(MIN_RING_MM)
    }

    /** Arrow length: the model's height, plus a head's worth of clearance. */
    fun arrowLengthMm(bounds: MeshBounds?): Float {
        if (bounds == null) return MIN_ARROW_MM
        val height = bounds.height
        val base = if (height.isFinite() && height > 0f) height else MIN_ARROW_MM
        return (base * 1.15f).coerceAtLeast(MIN_ARROW_MM)
    }

    private const val MIN_RING_MM = 8f
    private const val MIN_ARROW_MM = 10f

    fun rings(pivot: Point3, radiusMm: Float): List<GizmoRing> {
        require(radiusMm.isFinite() && radiusMm > 0f) { "Gizmo ring radius must be positive" }
        return ModelPlacement.Axis.entries.map { axis -> GizmoRing(axis, loopPoints(axis, pivot, radiusMm)) }
    }

    fun arrows(pivot: Point3, lengthMm: Float): List<GizmoArrow> {
        require(lengthMm.isFinite() && lengthMm > 0f) { "Gizmo arrow length must be positive" }
        val headLength = (lengthMm * 0.18f).coerceAtLeast(1.5f)
        val headRadius = headLength * 0.45f
        return ModelPlacement.Axis.entries.map { axis ->
            GizmoArrow(axis, arrowVertices(axis, pivot, lengthMm, headLength, headRadius))
        }
    }

    /**
     * The model's bounding box as twelve edges, for scale mode: a ring would not
     * say which way the model grows, and Cura shows a box here for the same
     * reason.
     */
    fun boxOutline(bounds: MeshBounds?): GizmoOverlay {
        if (bounds == null) return GizmoOverlay(emptyList())
        val x0 = bounds.minX; val y0 = bounds.minY; val z0 = bounds.minZ
        val x1 = bounds.maxX; val y1 = bounds.maxY; val z1 = bounds.maxZ
        val corners = listOf(
            floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0),
            floatArrayOf(x1, y1, z0), floatArrayOf(x0, y1, z0),
            floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1),
            floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1),
        )
        val edges = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 0,
            4 to 5, 5 to 6, 6 to 7, 7 to 4,
            0 to 4, 1 to 5, 2 to 6, 3 to 7,
        )
        val values = FloatArray(edges.size * 2 * 3)
        var target = 0
        edges.forEach { (from, to) ->
            for (component in 0..2) values[target++] = corners[from][component]
            for (component in 0..2) values[target++] = corners[to][component]
        }
        return GizmoOverlay(listOf(GizmoGroup(BOX_COLOR, values)))
    }

    /** Neutral, so it reads as "this whole thing" rather than one axis. */
    val BOX_COLOR = floatArrayOf(0.85f, 0.85f, 0.9f)

    /** Turns rings into drawable segments: GL_LINES wants pairs, not a strip. */
    fun ringsOverlay(rings: List<GizmoRing>): GizmoOverlay = GizmoOverlay(
        rings.map { ring ->
            val values = FloatArray(ring.points.size / 3 * 6)
            var target = 0
            val count = ring.points.size / 3
            for (index in 0 until count) {
                val next = (index + 1) % count
                for (component in 0..2) {
                    values[target++] = ring.points[index * 3 + component]
                }
                for (component in 0..2) {
                    values[target++] = ring.points[next * 3 + component]
                }
            }
            GizmoGroup(colorFor(ring.axis), values)
        },
    )

    fun arrowsOverlay(arrows: List<GizmoArrow>): GizmoOverlay = GizmoOverlay(
        arrows.map { arrow -> GizmoGroup(colorFor(arrow.axis), arrow.vertices) },
    )

    private fun loopPoints(axis: ModelPlacement.Axis, pivot: Point3, radiusMm: Float): FloatArray {
        val values = FloatArray(RING_SEGMENTS * 3)
        for (index in 0 until RING_SEGMENTS) {
            val angle = 2.0 * Math.PI * index / RING_SEGMENTS
            val c = (cos(angle) * radiusMm).toFloat()
            val s = (sin(angle) * radiusMm).toFloat()
            val (x, y, z) = when (axis) {
                ModelPlacement.Axis.X -> Triple(pivot.x, pivot.y + c, pivot.z + s)
                ModelPlacement.Axis.Y -> Triple(pivot.x + c, pivot.y, pivot.z + s)
                ModelPlacement.Axis.Z -> Triple(pivot.x + c, pivot.y + s, pivot.z)
            }
            values[index * 3] = x
            values[index * 3 + 1] = y
            values[index * 3 + 2] = z
        }
        return values
    }

    private fun arrowVertices(
        axis: ModelPlacement.Axis,
        pivot: Point3,
        lengthMm: Float,
        headLengthMm: Float,
        headRadiusMm: Float,
    ): FloatArray {
        val values = ArrayList<Float>(3 * 2 * (2 + HEAD_SEGMENTS))
        fun point(along: Float, sideA: Float, sideB: Float) {
            val (x, y, z) = when (axis) {
                ModelPlacement.Axis.X -> Triple(pivot.x + along, pivot.y + sideA, pivot.z + sideB)
                ModelPlacement.Axis.Y -> Triple(pivot.x + sideA, pivot.y + along, pivot.z + sideB)
                ModelPlacement.Axis.Z -> Triple(pivot.x + sideA, pivot.y + sideB, pivot.z + along)
            }
            values.add(x)
            values.add(y)
            values.add(z)
        }
        fun segment(fromAlong: Float, fromA: Float, fromB: Float, toAlong: Float, toA: Float, toB: Float) {
            point(fromAlong, fromA, fromB)
            point(toAlong, toA, toB)
        }

        val headBase = lengthMm - headLengthMm
        segment(0f, 0f, 0f, lengthMm, 0f, 0f)
        for (index in 0 until HEAD_SEGMENTS) {
            val angle = 2.0 * Math.PI * index / HEAD_SEGMENTS
            val a = (cos(angle) * headRadiusMm).toFloat()
            val b = (sin(angle) * headRadiusMm).toFloat()
            segment(lengthMm, 0f, 0f, headBase, a, b)
            val nextAngle = 2.0 * Math.PI * (index + 1) / HEAD_SEGMENTS
            segment(
                headBase,
                a,
                b,
                headBase,
                (cos(nextAngle) * headRadiusMm).toFloat(),
                (sin(nextAngle) * headRadiusMm).toFloat(),
            )
        }
        return values.toFloatArray()
    }
}
