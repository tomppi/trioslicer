package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GizmoDragTest {
    @Test
    fun anArrowMeasuredAgainstItsOwnProjection() {
        // The arrow covers 100 px on screen and stands for 50 mm, so a drag of
        // 100 px along it is 50 mm.
        val millimetres = GizmoDrag.axisMillimetres(
            deltaXPx = 100f,
            deltaYPx = 0f,
            fromX = 200f,
            fromY = 300f,
            toX = 300f,
            toY = 300f,
            lengthMm = 50f,
            fallbackMillimetresPerPixel = 0.5f,
        )
        assertEquals(50f, millimetres, 1e-3f)
    }

    @Test
    fun draggingAcrossAnArrowMovesNothing() {
        // Perpendicular to the arrow: no movement along its axis.
        val millimetres = GizmoDrag.axisMillimetres(
            deltaXPx = 0f,
            deltaYPx = 60f,
            fromX = 200f,
            fromY = 300f,
            toX = 300f,
            toY = 300f,
            lengthMm = 50f,
            fallbackMillimetresPerPixel = 0.5f,
        )
        assertEquals(0f, millimetres, 1e-3f)
    }

    @Test
    fun aDiagonalArrowWorksOnBothScreenAxes() {
        // 3-4-5 triangle: the arrow is 50 px long for 25 mm, and a drag of 30 px
        // along it (18, 24) is 15 mm.
        val millimetres = GizmoDrag.axisMillimetres(
            deltaXPx = 18f,
            deltaYPx = 24f,
            fromX = 0f,
            fromY = 0f,
            toX = 30f,
            toY = 40f,
            lengthMm = 25f,
            fallbackMillimetresPerPixel = 0.5f,
        )
        assertEquals(15f, millimetres, 1e-3f)
    }

    @Test
    fun anEndOnAxisFallsBackToVertical() {
        // Projected to a point: the axis points at the viewer.
        val millimetres = GizmoDrag.axisMillimetres(
            deltaXPx = 40f,
            deltaYPx = -20f,
            fromX = 100f,
            fromY = 100f,
            toX = 100f,
            toY = 100f,
            lengthMm = 30f,
            fallbackMillimetresPerPixel = 0.25f,
        )
        assertEquals(5f, millimetres, 1e-4f)
    }

    @Test
    fun aRingTurnsWithTheFingerAroundIt() {
        // Grabbed at the top of the ring, dragged right: clockwise, and a drag the
        // length of the radius is the stated number of degrees.
        val degrees = GizmoDrag.ringDegrees(
            deltaXPx = 100f,
            deltaYPx = 0f,
            centreX = 300f,
            centreY = 300f,
            touchX = 300f,
            touchY = 200f,
            radiusPx = 100f,
            degreesPerRadius = 60f,
        )
        assertEquals(60f, degrees, 1e-3f)
        // The opposite drag turns the other way.
        val back = GizmoDrag.ringDegrees(
            deltaXPx = -100f,
            deltaYPx = 0f,
            centreX = 300f,
            centreY = 300f,
            touchX = 300f,
            touchY = 200f,
            radiusPx = 100f,
        )
        assertEquals(-60f, back, 1e-3f)
    }

    @Test
    fun nonsenseInputsTurnNothing() {
        assertEquals(
            0f,
            GizmoDrag.axisMillimetres(Float.NaN, 0f, 0f, 0f, 10f, 0f, 10f, 0.5f),
            0f,
        )
        assertEquals(0f, GizmoDrag.ringDegrees(10f, 0f, 0f, 0f, 0f, 0f, 0f), 0f)
        assertTrue(GizmoDrag.TOUCH_RADIUS_PX > 0f)
    }
}
