package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewBudgetAndGridTest {
    @Test
    fun densePreviewPlanStaysInsideByteBudget() {
        val plan = PreviewMemoryBudget.plan(
            totalSegments = 800_000,
            selectedLayerSegments = 800_000,
        )

        assertTrue(plan.totalBytes <= PreviewMemoryBudget.DEFAULT_RENDERER_BYTE_BUDGET)
        assertTrue(plan.baseSegmentLimit > 0)
        assertTrue(plan.ribbonSegmentLimit in 1 until 800_000)
    }

    @Test
    fun checkedFloatCountRejectsIntegerOverflow() {
        val error = runCatching {
            PreviewMemoryBudget.checkedFloatCount(Int.MAX_VALUE, 18)
        }.exceptionOrNull()

        assertTrue(error is ArithmeticException)
    }

    @Test
    fun largeFiniteGridTerminatesWithBoundedLineCount() {
        val values = LayerGridBuilder.build(
            minX = 268_435_456f,
            maxX = 268_435_520f,
            minY = 268_435_456f,
            maxY = 268_435_520f,
            maxLines = 64,
        )

        assertTrue(values.isNotEmpty())
        assertTrue(values.size / 6 <= 64)
        assertEquals(0, values.size % 6)
    }

    @Test
    fun enormousGridIsCoarsenedOrOmittedInsteadOfGrowingWithoutBound() {
        val values = LayerGridBuilder.build(
            minX = -100_000_000f,
            maxX = 100_000_000f,
            minY = -100_000_000f,
            maxY = 100_000_000f,
            maxLines = 128,
        )

        assertTrue(values.size / 6 <= 128)
    }
}
