package com.tomppi.enderslicer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacementHistoryTest {
    private fun placement(x: Double): ModelPlacement =
        ModelPlacement(centerXmm = x, centerYmm = 0.0, baseZmm = 0.0)

    @Test
    fun undoTakesBackTheStepThatWasRecorded() {
        val history = PlacementHistory()
        assertFalse(history.canUndo)
        assertNull(history.undo())

        history.record("Model moved", placement(1.0))
        history.record("Manual rotation", placement(2.0))

        assertTrue(history.canUndo)
        assertEquals("Manual rotation", history.nextLabel)
        assertEquals(2.0, history.undo()?.placement?.centerXmm ?: 0.0, 1e-9)
        assertEquals("Model moved", history.nextLabel)
        assertEquals(1.0, history.undo()?.placement?.centerXmm ?: 0.0, 1e-9)
        assertFalse(history.canUndo)
        assertNull(history.undo())
    }

    @Test
    fun theLabelTravelsWithTheStepItBelongsTo() {
        val history = PlacementHistory()
        history.record("Model scaled to 120%", placement(3.0))
        val step = history.undo()
        assertEquals("Model scaled to 120%", step?.label)
        assertEquals(3.0, step?.placement?.centerXmm ?: 0.0, 1e-9)
    }

    @Test
    fun theOldestStepsFallOffTheEnd() {
        val history = PlacementHistory(limit = 3)
        for (index in 1..5) {
            history.record("Step " + index, placement(index.toDouble()))
        }
        // Only the last three survive, newest first when taken back.
        assertEquals(5.0, history.undo()?.placement?.centerXmm ?: 0.0, 1e-9)
        assertEquals(4.0, history.undo()?.placement?.centerXmm ?: 0.0, 1e-9)
        assertEquals(3.0, history.undo()?.placement?.centerXmm ?: 0.0, 1e-9)
        assertNull(history.undo())
    }

    @Test
    fun clearForgetsEverything() {
        val history = PlacementHistory()
        history.record("Model moved", placement(1.0))
        history.clear()
        assertFalse(history.canUndo)
        assertNull(history.nextLabel)
    }
}
