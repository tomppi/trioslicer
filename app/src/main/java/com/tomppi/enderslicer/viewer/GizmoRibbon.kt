package com.tomppi.enderslicer.viewer

import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Expands a gizmo's line pairs into quads of a fixed pixel width.
 *
 * The handles are drawn this way rather than with GL lines because glLineWidth is
 * capped at a single pixel on many Android drivers: a handle fat enough to aim a
 * finger at has to be geometry. Widths are measured in pixels and the vertices
 * are built in NDC, so a handle looks the same width on any screen and at any
 * zoom.
 */
internal object GizmoRibbon {
    /** Six vertices of three floats for every line pair. */
    const val SEGMENT_FLOATS = 18

    /** Floats a scratch buffer needs to hold a group of [vertexFloats] line vertices. */
    fun capacityFloats(vertexFloats: Int): Int = (maxOf(vertexFloats, 0) / 6) * SEGMENT_FLOATS

    /** Bytes for the same buffer. */
    fun capacityBytes(vertexFloats: Int): Int = capacityFloats(vertexFloats) * Float.SIZE_BYTES

    /**
     * Writes the quads for [vertices] (xyz pairs) into [target], returning the
     * number of vertices written.
     *
     * Points behind the eye are skipped, as are degenerate segments. Running out
     * of room stops the expansion rather than overflowing: a caller that
     * under-sized the buffer draws less, it does not crash the GL thread.
     */
    fun expand(
        vertices: FloatArray,
        mvp: FloatArray,
        viewportWidth: Int,
        viewportHeight: Int,
        halfWidthPx: Float,
        target: FloatBuffer,
    ): Int {
        if (viewportWidth <= 0 || viewportHeight <= 0) return 0
        if (!halfWidthPx.isFinite() || halfWidthPx <= 0f) return 0
        val halfWidthNdcX = halfWidthPx / (viewportWidth / 2f)
        val halfWidthNdcY = halfWidthPx / (viewportHeight / 2f)
        val start = FloatArray(3)
        val end = FloatArray(3)
        target.clear()
        var written = 0
        var index = 0
        while (index + 5 < vertices.size) {
            val hasStart = projectInto(mvp, vertices[index], vertices[index + 1], vertices[index + 2], start)
            val hasEnd = projectInto(mvp, vertices[index + 3], vertices[index + 4], vertices[index + 5], end)
            index += 6
            if (!hasStart || !hasEnd) continue
            if (target.remaining() < SEGMENT_FLOATS) break
            // Direction in pixels, so the ribbon is square whatever the aspect is.
            val directionX = (end[0] - start[0]) * viewportWidth / 2f
            val directionY = (end[1] - start[1]) * viewportHeight / 2f
            val length = sqrt(directionX * directionX + directionY * directionY)
            if (!length.isFinite() || length < 1e-3f) continue
            val offsetX = -directionY / length * halfWidthNdcX
            val offsetY = directionX / length * halfWidthNdcY
            put(target, start[0] + offsetX, start[1] + offsetY, start[2])
            put(target, end[0] + offsetX, end[1] + offsetY, end[2])
            put(target, end[0] - offsetX, end[1] - offsetY, end[2])
            put(target, start[0] + offsetX, start[1] + offsetY, start[2])
            put(target, end[0] - offsetX, end[1] - offsetY, end[2])
            put(target, start[0] - offsetX, start[1] - offsetY, start[2])
            written += 6
        }
        return written
    }

    private fun put(target: FloatBuffer, x: Float, y: Float, z: Float) {
        target.put(x)
        target.put(y)
        target.put(z)
    }

    /** Plate point to NDC through [mvp]; false when it is behind the eye. */
    fun projectInto(mvp: FloatArray, x: Float, y: Float, z: Float, out: FloatArray): Boolean {
        val clipX = mvp[0] * x + mvp[4] * y + mvp[8] * z + mvp[12]
        val clipY = mvp[1] * x + mvp[5] * y + mvp[9] * z + mvp[13]
        val clipZ = mvp[2] * x + mvp[6] * y + mvp[10] * z + mvp[14]
        val clipW = mvp[3] * x + mvp[7] * y + mvp[11] * z + mvp[15]
        if (!clipW.isFinite() || clipW <= 1e-4f) return false
        out[0] = clipX / clipW
        out[1] = clipY / clipW
        out[2] = clipZ / clipW
        return true
    }
}
