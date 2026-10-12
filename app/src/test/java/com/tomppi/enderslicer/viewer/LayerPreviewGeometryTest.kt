package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.engine.GcodeLayerPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layer view's geometry, measured rather than counted: every stored path of the selected
 * layer is in the buffers, the buffers hold the window and nothing else, and selecting another
 * layer rebuilds the geometry there.
 */
class LayerPreviewGeometryTest {

    private fun preview(
        layers: Int = 100,
        pathsOf: (Int) -> Int = { 20 },
        folded: Boolean = false,
    ): GcodeLayerPreview {
        val list = (0 until layers).map { index ->
            val count = pathsOf(index)
            val segments = FloatArray(count * GcodeLayerPreview.VALUES_PER_SEGMENT)
            for (path in 0 until count) {
                val offset = path * GcodeLayerPreview.VALUES_PER_SEGMENT
                val x = (index * 1_000 + path).toFloat()
                segments[offset] = x
                segments[offset + 1] = index.toFloat()
                segments[offset + 2] = x + 0.4f
                segments[offset + 3] = index.toFloat()
                segments[offset + 4] = 30f
                segments[offset + 5] = GcodeLayerPreview.Feature.MODEL.code.toFloat()
            }
            GcodeLayerPreview.Layer(
                number = index,
                z = 0.2f * (index + 1),
                height = 0.2f,
                segments = segments,
                supportSegmentCount = 0,
                supportInterfaceSegmentCount = 0,
                sourceSegmentCount = count,
            )
        }
        return GcodeLayerPreview(
            layers = list,
            minX = 0f, minY = 0f, maxX = 100_000f, maxY = 100f,
            minSpeedMmPerSecond = 30f, maxSpeedMmPerSecond = 30f,
            minLayerHeightMm = 0.2f, maxLayerHeightMm = 0.2f,
            totalSegmentCount = list.sumOf { it.sourceSegmentCount },
            storedSegmentCount = list.sumOf { it.segmentCount },
            folded = folded,
        )
    }

    private fun capacity(segments: Int) = PreviewCapacity(
        profile = PreviewMemoryProfile.AUTO,
        moves = 1_000,
        windows = 1_000,
        travels = 1_000,
        segments = segments,
        note = "auto profile · 4.0 GB free",
    )

    @Test
    fun everyPathOfTheSelectedLayerIsDrawn() {
        val value = preview(pathsOf = { 40 })
        val geometry = LayerPreviewGeometry(value, capacity(Int.MAX_VALUE))
        geometry.select(5)

        val layer = value.layers[5]
        assertEquals(40, layer.segmentCount)
        assertEquals(
            "two vertices per stored path",
            layer.segmentCount * 2,
            geometry.vertexEnd(5) - geometry.vertexStart(5),
        )

        val positions = requireNotNull(geometry.pathPositions)
        val start = geometry.vertexStart(5)
        for (path in 0 until layer.segmentCount) {
            val offset = path * GcodeLayerPreview.VALUES_PER_SEGMENT
            // Three floats per vertex, two vertices per path.
            positions.position((start + path * 2) * 3)
            assertEquals(layer.segments[offset], positions.get(), 1e-4f)
            assertEquals(layer.segments[offset + 1], positions.get(), 1e-4f)
            assertEquals(layer.z, positions.get(), 1e-4f)
            assertEquals(layer.segments[offset + 2], positions.get(), 1e-4f)
            assertEquals(layer.segments[offset + 3], positions.get(), 1e-4f)
            assertEquals(layer.z, positions.get(), 1e-4f)
        }
        assertTrue(geometry.ready)
    }

    @Test
    fun theBuffersHoldTheWindowAndNothingElse() {
        val value = preview(pathsOf = { 30 })
        val geometry = LayerPreviewGeometry(value, capacity(Int.MAX_VALUE))
        geometry.select(50)

        val expected = geometry.window.sumOf { value.layers[it].segmentCount } * 2
        assertEquals(expected, geometry.pathVertexCount())
        assertEquals("the window is a band of layers, not the print", true, geometry.window.count() <= 11)
        assertTrue(geometry.window.first <= 50 && geometry.window.last >= 50)
        assertEquals(
            "the ribbons are the selected layer's own paths",
            value.layers[50].segmentCount * 6,
            geometry.ribbonVertexCount,
        )
    }

    @Test
    fun selectingAnotherLayerRebuildsTheGeometryThere() {
        val value = preview()
        val geometry = LayerPreviewGeometry(value, capacity(Int.MAX_VALUE))
        geometry.select(5)
        val first = geometry.window

        geometry.select(6)
        assertEquals("stepping inside the window keeps it", first, geometry.window)
        assertEquals(6, geometry.selectedLayerIndex)
        assertEquals(
            "the ribbons follow the selection inside the window",
            value.layers[6].segmentCount * 6,
            geometry.ribbonVertexCount,
        )

        assertTrue("a far selection rebuilds", geometry.select(60))
        assertEquals(60, geometry.selectedLayerIndex)
        assertTrue(60 in geometry.window)
        assertTrue("the new window is somewhere else", geometry.window != first)
        assertEquals(
            "the ribbons follow the selection",
            value.layers[60].segmentCount * 6,
            geometry.ribbonVertexCount,
        )
    }

    @Test
    fun aTinyBudgetLeavesTheSelectedLayerAlone() {
        val value = preview(pathsOf = { 10 })
        val geometry = LayerPreviewGeometry(value, capacity(LayerWindow.MINIMUM_SEGMENTS))
        geometry.select(7)

        assertEquals("the selected layer is never dropped", 7..7, geometry.window)
        assertEquals(10 * 2, geometry.pathVertexCount())
        assertTrue(geometry.status, geometry.status.contains("1 layer"))
    }

    @Test
    fun aFoldedPreviewSaysWhatItStored() {
        val value = preview(pathsOf = { 10 }, folded = true).copy(
            totalSegmentCount = 5_000,
            storedSegmentCount = 3_000,
        )
        val geometry = LayerPreviewGeometry(value, capacity(Int.MAX_VALUE))
        geometry.select(1)
        assertTrue(geometry.status, geometry.status.contains("folded across the print"))
        assertTrue(geometry.status, geometry.status.contains("auto profile"))
    }
}
