package com.rawmechatronics.easyscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VioCalibrationProfileTest {

    private val good = """
%YAML:1.0

cam0:
  T_cam_imu:
    - [0.016223357727749611, -0.99986611091455824, -0.0021360965881699005, -0.00175224097210172]
    - [-0.99972708342713934, -0.016256944196209176, 0.016777080427392517, 0.0070993815125864083]
    - [-0.016809560562469301, 0.0018633330346089425, -0.99985697310350286, -0.00081236076953280752]
    - [0, 0, 0, 1]
  camera_model: pinhole
  distortion_coeffs: [0.161015277, -0.849706480, 0.0, 0.0]
  intrinsics: [426.736804189, 407.352849620, 363.817280001, 240.899048294]
  resolution: [640, 480]
  timeshift_cam_imu: 0.013165851798925843
"""

    @Test
    fun acceptsAValidProfile() {
        val r = VioCalibrationProfile.validate(good)
        assertTrue(r.message, r.ok)
        assertEquals(13.165851798925843, r.timeshiftMs, 1e-9)
        assertEquals(-1.75224097210172, r.translationMm[0], 1e-9)
        assertEquals(7.099381512586408, r.translationMm[1], 1e-9)
    }

    @Test
    fun refusesMissingPose() {
        assertTrue(!VioCalibrationProfile.validate("cam0:\n  timeshift_cam_imu: 0.01\n").ok)
    }

    @Test
    fun refusesANonOrthonormalRotation() {
        val bad = good.replace("-0.99986611091455824,", "-0.5,")
        assertTrue(!VioCalibrationProfile.validate(bad).ok)
    }

    @Test
    fun refusesAnInsaneTimeOffset() {
        val bad = good.replace("0.013165851798925843", "9.5")
        assertTrue(!VioCalibrationProfile.validate(bad).ok)
    }

    @Test
    fun refusesEmpty() {
        assertTrue(!VioCalibrationProfile.validate("").ok)
    }
}
