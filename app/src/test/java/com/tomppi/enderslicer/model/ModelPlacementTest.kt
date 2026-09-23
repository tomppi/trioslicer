package com.tomppi.enderslicer.model

import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.VertexData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPlacementTest {
    @Test
    fun centersAndDropsArbitraryCoordinatesOntoBed() {
        val mesh = triangleMesh(
            floatArrayOf(
                -4f, 8f, -2f,
                6f, 8f, -2f,
                -4f, 18f, 3f,
            ),
        )
        val placement = ModelPlacement.centeredOnBed(mesh, 230.0, 230.0)
        val transformed = placement.transformed(mesh)

        assertEquals(115.0, transformed.bounds.centerX.toDouble(), 1e-5)
        assertEquals(115.0, transformed.bounds.centerY.toDouble(), 1e-5)
        assertEquals(0.0, transformed.bounds.minZ.toDouble(), 1e-5)
    }

    @Test
    fun centeredOriginPlacesTheModelAroundZero() {
        val mesh = triangleMesh(
            floatArrayOf(
                -4f, 8f, -2f,
                6f, 8f, -2f,
                -4f, 18f, 3f,
            ),
        )

        val transformed = ModelPlacement.centeredOnBed(
            mesh = mesh,
            bedWidthMm = 230.0,
            bedDepthMm = 230.0,
            originAtCenter = true,
        ).transformed(mesh)

        assertEquals(0.0, transformed.bounds.centerX.toDouble(), 1e-5)
        assertEquals(0.0, transformed.bounds.centerY.toDouble(), 1e-5)
        assertEquals(0.0, transformed.bounds.minZ.toDouble(), 1e-5)
    }

    @Test
    fun imported3mfTransformUsesEmbeddedTargetBounds() {
        val mesh = triangleMesh(
            floatArrayOf(
                115f, 115f, 0f,
                117f, 115f, 0f,
                115f, 119f, 1f,
            ),
        )
        val affine = ModelPlacement.Affine3mf(
            linear = listOf(
                1.0, 0.0, 0.0,
                0.0, 0.0, -1.0,
                0.0, 1.0, 0.0,
            ),
            translationXmm = 100.0,
            translationYmm = 110.0,
            translationZmm = 7.0,
            targetCenterXmm = 101.0,
            targetCenterYmm = 109.5,
            targetBaseZmm = 7.0,
        )

        val floating = ModelPlacement.from3mf(mesh, affine, dropToBuildPlate = false).transformed(mesh)
        assertEquals(7.0, floating.bounds.minZ.toDouble(), 1e-5)
        assertEquals(101.0, floating.bounds.centerX.toDouble(), 1e-5)
        assertEquals(109.5, floating.bounds.centerY.toDouble(), 1e-5)

        val dropped = ModelPlacement.from3mf(mesh, affine, dropToBuildPlate = true).transformed(mesh)
        assertEquals(0.0, dropped.bounds.minZ.toDouble(), 1e-5)
        assertEquals(floating.bounds.centerX, dropped.bounds.centerX, 1e-5f)
        assertEquals(floating.bounds.centerY, dropped.bounds.centerY, 1e-5f)
    }

    @Test
    fun importedTransformDoesNotDoubleExternalStlCenter() {
        val externallyCenteredMesh = triangleMesh(
            floatArrayOf(
                114f, 113f, 0f,
                116f, 113f, 0f,
                114f, 117f, 1f,
            ),
        )
        val affine = ModelPlacement.Affine3mf(
            linear = ModelPlacement.IDENTITY,
            translationXmm = 115.0,
            translationYmm = 115.0,
            translationZmm = 22.0,
            targetCenterXmm = 115.0,
            targetCenterYmm = 115.0,
            targetBaseZmm = -18.0,
        )

        val transformed = ModelPlacement.from3mf(
            externallyCenteredMesh,
            affine,
            dropToBuildPlate = true,
        ).transformed(externallyCenteredMesh)

        assertEquals(115.0, transformed.bounds.centerX.toDouble(), 1e-5)
        assertEquals(115.0, transformed.bounds.centerY.toDouble(), 1e-5)
        assertEquals(0.0, transformed.bounds.minZ.toDouble(), 1e-5)
    }

    @Test
    fun layFlatPlacesLargestFaceOnBuildPlate() {
        val mesh = triangleMesh(
            floatArrayOf(
                0f, 0f, 0f,
                10f, 0f, 0f,
                0f, 0f, 10f,
            ),
        )
        val initial = ModelPlacement.centeredOnBed(mesh, 230.0, 230.0)
        val flattened = initial.layFlat(mesh).transformed(mesh)

        assertEquals(0.0, flattened.bounds.minZ.toDouble(), 1e-5)
        assertTrue(flattened.bounds.height < 1e-3f)
    }

    @Test
    fun scaledTo200PercentDoublesBoundsAndKeepsCenter() {
        val mesh = triangleMesh(
            floatArrayOf(
                0f, 0f, 0f,
                10f, 0f, 0f,
                0f, 0f, 10f,
            ),
        )
        val initial = ModelPlacement.centeredOnBed(mesh, 230.0, 230.0)
        val before = initial.transformed(mesh)
        val scaled = initial.scaled(200.0).transformed(mesh)

        assertEquals(2.0 * before.bounds.width.toDouble(), scaled.bounds.width.toDouble(), 1e-4)
        assertEquals(2.0 * before.bounds.depth.toDouble(), scaled.bounds.depth.toDouble(), 1e-4)
        assertEquals(2.0 * before.bounds.height.toDouble(), scaled.bounds.height.toDouble(), 1e-4)
        assertEquals(before.bounds.centerX.toDouble(), scaled.bounds.centerX.toDouble(), 1e-4)
        assertEquals(before.bounds.centerY.toDouble(), scaled.bounds.centerY.toDouble(), 1e-4)
        assertEquals(before.bounds.minZ.toDouble(), scaled.bounds.minZ.toDouble(), 1e-4)
        assertTrue(initial.scaled(200.0).source.contains("200%"))
    }

    @Test
    fun scaledTo50PercentHalvesBounds() {
        val mesh = triangleMesh(
            floatArrayOf(
                0f, 0f, 0f,
                10f, 0f, 0f,
                0f, 0f, 10f,
            ),
        )
        val initial = ModelPlacement.centeredOnBed(mesh, 230.0, 230.0)
        val before = initial.transformed(mesh)
        val scaled = initial.scaled(50.0).transformed(mesh)

        assertEquals(0.5 * before.bounds.width.toDouble(), scaled.bounds.width.toDouble(), 1e-4)
        assertEquals(0.5 * before.bounds.height.toDouble(), scaled.bounds.height.toDouble(), 1e-4)
    }

    @Test
    fun scaledRejectsNonPositiveOrNonFinitePercentages() {
        val mesh = triangleMesh(
            floatArrayOf(
                0f, 0f, 0f,
                10f, 0f, 0f,
                0f, 0f, 10f,
            ),
        )
        val initial = ModelPlacement.centeredOnBed(mesh, 230.0, 230.0)

        listOf(0.0, -50.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { percent ->
            val error = runCatching { initial.scaled(percent) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
        }
    }

    @Test
    fun largePlacedMeshKeepsVertexDataOffHeap() {
        val triangleCount = 200_000
        val interleaved = FloatArray(triangleCount * 18)
        repeat(triangleCount) { t ->
            val o = t * 18
            interleaved[o] = (t % 10).toFloat()
            interleaved[o + 1] = ((t / 10) % 10).toFloat()
            interleaved[o + 2] = 0f
            interleaved[o + 5] = 1f
            interleaved[o + 6] = (t % 10).toFloat()
            interleaved[o + 7] = ((t / 10) % 10).toFloat()
            interleaved[o + 8] = 0f
            interleaved[o + 11] = 1f
            interleaved[o + 12] = (t % 10).toFloat()
            interleaved[o + 13] = ((t / 10) % 10).toFloat()
            interleaved[o + 14] = 0f
            interleaved[o + 17] = 1f
        }
        val mesh = StlMesh(
            "big",
            VertexData.fromArray(interleaved),
            triangleCount,
            MeshBounds(0f, 0f, 0f, 9f, 9f, 0f),
        )
        val transformed = ModelPlacement.centeredOnBed(mesh, 230.0, 230.0).transformed(mesh)

        assertTrue(
            "placed big mesh must not sit in the Java heap",
            transformed.interleavedVertices.directOrNull() != null,
        )
        assertEquals(triangleCount, transformed.triangleCount)
        assertEquals(115.0, transformed.bounds.centerX.toDouble(), 1e-4)
        assertEquals(0.0, transformed.bounds.minZ.toDouble(), 1e-4)
    }

    @Test
    fun rejectsATransformWhoseFiniteScaleOverflowsTheRenderedYBounds() {
        val mesh = triangleMesh(
            floatArrayOf(
                -4f, 8f, -2f,
                6f, 8f, -2f,
                -4f, 18f, 3f,
            ),
        )
        // Finite in Double, infinite once the placement writes it as Float. The
        // bounds check looked at X only, so this passed and surfaced much later
        // as Infinity bounds (or an STL-writer failure).
        val placement = ModelPlacement(
            linear = listOf(1.0, 0.0, 0.0, 0.0, 1e300, 0.0, 0.0, 0.0, 1.0),
            centerXmm = 115.0,
            centerYmm = 115.0,
            baseZmm = 0.0,
        )

        val error = runCatching { placement.transformed(mesh) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("bounds"))
    }

    private fun triangleMesh(positions: FloatArray): StlMesh {
        require(positions.size == 9)
        val interleaved = FloatArray(18)
        repeat(3) { vertex ->
            interleaved[vertex * 6] = positions[vertex * 3]
            interleaved[vertex * 6 + 1] = positions[vertex * 3 + 1]
            interleaved[vertex * 6 + 2] = positions[vertex * 3 + 2]
            interleaved[vertex * 6 + 5] = 1f
        }
        val xs = listOf(positions[0], positions[3], positions[6])
        val ys = listOf(positions[1], positions[4], positions[7])
        val zs = listOf(positions[2], positions[5], positions[8])
        return StlMesh(
            displayName = "test.stl",
            interleavedVertices = VertexData.fromArray(interleaved),
            triangleCount = 1,
            bounds = MeshBounds(
                xs.min(), ys.min(), zs.min(),
                xs.max(), ys.max(), zs.max(),
            ),
        )
    }
}
