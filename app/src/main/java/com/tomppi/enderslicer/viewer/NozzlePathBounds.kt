package com.tomppi.enderslicer.viewer

import kotlin.math.max
import kotlin.math.min

/**
 * Robust bounds of the printed part from raw extrusion vertices.
 *
 * A plain min/max over extrusion moves is pulled toward the plate corner by
 * stray extrusions (purge line, distant skirt loops). The bounds are instead
 * trimmed per axis with percentiles over a couple of iterations, so small
 * outlier clouds are dropped while every substantial part of the print is
 * retained.
 */
object NozzlePathBounds {
    private const val VALUES_PER_VERTEX = 3
    private const val TRIM_FRACTION = 0.01f
    private const val MAX_ITERATIONS = 2

    /**
     * Returns [minX, minY, minZ, maxX, maxY, maxZ] of the retained vertices,
     * or null when fewer than two vertices are available.
     */
    fun printedBounds(vertices: FloatArray): FloatArray? {
        val vertexCount = vertices.size / VALUES_PER_VERTEX
        if (vertexCount < 2) return null
        val keep = BooleanArray(vertexCount) { true }
        var keptCount = vertexCount
        var iteration = 0
        while (iteration < MAX_ITERATIONS && keptCount > 2) {
            iteration++
            val xs = FloatArray(keptCount)
            val ys = FloatArray(keptCount)
            var i = 0
            for (vertex in 0 until vertexCount) {
                if (keep[vertex]) {
                    xs[i] = vertices[vertex * 3]
                    ys[i] = vertices[vertex * 3 + 1]
                    i++
                }
            }
            xs.sort()
            ys.sort()
            val lo = (keptCount * TRIM_FRACTION).toInt().coerceIn(0, keptCount - 1)
            val hi = (keptCount * (1f - TRIM_FRACTION)).toInt().coerceIn(lo, keptCount - 1)
            if (hi == lo) break
            val minX = xs[lo]
            val maxX = xs[hi]
            val minY = ys[lo]
            val maxY = ys[hi]
            var remaining = 0
            for (vertex in 0 until vertexCount) {
                if (!keep[vertex]) continue
                val x = vertices[vertex * 3]
                val y = vertices[vertex * 3 + 1]
                val inside = x >= minX && x <= maxX && y >= minY && y <= maxY
                if (inside) remaining++ else keep[vertex] = false
            }
            if (remaining == keptCount) break
            keptCount = remaining
        }
        if (keptCount < 2) return null
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        for (vertex in 0 until vertexCount) {
            if (!keep[vertex]) continue
            minX = min(minX, vertices[vertex * 3])
            minY = min(minY, vertices[vertex * 3 + 1])
            minZ = min(minZ, vertices[vertex * 3 + 2])
            maxX = max(maxX, vertices[vertex * 3])
            maxY = max(maxY, vertices[vertex * 3 + 1])
            maxZ = max(maxZ, vertices[vertex * 3 + 2])
        }
        return floatArrayOf(minX, minY, minZ, maxX, maxY, maxZ)
    }
}
