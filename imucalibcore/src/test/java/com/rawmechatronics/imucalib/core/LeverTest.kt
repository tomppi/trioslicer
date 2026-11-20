package com.rawmechatronics.imucalib.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class LeverTest {
    @Test
    fun estimateLeverArmOnFedSession() {
        val dir = System.getenv("IMU_CALIB_SESSION") ?: ""
        assumeTrue(dir.isNotEmpty())
        val s = CalibrationSession.fromCsv(File(dir, "frames.csv").readText(), File(dir, "imu.csv").readText())!!
        for (lever in doubleArrayOf(0.05, 0.02, 0.01)) {
            val cal = KalibrCalibrator(s.boardPoses, s.imu,
                CalibOptions(splineDt = 0.2, imuSubsample = 8, maxIterations = 20,
                    estimateLeverArm = true, leverArmSigma = lever))
            val r = cal.calibrate()
            val p = r.params
            println("leverSigma=" + lever + " pCi_mm=" + String.format(java.util.Locale.US, "%.2f,%.2f,%.2f",
                    p.pCi[0] * 1000, p.pCi[1] * 1000, p.pCi[2] * 1000) +
                " rot=" + String.format(java.util.Locale.US, "%.3f,%.3f,%.3f",
                    Math.toDegrees(p.rCi[0]), Math.toDegrees(p.rCi[1]), Math.toDegrees(p.rCi[2])) +
                " td=%.3f".format(java.util.Locale.US, p.td * 1000) +
                " board=%.3f/%.3f gyro=%.5f accel=%.5f".format(java.util.Locale.US,
                    r.diagnostics.rmsBoardRotDeg, r.diagnostics.rmsBoardTransMm,
                    r.diagnostics.rmsGyro, r.diagnostics.rmsAccel))
        }
    }
}
