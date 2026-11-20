package com.rawmechatronics.imucalib.core

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Integration run against a real recorded session (frames.csv + imu.csv from the
 * scanner's tracker recorder). Enabled by IMU_CALIB_SESSION=/path/to/session so
 * normal unit-test runs skip it.
 */
class RealSessionIntegrationTest {

    @Test
    fun calibratesARealRecordedSession() {
        val dir = System.getenv("IMU_CALIB_SESSION") ?: ""
        assumeTrue("set IMU_CALIB_SESSION to a session directory", dir.isNotEmpty())
        val session = CalibrationSession.fromCsv(
            File(dir, "frames.csv").readText(),
            File(dir, "imu.csv").readText(),
        )
        assertTrue("session should parse", session != null)
        session!!
        println("real session: board poses=" + session.boardPoses.size + " imu samples=" + session.imu.size)
        assertTrue("enough board poses", session.boardPoses.size >= 100)
        val initCal = KalibrCalibrator(
            session.boardPoses, session.imu,
            CalibOptions(splineDt = 0.2, imuSubsample = 8, maxIterations = 0),
        )
        println("init-only diagnostics: " + initCal.calibrate().diagnostics)
        val cal = KalibrCalibrator(
            session.boardPoses, session.imu,
            CalibOptions(splineDt = 0.2, imuSubsample = 8, maxIterations = 15),
        )
        val res = cal.calibrate()
        val d = res.diagnostics
        println("real session result: " + d)
        println("rCi(deg)=" + res.params.rCi.map { Math.toDegrees(it) })
        println("pCi(mm)=" + res.params.pCi.map { it * 1000 })
        println("td(ms)=" + res.params.td * 1000)
        println("bg=" + res.params.bg.toList())
        println("ba=" + res.params.ba.toList())
        println("g=" + res.params.gravityDir.toList())
        assertTrue("finite cost", d.cost.isFinite())
        assertTrue("board residual finite", d.rmsBoardRotDeg.isFinite() && d.rmsBoardTransMm.isFinite())
        assertTrue("residuals bounded: " + d, d.rmsBoardRotDeg < 5.0 && d.rmsBoardTransMm < 20.0)
        assertTrue("accel residual should be sensor-noise level: " + d.rmsAccel, d.rmsAccel < 2.0)
    }
}