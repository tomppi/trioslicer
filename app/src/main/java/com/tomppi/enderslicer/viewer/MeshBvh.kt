package com.tomppi.enderslicer.viewer

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Bounding volume hierarchy over a mesh's triangle soup, used to accelerate
 * picking.
 *
 * Picking used to scan the whole mesh: [MeshPicker.pick] looped
 * \`for (triangle in 0 until count)\` running a Möller-Trumbore test on each
 * triangle. On a 280k-triangle STL that is 280k intersection tests per pick,
 * and a paint stroke issues a pick per sample.
 *
 * The hierarchy is built once per mesh, so a pick becomes a handful of node
 * tests plus the triangles inside a few leaves. Positions are read through
 * [VertexData], which keeps large meshes in their off-heap direct buffer
 * instead of copying them back onto the Java heap.
 */
class MeshBvh private constructor(
    private val vertices: VertexData,
    private val order: IntArray,
    private val nodeMin: FloatArray,
    private val nodeMax: FloatArray,
    private val nodeLeft: IntArray,
    private val nodeRight: IntArray,
    private val nodeStart: IntArray,
    private val nodeCount: IntArray,
) {
    data class Hit(val triangleIndex: Int, val x: Float, val y: Float, val z: Float)

    val triangleCount: Int get() = order.size

    /** Number of nodes, exposed for tests and diagnostics. */
    val nodeTotal: Int get() = nodeCount.size

    /**
     * Nearest intersection along the ray, or \`null\` when it misses.
     *
     * [dx], [dy], [dz] must be a unit vector.
     */
    fun raycast(
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
    ): Hit? {
        if (triangleCount == 0) return null
        val invX = 1f / parallelSafe(dx)
        val invY = 1f / parallelSafe(dy)
        val invZ = 1f / parallelSafe(dz)

        var bestT = Float.POSITIVE_INFINITY
        var bestTriangle = -1
        var bestX = 0f
        var bestY = 0f
        var bestZ = 0f

        val stack = IntArray(STACK_DEPTH)
        var stackSize = 0
        stack[stackSize++] = 0
        while (stackSize > 0) {
            val node = stack[--stackSize]
            val tMin = slabEnter(node, ox, oy, oz, invX, invY, invZ, bestT)
            if (tMin > bestT) continue
            val count = nodeCount[node]
            if (count > 0) {
                val start = nodeStart[node]
                for (i in start until start + count) {
                    val triangle = order[i]
                    val t = intersect(triangle, ox, oy, oz, dx, dy, dz) ?: continue
                    if (t > RAY_EPSILON && t < bestT) {
                        bestT = t
                        bestTriangle = triangle
                        bestX = ox + dx * t
                        bestY = oy + dy * t
                        bestZ = oz + dz * t
                    }
                }
            } else {
                // Descend into the nearer child first so the far child is more
                // likely to be rejected by the tightened bestT.
                val left = nodeLeft[node]
                val right = nodeRight[node]
                val dLeft = entryDistance(left, ox, oy, oz, invX, invY, invZ)
                val dRight = entryDistance(right, ox, oy, oz, invX, invY, invZ)
                val near = if (dLeft <= dRight) left else right
                val far = if (dLeft <= dRight) right else left
                if (stackSize + 2 <= stack.size) {
                    stack[stackSize++] = far
                    stack[stackSize++] = near
                }
            }
        }
        if (bestTriangle < 0) return null
        return Hit(bestTriangle, bestX, bestY, bestZ)
    }

    /** Slab test; returns the entry distance, or infinity when the ray misses. */
    private fun slabEnter(
        node: Int, ox: Float, oy: Float, oz: Float,
        invX: Float, invY: Float, invZ: Float, limit: Float,
    ): Float {
        var tMin = 0f
        var tMax = limit
        var t0 = (nodeMin[node * 3] - ox) * invX
        var t1 = (nodeMax[node * 3] - ox) * invX
        if (t0 > t1) { val s = t0; t0 = t1; t1 = s }
        tMin = max(tMin, t0); tMax = min(tMax, t1)
        t0 = (nodeMin[node * 3 + 1] - oy) * invY
        t1 = (nodeMax[node * 3 + 1] - oy) * invY
        if (t0 > t1) { val s = t0; t0 = t1; t1 = s }
        tMin = max(tMin, t0); tMax = min(tMax, t1)
        t0 = (nodeMin[node * 3 + 2] - oz) * invZ
        t1 = (nodeMax[node * 3 + 2] - oz) * invZ
        if (t0 > t1) { val s = t0; t0 = t1; t1 = s }
        tMin = max(tMin, t0); tMax = min(tMax, t1)
        return if (tMax >= tMin) tMin else Float.POSITIVE_INFINITY
    }

    private fun entryDistance(
        node: Int, ox: Float, oy: Float, oz: Float,
        invX: Float, invY: Float, invZ: Float,
    ): Float = slabEnter(node, ox, oy, oz, invX, invY, invZ, Float.POSITIVE_INFINITY)

    /** Möller-Trumbore; returns the ray parameter or null. */
    private fun intersect(
        triangle: Int,
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
    ): Float? {
        val base = triangle * 18
        val ax = vertices[base]; val ay = vertices[base + 1]; val az = vertices[base + 2]
        val bx = vertices[base + 6]; val by = vertices[base + 7]; val bz = vertices[base + 8]
        val cx = vertices[base + 12]; val cy = vertices[base + 13]; val cz = vertices[base + 14]

        val e1x = bx - ax; val e1y = by - ay; val e1z = bz - az
        val e2x = cx - ax; val e2y = cy - ay; val e2z = cz - az
        val px = dy * e2z - dz * e2y
        val py = dz * e2x - dx * e2z
        val pz = dx * e2y - dy * e2x
        val det = e1x * px + e1y * py + e1z * pz
        if (det > -RAY_EPSILON && det < RAY_EPSILON) return null
        val invDet = 1f / det
        val tx = ox - ax; val ty = oy - ay; val tz = oz - az
        val u = (tx * px + ty * py + tz * pz) * invDet
        if (u < 0f || u > 1f) return null
        val qx = ty * e1z - tz * e1y
        val qy = tz * e1x - tx * e1z
        val qz = tx * e1y - ty * e1x
        val v = (dx * qx + dy * qy + dz * qz) * invDet
        if (v < 0f || u + v > 1f) return null
        return (e2x * qx + e2y * qy + e2z * qz) * invDet
    }

    companion object {
        private const val LEAF_TRIANGLES = 8
        private const val MAX_DEPTH = 48
        private const val STACK_DEPTH = 64
        private const val RAY_EPSILON = 1e-7f

        /** A zero component would make the inverse infinite; nudge it instead. */
        private fun parallelSafe(value: Float): Float =
            if (abs(value) < 1e-20f) (if (value < 0f) -1e-20f else 1e-20f) else value

        /**
         * Builds the hierarchy for [mesh]. Cost is O(triangles log triangles)
         * and runs once per loaded model, off the GL thread.
         */
        fun build(mesh: StlMesh): MeshBvh {
            val vertices = mesh.interleavedVertices
            val count = mesh.triangleCount
            val order = IntArray(count) { it }
            val centroidX = FloatArray(count)
            val centroidY = FloatArray(count)
            val centroidZ = FloatArray(count)
            for (t in 0 until count) {
                val base = t * 18
                centroidX[t] = (vertices[base] + vertices[base + 6] + vertices[base + 12]) / 3f
                centroidY[t] = (vertices[base + 1] + vertices[base + 7] + vertices[base + 13]) / 3f
                centroidZ[t] = (vertices[base + 2] + vertices[base + 8] + vertices[base + 14]) / 3f
            }

            val builder = Builder(vertices, order, centroidX, centroidY, centroidZ)
            if (count > 0) builder.buildNode(0, count, 0)
            return MeshBvh(
                vertices = vertices,
                order = order,
                nodeMin = builder.nodeMin.toFloatArray(),
                nodeMax = builder.nodeMax.toFloatArray(),
                nodeLeft = builder.nodeLeft.toIntArray(),
                nodeRight = builder.nodeRight.toIntArray(),
                nodeStart = builder.nodeStart.toIntArray(),
                nodeCount = builder.nodeCount.toIntArray(),
            )
        }

        private class Builder(
            private val vertices: VertexData,
            private val order: IntArray,
            private val centroidX: FloatArray,
            private val centroidY: FloatArray,
            private val centroidZ: FloatArray,
        ) {
            val nodeMin = ArrayList<Float>()
            val nodeMax = ArrayList<Float>()
            val nodeLeft = ArrayList<Int>()
            val nodeRight = ArrayList<Int>()
            val nodeStart = ArrayList<Int>()
            val nodeCount = ArrayList<Int>()

            fun buildNode(from: Int, to: Int, depth: Int): Int {
                val node = nodeCount.size
                var minX = Float.POSITIVE_INFINITY; var minY = Float.POSITIVE_INFINITY; var minZ = Float.POSITIVE_INFINITY
                var maxX = Float.NEGATIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY
                var cMinX = Float.POSITIVE_INFINITY; var cMinY = Float.POSITIVE_INFINITY; var cMinZ = Float.POSITIVE_INFINITY
                var cMaxX = Float.NEGATIVE_INFINITY; var cMaxY = Float.NEGATIVE_INFINITY; var cMaxZ = Float.NEGATIVE_INFINITY
                for (i in from until to) {
                    val t = order[i]
                    val base = t * 18
                    for (v in 0 until 3) {
                        val x = vertices[base + v * 6]
                        val y = vertices[base + v * 6 + 1]
                        val z = vertices[base + v * 6 + 2]
                        if (x < minX) minX = x; if (x > maxX) maxX = x
                        if (y < minY) minY = y; if (y > maxY) maxY = y
                        if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
                    }
                    val cx = centroidX[t]; val cy = centroidY[t]; val cz = centroidZ[t]
                    if (cx < cMinX) cMinX = cx; if (cx > cMaxX) cMaxX = cx
                    if (cy < cMinY) cMinY = cy; if (cy > cMaxY) cMaxY = cy
                    if (cz < cMinZ) cMinZ = cz; if (cz > cMaxZ) cMaxZ = cz
                }
                nodeMin.add(minX); nodeMin.add(minY); nodeMin.add(minZ)
                nodeMax.add(maxX); nodeMax.add(maxY); nodeMax.add(maxZ)

                val extent = to - from
                if (extent <= LEAF_TRIANGLES || depth >= MAX_DEPTH) {
                    nodeLeft.add(-1); nodeRight.add(-1)
                    nodeStart.add(from); nodeCount.add(extent)
                    return node
                }

                val spanX = cMaxX - cMinX; val spanY = cMaxY - cMinY; val spanZ = cMaxZ - cMinZ
                val axis = when {
                    spanX >= spanY && spanX >= spanZ -> 0
                    spanY >= spanZ -> 1
                    else -> 2
                }
                val split = when (axis) {
                    0 -> (cMinX + cMaxX) * 0.5f
                    1 -> (cMinY + cMaxY) * 0.5f
                    else -> (cMinZ + cMaxZ) * 0.5f
                }
                fun valueOf(t: Int): Float = when (axis) {
                    0 -> centroidX[t]
                    1 -> centroidY[t]
                    else -> centroidZ[t]
                }
                var i = from
                var j = to - 1
                while (i <= j) {
                    if (valueOf(order[i]) < split) {
                        i++
                    } else {
                        val tmp = order[i]; order[i] = order[j]; order[j] = tmp
                        j--
                    }
                }
                var mid = i
                // Degenerate partition (all centroids equal on this axis).
                if (mid == from || mid == to) mid = (from + to) / 2

                nodeStart.add(0); nodeCount.add(0)
                nodeLeft.add(-1); nodeRight.add(-1)
                val left = buildNode(from, mid, depth + 1)
                val right = buildNode(mid, to, depth + 1)
                nodeLeft[node] = left
                nodeRight[node] = right
                return node
            }
        }
    }
}
