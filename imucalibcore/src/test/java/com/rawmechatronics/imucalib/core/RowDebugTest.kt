package com.rawmechatronics.imucalib.core

import org.junit.Test
import java.io.File

class RowDebugTest {
    @Test
    fun rowOnFedSession() {
        val dir = System.getenv("IMU_CALIB_SESSION") ?: return
        val s = CalibrationSession.fromCsv(File(dir, "frames.csv").readText(), File(dir, "imu.csv").readText())!!
        val bp = s.boardPoses[0]
        val cam = CalibCameraModel(431.19137640949805, 430.718509351878, 324.17995250822435, 249.9409677695653,
            doubleArrayOf(0.0534236944418012, -0.06088468904926652, 0.006674683680240228, 0.0001703966471319626))
        var n = 0
        var sum = 0.0
        val r = bp.rWc; val p = bp.pWc
        for (j in 0 until 5) for (i in 0 until 3) {
            val x = doubleArrayOf((i + 1) * 45.0, (j + 1) * 45.0, 0.0)
            val dx = x[0] - p[0]; val dy = x[1] - p[1]; val dz = x[2] - p[2]
            val xc = r[0] * dx + r[3] * dy + r[6] * dz
            val yc = r[1] * dx + r[4] * dy + r[7] * dz
            val zc = r[2] * dx + r[5] * dy + r[8] * dz
            if (!zc.isFinite() || zc < 1e-6) continue
            n++; sum += zc
        }
        println("manual n=" + n + " mean zc=" + (sum / n.coerceAtLeast(1)))
        println("BoardTiming row=" + BoardTiming.meanCornerRow(s.boardPoses, cam))
    }
}
