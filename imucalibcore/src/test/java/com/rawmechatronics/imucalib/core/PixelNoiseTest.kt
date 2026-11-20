package com.rawmechatronics.imucalib.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PixelNoiseTest {
    @Test
    fun pixelFitWithKalibrLikeImuNoise() {
        val dir = System.getenv("IMU_CALIB_SESSION") ?: ""
        assumeTrue(dir.isNotEmpty())
        val session = CalibrationSession.fromCsv(
            File(dir, "frames.csv").readText(), File(dir, "imu.csv").readText(),
            File(dir, "corners.csv").readText())!!
        val cam = PinholeModel(431.19137640949805, 430.718509351878, 324.17995250822435, 249.9409677695653,
            0.0534236944418012, -0.06088468904926652, 0.006674683680240228, 0.0001703966471319626)
        val sg = 1.4e-4 * kotlin.math.sqrt(480.9)
        val sa = 6.0e-4 * kotlin.math.sqrt(480.9)
        val variants = listOf(
            Triple("sub2_fixed", 2, true),
            Triple("sub1_fixed", 1, true),
            Triple("sub4_measured", 4, false),
        )
        for ((name, sub, fixed) in variants) {
            val opts = if (fixed) CalibOptions(splineDt = 0.05, imuSubsample = sub, maxIterations = 200,
                useBoardPosePrior = false, pixelSigmaPx = 0.4,
                gyroSigmaOverride = sg, accelSigmaOverride = sa)
            else CalibOptions(splineDt = 0.05, imuSubsample = sub, maxIterations = 200,
                useBoardPosePrior = false, pixelSigmaPx = 0.4)
            val t0 = System.nanoTime()
            val cal = KalibrCalibrator(session.boardPoses, session.imu, opts, session.corners, cam, session.board)
            val r = cal.calibrate()
            val p = r.params
            println(name + " ms=" + (System.nanoTime() - t0) / 1_000_000 +
                " rot=" + String.format(java.util.Locale.US, "%.3f,%.3f,%.3f",
                    Math.toDegrees(p.rCi[0]), Math.toDegrees(p.rCi[1]), Math.toDegrees(p.rCi[2])) +
                " td=%.3f".format(java.util.Locale.US, p.td * 1000) +
                " pixel=%.3f gyro=%.5f accel=%.5f".format(java.util.Locale.US,
                    r.diagnostics.rmsPixelPx, r.diagnostics.rmsGyro, r.diagnostics.rmsAccel))
        }
    }
}
