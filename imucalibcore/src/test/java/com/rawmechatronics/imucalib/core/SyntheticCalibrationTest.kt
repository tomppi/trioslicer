package com.rawmechatronics.imucalib.core

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SyntheticCalibrationTest {

    private fun angleDeg(a: DoubleArray, b: DoubleArray): Double =
        So3.angleDeg(So3.mul(So3.transpose(So3.exp(a)), So3.exp(b)))

    @Test
    fun recoversParametersFromNoiseFreeSyntheticData() {
        val scenario = Synthetic.generate(
            seed = 3,
            durationS = 8.0,
            cameraRate = 30.0,
            imuRate = 240.0,
            knotDt = 0.05,
            noise = false,
        )
        val cal = KalibrCalibrator(
            scenario.boardPoses,
            scenario.imu,
            CalibOptions(splineDt = 0.05, imuSubsample = 4, maxIterations = 25, estimateLeverArm = true),
        )
        val res = cal.calibrate()
        val truth = scenario.truth
        val got = res.params
        val rotErr = angleDeg(got.rCi, truth.rCi)
        val transErr = kotlin.math.sqrt(
            (got.pCi[0] - truth.pCi[0]) * (got.pCi[0] - truth.pCi[0]) +
            (got.pCi[1] - truth.pCi[1]) * (got.pCi[1] - truth.pCi[1]) +
            (got.pCi[2] - truth.pCi[2]) * (got.pCi[2] - truth.pCi[2]))
        val tdErr = abs(got.td - truth.td)
        val bgErr = maxAbs(got.bg, truth.bg)
        val baErr = maxAbs(got.ba, truth.ba)
        val gErr = angleDeg(got.gravityDir, truth.gravityDir)
        val msg = "rot=" + rotErr + " deg, trans=" + transErr + " m, td=" + tdErr +
            " s, bg=" + bgErr + ", ba=" + baErr + ", g=" + gErr + " deg, diag=" + res.diagnostics
        assertTrue("recovery failed: " + msg,
            rotErr < 0.5 && transErr < 0.005 && tdErr < 0.005 && bgErr < 5e-4 && baErr < 0.02 && gErr < 1.0)
    }

    @Test
    fun recoversTimeOffsetAndExtrinsicWithSensorNoise() {
        val scenario = Synthetic.generate(seed = 5, durationS = 8.0, noise = true)
        val cal = KalibrCalibrator(scenario.boardPoses, scenario.imu, CalibOptions(maxIterations = 20))
        val res = cal.calibrate()
        val truth = scenario.truth
        val got = res.params
        val rotErr = angleDeg(got.rCi, truth.rCi)
        val tdErrMs = abs(got.td - truth.td) * 1000.0
        val bgErr = maxAbs(got.bg, truth.bg)
        val baErr = maxAbs(got.ba, truth.ba)
        println("noisy synthetic: rotErr=" + rotErr + " tdErrMs=" + tdErrMs +
            " bgErr=" + bgErr + " baErr=" + baErr + " diag=" + res.diagnostics)
        assertTrue("time offset sign/magnitude: got " + got.td + " truth " + truth.td, tdErrMs < 8.0)
        assertTrue("rotation error " + rotErr, rotErr < 2.0)
        assertTrue("gyro bias error " + bgErr, bgErr < 3e-3)
        assertTrue("accel bias error " + baErr, baErr < 0.05)
        assertTrue("accel residual noise level " + res.diagnostics.rmsAccel, res.diagnostics.rmsAccel < 0.1)
    }

    @Test
    fun costDropsFromInitialization() {
        val scenario = Synthetic.generate(seed = 4, durationS = 6.0, noise = false)
        val cal = KalibrCalibrator(scenario.boardPoses, scenario.imu, CalibOptions(splineDt = 0.05, imuSubsample = 4, maxIterations = 12))
        val res = cal.calibrate()
        assertTrue("calibration should not diverge: " + res.diagnostics, res.diagnostics.cost.isFinite())
        assertTrue("gyro rms should be small: " + res.diagnostics.rmsGyro, res.diagnostics.rmsGyro < 0.2)
        assertTrue("accel rms should be small: " + res.diagnostics.rmsAccel, res.diagnostics.rmsAccel < 1.0)
    }

    private fun maxAbs(a: DoubleArray, b: DoubleArray): Double {
        var m = 0.0
        for (i in a.indices) m = maxOf(m, abs(a[i] - b[i]))
        return m
    }
}