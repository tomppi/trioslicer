package com.rawmechatronics.imucalib.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ImuResidualTest {
    @Test
    fun residualVariants() {
        val dir = System.getenv("IMU_CALIB_SESSION") ?: ""
        assumeTrue(dir.isNotEmpty())
        val session = CalibrationSession.fromCsv(
            File(dir, "frames.csv").readText(), File(dir, "imu.csv").readText(),
            File(dir, "corners.csv").readText())!!
        val cam = PinholeModel(431.19137640949805, 430.718509351878, 324.17995250822435, 249.9409677695653,
            0.0534236944418012, -0.06088468904926652, 0.006674683680240228, 0.0001703966471319626)
        data class C(val name: String, val rate: Double, val sg: Double, val sa: Double, val sub: Int, val gFixed: Double, val aFixed: Double)
        val cases = listOf(
            C("rate1_rw", 1.0, 1e-5, 1e-3, 4, 0.0, 0.0),
            C("rate1_rw_half", 1.0, 5e-6, 5e-4, 4, 0.0, 0.0),
            C("rate0.1_sub2_kalibrnoise", 0.1, 1e-4, 1e-3, 2, 0.00307, 0.01316),
        )
        for (c in cases) {
            val opts = CalibOptions(splineDt = 0.02, imuSubsample = c.sub, maxIterations = 80,
                useBoardPosePrior = false, pixelSigmaPx = 0.30,
                biasKnotsPerSecond = c.rate,
                biasSmoothSigmaG = c.sg, biasSmoothSigmaA = c.sa,
                gyroSigmaOverride = c.gFixed, accelSigmaOverride = c.aFixed,
                estimateLeverArm = true, leverArmSigma = 0.02)
            val t0 = System.nanoTime()
            val cal = KalibrCalibrator(session.boardPoses, session.imu, opts, session.corners, cam, session.board)
            val r = cal.calibrate()
            val p = r.params
            println(c.name + " ms=" + (System.nanoTime()-t0)/1_000_000 +
                " rot=" + String.format(java.util.Locale.US, "%.3f,%.3f,%.3f",
                    Math.toDegrees(p.rCi[0]), Math.toDegrees(p.rCi[1]), Math.toDegrees(p.rCi[2])) +
                " td=%.3f".format(java.util.Locale.US, p.td*1000) +
                " pCi=%.2f,%.2f,%.2f".format(java.util.Locale.US, p.pCi[0]*1000,p.pCi[1]*1000,p.pCi[2]*1000) +
                " pixel=%.3f gyro=%.5f accel=%.5f".format(java.util.Locale.US,
                    r.diagnostics.rmsPixelPx, r.diagnostics.rmsGyro, r.diagnostics.rmsAccel))
        }
    }
}
