
package com.rawmechatronics.imucalib.core

import java.util.Locale

/** CSV writers with the exact recorder formats (used by tests and offline tooling). */
object SessionCsv {

    fun framesCsv(board: List<BoardPose>, offset: Double = 0.0): String {
        val sb = StringBuilder()
        sb.append("frame,ts_ns,scan_on,detected,corners,cam_x,cam_y,cam_z,roll,pitch,yaw,distance,reproj_rms,fx,fy,cx,cy,laser_state,laser_points,proc_ms,laser_cam_count,pose16,vio_pose16,jpeg\n")
        board.forEachIndexed { i, b ->
            // write the engine's board -> camera pose: inverse of the camera pose in the board
            val r = b.rWc
            val p = b.pWc
            val rBc = doubleArrayOf(r[0], r[3], r[6], r[1], r[4], r[7], r[2], r[5], r[8])
            val tBc = doubleArrayOf(
                -(rBc[0] * p[0] + rBc[1] * p[1] + rBc[2] * p[2]),
                -(rBc[3] * p[0] + rBc[4] * p[1] + rBc[5] * p[2]),
                -(rBc[6] * p[0] + rBc[7] * p[1] + rBc[8] * p[2]),
            )
            val vals = doubleArrayOf(rBc[0], rBc[1], rBc[2], tBc[0], rBc[3], rBc[4], rBc[5], tBc[1], rBc[6], rBc[7], rBc[8], tBc[2], 0.0, 0.0, 0.0, 1.0)
            val pose = vals.joinToString(" ") { String.format(Locale.US, "%.12f", it) }
            sb.append(i).append(',')
            sb.append(((b.t + offset) * 1e9).toLong()).append(',')
            sb.append("1.0,1.0,40,")
            sb.append("0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,")
            sb.append("640.0,480.0,320.0,240.0,")
            sb.append("0,0,30.0,0,")
            sb.append(pose).append(",,").append("f%05d.jpg".format(i)).append('\n')
        }
        return sb.toString()
    }

    fun imuCsv(samples: List<ImuSample>, offset: Double = 0.0): String {
        val sb = StringBuilder()
        sb.append("type,ts_ns,x,y,z\n")
        for (s in samples) {
            val ts = ((s.t + offset) * 1e9).toLong()
            sb.append(String.format(Locale.US, "g,%d,%.9f,%.9f,%.9f\n", ts, s.gyro[0], s.gyro[1], s.gyro[2]))
            sb.append(String.format(Locale.US, "a,%d,%.9f,%.9f,%.9f\n", ts, s.accel[0], s.accel[1], s.accel[2]))
        }
        return sb.toString()
    }
}
