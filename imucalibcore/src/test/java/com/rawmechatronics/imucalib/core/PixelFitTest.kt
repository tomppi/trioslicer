package com.rawmechatronics.imucalib.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class PixelFitTest {
    @Test
    fun pixelReprojectionOnCapturedSession() {
        val dir = System.getenv("IMU_CALIB_SESSION") ?: ""
        assumeTrue(dir.isNotEmpty())
        val frames = File(dir, "frames.csv").readText()
        val imu = File(dir, "imu.csv").readText()
        val cornersFile = File(dir, "corners.csv")
        assumeTrue(cornersFile.exists())
        val session = CalibrationSession.fromCsv(frames, imu, cornersFile.readText())!!
        val cam = PinholeModel(431.19137640949805, 430.718509351878, 324.17995250822435, 249.9409677695653,
            0.0534236944418012, -0.06088468904926652, 0.006674683680240228, 0.0001703966471319626)
        println("corners=" + session.corners.size + " poses=" + session.boardPoses.size)
        val cal = KalibrCalibrator(session.boardPoses, session.imu,
            CalibOptions(splineDt = 0.05, imuSubsample = 4, maxIterations = 80,
                useBoardPosePrior = false, pixelSigmaPx = 0.4),
            session.corners, cam, session.board)
        val r = cal.calibrate()
        val p = r.params
        println("PIXELFIT rot=" + String.format(java.util.Locale.US, "%.3f,%.3f,%.3f",
                Math.toDegrees(p.rCi[0]), Math.toDegrees(p.rCi[1]), Math.toDegrees(p.rCi[2])) +
            " td=%.3fms".format(java.util.Locale.US, p.td * 1000) +
            " pixel=%.3fpx".format(java.util.Locale.US, r.diagnostics.rmsPixelPx) +
            " board=%.3fdeg/%.3fmm".format(java.util.Locale.US, r.diagnostics.rmsBoardRotDeg, r.diagnostics.rmsBoardTransMm) +
            " gyro=%.5f accel=%.5f iter=%d".format(java.util.Locale.US, r.diagnostics.rmsGyro, r.diagnostics.rmsAccel, r.diagnostics.iterations))
    }
}
