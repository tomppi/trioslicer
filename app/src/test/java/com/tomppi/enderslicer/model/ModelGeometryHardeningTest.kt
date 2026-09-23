package com.tomppi.enderslicer.model

import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.VertexData
import com.tomppi.enderslicer.viewer.StlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.math.abs

class ModelGeometryHardeningTest {
    @Test
    fun asciiCoordinatesAreRebasedBeforeFloatPrecisionIsLost() {
        val directory = createTempDirectory("enderslicer-large-ascii").toFile()
        val file = File(directory, "large.stl").apply {
            writeText(
                """
                solid precise
                  facet normal 0 0 1
                    outer loop
                      vertex 16777216 0 0
                      vertex 16777217 0 0
                      vertex 16777216 1 0
                    endloop
                  endfacet
                endsolid precise
                """.trimIndent(),
            )
        }

        val mesh = StlParser.parse(file, maxTriangles = 10)
        val placement = ModelPlacement.from3mf(
            mesh = mesh,
            affine = ModelPlacement.Affine3mf(
                linear = ModelPlacement.IDENTITY,
                translationXmm = -16_777_216.0 + 100.0,
                translationYmm = 20.0,
                translationZmm = 7.0,
            ),
            dropToBuildPlate = false,
        )
        val transformed = placement.transformed(mesh)

        assertEquals(1f, mesh.bounds.width)
        assertEquals(16_777_216.0, mesh.sourceOriginXmm, 0.0)
        assertEquals(1f, transformed.bounds.width)
        assertEquals(100.5, placement.centerXmm, 0.0001)
        assertEquals(20.5, placement.centerYmm, 0.0001)
        assertEquals(7.0, placement.baseZmm, 0.0001)
    }

    @Test
    fun layFlatSelectsLargestConnectedPlanarPatch() {
        val triangles = mutableListOf<Float>()
        for (strip in 0 until 10) {
            addTriangle(triangles, strip.toFloat(), 0f, 0f, (strip + 1).toFloat(), 0f, 0f, (strip + 1).toFloat(), 10f, 0f)
            addTriangle(triangles, strip.toFloat(), 0f, 0f, (strip + 1).toFloat(), 10f, 0f, strip.toFloat(), 10f, 0f)
        }
        addTriangle(triangles, 20f, 0f, 0f, 20f, 4f, 0f, 20f, 0f, 5f)
        val mesh = mesh(triangles)

        val patch = PlanarPatchSelector.largest(mesh, ModelPlacement.IDENTITY)

        assertEquals(100.0, patch.area, 0.001)
        assertTrue(abs(patch.normal[2]) > 0.999)
    }

    @Test
    fun disconnectedCoplanarSurfacesAreNotSummedTogether() {
        val triangles = mutableListOf<Float>()
        addTriangle(triangles, 0f, 0f, 0f, 4f, 0f, 0f, 0f, 3f, 0f) // area 6
        addTriangle(triangles, 10f, 0f, 0f, 14f, 0f, 0f, 10f, 3f, 0f) // separate area 6
        addTriangle(triangles, 20f, 0f, 0f, 20f, 4f, 0f, 20f, 0f, 5f) // vertical area 10
        val mesh = mesh(triangles)

        val patch = PlanarPatchSelector.largest(mesh, ModelPlacement.IDENTITY)

        assertEquals(10.0, patch.area, 0.001)
        assertTrue(abs(patch.normal[0]) > 0.999)
    }

    private fun addTriangle(
        output: MutableList<Float>,
        x0: Float,
        y0: Float,
        z0: Float,
        x1: Float,
        y1: Float,
        z1: Float,
        x2: Float,
        y2: Float,
        z2: Float,
    ) {
        val ax = x1 - x0
        val ay = y1 - y0
        val az = z1 - z0
        val bx = x2 - x0
        val by = y2 - y0
        val bz = z2 - z0
        val nx = ay * bz - az * by
        val ny = az * bx - ax * bz
        val nz = ax * by - ay * bx
        listOf(
            x0, y0, z0, nx, ny, nz,
            x1, y1, z1, nx, ny, nz,
            x2, y2, z2, nx, ny, nz,
        ).forEach(output::add)
    }

    private fun mesh(values: List<Float>): StlMesh {
        val vertices = values.toFloatArray()
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        var offset = 0
        while (offset < vertices.size) {
            minX = minOf(minX, vertices[offset])
            minY = minOf(minY, vertices[offset + 1])
            minZ = minOf(minZ, vertices[offset + 2])
            maxX = maxOf(maxX, vertices[offset])
            maxY = maxOf(maxY, vertices[offset + 1])
            maxZ = maxOf(maxZ, vertices[offset + 2])
            offset += 6
        }
        return StlMesh(
            displayName = "geometry.stl",
            interleavedVertices = VertexData.fromArray(vertices),
            triangleCount = vertices.size / 18,
            bounds = MeshBounds(minX, minY, minZ, maxX, maxY, maxZ),
        )
    }
}
