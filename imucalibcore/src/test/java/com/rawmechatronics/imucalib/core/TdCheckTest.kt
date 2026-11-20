package com.rawmechatronics.imucalib.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class TdCheckTest {
    @Test
    fun gyroCostVsTimeOffset() {
        val dir = System.getenv("IMU_CALIB_SESSION") ?: ""
        assumeTrue(dir.isNotEmpty())
        val s = CalibrationSession.fromCsv(File(dir, "frames.csv").readText(), File(dir, "imu.csv").readText())!!
        val cal = KalibrCalibrator(s.boardPoses, s.imu, CalibOptions(splineDt = 0.2, imuSubsample = 8, maxIterations = 0))
        cal.debugInitializeOnly()
        val R = doubleArrayOf(
            0.016223357727749611, -0.99986611091455824, -0.0021360965881699005,
            -0.99972708342713934, -0.016256944196209176, 0.016777080427392517,
            -0.016809560562469301, 0.0018633330346089425, -0.99985697310350286)
        val rCi = So3.log(R)
        var bestTd = 0.0
        var best = Double.MAX_VALUE
        var td = -0.05
        while (td <= 0.05) {
            val c = cal.debugGyroCost(rCi, td)
            if (c < best) { best = c; bestTd = td }
            td += 0.002
        }
        println("gyro cost best td=" + (bestTd * 1000) + " ms cost=" + best +
            "  at 13.17ms=" + cal.debugGyroCost(rCi, 0.013165851798925843) +
            "  at -5ms=" + cal.debugGyroCost(rCi, -0.005))
    }
}
