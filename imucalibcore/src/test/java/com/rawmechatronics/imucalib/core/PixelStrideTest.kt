package com.rawmechatronics.imucalib.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PixelStrideTest {
    @Test
    fun pixelReprojectionAtReducedFrameRate() {
        val dir = System.getenv("IMU_CALIB_SESSION") ?: ""
        assumeTrue(dir.isNotEmpty())
        val frames = File(dir, "frames.csv").readText()
        val imu = File(dir, "imu.csv").readText()
        val session = CalibrationSession.fromCsv(frames, imu, File(dir, "corners.csv").readText())!!
        val cam = PinholeModel(431.19137640949805, 430.718509351878, 324.17995250822435, 249.9409677695653,
            0.0534236944418012, -0.06088468904926652, 0.006674683680240228, 0.0001703966471319626)
        val byTime = session.corners.groupBy { it.t }
        val times = byTime.keys.sorted()
        for (stride in intArrayOf(2, 3)) {
            val keep = times.filterIndexed { i, _ -> i % stride == 0 }.toHashSet()
            val obs = session.corners.filter { it.t in keep }
            val t0 = System.nanoTime()
            val cal = KalibrCalibrator(session.boardPoses, session.imu,
                CalibOptions(splineDt = 0.05, imuSubsample = 4, maxIterations = 40,
                    useBoardPosePrior = false, pixelSigmaPx = 0.4),
                obs, cam, session.board)
            val r = cal.calibrate()
            val ms = (System.nanoTime() - t0) / 1_000_000
            val p = r.params
            println("stride=" + stride + " obs=" + obs.size + " ms=" + ms +
                " rot=" + String.format(java.util.Locale.US, "%.3f,%.3f,%.3f",
                    Math.toDegrees(p.rCi[0]), Math.toDegrees(p.rCi[1]), Math.toDegrees(p.rCi[2])) +
                " td=%.3f".format(java.util.Locale.US, p.td * 1000) +
                " pixel=%.3fpx".format(java.util.Locale.US, r.diagnostics.rmsPixelPx) +
                " gyro=%.5f accel=%.5f".format(java.util.Locale.US, r.diagnostics.rmsGyro, r.diagnostics.rmsAccel))
        }
    }
}
