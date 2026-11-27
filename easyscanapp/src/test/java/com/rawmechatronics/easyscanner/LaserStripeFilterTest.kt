package com.rawmechatronics.easyscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.exp

class LaserStripeFilterTest {

    private fun makeYuv(w: Int, h: Int, paint: (Int, Int) -> IntArray): LaserStripeFilter.YuvPlanes {
        val y = ByteArray(w * h)
        val cw = w / 2
        val ch = h / 2
        val uAcc = DoubleArray(cw * ch)
        val vAcc = DoubleArray(cw * ch)
        for (py in 0 until h) {
            for (px in 0 until w) {
                val rgb = paint(px, py)
                val r = rgb[0].toDouble(); val g = rgb[1].toDouble(); val b = rgb[2].toDouble()
                y[py * w + px] = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255).toByte()
                val ci = (py / 2) * cw + (px / 2)
                uAcc[ci] += 0.25 * (128.0 - 0.168736 * r - 0.331264 * g + 0.5 * b)
                vAcc[ci] += 0.25 * (128.0 + 0.5 * r - 0.418688 * g - 0.081312 * b)
            }
        }
        val u = ByteArray(cw * ch) { uAcc[it].toInt().coerceIn(0, 255).toByte() }
        val v = ByteArray(cw * ch) { vAcc[it].toInt().coerceIn(0, 255).toByte() }
        return LaserStripeFilter.YuvPlanes(
            width = w, height = h,
            y = ByteBuffer.wrap(y), yStride = w,
            u = ByteBuffer.wrap(u), v = ByteBuffer.wrap(v),
            uvStride = cw, uvPixelStride = 1,
        )
    }

    private fun makeResult(rawPts: List<Pair<Float, Float>>, includePoseAndCam: Boolean = true): FrameResult {
        val r = FrameResult(4096)
        r.data[FrameResult.H.FRAME_W] = 640f
        r.data[FrameResult.H.FRAME_H] = 480f
        r.data[FrameResult.H.LASER_STATE] = 2f
        r.data[FrameResult.H.LASER_POINTS] = rawPts.size.toFloat()
        r.data[FrameResult.H.LASER_PEAK] = 180f
        var p = FrameResult.H.LEN
        if (includePoseAndCam) {
            // Keep a non-laser layer before laser layers to ensure clearLaserLayers preserves it.
            r.data[p++] = FrameResult.Layer.POSE.toFloat()
            r.data[p++] = 16f
            r.data[p++] = 1f
            for (k in 0 until 16) r.data[p++] = if (k % 5 == 0) 1f else 0f
        }
        r.data[p++] = FrameResult.Layer.LASER_RAW.toFloat()
        r.data[p++] = 2f
        r.data[p++] = rawPts.size.toFloat()
        for ((x, y) in rawPts) {
            r.data[p++] = x
            r.data[p++] = y
        }
        if (includePoseAndCam) {
            r.data[p++] = FrameResult.Layer.LASER_CAM.toFloat()
            r.data[p++] = 3f
            r.data[p++] = rawPts.size.toFloat()
            for (k in rawPts.indices) {
                r.data[p++] = 10f; r.data[p++] = 20f; r.data[p++] = 400f
            }
            // Non-laser layer after laser layers to verify compaction preserves it too.
            r.data[p++] = FrameResult.Layer.CORNERS.toFloat()
            r.data[p++] = 2f
            r.data[p++] = 1f
            r.data[p++] = 100f; r.data[p++] = 120f
        }
        r.length = p
        return r
    }

    @Test
    fun acceptsRealRedLaserStripeOverWarmWoodBackground() {
        val planes = makeYuv(640, 480) { _, y ->
            val d = abs(y - 240).toDouble()
            val k = exp(-d * d / 4.0)
            // Warm wooden background + bright red laser line.
            val bg = intArrayOf(145, 110, 80)
            val laser = intArrayOf(255, 25, 45)
            IntArray(3) { (bg[it] * (1.0 - k) + laser[it] * k).toInt() }
        }
        val pts = (60 until 580 step 4).map { it.toFloat() to 240f }
        val res = makeResult(pts)
        val verdict = LaserStripeFilter.filterInPlace(res, planes, "red")
        assertTrue("real red laser over warm wood must pass: $verdict", verdict.accepted)
        assertEquals(2f, res[FrameResult.H.LASER_STATE], 0f)
    }

    @Test
    fun acceptsOversaturatedWhiteCoreMagentaHaloLaser() {
        val planes = makeYuv(640, 480) { _, y ->
            val d = abs(y - 220)
            when {
                d <= 1 -> intArrayOf(255, 245, 250) // white-hot center
                d <= 4 -> intArrayOf(255, 40, 175)  // bright magenta halo
                else -> intArrayOf(95, 100, 96)
            }
        }
        val pts = (80 until 560 step 4).map { it.toFloat() to 220f }
        val res = makeResult(pts)
        val verdict = LaserStripeFilter.filterInPlace(res, planes, "red")
        assertTrue("oversaturated laser must pass: $verdict", verdict.accepted)
    }

    @Test
    fun rejectsFlatWarmWoodAndRedPillowFalsePositivesAndStripsLayers() {
        // Uniform saturated reddish-brown object across the scene (no physical laser).
        val planes = makeYuv(640, 480) { _, _ -> intArrayOf(190, 85, 65) }
        val pts = (60 until 580 step 4).map { it.toFloat() to 240f }
        val res = makeResult(pts, includePoseAndCam = true)
        val verdict = LaserStripeFilter.filterInPlace(res, planes, "red")
        assertFalse("broad reddish surface with no laser must be rejected: $verdict", verdict.accepted)
        assertEquals(0f, res[FrameResult.H.LASER_STATE], 0f)
        assertEquals(0f, res[FrameResult.H.LASER_POINTS], 0f)

        var sawLaser = false
        var sawPose = false
        var sawCorners = false
        res.forEachLayer { kind, _, count, _ ->
            if (kind == FrameResult.Layer.LASER_RAW || kind == FrameResult.Layer.LASER_CAM) sawLaser = true
            if (kind == FrameResult.Layer.POSE && count == 1) sawPose = true
            if (kind == FrameResult.Layer.CORNERS && count == 1) sawCorners = true
        }
        assertFalse("all laser layers must be stripped on rejection", sawLaser)
        assertTrue("non-laser POSE layer must remain intact", sawPose)
        assertTrue("non-laser CORNERS layer must remain intact", sawCorners)
    }

    @Test
    fun rejectsSharpRedObjectStepEdge() {
        // White table on top (y < 240), bright red book on bottom (y >= 240).
        val planes = makeYuv(640, 480) { _, y ->
            if (y < 240) intArrayOf(210, 210, 210) else intArrayOf(235, 35, 35)
        }
        val pts = (60 until 580 step 4).map { it.toFloat() to 241f }
        val res = makeResult(pts)
        val verdict = LaserStripeFilter.filterInPlace(res, planes, "red")
        assertFalse("sharp red step edge must be rejected (not a narrow ridge): $verdict", verdict.accepted)
    }

    @Test
    fun acceptsRealGreenVerticalLaserStripe() {
        val planes = makeYuv(640, 480) { x, _ ->
            val d = abs(x - 320).toDouble()
            val k = exp(-d * d / 3.5)
            val bg = intArrayOf(90, 95, 90)
            val laser = intArrayOf(30, 255, 50)
            IntArray(3) { (bg[it] * (1.0 - k) + laser[it] * k).toInt() }
        }
        val pts = (60 until 420 step 4).map { 320f to it.toFloat() }
        val res = makeResult(pts)
        val verdict = LaserStripeFilter.filterInPlace(res, planes, "green")
        assertTrue("real vertical green laser must pass: $verdict", verdict.accepted)
    }

    @Test
    fun acceptsDimRedLaserOnDarkMatteSurfaceAnywhereInFrame() {
        // Faint red laser stripe (R=135) on a dark charcoal object (45,48,46) at an off-center position.
        val planes = makeYuv(640, 480) { _, y ->
            val d = abs(y - 120).toDouble()
            val k = exp(-d * d / 3.5)
            val bg = intArrayOf(45, 48, 46)
            val laser = intArrayOf(135, 42, 48)
            IntArray(3) { (bg[it] * (1.0 - k) + laser[it] * k).toInt() }
        }
        val pts = (60 until 580 step 4).map { it.toFloat() to 120f }
        val res = makeResult(pts)
        val verdict = LaserStripeFilter.filterInPlace(res, planes, "red")
        assertTrue("faint red laser on dark matte surface must pass: $verdict", verdict.accepted)
    }

    @Test
    fun handlesTightlySizedUvBuffersWithoutOutOfBounds() {
        // Simulate Android Camera2 UV buffers whose limit omits trailing stride padding on the last row.
        val yBuf = ByteBuffer.allocate(640 * 480)
        val uBuf = ByteBuffer.allocate(320 * 239) // deliberately short of last row
        val vBuf = ByteBuffer.allocate(320 * 239)
        val planes = LaserStripeFilter.YuvPlanes(
            width = 640, height = 480,
            y = yBuf, yStride = 640,
            u = uBuf, v = vBuf,
            uvStride = 320, uvPixelStride = 1,
        )
        val pts = (20 until 620 step 8).map { it.toFloat() to 478f }
        val res = makeResult(pts)
        val verdict = LaserStripeFilter.filterInPlace(res, planes, "red")
        assertFalse(verdict.accepted)
    }
}
