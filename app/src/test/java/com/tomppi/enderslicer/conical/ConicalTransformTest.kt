package com.tomppi.enderslicer.conical

import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.VertexData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConicalTransformTest {
    @Test
    fun forwardThenInverseRestoresTheOriginalPoint() {
        val settings = ConicalSettings(coneAngleDegrees = 16.0, coneType = ConeType.OUTWARD)
        val centerX = 110.0
        val centerY = 90.0
        val point = doubleArrayOf(centerX + 20.0, centerY - 15.0, 7.5)

        val warped = ConicalTransform.forward(
            point[0], point[1], point[2], centerX, centerY, settings,
        )
        val restored = ConicalTransform.inverse(
            warped[0], warped[1], warped[2], centerX, centerY, settings,
        )

        assertEquals(point[0], restored[0], 1e-9)
        assertEquals(point[1], restored[1], 1e-9)
        assertEquals(point[2], restored[2], 1e-9)
    }

    @Test
    fun outwardForwardStretchesAndLiftsRadially() {
        val settings = ConicalSettings(coneAngleDegrees = 16.0, coneType = ConeType.OUTWARD)
        val centerX = 0.0
        val centerY = 0.0

        val result = ConicalTransform.forward(10.0, 0.0, 0.0, centerX, centerY, settings)

        assertTrue("Outward stretch must exceed the input radius", result[0] > 10.0)
        assertTrue("Outward lift must raise the point", result[2] > 0.0)
        assertEquals(10.0 / Math.cos(Math.toRadians(16.0)), result[0], 1e-9)
        assertEquals(10.0 * Math.tan(Math.toRadians(16.0)), result[2], 1e-9)
    }

    @Test
    fun inwardForwardLowersRadialPoints() {
        val settings = ConicalSettings(coneAngleDegrees = 16.0, coneType = ConeType.INWARD)
        val result = ConicalTransform.forward(10.0, 0.0, 0.0, 0.0, 0.0, settings)
        assertEquals(10.0 / Math.cos(Math.toRadians(16.0)), result[0], 1e-9)
        assertEquals(-10.0 * Math.tan(Math.toRadians(16.0)), result[2], 1e-9)
    }

    @Test
    fun refinementQuadruplesTrianglesAndPreservesBounds() {
        val source = mesh(
            floatArrayOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f),
            name = "triangle.stl",
        )
        val refined = ConicalTransform.refine(source, 1)

        assertEquals(4, refined.triangleCount)
        assertEquals(source.triangleCount * 18 * 4, refined.interleavedVertices.size)
        assertEquals(source.bounds.minX, refined.bounds.minX, 0.0f)
        assertEquals(source.bounds.minY, refined.bounds.minY, 0.0f)
        assertEquals(source.bounds.maxX, refined.bounds.maxX, 0.0f)
        assertEquals(source.bounds.maxY, refined.bounds.maxY, 0.0f)

        val refinedTwice = ConicalTransform.refine(source, 2)
        assertEquals(16, refinedTwice.triangleCount)
    }

    @Test
    fun warpKeepsTheCenterAndExpandsTheEnvelope() {
        val settings = ConicalSettings(coneAngleDegrees = 16.0, coneType = ConeType.OUTWARD)
        val source = mesh(
            floatArrayOf(100f, 100f, 0f, 120f, 100f, 0f, 100f, 120f, 0f),
            floatArrayOf(120f, 100f, 0f, 120f, 120f, 0f, 100f, 120f, 0f),
            name = "slab.stl",
        )
        val warped = ConicalTransform.warp(source, settings)

        assertEquals(source.bounds.centerX, warped.bounds.centerX, 0.0001f)
        assertEquals(source.bounds.centerY, warped.bounds.centerY, 0.0001f)
        assertTrue(warped.bounds.width > source.bounds.width)
        assertTrue(warped.bounds.maxZ > source.bounds.maxZ)
        // Nonlinear warps must bake geometry: no linear sidecar survives.
        assertEquals(null, warped.slicingTransform)
        assertEquals(null, warped.slicingSourceInterleavedVertices)
    }

    private fun mesh(vararg triangles: FloatArray, name: String): StlMesh {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        val interleaved = FloatArray(triangles.size * 18)
        var offset = 0
        for (triangle in triangles) {
            require(triangle.size == 9) { "Triangles must carry nine coordinates" }
            repeat(3) { vertex ->
                val base = offset + vertex * 6
                val x = triangle[vertex * 3]
                val y = triangle[vertex * 3 + 1]
                val z = triangle[vertex * 3 + 2]
                interleaved[base] = x
                interleaved[base + 1] = y
                interleaved[base + 2] = z
                interleaved[base + 3] = 0f
                interleaved[base + 4] = 0f
                interleaved[base + 5] = 0f
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                minZ = minOf(minZ, z)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)
                maxZ = maxOf(maxZ, z)
            }
            offset += 18
        }
        return StlMesh(
            displayName = name,
            interleavedVertices = VertexData.fromArray(interleaved),
            triangleCount = triangles.size,
            bounds = MeshBounds(minX, minY, minZ, maxX, maxY, maxZ),
        )
    }
}
