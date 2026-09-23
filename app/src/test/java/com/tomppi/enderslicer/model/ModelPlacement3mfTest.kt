package com.tomppi.enderslicer.model

import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.VertexData
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelPlacement3mfTest {
    @Test
    fun affineWithoutTargetBoundsAddsTranslationToRawBounds() {
        val mesh = mesh(
            floatArrayOf(5f, 15f, 3f),
            floatArrayOf(15f, 15f, 3f),
            floatArrayOf(10f, 25f, 8f),
        )
        val affine = ModelPlacement.Affine3mf(
            linear = ModelPlacement.IDENTITY,
            translationXmm = 100.0,
            translationYmm = 200.0,
            translationZmm = 7.0,
        )

        val placement = ModelPlacement.from3mf(mesh, affine, dropToBuildPlate = false)
        val transformed = placement.transformed(mesh)

        assertEquals(110.0, placement.centerXmm, 0.0001)
        assertEquals(220.0, placement.centerYmm, 0.0001)
        assertEquals(10.0, placement.baseZmm, 0.0001)
        assertEquals(110.0, transformed.bounds.centerX.toDouble(), 0.0001)
        assertEquals(220.0, transformed.bounds.centerY.toDouble(), 0.0001)
        assertEquals(10.0, transformed.bounds.minZ.toDouble(), 0.0001)
        assertEquals(100.0, transformed.slicingTransform?.translationXmm ?: Double.NaN, 0.0001)
        assertEquals(200.0, transformed.slicingTransform?.translationYmm ?: Double.NaN, 0.0001)
        assertEquals(7.0, transformed.slicingTransform?.translationZmm ?: Double.NaN, 0.0001)
    }

    @Test
    fun fallbackTranslationIsAppliedAfterTheLinearTransform() {
        val mesh = mesh(
            floatArrayOf(5f, 15f, 3f),
            floatArrayOf(15f, 15f, 3f),
            floatArrayOf(10f, 25f, 8f),
        )
        val scaleX = listOf(
            2.0, 0.0, 0.0,
            0.0, 1.0, 0.0,
            0.0, 0.0, 1.0,
        )
        val affine = ModelPlacement.Affine3mf(scaleX, 100.0, 200.0, 7.0)

        val placement = ModelPlacement.from3mf(mesh, affine, dropToBuildPlate = false)
        val transformed = placement.transformed(mesh)

        assertEquals(120.0, placement.centerXmm, 0.0001)
        assertEquals(220.0, placement.centerYmm, 0.0001)
        assertEquals(10.0, placement.baseZmm, 0.0001)
        assertEquals(120.0, transformed.bounds.centerX.toDouble(), 0.0001)
    }

    @Test
    fun embeddedTargetBoundsRemainAuthoritative() {
        val mesh = mesh(
            floatArrayOf(5f, 15f, 3f),
            floatArrayOf(15f, 15f, 3f),
            floatArrayOf(10f, 25f, 8f),
        )
        val affine = ModelPlacement.Affine3mf(
            linear = ModelPlacement.IDENTITY,
            translationXmm = 100.0,
            translationYmm = 200.0,
            translationZmm = 7.0,
            targetCenterXmm = 150.0,
            targetCenterYmm = 160.0,
            targetBaseZmm = 12.0,
        )

        val placement = ModelPlacement.from3mf(mesh, affine, dropToBuildPlate = false)

        assertEquals(150.0, placement.centerXmm, 0.0)
        assertEquals(160.0, placement.centerYmm, 0.0)
        assertEquals(12.0, placement.baseZmm, 0.0)
    }

    @Test
    fun dropToBuildPlateOnlyOverridesTheFinalZPlacement() {
        val mesh = mesh(
            floatArrayOf(5f, 15f, 3f),
            floatArrayOf(15f, 15f, 3f),
            floatArrayOf(10f, 25f, 8f),
        )
        val affine = ModelPlacement.Affine3mf(ModelPlacement.IDENTITY, 100.0, 200.0, 7.0)

        val placement = ModelPlacement.from3mf(mesh, affine, dropToBuildPlate = true)

        assertEquals(110.0, placement.centerXmm, 0.0001)
        assertEquals(220.0, placement.centerYmm, 0.0001)
        assertEquals(0.0, placement.baseZmm, 0.0)
    }

    private fun mesh(a: FloatArray, b: FloatArray, c: FloatArray): StlMesh {
        val values = FloatArray(18)
        listOf(a, b, c).forEachIndexed { index, vertex ->
            val offset = index * 6
            values[offset] = vertex[0]
            values[offset + 1] = vertex[1]
            values[offset + 2] = vertex[2]
            values[offset + 5] = 1f
        }
        val minX = minOf(a[0], b[0], c[0])
        val minY = minOf(a[1], b[1], c[1])
        val minZ = minOf(a[2], b[2], c[2])
        val maxX = maxOf(a[0], b[0], c[0])
        val maxY = maxOf(a[1], b[1], c[1])
        val maxZ = maxOf(a[2], b[2], c[2])
        return StlMesh(
            displayName = "external.stl",
            interleavedVertices = VertexData.fromArray(values),
            triangleCount = 1,
            bounds = MeshBounds(minX, minY, minZ, maxX, maxY, maxZ),
        )
    }
}
