package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layer view holds a window of layers, never the whole print: the selected layer, the ones
 * under it the build-up style shows as context, and a couple above. The window may shrink when
 * the device refuses a bigger allocation, but it may never drop the selected layer - that is
 * what the refusal sentence is for.
 */
class LayerWindowTest {

    /** A hundred layers: layer 50 is the dense one, the rest are thin. */
    private val costs = IntArray(100) { index -> if (index == 50) 5_000 else 100 }

    private fun plan(anchor: Int, budget: Int): IntRange =
        LayerWindow.plan(costs.size, { costs[it] }, anchor, budget)

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
}
