package com.rawmechatronics.easyscanner

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class VioIntrinsicsAndImuTest {

    @Test
    fun scaleIntrinsicsMatchesGeomRsPixelCentreConvention() {
        // Matches easyscan-core/algo/src/geom.rs::Intrinsics::for_frame: cx' = (cx + 0.5) * s - 0.5
        val out = VioRunner.scaleIntrinsicsForVio(
            fx = 661.5, fy = 652.5, cx = 479.5, cy = 359.5,
            srcW = 960.0, srcH = 720.0, dstW = 640.0, dstH = 480.0,
        )
        val s = 640.0 / 960.0
        assertEquals(661.5 * s, out[0], 1e-9)
        assertEquals(652.5 * s, out[1], 1e-9)
        // Pixel centre of 960 is 479.5 -> maps to exact pixel centre of 640 (319.5)!
        assertEquals(319.5, out[2], 1e-9)
        assertEquals(239.5, out[3], 1e-9)
    }

    @Test
    fun bilinearDownsampleYHasZeroSubpixelShiftOn960x720To640x480() {
        val srcW = 960; val srcH = 720
        val dstW = 640; val dstH = 480
        // Linear horizontal ramp: value at pixel centre x is 20 + 200 * (x + 0.5) / srcW.
        val y = ByteArray(srcW * srcH)
        for (sy in 0 until srcH) for (sx in 0 until srcW) {
            val v = Math.round(20.0 + 200.0 * (sx + 0.5) / srcW).toInt().coerceIn(0, 255)
            y[sy * srcW + sx] = v.toByte()
        }
        val down = VioRunner.bilinearDownsampleY(y, srcW, srcW, srcH, dstW, dstH)
        var maxErr = 0.0
        var signedSum = 0.0
        var count = 0
        for (dx in 2 until dstW - 2) {
            val expected = 20.0 + 200.0 * (dx + 0.5) / dstW
            val actual = (down[240 * dstW + dx].toInt() and 0xff).toDouble()
            val diff = actual - expected
            signedSum += diff
            count++
            if (abs(diff) > maxErr) maxErr = abs(diff)
        }
        // Zero systematic subpixel bias (mean shift < 0.1 grey level) and max error <= 1 grey level.
        assertEquals(0.0, signedSum / count, 0.15)
        assertEquals(0.0, maxErr, 1.0)
    }

    @Test
    fun interpolateAccelToGyroTracksLinearRampAndHandlesGaps() {
        val a0 = floatArrayOf(1.0f, 2.0f, 9.8f)
        val a1 = floatArrayOf(3.0f, 6.0f, 9.8f)
        val out = FloatArray(3)
        // Midpoint between t0=10.000 and t1=10.002 -> gyro at 10.001 should give exact average.
        VioRunner.interpolateAccelToGyro(a0, 10.000, a1, 10.002, 10.001, out)
        assertEquals(2.0f, out[0], 1e-4f)
        assertEquals(4.0f, out[1], 1e-4f)
        assertEquals(9.8f, out[2], 1e-4f)

        // Slight lead (gyro 0.5 ms past newest accel) -> bounded linear extrapolation.
        VioRunner.interpolateAccelToGyro(a0, 10.000, a1, 10.002, 10.0025, out)
        assertEquals(3.5f, out[0], 1e-4f)

        // Large timestamp gap -> falls back safely to latest sample without exploding.
        VioRunner.interpolateAccelToGyro(a0, 10.000, a1, 10.200, 10.201, out)
        assertArrayEquals(a1, out, 1e-6f)
    }

    @Test
    fun androidDistortionReordersToOpenCvBrownConrady() {
        // Android LENS_DISTORTION is [k1, k2, k3, p1, p2] -> OpenCV is [k1, k2, p1, p2, k3].
        val android = floatArrayOf(0.18f, -0.61f, 0.05f, 0.002f, -0.003f)
        val ocv = VendorIntrinsics.androidDistortionToOpenCv(android)
        assertEquals(0.18, ocv[0], 1e-6)
        assertEquals(-0.61, ocv[1], 1e-6)
        assertEquals(0.002, ocv[2], 1e-6)
        assertEquals(-0.003, ocv[3], 1e-6)
        assertEquals(0.05, ocv[4], 1e-6)

        // Null / NaN returns safe zeros.
        assertArrayEquals(DoubleArray(5), VendorIntrinsics.androidDistortionToOpenCv(null), 0.0)
        assertArrayEquals(
            DoubleArray(5),
            VendorIntrinsics.androidDistortionToOpenCv(floatArrayOf(Float.NaN, 0f, 0f, 0f, 0f)),
            0.0,
        )
    }

    @Test
    fun yuvRgbToRgbHasZeroSubpixelShiftOn960x720To640x480() {
        val srcW = 960; val srcH = 720; val dstW = 640; val dstH = 480
        val y = ByteArray(srcW * srcH)
        for (sy in 0 until srcH) for (sx in 0 until srcW) {
            // Neutral BT.601 luma ramp: 32..220
            y[sy * srcW + sx] = Math.round(32.0 + 188.0 * (sx + 0.5) / srcW).toInt().coerceIn(0, 255).toByte()
        }
        val uv = ByteArray((srcW / 2) * (srcH / 2)) { 128.toByte() }
        val rgb = YuvRgb.toRgb(y, uv, uv, srcW, srcH, srcW, srcW / 2, 1, dstW, dstH)
        var signedSum = 0.0; var count = 0
        for (dx in 4 until dstW - 4) {
            val yExpected = 32.0 + 188.0 * (dx + 0.5) / dstW
            val rgbExpected = ((298.0 * (yExpected - 16.0) + 128.0) / 256.0).coerceIn(0.0, 255.0)
            val actualR = (rgb[240 * dstW + dx].toInt() and 0xff).toDouble()
            signedSum += actualR - rgbExpected
            count++
        }
        assertEquals(0.0, signedSum / count, 0.5)
    }
}
