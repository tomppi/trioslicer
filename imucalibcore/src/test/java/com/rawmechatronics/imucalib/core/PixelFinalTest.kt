package com.rawmechatronics.imucalib.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PixelFinalTest {
    @Test
    fun finalVariants() {
        val dir = System.getenv("IMU_CALIB_SESSION") ?: ""
        assumeTrue(dir.isNotEmpty())
        val session = CalibrationSession.fromCsv(
            File(dir, "frames.csv").readText(), File(dir, "imu.csv").readText(),
            File(dir, "corners.csv").readText())!!
        val cam = PinholeModel(431.19137640949805, 430.718509351878, 324.17995250822435, 249.9409677695653,
            0.0534236944418012, -0.06088468904926652, 0.006674683680240228, 0.0001703966471319626)
        data class Case(val name: String, val dt: Double, val sigma: Double, val lever: Boolean)
        val cases = listOf(
            Case("dt0.02_s0.30", 0.02, 0.30, false),
            Case("dt0.02_s0.35", 0.02, 0.35, false),
            Case("dt0.02_s0.30_lever", 0.02, 0.30, true),
            Case("dt0.025_s0.30", 0.025, 0.30, false),
        )
        for (c in cases) {
            val opts = CalibOptions(splineDt = c.dt, imuSubsample = 4, maxIterations = 120,
                useBoardPosePrior = false, pixelSigmaPx = c.sigma,
                estimateLeverArm = c.lever, leverArmSigma = 0.02)
            val cal = KalibrCalibrator(session.boardPoses, session.imu, opts, session.corners, cam, session.board)
            val r = cal.calibrate()
            val p = r.params
            println(c.name + " rot=" + String.format(java.util.Locale.US, "%.3f,%.3f,%.3f",
                    Math.toDegrees(p.rCi[0]), Math.toDegrees(p.rCi[1]), Math.toDegrees(p.rCi[2])) +
                " td=%.3f".format(java.util.Locale.US, p.td * 1000) +
                " pCi=%.2f,%.2f,%.2f".format(java.util.Locale.US, p.pCi[0]*1000, p.pCi[1]*1000, p.pCi[2]*1000) +
                " pixel=%.3f gyro=%.5f accel=%.5f".format(java.util.Locale.US,
                    r.diagnostics.rmsPixelPx, r.diagnostics.rmsGyro, r.diagnostics.rmsAccel))
        }
    }
}
