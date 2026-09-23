package com.tomppi.enderslicer.conical

import com.tomppi.enderslicer.engine.checkCancellation
import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.VertexData
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pure geometric core of the EasyConical forward transform: optional midpoint
 * triangle refinement followed by a cone warp around a chosen XY centre.
 *
 * The interleaved STL layout is 18 floats per triangle (3 vertices of
 * `(x, y, z, nx, ny, nz)`); every produced triangle carries a normal recomputed
 * from its own geometry, matching the StlParser/StlMeshWriter contract.
 */
internal object ConicalTransform {
    /** Forward cone transform: `(x', y', z')` around a chosen XY centre. */
    fun forward(
        x: Double,
        y: Double,
        z: Double,
        centerX: Double,
        centerY: Double,
        settings: ConicalSettings,
    ): DoubleArray {
        val dx = x - centerX
        val dy = y - centerY
        val radius = hypot(dx, dy)
        return doubleArrayOf(
            centerX + dx * settings.inverseCosine,
            centerY + dy * settings.inverseCosine,
            z + settings.coneType.sign * radius * settings.tangent,
        )
    }

    /** Inverse of [forward], restoring the original coordinates. */
    fun inverse(
        x: Double,
        y: Double,
        z: Double,
        centerX: Double,
        centerY: Double,
        settings: ConicalSettings,
    ): DoubleArray {
        val dx = x - centerX
        val dy = y - centerY
        val ox = centerX + dx * settings.cosine
        val oy = centerY + dy * settings.cosine
        val radius = hypot(ox - centerX, oy - centerY)
        return doubleArrayOf(ox, oy, z - settings.coneType.sign * radius * settings.tangent)
    }

    /** Midpoint subdivision: `4^iterations` times the source triangle count. */
    fun refine(mesh: StlMesh, iterations: Int): StlMesh {
        require(iterations >= 0) { "Refinement iterations must be non-negative" }
        var current = mesh
        repeat(iterations) { current = refineOnce(current) }
        return current
    }

    private fun refineOnce(mesh: StlMesh): StlMesh {
        val input = mesh.interleavedVertices
        val output = FloatArray(Math.multiplyExact(mesh.triangleCount, 72))
        var inOffset = 0
        var outOffset = 0
        val bounds = BoundsAccumulator()
        repeat(mesh.triangleCount) { triangleIndex ->
            checkCancellation(triangleIndex, "Conical slicing")
            val x0 = input[inOffset].toDouble()
            val y0 = input[inOffset + 1].toDouble()
            val z0 = input[inOffset + 2].toDouble()
            val x1 = input[inOffset + 6].toDouble()
            val y1 = input[inOffset + 7].toDouble()
            val z1 = input[inOffset + 8].toDouble()
            val x2 = input[inOffset + 12].toDouble()
            val y2 = input[inOffset + 13].toDouble()
            val z2 = input[inOffset + 14].toDouble()

            val m12x = (x0 + x1) / 2.0
            val m12y = (y0 + y1) / 2.0
            val m12z = (z0 + z1) / 2.0
            val m23x = (x1 + x2) / 2.0
            val m23y = (y1 + y2) / 2.0
            val m23z = (z1 + z2) / 2.0
            val m31x = (x2 + x0) / 2.0
            val m31y = (y2 + y0) / 2.0
            val m31z = (z2 + z0) / 2.0

            emitTriangle(output, outOffset, x0, y0, z0, m12x, m12y, m12z, m31x, m31y, m31z, bounds)
            outOffset += 18
            emitTriangle(output, outOffset, x1, y1, z1, m23x, m23y, m23z, m12x, m12y, m12z, bounds)
            outOffset += 18
            emitTriangle(output, outOffset, x2, y2, z2, m31x, m31y, m31z, m23x, m23y, m23z, bounds)
            outOffset += 18
            emitTriangle(output, outOffset, m12x, m12y, m12z, m23x, m23y, m23z, m31x, m31y, m31z, bounds)
            outOffset += 18
            inOffset += 18
        }
        return StlMesh(
            displayName = mesh.displayName,
            interleavedVertices = VertexData.fromArray(output),
            triangleCount = Math.multiplyExact(mesh.triangleCount, 4),
            bounds = bounds.finish(),
        )
    }

    /** Warp around the mesh's own XY bounds centre. */
    fun warp(mesh: StlMesh, settings: ConicalSettings): StlMesh =
        warpAround(mesh, mesh.bounds.centerX.toDouble(), mesh.bounds.centerY.toDouble(), settings)

    fun warpAround(mesh: StlMesh, centerX: Double, centerY: Double, settings: ConicalSettings): StlMesh {
        val input = mesh.interleavedVertices
        val output = FloatArray(input.size)
        val bounds = BoundsAccumulator()
        var offset = 0
        repeat(mesh.triangleCount) { triangleIndex ->
            checkCancellation(triangleIndex, "Conical slicing")
            val x0 = input[offset].toDouble()
            val y0 = input[offset + 1].toDouble()
            val z0 = input[offset + 2].toDouble()
            val x1 = input[offset + 6].toDouble()
            val y1 = input[offset + 7].toDouble()
            val z1 = input[offset + 8].toDouble()
            val x2 = input[offset + 12].toDouble()
            val y2 = input[offset + 13].toDouble()
            val z2 = input[offset + 14].toDouble()

            val p0 = forward(x0, y0, z0, centerX, centerY, settings)
            val p1 = forward(x1, y1, z1, centerX, centerY, settings)
            val p2 = forward(x2, y2, z2, centerX, centerY, settings)
            emitTriangle(output, offset, p0[0], p0[1], p0[2], p1[0], p1[1], p1[2], p2[0], p2[1], p2[2], bounds)
            offset += 18
        }
        return StlMesh(
            displayName = mesh.displayName,
            interleavedVertices = VertexData.fromArray(output),
            triangleCount = mesh.triangleCount,
            bounds = bounds.finish(),
        )
    }

    private fun emitTriangle(
        output: FloatArray,
        offset: Int,
        x0: Double,
        y0: Double,
        z0: Double,
        x1: Double,
        y1: Double,
        z1: Double,
        x2: Double,
        y2: Double,
        z2: Double,
        bounds: BoundsAccumulator,
    ) {
        val ax = x1 - x0
        val ay = y1 - y0
        val az = z1 - z0
        val bx = x2 - x0
        val by = y2 - y0
        val bz = z2 - z0
        var nx = ay * bz - az * by
        var ny = az * bx - ax * bz
        var nz = ax * by - ay * bx
        val length = sqrt(nx * nx + ny * ny + nz * nz)
        if (length > 1e-12) {
            nx /= length
            ny /= length
            nz /= length
        } else {
            nx = 0.0
            ny = 0.0
            nz = 0.0
        }
        val nxf = nx.toFloat()
        val nyf = ny.toFloat()
        val nzf = nz.toFloat()

        fun writeVertex(base: Int, x: Double, y: Double, z: Double) {
            val xf = x.toFloat()
            val yf = y.toFloat()
            val zf = z.toFloat()
            output[base] = xf
            output[base + 1] = yf
            output[base + 2] = zf
            output[base + 3] = nxf
            output[base + 4] = nyf
            output[base + 5] = nzf
            bounds.include(xf, yf, zf)
        }
        writeVertex(offset, x0, y0, z0)
        writeVertex(offset + 6, x1, y1, z1)
        writeVertex(offset + 12, x2, y2, z2)
    }

    private class BoundsAccumulator {
        private var minX = Float.POSITIVE_INFINITY
        private var minY = Float.POSITIVE_INFINITY
        private var minZ = Float.POSITIVE_INFINITY
        private var maxX = Float.NEGATIVE_INFINITY
        private var maxY = Float.NEGATIVE_INFINITY
        private var maxZ = Float.NEGATIVE_INFINITY

        fun include(x: Float, y: Float, z: Float) {
            minX = min(minX, x)
            minY = min(minY, y)
            minZ = min(minZ, z)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
            maxZ = max(maxZ, z)
        }

        fun finish(): MeshBounds {
            require(minX.isFinite()) { "Transformed mesh bounds could not be calculated" }
            return MeshBounds(minX, minY, minZ, maxX, maxY, maxZ)
        }
    }
}
