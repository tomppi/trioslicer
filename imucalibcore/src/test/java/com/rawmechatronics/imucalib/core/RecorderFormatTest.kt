
package com.rawmechatronics.imucalib.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parser must accept the scanner recorder's real CSV layout, including the column shift. */
class RecorderFormatTest {

    private fun poseField(r: DoubleArray, t: DoubleArray): String {
        val v = doubleArrayOf(r[0], r[1], r[2], t[0], r[3], r[4], r[5], t[1], r[6], r[7], r[8], t[2], 0.0, 0.0, 0.0, 1.0)
        return v.joinToString(" ") { String.format(java.util.Locale.US, "%.12f", it) }
    }

    private val header = "frame,ts_ns,scan_on,detected,corners,cam_x,cam_y,cam_z,roll,pitch,yaw,distance,reproj_rms,fx,fy,cx,cy,laser_state,laser_points,proc_ms,laser_cam_count,pose16,vio_pose16,jpeg"

    @Test
    fun parsesPoseWhenTheRecorderShiftedItOneColumn() {
        val r = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val tBc = doubleArrayOf(-0.4, -0.1, 0.2)
        val pose = poseField(r, tBc)
        // like the real captures: pose16 column is "0", the 16 floats sit one column later
        val row = "0,1000000000000,1.0,1.0,40,0,0,0,0,0,0,0,0.2,640.1,611.0,545.0,361.0,0,0,16,56,0," + pose + ",f00000.jpg"
        val poses = CalibrationSession.parseFramesCsv(header + "\n" + row + "\n")
        assertEquals(1, poses.size)
        val p = poses[0]
        assertEquals(0.0, So3.angleDeg(So3.mul(So3.transpose(p.rWc), So3.identity())), 1e-9)
        assertEquals(0.4, p.pWc[0], 1e-9)
        assertEquals(0.1, p.pWc[1], 1e-9)
        assertEquals(-0.2, p.pWc[2], 1e-9)
    }

    @Test
    fun convertsMillimetrePosesToMetres() {
        val r = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val tBc = doubleArrayOf(-400.0, -100.0, 200.0)
        val pose = poseField(r, tBc)
        val row = "0,1000000000000,1.0,1.0,40,0,0,0,0,0,0,0,0.2,640.1,611.0,545.0,361.0,0,0,16,56," + pose + ",,f00000.jpg"
        val poses = CalibrationSession.parseFramesCsv(header + "\n" + row + "\n")
        assertEquals(1, poses.size)
        assertEquals(0.4, poses[0].pWc[0], 1e-9)
    }

    @Test
    fun mergesAsynchronousGyroAndAccelRows() {
        val onePose = poseField(doubleArrayOf(1.0,0.0,0.0,0.0,1.0,0.0,0.0,0.0,1.0), doubleArrayOf(-0.4,0.0,0.0))
        val frames = "frame,ts_ns,scan_on,detected,pose16,jpeg\n0,1000000000,1.0,1.0," + onePose + ",f00000.jpg\n"
        val rows = StringBuilder("type,ts_ns,x,y,z\n")
        for (i in 0 until 60) {
            rows.append("g,").append(1000000000L + i * 2_000_000L).append(",0.01,0.02,0.03\n")
            rows.append("a,").append(1000000000L + i * 2_000_000L + 900_000L).append(",0.1,0.2,9.8\n")
        }
        val session = CalibrationSession.fromCsv(frames, rows.toString())
        assertTrue(session != null)
        session!!
        // union of the 10 gyro and 10 accel timestamps
        assertEquals(120, session.imu.size)
        for (s in session.imu) {
            assertEquals(0.1, s.accel[0], 1e-6)
            assertEquals(9.8, s.accel[2], 1e-6)
        }
    }
}
