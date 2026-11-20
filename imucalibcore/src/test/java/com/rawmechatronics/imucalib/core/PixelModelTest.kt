package com.rawmechatronics.imucalib.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelModelTest {

    @Test
    fun boardLayoutMatchesOpenCvCharucoCorners() {
        val b = BoardLayout(4, 6, 45.0)
        assertEquals(15, b.cornerCount)
        assertEquals(0.045, b.corner3d(0)!![0], 1e-9)
        assertEquals(0.045, b.corner3d(0)!![1], 1e-9)
        assertEquals(0.090, b.corner3d(1)!![0], 1e-9)
        assertEquals(0.045, b.corner3d(1)!![1], 1e-9)
        // id 3 is the first corner of the second row
        assertEquals(0.045, b.corner3d(3)!![0], 1e-9)
        assertEquals(0.090, b.corner3d(3)!![1], 1e-9)
        assertNull(b.corner3d(15))
        assertNull(b.corner3d(-1))
    }

    @Test
    fun pinholeProjectsTheOpticalAxisToThePrincipalPoint() {
        val c = PinholeModel(430.0, 431.0, 320.0, 240.0)
        val uv = c.project(0.0, 0.0, 0.5)!!
        assertEquals(320.0, uv[0], 1e-9)
        assertEquals(240.0, uv[1], 1e-9)
        assertNull(c.project(0.0, 0.0, -1.0))
        val right = c.project(0.1, 0.0, 0.5)!!
        assertTrue(right[0] > 320.0)
    }

    @Test
    fun cornersCsvParsesOntoTheSameClock() {
        val board = BoardLayout(4, 6, 45.0)
        val csv = "frame,ts_ns,id,u,v\n0,2000000000,0,100.5,200.25\n0,2000000000,99,1,2\n1,2030000000,1,101,201\n"
        val obs = CalibrationSession.parseCornersCsv(csv, board, 1.5)
        assertEquals(2, obs.size)
        assertEquals(0.5, obs[0].t, 1e-9)
        assertEquals(0, obs[0].id)
        assertEquals(100.5, obs[0].u, 1e-9)
        assertEquals(0.53, obs[1].t, 1e-9)
        assertEquals(1, obs[1].id)
    }

    @Test
    fun sessionCarriesCornersAndBoard() {
        val frames = "frame,ts_ns,detected,pose16\n0,1000000000,1.0,1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1\n"
        val corners = "frame,ts_ns,id,u,v\n0,1000000000,0,100,200\n"
        val imu = StringBuilder("type,ts_ns,x,y,z\n")
        for (i in 0 until 200) {
            imu.append("g,").append(1000000000L + i * 5_000_000L).append(",0.01,0.02,0.03\n")
            imu.append("a,").append(1000000000L + i * 5_000_000L + 1000).append(",0.1,0.2,9.8\n")
        }
        val s = CalibrationSession.fromCsv(frames, imu.toString(), corners)
        assertTrue(s != null)
        assertEquals(1, s!!.corners.size)
        assertEquals(15, s.board.cornerCount)
    }
}
