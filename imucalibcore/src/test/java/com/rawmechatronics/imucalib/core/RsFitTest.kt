package com.rawmechatronics.imucalib.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class RsFitTest {
    @Test
    fun rowShiftGrid() {
        val root = System.getenv("RS_ROOT") ?: return
        val names = listOf("session-rs-0.15","session-rs-0.10","session-rs-0.05","session-rs+0.00","session-rs+0.05","session-rs+0.10","session-rs+0.15")
        for (name in names) {
            val dir = File(root, name)
            if (!File(dir, "frames.csv").exists()) continue
            val s = CalibrationSession.fromCsv(File(dir, "frames.csv").readText(), File(dir, "imu.csv").readText()) ?: continue
            val cal = KalibrCalibrator(s.boardPoses, s.imu, CalibOptions(splineDt = 0.2, imuSubsample = 8, maxIterations = 20))
            val r = cal.calibrate()
            println("%-16s cost=%.0f td=%.3fms rot=%.3f,%.3f,%.3f board=%.3f/%.3f gyro=%.5f accel=%.5f".format(
                java.util.Locale.US, name, r.diagnostics.cost, r.params.td * 1000,
                Math.toDegrees(r.params.rCi[0]), Math.toDegrees(r.params.rCi[1]), Math.toDegrees(r.params.rCi[2]),
                r.diagnostics.rmsBoardRotDeg, r.diagnostics.rmsBoardTransMm, r.diagnostics.rmsGyro, r.diagnostics.rmsAccel))
        }
    }
}
