package com.rawmechatronics.imucalib.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale

class LeverFixTest {
    @Test
    fun leverVariants() {
        val dir = System.getenv("IMU_CALIB_SESSION") ?: ""
        assumeTrue(dir.isNotEmpty())
        val session = CalibrationSession.fromCsv(
            File(dir, "frames.csv").readText(), File(dir, "imu.csv").readText(),
            File(dir, "corners.csv").readText())!!
        val cam = PinholeModel(431.19137640949805, 430.718509351878, 324.17995250822435, 249.9409677695653,
            0.0534236944418012, -0.06088468904926652, 0.006674683680240228, 0.0001703966471319626)
        data class V(val name: String, val leverSigma: Double, val biasKnots: Double,
                     val fixedTd: Double, val tdTarget: Double, val tdSigma: Double)
        val variants = listOf(
            V("A fixed td", 0.02, 0.1, 0.013166, Double.NaN, 0.0),
            V("B fixed td no lever prior", 1e6, 0.1, 0.013166, Double.NaN, 0.0),
            V("C td prior no lever prior", 1e6, 0.1, Double.NaN, 0.013166, 5e-4),
            V("D bias 1/s no lever prior", 1e6, 1.0, Double.NaN, Double.NaN, 0.0),
        )
        for (v in variants) {
            val opts = CalibOptions(splineDt = 0.02, imuSubsample = 4, maxIterations = 100,
                useBoardPosePrior = false, pixelSigmaPx = 0.30, biasKnotsPerSecond = v.biasKnots,
                biasSmoothSigmaG = 1e-4, biasSmoothSigmaA = 1e-3,
                estimateLeverArm = true, leverArmSigma = v.leverSigma,
                fixedTimeOffset = v.fixedTd, tdPriorTarget = v.tdTarget, tdPriorSigma = v.tdSigma)
            val t0 = System.nanoTime()
            val cal = KalibrCalibrator(session.boardPoses, session.imu, opts, session.corners, cam, session.board)
            val res = cal.calibrate()
            val p = res.params
            println(String.format(Locale.US,
                "%s | ms=%d rot=%.3f,%.3f,%.3f td=%.3f pCi=%.2f,%.2f,%.2f pixel=%.3f gyro=%.5f accel=%.5f it=%d",
                v.name, (System.nanoTime()-t0)/1_000_000,
                Math.toDegrees(p.rCi[0]), Math.toDegrees(p.rCi[1]), Math.toDegrees(p.rCi[2]), p.td*1000,
                p.pCi[0]*1000, p.pCi[1]*1000, p.pCi[2]*1000,
                res.diagnostics.rmsPixelPx, res.diagnostics.rmsGyro, res.diagnostics.rmsAccel, res.diagnostics.iterations))
        }
    }
}
