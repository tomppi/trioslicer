package com.tomppi.enderslicer.supportpaint

import com.tomppi.enderslicer.viewer.MeshBounds
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.VertexData
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportPaintStateTest {
    @Test
    fun clippedToMeshDropsIndicesBeyondTheTriangleCount() {
        val state = SupportPaintState(
            enforcerTriangles = setOf(0, 5, 12),
            blockerTriangles = setOf(1, 99),
        )

        val clipped = state.clippedToMesh(6)

        assertEquals(setOf(0, 5), clipped.enforcerTriangles)
        assertEquals(setOf(1), clipped.blockerTriangles)
        assertEquals(state.brushRadiusMm, clipped.brushRadiusMm, 0.0)
    }

    @Test
    fun clippedToMeshKeepsInRangeStateIntact() {
        val state = SupportPaintState(
            enforcerTriangles = setOf(0, 3),
            blockerTriangles = setOf(4),
            brushRadiusMm = 5.0,
        )

        val clipped = state.clippedToMesh(10)

        assertEquals(state.enforcerTriangles, clipped.enforcerTriangles)
        assertEquals(state.blockerTriangles, clipped.blockerTriangles)
        assertEquals(5.0, clipped.brushRadiusMm, 0.0)
    }

    @Test
    fun clippedToMeshOnEmptyMeshDropsEverything() {
        val state = SupportPaintState(
            enforcerTriangles = setOf(0),
            blockerTriangles = setOf(2),
        )

        val clipped = state.clippedToMesh(0)

        assertTrue(clipped.isEmpty)
    }

    @Test
    fun generateRejectsPaintRegionsBeyondTheTriangleLimit() {
        val directory = Files.createTempDirectory("support-paint-cap").toFile()
        try {
            val paint = SupportPaintState(
                enforcerTriangles = (0 until SupportPaintModifiers.MAX_PAINTED_TRIANGLES + 1).toSet(),
            )
            val mesh = StlMesh(
                displayName = "dummy.stl",
                interleavedVertices = VertexData.fromArray(FloatArray(0)),
                triangleCount = 0,
                bounds = MeshBounds(0f, 0f, 0f, 1f, 1f, 1f),
            )

            val error = runCatching {
                SupportPaintModifiers.generate(mesh, paint, File(directory, "modifiers"), 0.8)
            }.exceptionOrNull()

            assertTrue(error is IllegalArgumentException)
            assertTrue(error?.message.orEmpty().contains("Erase some paint"))
        } finally {
            directory.deleteRecursively()
        }
    }
}
