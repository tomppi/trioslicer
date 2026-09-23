package com.tomppi.enderslicer.smartinfill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The selection maths a remote point mass depends on. */
class SmartInfillSelectionTest {

    /** Two triangles of a 10 x 10 mm square in the z = 5 plane. */
    private val square = floatArrayOf(
        0f, 0f, 5f, 10f, 0f, 5f, 10f, 10f, 5f,
        0f, 0f, 5f, 10f, 10f, 5f, 0f, 10f, 5f,
    )

    @Test
    fun theCentroidIsAreaWeighted() {
        val centroid = SmartInfillSelection.centroid(square, intArrayOf(0, 1))!!
        assertEquals(5.0, centroid[0], 1e-4)
        assertEquals(5.0, centroid[1], 1e-4)
        assertEquals(5.0, centroid[2], 1e-6)
    }

    @Test
    fun anUnequalSplitStillLandsOnTheArea() {
        // Add a small triangle far away: it must pull the centroid less than an
        // equal-area one would.
        val positions = square + floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
        val onlySquare = SmartInfillSelection.centroid(positions, intArrayOf(0, 1))!!
        val withSliver = SmartInfillSelection.centroid(positions, intArrayOf(0, 1, 2))!!
        assertEquals(5.0, onlySquare[0], 1e-4)
        assertEquals(true, withSliver[0] < 5.0)
        assertEquals(true, withSliver[0] > 4.0)
    }

    @Test
    fun nothingSelectedHasNoCentroid() {
        assertNull(SmartInfillSelection.centroid(square, IntArray(0)))
        assertNull("out-of-range indices cannot snap a mass", SmartInfillSelection.centroid(square, intArrayOf(9)))
        assertNull(
            "a degenerate triangle has no area to weight by",
            SmartInfillSelection.centroid(floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f, 2f, 2f, 2f), intArrayOf(0)),
        )
    }
}
