package com.tomppi.enderslicer.viewer

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds a [StlMesh] one triangle at a time in the viewer's interleaved
 * position + normal layout: six floats per vertex, eighteen per triangle.
 *
 * A triangle's normal is its own face normal, taken from its winding - counter
 * clockwise seen from outside, the convention every mesh in the app already
 * follows and the one Manifold returns. Boolean results are position-only and
 * the generated joints are flat faced, so a face normal is the honest answer;
 * no smooth vertex normal is invented here.
 *
 * A result with at least [OFF_HEAP_MIN_TRIANGLES] triangles goes to a direct
 * buffer exactly like a clipped one does, so a mesh built here is the same kind
 * of object as a mesh that came out of a cut.
 */
internal class MeshSolidBuilder(private val displayName: String) {
    private var floats = FloatArray(INITIAL_CAPACITY)
    private var written = 0
    private var triangles = 0
    private var minX = Float.POSITIVE_INFINITY
    private var minY = Float.POSITIVE_INFINITY
    private var minZ = Float.POSITIVE_INFINITY
    private var maxX = Float.NEGATIVE_INFINITY
    private var maxY = Float.NEGATIVE_INFINITY
    private var maxZ = Float.NEGATIVE_INFINITY

    val triangleCount: Int get() = triangles

    /** Appends one triangle from three positions; the normal follows the winding. */
    fun addTriangle(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
    ) {
        var nx = (by - ay) * (cz - az) - (bz - az) * (cy - ay)
        var ny = (bz - az) * (cx - ax) - (bx - ax) * (cz - az)
        var nz = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
        val length = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
        if (length > 0f) {
            nx /= length
            ny /= length
            nz /= length
        } else {
            nx = 0f
            ny = 0f
            nz = 0f
        }
        ensure(FLOATS_PER_TRIANGLE)
        put(ax); put(ay); put(az); put(nx); put(ny); put(nz)
        put(bx); put(by); put(bz); put(nx); put(ny); put(nz)
        put(cx); put(cy); put(cz); put(nx); put(ny); put(nz)
        triangles++
        grow(ax, ay, az)
        grow(bx, by, bz)
        grow(cx, cy, cz)
    }

    /** Copies one triangle - normals and all - out of an existing interleaved mesh. */
    fun addInterleaved(source: VertexData, triangle: Int) {
        val base = triangle * FLOATS_PER_TRIANGLE
        ensure(FLOATS_PER_TRIANGLE)
        for (index in 0 until FLOATS_PER_TRIANGLE) floats[written + index] = source[base + index]
        written += FLOATS_PER_TRIANGLE
        triangles++
        for (corner in 0 until VERTICES_PER_TRIANGLE) {
            val at = base + corner * FLOATS_PER_VERTEX
            grow(source[at], source[at + 1], source[at + 2])
        }
    }

    /** The mesh so far; empty when nothing was added. */
    fun build(): StlMesh {
        val complete = if (written == floats.size) floats else floats.copyOf(written)
        val vertices = if (triangles >= OFF_HEAP_MIN_TRIANGLES) {
            val direct = ByteBuffer.allocateDirect(complete.size * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            direct.put(complete, 0, complete.size)
            direct.position(0)
            VertexData.fromDirect(direct)
        } else {
            VertexData.fromArray(complete)
        }
        return StlMesh(
            displayName = displayName,
            interleavedVertices = vertices,
            triangleCount = triangles,
            bounds = if (triangles == 0) {
                MeshBounds(0f, 0f, 0f, 0f, 0f, 0f)
            } else {
                MeshBounds(minX, minY, minZ, maxX, maxY, maxZ)
            },
        )
    }

    private fun put(value: Float) {
        floats[written++] = value
    }

    private fun grow(x: Float, y: Float, z: Float) {
        if (x < minX) minX = x
        if (y < minY) minY = y
        if (z < minZ) minZ = z
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
        if (z > maxZ) maxZ = z
    }

    private fun ensure(count: Int) {
        if (written + count <= floats.size) return
        var capacity = floats.size * 2
        while (capacity < written + count) capacity *= 2
        floats = floats.copyOf(capacity)
    }

    companion object {
        const val FLOATS_PER_VERTEX = 6
        const val FLOATS_PER_TRIANGLE = 18
        private const val VERTICES_PER_TRIANGLE = 3
        private const val INITIAL_CAPACITY = 18 * 64
    }
}
