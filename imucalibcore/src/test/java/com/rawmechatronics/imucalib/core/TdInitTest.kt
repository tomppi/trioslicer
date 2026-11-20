package com.rawmechatronics.imucalib.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class TdInitTest {
    @Test
    fun multiStartTimeOffset() {
        val dir = System.getenv("IMU_CALIB_SESSION") ?: ""
        assumeTrue(dir.isNotEmpty())
        val s = CalibrationSession.fromCsv(File(dir, "frames.csv").readText(), File(dir, "imu.csv").readText())!!
        for (td0 in doubleArrayOf(-0.030, -0.015, 0.0, 0.010, 0.013, 0.020, 0.030)) {
            val cal = KalibrCalibrator(s.boardPoses, s.imu,
                CalibOptions(splineDt = 0.2, imuSubsample = 8, maxIterations = 20))
            val r = cal.calibrate(td0)
            val p = r.params
            println("td0=" + (td0 * 1000) + " ms -> td=" + String.format(java.util.Locale.US, "%.3f", p.td * 1000) +
                " rot=" + String.format(java.util.Locale.US, "%.3f,%.3f,%.3f",
                    Math.toDegrees(p.rCi[0]), Math.toDegrees(p.rCi[1]), Math.toDegrees(p.rCi[2])) +
                " cost=%.0f".format(java.util.Locale.US, r.diagnostics.cost) +
                " board=%.3f/%.3f gyro=%.5f accel=%.5f".format(java.util.Locale.US,
                    r.diagnostics.rmsBoardRotDeg, r.diagnostics.rmsBoardTransMm,
                    r.diagnostics.rmsGyro, r.diagnostics.rmsAccel))
        }
    }
}
