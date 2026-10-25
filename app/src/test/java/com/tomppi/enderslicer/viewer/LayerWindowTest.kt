package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layer view holds a window of layers, never the whole print. The current-layer style holds
 * the selected layer and its context; the build-up style holds layer zero to the selected layer,
 * the whole model under the scrub. The window may shrink when the device's profile cannot take
 * it, but it may never drop the selected layer, and a reduced window is named with both numbers.
 */
class LayerWindowTest {

    /** A hundred layers: layer 50 is the dense one, the rest are thin. */
    private val costs = IntArray(100) { index -> if (index == 50) 5_000 else 100 }

    private fun plan(anchor: Int, budget: Int): IntRange =
        LayerWindow.plan(costs.size, { costs[it] }, anchor, budget)

    private fun planBuildUp(anchor: Int, budget: Int): IntRange =
        LayerWindow.planBuildUp(costs.size, { costs[it] }, anchor, budget)

    @Test
    fun theWindowHoldsTheSelectedLayerAndItsContext() {
        val range = plan(anchor = 50, budget = Int.MAX_VALUE)
        assertTrue(50 in range)
        assertEquals(50 - LayerWindow.CONTEXT_BELOW, range.first)
        assertEquals(50 + LayerWindow.CONTEXT_ABOVE, range.last)
    }

    @Test
    fun aBudgetThatOnlyFitsTheLayerLeavesItAlone() {
        val range = plan(anchor = 50, budget = 5_000)
        assertEquals("the selected layer is never dropped", 50..50, range)
    }

    @Test
    fun theSelectedLayerSurvivesABudgetItCannotFit() {
        val range = plan(anchor = 50, budget = 1)
        assertEquals(50..50, range)
        assertEquals(5_000, LayerWindow.segments(range) { costs[it] })
    }

    @Test
    fun aSmallerBudgetShrinksTheContextFirst() {
        val wide = plan(anchor = 50, budget = Int.MAX_VALUE).count()
        val narrow = plan(anchor = 50, budget = 1_500).count()
        assertTrue("a smaller budget holds fewer layers", narrow < wide)
        assertTrue("but never fewer than the selected one", narrow >= 1)
        assertTrue(50 in plan(anchor = 50, budget = 1_500))
    }

    @Test
    fun theWindowStaysInsideThePrint() {
        val first = plan(anchor = 0, budget = Int.MAX_VALUE)
        assertEquals(0, first.first)
        val last = plan(anchor = costs.lastIndex, budget = Int.MAX_VALUE)
        assertEquals(costs.lastIndex, last.last)
    }

    @Test
    fun segmentsAndBytesAddUp() {
        val range = plan(anchor = 10, budget = Int.MAX_VALUE)
        val expected = range.sumOf { costs[it] }
        assertEquals(expected, LayerWindow.segments(range) { costs[it] })
        assertEquals(
            expected.toLong() * (LayerWindow.PATH_POSITION_FLOATS + LayerWindow.PATH_COLOR_FLOATS) * 4L,
            LayerWindow.bytes(expected),
        )
    }

    @Test
    fun halvingWalksDownToTheSelectedLayerAlone() {
        val attempts = BandAllocator.attempts(5_000, LayerWindow.MINIMUM_SEGMENTS, Int.MAX_VALUE)
        assertEquals(5_000, attempts.first())
        assertEquals(LayerWindow.MINIMUM_SEGMENTS, attempts.last())
        // Every attempt still holds the selected layer; the smallest holds only it.
        assertTrue(attempts.all { 50 in plan(anchor = 50, budget = it) })
        assertEquals(50..50, plan(anchor = 50, budget = attempts.last()))
    }

    @Test
    fun theRefusalNamesTheLayerAndBothNumbers() {
        val bytes = LayerWindow.bytes(48_000)
        val text = LayerWindow.refusal(layerNumber = 7, paths = 48_000, bytes = bytes)
        assertTrue(text, text.contains("layer 7"))
        assertTrue(text, text.contains("48000"))
        assertTrue(text, text.contains("MB"))
    }

    // --- the build-up style holds the whole stack, not a context band -------------------------

    @Test
    fun buildUpPlansFromTheBedToTheSelection() {
        val layers = 586
        val perLayer = IntArray(layers) { 1_000 }
        for (anchor in intArrayOf(0, 1, 10, 325, 500, 585)) {
            assertEquals(
                "the build-up window is layer 0 to layer $anchor",
                0..anchor,
                LayerWindow.planBuildUp(layers, { perLayer[it] }, anchor, Int.MAX_VALUE),
            )
        }
    }

    @Test
    fun theReportedPrintHoldsTheWholeStackOnTheReportedDevice() {
        // The user's Cura slice: 586 layers at about a thousand paths a layer.
        val layers = 586
        val perLayer = IntArray(layers) { 1_000 }
        // The reported screenshot's phone: 6.9 GB free, auto profile, a 512 MB heap class.
        val device = PreviewDeviceMemory(
            totalBytes = 8_000_000_000L,
            availableBytes = 6_900_000_000L,
            lowRamDevice = false,
            memoryClassMb = 512,
            largeMemoryClassMb = 512,
        )
        val capacity = PreviewMemoryPolicy.resolve(PreviewMemoryProfile.AUTO, device, conservativeMoves = 1_000_000)
        val budget = LayerWindow.vertexPathBudget(capacity.segments)
        assertEquals(
            "the profile's heap share is 24 bytes a path against 56 in the buffers",
            (capacity.segments.toLong() * PreviewMemoryPolicy.LAYER_SEGMENT_BYTES / 56L).toInt(),
            budget,
        )
        assertTrue(
            "the whole 586000-path stack has to fit the device's own budget, and it does by 4x",
            budget >= 4 * layers * 1_000,
        )
        val range = LayerWindow.planBuildUp(layers, { perLayer[it] }, 585, budget)
        assertEquals(0..585, range)
        assertEquals(586, range.count())
        assertEquals(586_000, LayerWindow.segments(range) { perLayer[it] })
    }

    @Test
    fun aBudgetTooSmallForTheWholeStackHoldsTheTopOfTheBuild() {
        // A thousand paths a layer: 85,000 is room for 85 of the 100 layers.
        val dense = IntArray(100) { 1_000 }
        val range = LayerWindow.planBuildUp(100, { dense[it] }, anchor = 99, segmentBudget = 85_000)
        assertEquals("the selected layer is never dropped", 99, range.last)
        assertEquals("the window holds the layers under the selection that fit", 15, range.first)
        assertEquals(85, range.count())
        assertEquals(85_000, LayerWindow.segments(range) { dense[it] })
    }

    @Test
    fun aBuildUpBudgetOfNothingStillLeavesTheSelectedLayer() {
        val range = planBuildUp(anchor = 50, budget = 0)
        assertEquals(50..50, range)
        assertEquals(5_000, LayerWindow.segments(range) { costs[it] })
    }

    @Test
    fun growingTheSelectionUpwardOnlyExtendsTheSameStack() {
        val layers = 1_000
        val perLayer = IntArray(layers) { 100 }
        val first = LayerWindow.planBuildUp(layers, { perLayer[it] }, anchor = 400, segmentBudget = Int.MAX_VALUE)
        val grown = LayerWindow.planBuildUp(layers, { perLayer[it] }, anchor = 401, segmentBudget = Int.MAX_VALUE)
        assertEquals(0..400, first)
        assertEquals(0..401, grown)
        assertEquals("the grown window keeps every held layer", first, grown.first..(grown.first + first.count() - 1))
    }

    @Test
    fun theBuildUpBudgetIsTheProfilesHeapShareInVertexBytes() {
        // 24 bytes a stored path from the profile buys 56-byte path vertices.
        assertEquals("the floor is one path, so a selection is never dropped", LayerWindow.MINIMUM_SEGMENTS, LayerWindow.vertexPathBudget(0))
        assertEquals(LayerWindow.MINIMUM_SEGMENTS, LayerWindow.vertexPathBudget(1))
        assertEquals(428_571, LayerWindow.vertexPathBudget(1_000_000))
        assertEquals(
            (5_590_000L * PreviewMemoryPolicy.LAYER_SEGMENT_BYTES / 56L).toInt(),
            LayerWindow.vertexPathBudget(5_590_000),
        )
    }
}
