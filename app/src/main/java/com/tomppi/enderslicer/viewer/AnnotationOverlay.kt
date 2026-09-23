package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.annotation.AnnotationState
import com.tomppi.enderslicer.annotation.Point3
import com.tomppi.enderslicer.annotation.SegmentEnd

/**
 * Line geometry for the annotation overlay, in model space.
 *
 * Vertices are laid out for a single `GL_LINES` buffer: the committed series
 * and the segment being placed come first, then the marker crosses for the end
 * handles. Both halves are drawn from the same buffer with different colours,
 * which is why the counts are tracked separately.
 *
 * [thicknessPx] rides along because line width is render state, not geometry:
 * it cannot be expressed in a vertex buffer drawn with `GL_LINES`.
 */
class AnnotationOverlay(
    val vertices: FloatArray,
    val lineVertexCount: Int,
    val markerVertexCount: Int,
    val thicknessPx: Float,
    /** End handles, so the renderer can tell which one a touch landed on. */
    val handles: List<Pair<SegmentEnd, Point3>>,
) {
    val isEmpty: Boolean get() = lineVertexCount == 0 && markerVertexCount == 0

    val totalVertexCount: Int get() = lineVertexCount + markerVertexCount
}

/**
 * Turns annotation state into drawable geometry.
 *
 * Kept separate from the renderer so the layout can be tested without a GL
 * context.
 */
object AnnotationOverlayBuilder {

    /** Fraction of the model's largest dimension used for a handle cross. */
    private const val MARKER_FRACTION = 0.02f

    /**
     * Half-size of a handle cross, in model millimetres.
     *
     * Scaled to the model so a handle is the same visual weight on a 20 mm part
     * as on a 300 mm one, with a floor so it never vanishes on a tiny mesh.
     */
    fun markerSizeMm(bounds: MeshBounds?): Float {
        if (bounds == null) return MIN_MARKER_MM
        val largest = maxOf(bounds.width, bounds.depth, bounds.height)
        if (!largest.isFinite() || largest <= 0f) return MIN_MARKER_MM
        return (largest * MARKER_FRACTION).coerceAtLeast(MIN_MARKER_MM)
    }

    private const val MIN_MARKER_MM = 0.35f

    fun build(state: AnnotationState, markerSizeMm: Float): AnnotationOverlay {
        val values = ArrayList<Float>(128)

        // Committed series.
        for (chain in state.chains) {
            val points = chain.points
            for (i in 0 until points.size - 1) {
                segment(values, points[i].position, points[i + 1].position)
            }
            if (chain.closed && points.size >= 3) {
                segment(values, points.last().position, points.first().position)
            }
        }

        // The series being drawn, including the run into the pending end, so the
        // whole path stays visible while it is being extended.
        val series = state.currentSeries
        for (i in 0 until series.size - 1) {
            segment(values, series[i].position, series[i + 1].position)
        }
        val start = state.segmentStart
        val end = state.endHandle
        if (start != null && end != null) {
            segment(values, start.position, end.position)
        }

        val lineVertexCount = values.size / 3

        // Every handle is marked, because both ends stay draggable until the
        // segment is locked and the user has to be able to see what to grab.
        var markerVertexCount = 0
        val handles = state.handles()
        for ((_, handle) in handles) {
            val p = handle.position
            val s = markerSizeMm
            // A three-axis cross reads as a point from any viewing angle, which
            // a flat screen-facing marker cannot do while the camera orbits.
            segment(values, Point3(p.x - s, p.y, p.z), Point3(p.x + s, p.y, p.z))
            segment(values, Point3(p.x, p.y - s, p.z), Point3(p.x, p.y + s, p.z))
            segment(values, Point3(p.x, p.y, p.z - s), Point3(p.x, p.y, p.z + s))
            markerVertexCount += 6
        }

        return AnnotationOverlay(
            vertices = FloatArray(values.size) { values[it] },
            lineVertexCount = lineVertexCount,
            markerVertexCount = markerVertexCount,
            thicknessPx = state.thicknessPx,
            handles = handles.map { (end, point) -> end to point.position },
        )
    }

    private fun segment(into: MutableList<Float>, from: Point3, to: Point3) {
        into.add(from.x); into.add(from.y); into.add(from.z)
        into.add(to.x); into.add(to.y); into.add(to.z)
    }
}
