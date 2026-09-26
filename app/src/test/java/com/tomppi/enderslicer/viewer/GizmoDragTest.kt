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
    fun aHandleBehindTheModelIsNotGrabbable() {
        val candidates = listOf(
            GizmoDrag.HandleCandidate(screenDistancePx = 20f, depthMm = 140f),
            GizmoDrag.HandleCandidate(screenDistancePx = 30f, depthMm = 60f),
        )
        // The finger is on the model at 100 mm, so the far side of the ring at
        // 140 mm is behind it: the slightly further but visible handle wins.
        assertEquals(1, GizmoDrag.chooseHandle(candidates, 40f, surfaceDepthMm = 100f))
        // Off the model nothing is in the way, so the nearest on screen wins.
        assertEquals(0, GizmoDrag.chooseHandle(candidates, 40f, surfaceDepthMm = null))
    }

    @Test
    fun aHandleOnTheSurfaceIsStillGrabbable() {
        val candidates = listOf(GizmoDrag.HandleCandidate(5f, 100.5f))
        assertEquals(0, GizmoDrag.chooseHandle(candidates, 40f, surfaceDepthMm = 100f))
    }

    @Test
    fun aTouchAwayFromEveryHandleGrabsNothing() {
        val candidates = listOf(GizmoDrag.HandleCandidate(90f, 10f))
        assertEquals(-1, GizmoDrag.chooseHandle(candidates, 40f, surfaceDepthMm = null))
        assertEquals(-1, GizmoDrag.chooseHandle(emptyList(), 40f, surfaceDepthMm = 100f))
    }

    @Test
    fun theTwoSidesOfAnEdgeOnRingAreDecidedByDepth() {
        // Both land within a couple of pixels of the touch; the near one is the
        // one being aimed at.
        val candidates = listOf(
            GizmoDrag.HandleCandidate(18f, 150f),
            GizmoDrag.HandleCandidate(19f, 50f),
        )
        assertEquals(1, GizmoDrag.chooseHandle(candidates, 40f, surfaceDepthMm = null))
    }

    @Test
    fun nonsenseCandidatesGrabNothing() {
        val candidates = listOf(
            GizmoDrag.HandleCandidate(Float.NaN, 10f),
            GizmoDrag.HandleCandidate(5f, Float.NaN),
        )
        assertEquals(-1, GizmoDrag.chooseHandle(candidates, 40f, surfaceDepthMm = null))
        assertEquals(-1, GizmoDrag.chooseHandle(listOf(GizmoDrag.HandleCandidate(5f, 10f)), 0f, null))
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
