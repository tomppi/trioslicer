package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        // 3-4-5 triangle: 50 px of arrow for 25 mm, so a drag of 30 px along it
        // (18, 24) is 15 mm.
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

    /**
     * An arrow that is ALMOST end-on is the same problem as one that is exactly
     * end-on: a joint's own depth arrow stands ten millimetres proud and points
     * at the viewer, so its whole arrow projects to a few pixels. Measuring the
     * finger against those pixels turned a 70 px drag into 86 mm of depth; under
     * the threshold the vertical screen axis is the ruler instead.
     */
    @Test
    fun anAlmostEndOnAxisFallsBackTooRatherThanBlowingUp() {
        val millimetres = GizmoDrag.axisMillimetres(
            deltaXPx = 0f,
            deltaYPx = 70f,
            fromX = 100f,
            fromY = 100f,
            toX = 102f,
            toY = 106f,
            lengthMm = 10f,
            fallbackMillimetresPerPixel = 0.06f,
        )
        assertEquals("70 px down is 70 px at the plate's own scale", -4.2f, millimetres, 1e-3f)
    }

    @Test
    fun aRingTurnsAlongItsOwnTangent() {
        // Tangent pointing right: dragging right is a positive turn, and a drag the
        // length of the radius is the stated number of degrees.
        assertEquals(60f, GizmoDrag.ringDegrees(100f, 0f, 1f, 0f, 100f, 60f), 1e-3f)
        assertEquals(-60f, GizmoDrag.ringDegrees(-100f, 0f, 1f, 0f, 100f, 60f), 1e-3f)
        // Across the tangent: no turn.
        assertEquals(0f, GizmoDrag.ringDegrees(0f, 80f, 1f, 0f, 100f), 1e-3f)
    }

    @Test
    fun theTangentComesFromTheRingSoBothSidesTurnTheSameWay() {
        // The ring as it looks on screen: twelve points around a circle of radius
        // 50. The tangent at the top of the screen and the tangent at the bottom
        // point opposite ways on screen, which is what makes the same finger
        // movement turn the ring the same way whichever side was grabbed. Deriving
        // the direction from the touch instead got this backwards on one side.
        val points = FloatArray(24)
        for (index in 0 until 12) {
            val angle = 2.0 * Math.PI * index / 12.0
            points[index * 2] = 100f + 50f * kotlin.math.cos(angle).toFloat()
            points[index * 2 + 1] = 100f + 50f * kotlin.math.sin(angle).toFloat()
        }

        val top = GizmoDrag.ringReference(points, 100f, 50f)
        val bottom = GizmoDrag.ringReference(points, 100f, 150f)

        assertNotNull(top)
        assertNotNull(bottom)
        assertTrue("opposite sides travel opposite ways", top!![0] * bottom!![0] < 0f)
        assertEquals("the ring's radius on screen", 50f, top[2], 1e-2f)
    }

    @Test
    fun aHandleDrawnOverTheModelIsGrabbedEvenWhenGeometryIsInFrontOfIt() {
        // The user's two-part case: the lid covers the X/Y arrows that sit at the
        // lid's own mid-plane. The touch that lands on an arrow is aiming at the
        // arrow - the gizmo is drawn over the model - so screen distance decides
        // and the geometry under the finger does not take the gesture back.
        val lidCoveredAxis = listOf(
            GizmoDrag.HandleCandidate(screenDistancePx = 6f, depthMm = 33f),
            GizmoDrag.HandleCandidate(screenDistancePx = 30f, depthMm = 12f),
        )
        assertEquals(0, GizmoDrag.chooseHandle(lidCoveredAxis, 40f))
        // The nearer-in-depth candidate only wins when the screen distances tie.
        val tie = listOf(
            GizmoDrag.HandleCandidate(screenDistancePx = 6f, depthMm = 33f),
            GizmoDrag.HandleCandidate(screenDistancePx = 7f, depthMm = 12f),
        )
        assertEquals(1, GizmoDrag.chooseHandle(tie, 40f))
    }

    @Test
    fun aTouchAwayFromEveryHandleGrabsNothing() {
        val candidates = listOf(GizmoDrag.HandleCandidate(90f, 10f))
        assertEquals(-1, GizmoDrag.chooseHandle(candidates, 40f))
        assertEquals(-1, GizmoDrag.chooseHandle(emptyList(), 40f))
    }

    @Test
    fun nonsenseCandidatesGrabNothing() {
        val candidates = listOf(
            GizmoDrag.HandleCandidate(Float.NaN, 10f),
            GizmoDrag.HandleCandidate(5f, Float.NaN),
        )
        assertEquals(-1, GizmoDrag.chooseHandle(candidates, 40f))
        assertEquals(-1, GizmoDrag.chooseHandle(listOf(GizmoDrag.HandleCandidate(5f, 10f)), 0f))
    }

    @Test
    fun nonsenseInputsTurnNothing() {
        assertEquals(
            0f,
            GizmoDrag.axisMillimetres(Float.NaN, 0f, 0f, 0f, 10f, 0f, 10f, 0.5f),
            0f,
        )
        assertEquals(0f, GizmoDrag.ringDegrees(10f, 0f, 0f, 0f, 0f), 0f)
        assertEquals(0f, GizmoDrag.ringDegrees(10f, 0f, 1f, 0f, Float.NaN), 0f)
        assertTrue(GizmoDrag.TOUCH_RADIUS_PX > 0f)
    }
}
