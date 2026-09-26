package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BedPlaneDragTest {
    @Test
    fun millimetresPerPixelFollowsTheViewportAndTheFieldOfView() {
        // A 90-degree field of view at 100 mm, on a 1000 px viewport: the visible
        // height is exactly twice the distance, so a pixel is 0.2 mm.
        assertEquals(
            0.2f,
            BedPlaneDrag.millimetresPerPixel(100f, 1000, 90f, 1f),
            1e-6f,
        )
        // Half the viewport height means twice the millimetres per pixel.
        assertEquals(
            0.4f,
            BedPlaneDrag.millimetresPerPixel(100f, 500, 90f, 1f),
            1e-6f,
        )
        assertEquals(0f, BedPlaneDrag.millimetresPerPixel(0f, 1000, 42f, 1.17f), 0f)
    }

    @Test
    fun aFlatViewMovesTheModelWithTheFinger() {
        // Camera on the plate's -Y side looking at it: screen right is plate +X,
        // and a finger dragged up moves the model away from the viewer, +Y.
        val right = BedPlaneDrag.plateDeltaMm(10f, 0f, 0.5f, yawDegrees = 0f, pitchDegrees = 0f)
        assertEquals(5f, right[0], 1e-4f)
        assertEquals(0f, right[1], 1e-4f)

        val up = BedPlaneDrag.plateDeltaMm(0f, -10f, 0.5f, yawDegrees = 0f, pitchDegrees = 0f)
        assertEquals(0f, up[0], 1e-4f)
        assertEquals(5f, up[1], 1e-4f)

        val down = BedPlaneDrag.plateDeltaMm(0f, 10f, 0.5f, yawDegrees = 0f, pitchDegrees = 0f)
        assertEquals(-5f, down[1], 1e-4f)
    }

    @Test
    fun yawTurnsTheScreenAxesIntoThePlateAxes() {
        // A quarter turn puts the plate's +X where the viewer's up is, so a finger
        // moving right now moves the model along the plate's -Y.
        val turned = BedPlaneDrag.plateDeltaMm(10f, 0f, 0.5f, yawDegrees = 90f, pitchDegrees = 0f)
        assertEquals(0f, turned[0], 1e-4f)
        assertEquals(-5f, turned[1], 1e-4f)
    }

    @Test
    fun anEdgeOnPlateSwallowsTheVerticalComponent() {
        // Looking along the plate, a finger dragged up would have to lift the
        // model off the bed: the move stays flat and that part is dropped.
        val vertical = BedPlaneDrag.plateDeltaMm(0f, -10f, 0.5f, yawDegrees = 0f, pitchDegrees = 90f)
        assertEquals(0f, vertical[0], 1e-4f)
        assertEquals(0f, vertical[1], 1e-4f)

        // Horizontal movement still reaches the plate.
        val horizontal = BedPlaneDrag.plateDeltaMm(10f, 0f, 0.5f, yawDegrees = 0f, pitchDegrees = 90f)
        assertEquals(5f, horizontal[0], 1e-4f)
        assertEquals(0f, horizontal[1], 1e-4f)
    }

    @Test
    fun theMovementScalesWithTheDrag() {
        val small = BedPlaneDrag.plateDeltaMm(4f, 3f, 0.4f, yawDegrees = 31f, pitchDegrees = -22f)
        val large = BedPlaneDrag.plateDeltaMm(8f, 6f, 0.4f, yawDegrees = 31f, pitchDegrees = -22f)
        assertEquals(small[0] * 2f, large[0], 1e-3f)
        assertEquals(small[1] * 2f, large[1], 1e-3f)
        assertTrue(small[0] != 0f || small[1] != 0f)
    }

    @Test
    fun nonsenseInputsMoveNothing() {
        val zero = BedPlaneDrag.plateDeltaMm(Float.NaN, 4f, 0.5f, 0f, 0f)
        assertEquals(0f, zero[0], 0f)
        assertEquals(0f, zero[1], 0f)
        val noScale = BedPlaneDrag.plateDeltaMm(10f, 10f, Float.NaN, 0f, 0f)
        assertEquals(0f, noScale[0], 0f)
        assertEquals(0f, noScale[1], 0f)
    }
}
