package com.tomppi.enderslicer.viewer

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cuts a placed mesh at the build plate's Z=0 plane and keeps the part above it.
 *
 * This is what makes a model that hangs below the bed sliceable: the user may
 * drag an object down through the plate, the viewer keeps drawing the whole
 * thing so they can see what they are cutting, and only the geometry the engine
 * is handed is clipped. The cross-section is left open - the slicer treats the
 * first layer as the bottom, which is exactly "the stuff above is the new
 * bottom" - so nothing is capped and the removed volume is not re-sealed.
 *
 * A triangle that crosses the plane is replaced by the part of it above the
 * plane: one triangle when it keeps one or two of its corners, two when it
 * keeps two and gains a crossing. Crossing points are interpolated along the
 * crossing edge, vertex normals included, and every replacement triangle is
 * written in the original's own edge order, so the winding is preserved.
 */
object BedClipper {
    /**
     * The mesh [mesh] clipped to Z >= 0, or [mesh] itself when it has no
     * geometry below the plane.
     *
     * The identity return is the guarantee that a model sitting on or above the
     * bed goes on to be staged byte-for-byte as before: nothing is copied,
     * nothing is rewritten, and no clipped file is produced.
     *
     * The clipped result has no retained precision slice source: that source is
     * the *unclipped* original geometry, so handing it to CuraEngine with its
     * affine sidecar would put the below-bed part back into the engine. A
     * clipped object is sliced from the clipped vertices themselves.
     *
     * A result with [StlMesh.triangleCount] == 0 means the whole model was below
     * the plane. The caller must not stage it: there is nothing to print, and an
     * empty mesh is refused by [StlMeshWriter].
     */
    fun clipToBed(mesh: StlMesh): StlMesh {
        // A hair under a nanometre of slack: the placed mesh is float, so a model
        // that sits exactly on the bed can land on a minZ of -1e-7. Treating that
        // as "below the bed" would re-triangulate the whole bottom surface for a
        // difference no printer can move, so such a mesh is returned untouched -
        // and the caller's own < 0 test is what decides to call this at all.
        if (mesh.bounds.minZ >= -PLANE_EPSILON_MM) return mesh
        require(mesh.triangleCount > 0 && mesh.interleavedVertices.size == mesh.triangleCount * FLOATS_PER_TRIANGLE) {
            "Bed clipping needs complete model geometry"
        }

        // A triangle that crosses the plane keeps at most two of its own: one
        // that does not stays one. The buffer is sized from that ceiling, and
        // the count that comes out is what the emitted triangles add up to.
        var capacity = 0
        forEachTriangle(mesh) { triangle ->
            capacity += if (crossesThePlane(triangle)) MAX_TRIANGLES_PER_CUT else 1
        }

        val floats = FloatArray(Math.multiplyExact(capacity, FLOATS_PER_TRIANGLE))
        var written = 0
        var keptTriangles = 0
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY

        /** Appends one vertex, at its own height above the plane. */
        fun keep(vertex: ClipVertex) {
            // A corner the tolerance already reads as on the plane is written as
            // exactly 0, so float rounding cannot carry a negative Z to the
            // engine. Everything above the plane keeps its own height.
            val z = if (vertex.z >= 0f) vertex.z else 0f
            floats[written++] = vertex.x
            floats[written++] = vertex.y
            floats[written++] = z
            floats[written++] = vertex.nx
            floats[written++] = vertex.ny
            floats[written++] = vertex.nz
            minX = minOf(minX, vertex.x)
            minY = minOf(minY, vertex.y)
            minZ = minOf(minZ, z)
            maxX = maxOf(maxX, vertex.x)
            maxY = maxOf(maxY, vertex.y)
            maxZ = maxOf(maxZ, z)
        }

        fun keepTriangle(first: ClipVertex, second: ClipVertex, third: ClipVertex) {
            keep(first)
            keep(second)
            keep(third)
            keptTriangles++
        }

        forEachTriangle(mesh) { triangle ->
            val a = ClipVertex.from(triangle, 0)
            val b = ClipVertex.from(triangle, 1)
            val c = ClipVertex.from(triangle, 2)
            val belowA = strictlyBelow(a.z)
            val belowB = strictlyBelow(b.z)
            val belowC = strictlyBelow(c.z)

            // One corner below: the edges that leave it are cut, and what is
            // left is that corner's two neighbours and the two crossings,
            // around the same loop the triangle walked.
            if (belowA && !belowB && !belowC) {
                val onAB = cut(a, b)
                val onCA = cut(a, c)
                keepTriangle(b, c, onCA)
                keepTriangle(b, onCA, onAB)
            } else if (belowB && !belowC && !belowA) {
                val onAB = cut(b, a)
                val onBC = cut(b, c)
                keepTriangle(c, a, onAB)
                keepTriangle(c, onAB, onBC)
            } else if (belowC && !belowA && !belowB) {
                val onBC = cut(c, b)
                val onCA = cut(c, a)
                keepTriangle(a, b, onBC)
                keepTriangle(a, onBC, onCA)
            } else if (belowA && belowB && !belowC) {
                // Two corners below: the one above and the two crossings make
                // the whole of what is left, still in the triangle's own order.
                keepTriangle(c, cut(c, a), cut(c, b))
            } else if (belowB && belowC && !belowA) {
                keepTriangle(a, cut(a, b), cut(a, c))
            } else if (belowC && belowA && !belowB) {
                keepTriangle(b, cut(b, c), cut(b, a))
            } else {
                // Nothing below the plane: copied across unchanged. A triangle
                // lying in the plane is copied too - it has no part to cut. The
                // last case, all three below, falls through to nothing.
                if (!belowA) keepTriangle(a, b, c)
            }
        }
        check(written == keptTriangles * FLOATS_PER_TRIANGLE && written <= floats.size) {
            "Bed clipping wrote an unexpected number of vertices"
        }

        val vertices = if (keptTriangles >= OFF_HEAP_MIN_TRIANGLES) {
            val direct = ByteBuffer.allocateDirect(written * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            direct.put(floats, 0, written)
            direct.position(0)
            VertexData.fromDirect(direct)
        } else {
            VertexData.fromArray(if (written == floats.size) floats else floats.copyOf(written))
        }
        return StlMesh(
            displayName = mesh.displayName,
            interleavedVertices = vertices,
            triangleCount = keptTriangles,
            bounds = if (keptTriangles == 0) EMPTY_BOUNDS else MeshBounds(minX, minY, minZ, maxX, maxY, maxZ),
        )
    }

    /** True when any corner of the triangle is under the plane. */
    private fun crossesThePlane(triangle: FloatArray): Boolean =
        strictlyBelow(triangle[2]) || strictlyBelow(triangle[8]) || strictlyBelow(triangle[14])

    /**
     * True when a corner at [z] is under the plane by more than the tolerance.
     *
     * The tolerance is what keeps a placed mesh from being re-triangulated over
     * float dust: a model that sits exactly on the bed can land a millionth of a
     * millimetre below it, and that is a corner on the plane, not under it.
     */
    private fun strictlyBelow(z: Float): Boolean = z < -PLANE_EPSILON_MM

    /**
     * Where the edge from [below] to [above] meets the plane.
     *
     * [below] is strictly under the plane and [above] is not, so the walk from
     * one to the other crosses Z=0 exactly once, at a parameter in [0, 1]: the
     * returned position is a convex combination of the two vertices, which keeps
     * it inside the mesh's own bounds, and its Z is exactly 0. The vertex normal
     * is interpolated the same way.
     */
    private fun cut(below: ClipVertex, above: ClipVertex): ClipVertex {
        val fraction = (-below.z / (above.z - below.z)).coerceIn(0f, 1f)
        return ClipVertex(
            x = below.x + (above.x - below.x) * fraction,
            y = below.y + (above.y - below.y) * fraction,
            z = 0f,
            nx = below.nx + (above.nx - below.nx) * fraction,
            ny = below.ny + (above.ny - below.ny) * fraction,
            nz = below.nz + (above.nz - below.nz) * fraction,
        )
    }

    private inline fun forEachTriangle(mesh: StlMesh, block: (FloatArray) -> Unit) {
        val vertices = mesh.interleavedVertices
        val triangle = FloatArray(FLOATS_PER_TRIANGLE)
        var offset = 0
        repeat(mesh.triangleCount) {
            for (index in 0 until FLOATS_PER_TRIANGLE) triangle[index] = vertices[offset + index]
            block(triangle)
            offset += FLOATS_PER_TRIANGLE
        }
    }

    /** One vertex of the 18-float interleaved position+normal layout. */
    private class ClipVertex(
        val x: Float,
        val y: Float,
        val z: Float,
        val nx: Float,
        val ny: Float,
        val nz: Float,
    ) {
        companion object {
            fun from(triangle: FloatArray, slot: Int): ClipVertex {
                val base = slot * 6
                return ClipVertex(
                    x = triangle[base],
                    y = triangle[base + 1],
                    z = triangle[base + 2],
                    nx = triangle[base + 3],
                    ny = triangle[base + 4],
                    nz = triangle[base + 5],
                )
            }
        }
    }

    private const val FLOATS_PER_TRIANGLE = 18

    /** The most triangles one clipped triangle can become: two crossings. */
    private const val MAX_TRIANGLES_PER_CUT = 2
    private const val PLANE_EPSILON_MM = 1e-6f
    private val EMPTY_BOUNDS = MeshBounds(0f, 0f, 0f, 0f, 0f, 0f)
}
