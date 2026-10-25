package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.engine.GcodeLayerPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layer view's geometry, measured rather than counted: every stored path of every layer in
 * the window is in the buffers, the buffers hold the window and nothing else, the current-layer
 * style keeps its context band, the build-up style holds the whole stack from the bed to the
 * scrub, and a build-up window that grows by a layer appends it instead of rebuilding the stack.
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

    private fun geometry(
        value: GcodeLayerPreview,
        capacitySegments: Int = Int.MAX_VALUE,
        style: LayerPreviewStyle = LayerPreviewStyle.CURRENT_LAYER,
    ): LayerPreviewGeometry = LayerPreviewGeometry(value, capacity(capacitySegments), style)

    /** The layer count the footer's "N layers in the buffers" holds. */
    private fun heldLayers(status: String): Int {
        val match = Regex("^(\\d+) layers? in the buffers").find(status)
        requireNotNull(match) { "no layer count in '$status'" }
        return match.groupValues[1].toInt()
    }

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
    fun theCurrentLayerBuffersHoldItsContextWindowAndNothingElse() {
        val value = preview(pathsOf = { 30 })
        val geometry = LayerPreviewGeometry(value, capacity(Int.MAX_VALUE))
        geometry.select(50)

        val expected = geometry.window.sumOf { value.layers[it].segmentCount } * 2
        assertEquals(expected, geometry.pathVertexCount())
        assertEquals("the current-layer window is a band", true, geometry.window.count() <= 11)
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

    /** The current-layer style keeps exactly the window it always had: selected, 8 under, 2 over. */
    @Test
    fun theCurrentLayerStyleHoldsTheSelectedLayerAndTheContextAroundIt() {
        val value = preview(layers = 400, pathsOf = { 20 })
        val geometry = geometry(value, style = LayerPreviewStyle.CURRENT_LAYER)
        geometry.select(300)

        assertEquals(300 - LayerWindow.CONTEXT_BELOW, geometry.window.first)
        assertEquals(300 + LayerWindow.CONTEXT_ABOVE, geometry.window.last)
        assertTrue(
            "the layers below the selection are in the path buffers",
            geometry.vertexStart(300) > 0 && geometry.vertexEnd(300) > geometry.vertexStart(300),
        )
        assertEquals(geometry.pathVertexCount(), geometry.vertexEnd(geometry.window.last))
        assertEquals(1 + LayerWindow.CONTEXT_BELOW + LayerWindow.CONTEXT_ABOVE, geometry.window.count())
        assertEquals(geometry.window.count(), heldLayers(geometry.status))
    }

    @Test
    fun aReducedWindowSaysWhatItIsMissingAndWhy() {
        val value = preview(layers = 40, pathsOf = { 100 })
        // 300 segments of budget: three layers, where the context would give eleven.
        val geometry = LayerPreviewGeometry(value, capacity(300))
        geometry.select(20)

        assertEquals(3, geometry.window.count())
        assertTrue(geometry.status, geometry.status.contains("the window holds 300 of the 1100 runs"))
        assertTrue(geometry.status, geometry.status.contains("the 11 layers around it hold"))

        // The capacity comes from the device profile, so a profile tap that frees room gives the
        // full window back rather than staying where the small one left it.
        val wide = LayerPreviewGeometry(value, capacity(Int.MAX_VALUE))
        wide.select(20)
        assertEquals(1 + LayerWindow.CONTEXT_BELOW + LayerWindow.CONTEXT_ABOVE, wide.window.count())
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

    // --- the build-up style holds the whole stack, from the bed to the scrub -----------------

    @Test
    fun buildUpHoldsEveryLayerFromTheBedToTheSelection() {
        val value = preview(layers = 400, pathsOf = { 20 })
        val geometry = geometry(value, style = LayerPreviewStyle.BUILD_UP)
        geometry.select(300)

        assertEquals("the whole stack to the scrub, and nothing above it", 0..300, geometry.window)
        assertEquals(301, geometry.window.count())
        assertEquals(301 * 20 * 2, geometry.pathVertexCount())
        assertEquals(0, geometry.vertexStart(0))
        assertEquals(20 * 2, geometry.vertexEnd(0))
        assertTrue(geometry.status, geometry.status.startsWith("301 layers in the buffers"))
        assertTrue(geometry.status, geometry.status.contains("6020 runs for 6020 paths"))
        assertEquals("the footer names the layers actually held", 301, heldLayers(geometry.status))
    }

    @Test
    fun buildUpStepsOneTenAndFiveHundredLayersDeep() {
        val value = preview(layers = 586, pathsOf = { 30 })
        val geometry = geometry(value, style = LayerPreviewStyle.BUILD_UP)
        for (anchor in intArrayOf(1, 10, 500)) {
            geometry.select(anchor)
            assertEquals("build-up is planned from layer zero", 0..anchor, geometry.window)
            assertEquals(anchor + 1, geometry.window.count())
            assertEquals((anchor + 1) * 30 * 2, geometry.pathVertexCount())
            assertEquals(anchor + 1, heldLayers(geometry.status))
        }
    }

    /**
     * The reported slice on the reported kind of device: 586 layers of about a thousand paths
     * each, on the auto profile from a phone with 6.9 GB free and a 512 MB heap class. The whole
     * stack is about 33 MB of path buffers, so it is held in full - this is not a reduced window.
     */
    @Test
    fun theReportedPrintHoldsTheWholeStackAtTheScrub() {
        val value = preview(layers = 586, pathsOf = { 1_000 })
        val device = PreviewDeviceMemory(
            totalBytes = 8_000_000_000L,
            availableBytes = 6_900_000_000L,
            lowRamDevice = false,
            memoryClassMb = 512,
            largeMemoryClassMb = 512,
        )
        val capacity = PreviewMemoryPolicy.resolve(PreviewMemoryProfile.AUTO, device, conservativeMoves = 1_000_000)
        val geometry = LayerPreviewGeometry(value, capacity, LayerPreviewStyle.BUILD_UP)
        geometry.select(325)

        assertEquals(0..325, geometry.window)
        assertEquals(326, geometry.window.count())
        assertEquals(326_000 * 2, geometry.pathVertexCount())
        assertTrue(geometry.status, geometry.status.startsWith("326 layers in the buffers"))
        assertTrue(geometry.status, geometry.status.contains("326000 runs for 326000 paths"))
        assertTrue("the whole stack fits the device profile", geometry.status.contains("auto profile"))
        assertTrue("no reduction is named for a whole stack that fits", !geometry.status.contains("the window holds"))
        assertEquals(326, heldLayers(geometry.status))
    }

    @Test
    fun aBudgetTooSmallForTheWholeStackReducesTheWindowAndNamesBothNumbers() {
        val value = preview(layers = 100, pathsOf = { 1_000 })
        // 200,000 stored paths of profile budget are 85,714 window paths of vertex buffers.
        val capacity = capacity(200_000)
        val budget = LayerWindow.vertexPathBudget(capacity.segments)
        val geometry = LayerPreviewGeometry(value, capacity, LayerPreviewStyle.BUILD_UP)
        geometry.select(99)

        assertEquals("the selected layer is never dropped", 99, geometry.window.last)
        assertTrue("the stack does not start at the bed in a reduced window", geometry.window.first > 0)
        assertEquals(85, geometry.window.count())
        assertTrue(
            geometry.status,
            geometry.status.contains("the window holds 85000 of the 100000 runs the whole stack to this layer holds"),
        )
        assertTrue(geometry.status, geometry.status.contains("(85 of 100 layers)"))
        assertEquals("the footer names the layers actually held", 85, heldLayers(geometry.status))
        assertTrue(
            "what is held fits the budget",
            LayerWindow.segments(geometry.window) { value.layers[it].segmentCount } <= budget,
        )
    }

    @Test
    fun aReducedBuildUpWindowDrawsOnlyWhatItHoldsAndSaysWhatIsMissing() {
        val value = preview(layers = 100, pathsOf = { 1_000 })
        val geometry = LayerPreviewGeometry(value, capacity(200_000), LayerPreviewStyle.BUILD_UP)
        geometry.select(99)

        val stack = LayerPreviewDraw.buildUp(geometry, LayerPreviewStyle.BUILD_UP)
        assertEquals("the drawn stack is the held window under the selection", geometry.window.first..98, stack.layers)
        assertEquals(geometry.window.count() - 1, stack.layerCount)
        assertTrue("no silent sampling: the drawn paths are all of the held layers'", stack.vertexCount > 0)
        assertEquals(
            LayerWindow.segments(geometry.window.first..98) { value.layers[it].segmentCount } * 2,
            stack.vertexCount,
        )
    }

    @Test
    fun aBuildUpStepUpAppendsTheLayerInsteadOfRebuildingTheStack() {
        val value = preview(layers = 400, pathsOf = { 20 })
        val geometry = geometry(value, style = LayerPreviewStyle.BUILD_UP)
        geometry.select(300)
        val positions = requireNotNull(geometry.pathPositions)
        val colors = requireNotNull(geometry.pathColors)
        val vertices = geometry.pathVertexCount()

        assertTrue("stepping up one layer", geometry.select(301))
        assertSame("the path buffers are kept, not rebuilt", positions, geometry.pathPositions)
        assertSame("the colours are kept too", colors, geometry.pathColors)
        assertEquals(0..301, geometry.window)
        assertEquals(
            "only the new layer's paths were written",
            vertices + value.layers[301].segmentCount * 2,
            geometry.pathVertexCount(),
        )
        assertEquals(302, heldLayers(geometry.status))

        // The appended vertices really are layer 301's own first path.
        val start = geometry.vertexStart(301)
        val layer = value.layers[301]
        positions.position(start * 3)
        assertEquals(layer.segments[0], positions.get(), 1e-4f)
        assertEquals(layer.segments[1], positions.get(), 1e-4f)
        assertEquals(layer.z, positions.get(), 1e-4f)
        assertEquals(layer.segments[2], positions.get(), 1e-4f)
        assertEquals(layer.segments[3], positions.get(), 1e-4f)
        assertEquals(layer.z, positions.get(), 1e-4f)
    }

    @Test
    fun movingDownInsideTheBuildUpStackKeepsTheBuffersAndGrowsBackIntoThem() {
        val value = preview(layers = 400, pathsOf = { 20 })
        val geometry = geometry(value, style = LayerPreviewStyle.BUILD_UP)
        geometry.select(300)
        val held = geometry.window
        val positions = requireNotNull(geometry.pathPositions)

        geometry.select(100)
        assertEquals("moving down does not shrink the held stack", held, geometry.window)
        assertSame("and does not rebuild it", positions, geometry.pathPositions)
        assertEquals(100, geometry.selectedLayerIndex)
        assertEquals(held.count(), heldLayers(geometry.status))

        geometry.select(301)
        assertSame("growing back into the held buffer does not rebuild it", positions, geometry.pathPositions)
        assertEquals(0..301, geometry.window)
    }

    @Test
    fun growingPastTheBuffersCapacityStillHoldsTheWholeStack() {
        val value = preview(layers = 120, pathsOf = { 20 })
        val geometry = geometry(value, style = LayerPreviewStyle.BUILD_UP)
        geometry.select(0)
        // The first layer is small, so the roomy allocation is soon full: the stack must still
        // grow to the whole print, by copying what is held rather than by dropping it.
        for (layer in 1 until 120) geometry.select(layer)
        assertEquals(0..119, geometry.window)
        assertEquals(120 * 20 * 2, geometry.pathVertexCount())
        assertEquals(120, heldLayers(geometry.status))
    }

    @Test
    fun switchingStyleReplansTheWindow() {
        val value = preview(layers = 400, pathsOf = { 20 })
        val geometry = geometry(value)
        geometry.select(300)
        assertEquals(292..302, geometry.window)

        assertTrue(geometry.setStyle(LayerPreviewStyle.BUILD_UP))
        assertEquals("build-up is the whole stack", 0..300, geometry.window)
        assertEquals(301 * 20 * 2, geometry.pathVertexCount())

        assertTrue(geometry.setStyle(LayerPreviewStyle.CURRENT_LAYER))
        assertEquals(292..302, geometry.window)
        assertEquals(11, heldLayers(geometry.status))
    }

    @Test
    fun theFooterAgreesWithWhatTheBuffersHoldInBothStyles() {
        val value = preview(layers = 200, pathsOf = { 40 })
        for (style in arrayOf(LayerPreviewStyle.CURRENT_LAYER, LayerPreviewStyle.BUILD_UP)) {
            val geometry = geometry(value, style = style)
            geometry.select(150)
            assertEquals(
                "$style footer names the layers in the buffers",
                geometry.window.count(),
                heldLayers(geometry.status),
            )
            // And the buffers really hold those layers and no others.
            assertEquals(
                geometry.window.sumOf { value.layers[it].segmentCount } * 2,
                geometry.pathVertexCount(),
            )
        }
    }
}
