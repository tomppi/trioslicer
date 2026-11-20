package com.rawmechatronics.imucalib.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BoardTimingTest {

    @Test
    fun meanRowOfCentredBoardIsThePrincipalRow() {
        // Camera at (90,135,500) mm looking down -Z at the board plane; its pose in
        // the board frame has R = diag(1,-1,-1), p = (90,135,500).
        val bp = BoardPose(
            t = 0.0,
            rWc = doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, -1.0),
            pWc = doubleArrayOf(0.09, 0.135, 0.5),
        )
        val cam = CalibCameraModel(430.0, 430.0, 320.0, 240.0, doubleArrayOf(0.0, 0.0, 0.0, 0.0))
        val row = BoardTiming.meanCornerRow(listOf(bp), cam)
        assertNotNull(row)
        assertEquals(240.0, row!!, 2.0)
    }

    @Test
    fun rowFollowsTheCameraPose() {
        val cam = CalibCameraModel(430.0, 430.0, 320.0, 240.0, doubleArrayOf(0.0, 0.0, 0.0, 0.0))
        val base = BoardPose(
            t = 0.0,
            rWc = doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, -1.0),
            pWc = doubleArrayOf(0.09, 0.135, 0.5),
        )
        val up = base.copy(pWc = doubleArrayOf(base.pWc[0], base.pWc[1] + 0.05, base.pWc[2])) // camera higher -> board lower in the image
        val r0 = BoardTiming.meanCornerRow(listOf(base), cam)!!
        val r1 = BoardTiming.meanCornerRow(listOf(up), cam)!!
        org.junit.Assert.assertTrue("row should increase when the camera is higher: $r0 -> $r1", r1 > r0 + 10.0)
    }
}
