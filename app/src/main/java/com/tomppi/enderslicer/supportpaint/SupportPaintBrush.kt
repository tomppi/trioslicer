package com.tomppi.enderslicer.supportpaint

import com.tomppi.enderslicer.viewer.StlMesh

/** Expands a paint hit into the set of triangles within the brush radius. */
object SupportPaintBrush {
    fun expand(
        mesh: StlMesh,
        hitX: Float,
        hitY: Float,
        hitZ: Float,
        radiusMm: Float,
    ): Set<Int> {
        val vertices = mesh.interleavedVertices
        val radiusSquared = radiusMm * radiusMm
        val result = mutableSetOf<Int>()
        for (triangle in 0 until mesh.triangleCount) {
            val base = triangle * 18
            val centerX = (vertices[base] + vertices[base + 6] + vertices[base + 12]) / 3f
            val centerY = (vertices[base + 1] + vertices[base + 7] + vertices[base + 13]) / 3f
            val centerZ = (vertices[base + 2] + vertices[base + 8] + vertices[base + 14]) / 3f
            val dx = centerX - hitX
            val dy = centerY - hitY
            val dz = centerZ - hitZ
            if (dx * dx + dy * dy + dz * dz <= radiusSquared) result += triangle
        }
        return result
    }
}
