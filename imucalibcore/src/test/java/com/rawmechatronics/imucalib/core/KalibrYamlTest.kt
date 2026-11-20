package com.rawmechatronics.imucalib.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KalibrYamlTest {

    @Test
    fun writesTransformIntrinsicsAndTimeOffset() {
        val params = CalibParams(
            rCi = doubleArrayOf(0.4, -0.2, 1.1),
            pCi = doubleArrayOf(0.018, -0.012, 0.004),
            td = 0.0135,
            bg = doubleArrayOf(0.0, 0.0, 0.0),
            ba = doubleArrayOf(0.0, 0.0, 0.0),
            gravityDir = doubleArrayOf(0.0, 0.0, -1.0),
        )
        val cam = CalibCameraModel(426.7368, 407.3528, 363.8172, 240.8990,
            doubleArrayOf(0.161015, -0.849706, 0.0, 0.0))
        val text = KalibrYaml.imuCamChain(params, cam)
        assertTrue(text.contains("timeshift_cam_imu: 0.013500"))
        assertTrue(text.contains("resolution: [640, 480]"))
        assertTrue(text.contains("camera_model: pinhole"))
        // parse the three rotation rows back and check that they equal exp(rCi)
        val rows = text.lines().filter { it.trimStart().startsWith("- [") && !it.contains("0.0, 0.0, 0.0, 1.0]") }
            .map { line ->
                line.substringAfter("[").substringBefore("]").split(',').map { it.trim().toDouble() }
            }
        assertEquals(3, rows.size)
        val expected = So3.exp(params.rCi)
        for (r in 0 until 3) {
            for (c in 0 until 3) assertEquals(expected[r * 3 + c], rows[r][c], 1e-11)
            assertEquals(params.pCi[r], rows[r][3], 1e-11)
        }
    }
}
