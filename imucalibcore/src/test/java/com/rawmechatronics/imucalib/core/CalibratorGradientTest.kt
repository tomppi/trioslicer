package com.rawmechatronics.imucalib.core

import org.junit.Assert.assertTrue
import org.junit.Test

class CalibratorGradientTest {

    private fun scenario() = Synthetic.generate(
        seed = 3, durationS = 5.0, cameraRate = 30.0, imuRate = 240.0, knotDt = 0.05, noise = false,
    )

    @Test
    fun splineFitOfNoiseFreeBoardPosesIsExact() {
        val cal = KalibrCalibrator(scenario().boardPoses, scenario().imu, CalibOptions(splineDt = 0.05, imuSubsample = 8))
        cal.debugInitialize()
        val rms = cal.debugSplineFitRms()
        assertTrue("board rotation fit rms " + rms.first, rms.first < 1e-4)
        assertTrue("board translation fit rms " + rms.second, rms.second < 1e-6)
    }

    @Test
    fun initializationSystemMatchesDense() {
        val s = scenario()
        val cal = KalibrCalibrator(s.boardPoses, s.imu, CalibOptions(splineDt = 0.05, imuSubsample = 8))
        val diff = cal.debugInitSystemDiff()
        assertTrue("init system diff " + diff, diff < 1e-9)
    }

    @Test
    fun bandedSolveMatchesDenseSolveOnInitSystem() {
        val s = scenario()
        val cal = KalibrCalibrator(s.boardPoses, s.imu, CalibOptions(splineDt = 0.05, imuSubsample = 8))
        val mismatch = cal.debugInitSolveMismatch()
        assertTrue("solve mismatch " + mismatch, mismatch < 1e-6)
    }

    @Test
    fun bandedAndDenseCoefficientsAgree() {
        val s = scenario()
        val cal = KalibrCalibrator(s.boardPoses, s.imu, CalibOptions(splineDt = 0.05, imuSubsample = 8))
        val diff = cal.debugBandedVsDenseCoeff()
        assertTrue("coefficient difference " + diff, diff < 1e-6)
    }

    @Test
    fun denseLeastSquaresAgreesWithBandedFit() {
        val s = scenario()
        val cal = KalibrCalibrator(s.boardPoses, s.imu, CalibOptions(splineDt = 0.05, imuSubsample = 8))
        cal.debugInitialize()
        val banded = cal.debugSplineFitRms()
        val dense = cal.debugDenseFitRms()
        assertTrue("banded " + banded + " dense " + dense,
            kotlin.math.abs(banded.first - dense.first) < 1e-6 && kotlin.math.abs(banded.second - dense.second) < 1e-9)
    }

    @Test
    fun analyticGradientMatchesFiniteDifference() {
        val s = scenario()
        val cal = KalibrCalibrator(s.boardPoses, s.imu, CalibOptions(splineDt = 0.05, imuSubsample = 8))
        cal.debugInitialize()
        val mismatch = cal.debugGradientMismatch(40)
        assertTrue("gradient mismatch " + mismatch, mismatch < 5e-3)
    }
}