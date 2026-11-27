package com.tomppi.splatproto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HybridSessionTest {
    private fun sessionDir(): File {
        val dir = kotlin.io.path.createTempDirectory("hybrid-session-").toFile()
        File(dir, "laser").mkdirs()
        File(dir, "frames.csv").writeText(
            "frame,ts_ns,scan_on,detected,corners,cam_x,cam_y,cam_z,roll,pitch,yaw,distance," +
                "reproj_rms,fx,fy,cx,cy,laser_state,laser_points,proc_ms,laser_cam_count,pose16,vio_pose16,jpeg\n" +
                "42,1000000000,1,1,36,0,0,0,0,0,0,0,0,640,480,320,240,2,2,1,2," +
                "1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1,1 0 0 0 0 1 0 0 0 0 1 0 0 0 0 1,f00042.jpg\n" +
                "43,1016666666,1,1,36,0,0,0,0,0,0,0,0,640,480,320,240,2,1,1,1," +
                "1 0 0 0 0 1 0 0 0 0 1 0 10 20 30 1,1 0 0 0 0 1 0 0 0 0 1 0 0.1 0.2 0.3 1,f00043.jpg\n"
        )
        val bb = ByteBuffer.allocate(6 * 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putFloat(1f); bb.putFloat(2f); bb.putFloat(3f)
        bb.putFloat(-1f); bb.putFloat(-2f); bb.putFloat(-3f)
        File(dir, "laser/f00042.f32").writeBytes(bb.array())
        return dir
    }

    @Test
    fun findsNearestFrameAndLoadsLaser() {
        val dir = sessionDir()
        val s = HybridSession.load(dir)
        assertEquals(2, s.frames.size)
        val n = s.nearest(1_000_200_000L)
        assertNotNull(n)
        assertEquals(42, n!!.frame.index)
        assertEquals(-200_000L, n.gapNs)
        assertEquals(2, n.frame.laserPoints)
        val pts = s.readLaserPoints(n.frame)!!
        assertEquals(6, pts.size)
        assertEquals(3f, pts[2], 0f)
        assertEquals(-3f, pts[5], 0f)
    }

    @Test
    fun toleranceKeepsUnmappedStillOut() {
        val s = HybridSession.load(sessionDir())
        assertNull(s.nearest(1_200_000_000L))
        assertNotNull(s.nearest(1_010_000_000L))
    }

    @Test
    fun oldSessionWithoutFramesIsEmpty() {
        val dir = kotlin.io.path.createTempDirectory("hybrid-empty-").toFile()
        val s = HybridSession.load(dir)
        assertEquals(0, s.frames.size)
        assertNull(s.nearest(123L))
    }
}
