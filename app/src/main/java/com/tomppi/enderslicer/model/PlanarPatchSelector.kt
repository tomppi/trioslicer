package com.tomppi.enderslicer.model

import com.tomppi.enderslicer.viewer.StlMesh
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Selects the largest connected coplanar surface rather than one tessellation triangle. */
internal object PlanarPatchSelector {
    data class Patch(
        val normal: DoubleArray,
        val centroid: DoubleArray,
        val area: Double,
    )

    fun largest(mesh: StlMesh, linear: List<Double>): Patch {
        require(mesh.triangleCount in 1..MAX_TRIANGLES_FOR_PATCH_GROUPING) {
            "Lay-flat planar grouping supports at most $MAX_TRIANGLES_FOR_PATCH_GROUPING triangles"
        }
        require(linear.size == 9 && linear.all(Double::isFinite)) {
            "Lay-flat transform is invalid"
        }

        val count = mesh.triangleCount
        val normalX = DoubleArray(count)
        val normalY = DoubleArray(count)
        val normalZ = DoubleArray(count)
        val area = DoubleArray(count)
        val centerX = DoubleArray(count)
        val centerY = DoubleArray(count)
        val centerZ = DoubleArray(count)
        val union = UnionFind(count)
        val edges = HashMap<EdgeKey, Int>((count * 4).coerceAtMost(MAX_EDGE_MAP_CAPACITY))
        val values = mesh.interleavedVertices
        var offset = 0

        repeat(count) { triangle ->
            val x0 = transformX(linear, values[offset].toDouble(), values[offset + 1].toDouble(), values[offset + 2].toDouble())
            val y0 = transformY(linear, values[offset].toDouble(), values[offset + 1].toDouble(), values[offset + 2].toDouble())
            val z0 = transformZ(linear, values[offset].toDouble(), values[offset + 1].toDouble(), values[offset + 2].toDouble())
            val x1 = transformX(linear, values[offset + 6].toDouble(), values[offset + 7].toDouble(), values[offset + 8].toDouble())
            val y1 = transformY(linear, values[offset + 6].toDouble(), values[offset + 7].toDouble(), values[offset + 8].toDouble())
            val z1 = transformZ(linear, values[offset + 6].toDouble(), values[offset + 7].toDouble(), values[offset + 8].toDouble())
            val x2 = transformX(linear, values[offset + 12].toDouble(), values[offset + 13].toDouble(), values[offset + 14].toDouble())
            val y2 = transformY(linear, values[offset + 12].toDouble(), values[offset + 13].toDouble(), values[offset + 14].toDouble())
            val z2 = transformZ(linear, values[offset + 12].toDouble(), values[offset + 13].toDouble(), values[offset + 14].toDouble())
            offset += 18

            val ax = x1 - x0
            val ay = y1 - y0
            val az = z1 - z0
            val bx = x2 - x0
            val by = y2 - y0
            val bz = z2 - z0
            var nx = ay * bz - az * by
            var ny = az * bx - ax * bz
            var nz = ax * by - ay * bx
            val twiceArea = sqrt(nx * nx + ny * ny + nz * nz)
            if (twiceArea <= DEGENERATE_EPSILON) return@repeat
            nx /= twiceArea
            ny /= twiceArea
            nz /= twiceArea
            normalX[triangle] = nx
            normalY[triangle] = ny
            normalZ[triangle] = nz
            area[triangle] = twiceArea * 0.5
            centerX[triangle] = (x0 + x1 + x2) / 3.0
            centerY[triangle] = (y0 + y1 + y2) / 3.0
            centerZ[triangle] = (z0 + z1 + z2) / 3.0

            val v0 = vertexKey(x0, y0, z0)
            val v1 = vertexKey(x1, y1, z1)
            val v2 = vertexKey(x2, y2, z2)
            connect(EdgeKey.of(v0, v1), triangle, edges, union, normalX, normalY, normalZ, centerX, centerY, centerZ)
            connect(EdgeKey.of(v1, v2), triangle, edges, union, normalX, normalY, normalZ, centerX, centerY, centerZ)
            connect(EdgeKey.of(v2, v0), triangle, edges, union, normalX, normalY, normalZ, centerX, centerY, centerZ)
        }

        val patchArea = DoubleArray(count)
        val patchNormalX = DoubleArray(count)
        val patchNormalY = DoubleArray(count)
        val patchNormalZ = DoubleArray(count)
        val patchCenterX = DoubleArray(count)
        val patchCenterY = DoubleArray(count)
        val patchCenterZ = DoubleArray(count)
        val referenceTriangle = IntArray(count) { -1 }

        repeat(count) { triangle ->
            val triangleArea = area[triangle]
            if (triangleArea <= 0.0) return@repeat
            val root = union.find(triangle)
            val reference = referenceTriangle[root]
            if (reference < 0) referenceTriangle[root] = triangle
            val sign = if (
                reference >= 0 &&
                normalX[triangle] * normalX[reference] +
                    normalY[triangle] * normalY[reference] +
                    normalZ[triangle] * normalZ[reference] < 0.0
            ) {
                -1.0
            } else {
                1.0
            }
            patchArea[root] += triangleArea
            patchNormalX[root] += normalX[triangle] * triangleArea * sign
            patchNormalY[root] += normalY[triangle] * triangleArea * sign
            patchNormalZ[root] += normalZ[triangle] * triangleArea * sign
            patchCenterX[root] += centerX[triangle] * triangleArea
            patchCenterY[root] += centerY[triangle] * triangleArea
            patchCenterZ[root] += centerZ[triangle] * triangleArea
        }

        var bestRoot = -1
        var bestArea = 0.0
        repeat(count) { root ->
            if (union.find(root) == root && patchArea[root] > bestArea) {
                bestArea = patchArea[root]
                bestRoot = root
            }
        }
        require(bestRoot >= 0 && bestArea > DEGENERATE_EPSILON) {
            "No usable planar face was found for lay flat"
        }

        var nx = patchNormalX[bestRoot]
        var ny = patchNormalY[bestRoot]
        var nz = patchNormalZ[bestRoot]
        val length = sqrt(nx * nx + ny * ny + nz * nz)
        require(length > DEGENERATE_EPSILON) { "Largest planar face has no stable normal" }
        nx /= length
        ny /= length
        nz /= length
        return Patch(
            normal = doubleArrayOf(nx, ny, nz),
            centroid = doubleArrayOf(
                patchCenterX[bestRoot] / bestArea,
                patchCenterY[bestRoot] / bestArea,
                patchCenterZ[bestRoot] / bestArea,
            ),
            area = bestArea,
        )
    }

    private fun connect(
        edge: EdgeKey,
        triangle: Int,
        edges: MutableMap<EdgeKey, Int>,
        union: UnionFind,
        normalX: DoubleArray,
        normalY: DoubleArray,
        normalZ: DoubleArray,
        centerX: DoubleArray,
        centerY: DoubleArray,
        centerZ: DoubleArray,
    ) {
        val adjacent = edges.putIfAbsent(edge, triangle) ?: return
        val dot = normalX[triangle] * normalX[adjacent] +
            normalY[triangle] * normalY[adjacent] +
            normalZ[triangle] * normalZ[adjacent]
        if (abs(dot) < COPLANAR_NORMAL_DOT) return
        val planeDistance = abs(
            normalX[adjacent] * (centerX[triangle] - centerX[adjacent]) +
                normalY[adjacent] * (centerY[triangle] - centerY[adjacent]) +
                normalZ[adjacent] * (centerZ[triangle] - centerZ[adjacent]),
        )
        if (planeDistance <= COPLANAR_DISTANCE_MM) union.union(triangle, adjacent)
    }

    private fun vertexKey(x: Double, y: Double, z: Double): VertexKey = VertexKey(
        (x / POSITION_TOLERANCE_MM).roundToLong(),
        (y / POSITION_TOLERANCE_MM).roundToLong(),
        (z / POSITION_TOLERANCE_MM).roundToLong(),
    )

    private fun transformX(matrix: List<Double>, x: Double, y: Double, z: Double): Double =
        matrix[0] * x + matrix[1] * y + matrix[2] * z

    private fun transformY(matrix: List<Double>, x: Double, y: Double, z: Double): Double =
        matrix[3] * x + matrix[4] * y + matrix[5] * z

    private fun transformZ(matrix: List<Double>, x: Double, y: Double, z: Double): Double =
        matrix[6] * x + matrix[7] * y + matrix[8] * z

    private data class VertexKey(val x: Long, val y: Long, val z: Long) : Comparable<VertexKey> {
        override fun compareTo(other: VertexKey): Int {
            if (x != other.x) return x.compareTo(other.x)
            if (y != other.y) return y.compareTo(other.y)
            return z.compareTo(other.z)
        }
    }

    private data class EdgeKey(
        val ax: Long,
        val ay: Long,
        val az: Long,
        val bx: Long,
        val by: Long,
        val bz: Long,
    ) {
        companion object {
            fun of(first: VertexKey, second: VertexKey): EdgeKey {
                val (a, b) = if (first <= second) first to second else second to first
                return EdgeKey(a.x, a.y, a.z, b.x, b.y, b.z)
            }
        }
    }

    private class UnionFind(size: Int) {
        private val parent = IntArray(size) { it }
        private val rank = ByteArray(size)

        fun find(value: Int): Int {
            var root = value
            while (parent[root] != root) root = parent[root]
            var current = value
            while (parent[current] != current) {
                val next = parent[current]
                parent[current] = root
                current = next
            }
            return root
        }

        fun union(first: Int, second: Int) {
            var firstRoot = find(first)
            var secondRoot = find(second)
            if (firstRoot == secondRoot) return
            if (rank[firstRoot] < rank[secondRoot]) {
                val swap = firstRoot
                firstRoot = secondRoot
                secondRoot = swap
            }
            parent[secondRoot] = firstRoot
            if (rank[firstRoot] == rank[secondRoot]) rank[firstRoot] = (rank[firstRoot] + 1).toByte()
        }
    }

    private const val MAX_TRIANGLES_FOR_PATCH_GROUPING = 250_000
    private const val MAX_EDGE_MAP_CAPACITY = 1_000_000
    private const val POSITION_TOLERANCE_MM = 0.0001
    private const val COPLANAR_DISTANCE_MM = 0.002
    private val COPLANAR_NORMAL_DOT = cos(Math.toRadians(1.0))
    private const val DEGENERATE_EPSILON = 1e-12
}
