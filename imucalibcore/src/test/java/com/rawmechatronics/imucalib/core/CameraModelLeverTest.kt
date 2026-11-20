package com.rawmechatronics.imucalib.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class CameraModelLeverTest {
    @Test
    fun leverWithDifferentCameraModels() {
        val dir = System.getenv("IMU_CALIB_SESSION") ?: ""
        assumeTrue(dir.isNotEmpty())
        val session = CalibrationSession.fromCsv(
            File(dir, "frames.csv").readText(), File(dir, "imu.csv").readText(),
            File(dir, "corners.csv").readText())!!
        val models = mapOf(
            "kalibr" to PinholeModel(431.19137640949805, 430.718509351878, 324.17995250822435, 249.9409677695653,
                0.0534236944418012, -0.06088468904926652, 0.006674683680240228, 0.0001703966471319626),
            "charuco" to PinholeModel(410.63161235, 408.61161111, 338.42060697, 244.80774179,
                -0.00121771, 0.04393233, -0.0051883, 0.00894831),
            "engine" to PinholeModel(426.736804189, 407.352849620, 363.817280001, 240.899048294,
                0.161015277, -0.849706480, 0.0, 0.0),
        )
        for ((name, cam) in models) {
            val opts = CalibOptions(splineDt = 0.02, imuSubsample = 4, maxIterations = 100,
                useBoardPosePrior = false, pixelSigmaPx = 0.3,
                estimateLeverArm = true, leverArmSigma = 0.02)
            val cal = KalibrCalibrator(session.boardPoses, session.imu, opts, session.corners, cam, session.board)
            val r = cal.calibrate()
            val p = r.params
            println(name + " rot=" + String.format(java.util.Locale.US, "%.3f,%.3f,%.3f",
                    Math.toDegrees(p.rCi[0]), Math.toDegrees(p.rCi[1]), Math.toDegrees(p.rCi[2])) +
                " td=%.3f".format(java.util.Locale.US, p.td*1000) +
                " pCi=%.2f,%.2f,%.2f".format(java.util.Locale.US, p.pCi[0]*1000,p.pCi[1]*1000,p.pCi[2]*1000) +
                " pixel=%.3f gyro=%.5f accel=%.5f".format(java.util.Locale.US,
                    r.diagnostics.rmsPixelPx, r.diagnostics.rmsGyro, r.diagnostics.rmsAccel))
        }
    }
}
