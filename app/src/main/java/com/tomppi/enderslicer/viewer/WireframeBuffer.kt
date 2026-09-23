package com.tomppi.enderslicer.viewer

/**
 * A mesh's geometry as the result view draws it: a flat pair of arrays,
 * positions and a colour per vertex, with [verticesPerTriangle] deciding the
 * shape — three vertices for translucent region surfaces, six for the same
 * geometry's edges when a wireframe is wanted instead.
 *
 * Nothing here knows about OpenGL: the renderer uploads [positions] and
 * [colors] as two buffers and draws [vertexCount] vertices with GL_TRIANGLES or
 * GL_LINES according to the shape. A triangle that refers to a vertex that is
 * not there is dropped rather than drawn at the origin, so a malformed region
 * cannot crash the viewer.
 */
class WireframeBuffer(
    /** [verticesPerTriangle] vertices of three floats each per triangle. */
    val positions: FloatArray,
    /** The same vertices, each repeating its triangle's RGB. */
    val colors: FloatArray,
    /** 3 for surfaces, 6 for edges. */
    private val verticesPerTriangle: Int = SURFACE_VERTICES,
) {
    /** Triangles this buffer holds. */
    val triangleCount: Int get() = positions.size / (VERTEX_FLOATS * verticesPerTriangle)

    /** The count for the draw call. */
    val vertexCount: Int get() = triangleCount * verticesPerTriangle

    val isEmpty: Boolean get() = triangleCount == 0

    companion object {
        private const val VERTEX_FLOATS = 3
        private const val SURFACE_VERTICES = 3
        private const val EDGE_VERTICES = 6

        /**
         * One buffer holding several meshes — the shape the result view needs,
         * where every optimized region is its own little mesh. An empty list
         * yields an empty buffer, so the caller needs no special case.
         */
        fun concat(parts: List<WireframeBuffer>): WireframeBuffer {
            var floats = 0
            parts.forEach { part -> floats += part.positions.size }
            // Every part has to be the same shape; an empty list stays a surface.
            val shape = parts.firstOrNull { !it.isEmpty }?.verticesPerTriangle ?: SURFACE_VERTICES
            if (floats == 0) return WireframeBuffer(FloatArray(0), FloatArray(0), shape)
            require(parts.all { it.isEmpty || it.verticesPerTriangle == shape }) {
                "Cannot join surfaces and edges into one buffer"
            }
            val positions = FloatArray(floats)
            val colors = FloatArray(floats)
            var at = 0
            parts.forEach { part ->
                part.positions.copyInto(positions, at)
                part.colors.copyInto(colors, at)
                at += part.positions.size
            }
            return WireframeBuffer(positions, colors, shape)
        }

        /**
         * From an indexed mesh as plain triangles, each carrying its mesh
         * colour: the optimized regions as translucent surfaces.
         */
        fun triangles(positions: FloatArray, indices: IntArray, colors: FloatArray): WireframeBuffer {
            val triangles = indices.size / 3
            require(colors.size == triangles * 3) {
                "Expected one colour per triangle: ${colors.size} floats for $triangles triangles"
            }
            var drawn = 0
            for (triangle in 0 until triangles) {
                if (usable(indices[triangle * 3], positions) &&
                    usable(indices[triangle * 3 + 1], positions) &&
                    usable(indices[triangle * 3 + 2], positions)
                ) {
                    drawn++
                }
            }
            val out = FloatArray(drawn * VERTEX_FLOATS * SURFACE_VERTICES)
            val outColors = FloatArray(drawn * VERTEX_FLOATS * SURFACE_VERTICES)
            var write = 0
            for (triangle in 0 until triangles) {
                val a = indices[triangle * 3]
                val b = indices[triangle * 3 + 1]
                val c = indices[triangle * 3 + 2]
                if (!usable(a, positions) || !usable(b, positions) || !usable(c, positions)) continue
                val colour = triangle * 3
                for (vertex in intArrayOf(a, b, c)) {
                    val offset = vertex * 3
                    out[write] = positions[offset]
                    out[write + 1] = positions[offset + 1]
                    out[write + 2] = positions[offset + 2]
                    outColors[write] = colors[colour]
                    outColors[write + 1] = colors[colour + 1]
                    outColors[write + 2] = colors[colour + 2]
                    write += VERTEX_FLOATS
                }
            }
            return WireframeBuffer(out, outColors)
        }

        /**
         * From a triangle soup: nine floats per triangle, [colors] three per
         * triangle. An empty mesh yields an empty buffer rather than a failure.
         */
        fun soup(positions: FloatArray, colors: FloatArray): WireframeBuffer {
            val triangles = positions.size / 9
            require(colors.size == triangles * 3) {
                "Expected one colour per triangle: ${colors.size} floats for $triangles triangles"
            }
            val offsets = IntArray(triangles * 3) { slot ->
                // The whole triangle is present with a 9-float layout, or it is
                // dropped: a trailing partial triangle cannot be drawn.
                val triangle = slot / 3
                if (positions.size >= (triangle + 1) * 9) triangle * 9 + (slot % 3) * 3 else -1
            }
            return build(offsets, positions, colors)
        }

        /**
         * From an indexed mesh, the shape the optimizer reports its regions in.
         * A triangle referring to a vertex that is not there is dropped rather
         * than drawn at the origin.
         */
        fun indexed(positions: FloatArray, indices: IntArray, colors: FloatArray): WireframeBuffer {
            val triangles = indices.size / 3
            require(colors.size == triangles * 3) {
                "Expected one colour per triangle: ${colors.size} floats for $triangles triangles"
            }
            val offsets = IntArray(triangles * 3) { slot ->
                val vertex = indices[slot]
                val offset = vertex * 3
                if (usable(vertex, positions)) offset else -1
            }
            return build(offsets, positions, colors)
        }

        private fun usable(vertex: Int, positions: FloatArray): Boolean {
            val offset = vertex * 3
            return vertex >= 0 && offset + 2 < positions.size
        }

        private fun build(
            offsets: IntArray,
            positions: FloatArray,
            colors: FloatArray,
        ): WireframeBuffer {
            val triangles = offsets.size / 3
            var drawn = 0
            for (triangle in 0 until triangles) {
                val base = triangle * 3
                if (offsets[base] >= 0 && offsets[base + 1] >= 0 && offsets[base + 2] >= 0) {
                    drawn++
                }
            }
            val out = FloatArray(drawn * VERTEX_FLOATS * EDGE_VERTICES)
            val outColors = FloatArray(drawn * VERTEX_FLOATS * EDGE_VERTICES)
            // v0-v1, v1-v2, v2-v0, written without allocating per triangle.
            val order = IntArray(EDGE_VERTICES)
            var write = 0
            for (triangle in 0 until triangles) {
                val a = offsets[triangle * 3]
                val b = offsets[triangle * 3 + 1]
                val c = offsets[triangle * 3 + 2]
                if (a < 0 || b < 0 || c < 0) continue
                val colour = triangle * 3
                val r = colors[colour]
                val g = colors[colour + 1]
                val bl = colors[colour + 2]
                order[0] = a; order[1] = b; order[2] = b
                order[3] = c; order[4] = c; order[5] = a
                for (offset in order) {
                    out[write] = positions[offset]
                    out[write + 1] = positions[offset + 1]
                    out[write + 2] = positions[offset + 2]
                    outColors[write] = r
                    outColors[write + 1] = g
                    outColors[write + 2] = bl
                    write += VERTEX_FLOATS
                }
            }
            return WireframeBuffer(out, outColors, EDGE_VERTICES)
        }
    }
}
