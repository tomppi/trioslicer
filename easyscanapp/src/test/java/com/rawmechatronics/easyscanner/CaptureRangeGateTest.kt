package com.rawmechatronics.easyscanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureRangeGateTest {

    @Test
    fun trimmedMeanRejectsTwoFrameOutlierSpikesWhileHoldingStill() {
        val gate = CaptureRangeGate(windowSize = 7, enterFrames = 3, exitFrames = 5, unknownGraceFrames = 3)
        // Calibrated focus 380 mm ± 120 mm -> [260, 500] mm.
        val minMm = 260.0
        val maxMm = 500.0

        // Fill window at steady 380 mm.
        repeat(7) { gate.evaluateDistance(380.0, "laser", true, minMm, maxMm) }
        assertTrue(gate.evaluateDistance(380.0, "laser", true, minMm, maxMm).allowCapture)

        // Two sudden bogus red spikes (e.g. specular reflection at 900 mm / 120 mm) must NOT
        // flip status to red or stop capture thanks to the 7-frame trimmed mean.
        val s1 = gate.evaluateDistance(900.0, "laser", true, minMm, maxMm)
        assertEquals(CaptureRangeGate.Status.IN_RANGE, s1.status)
        assertTrue(s1.allowCapture)
        val s2 = gate.evaluateDistance(120.0, "laser", true, minMm, maxMm)
        assertEquals(CaptureRangeGate.Status.IN_RANGE, s2.status)
        assertTrue(s2.allowCapture)
    }

    @Test
    fun sustainedOutOfRangePausesAndResumesSmoothlyInsideFocusBand() {
        val gate = CaptureRangeGate(windowSize = 7, enterFrames = 3, exitFrames = 4, unknownGraceFrames = 3)
        val minMm = 260.0
        val maxMm = 500.0

        repeat(5) { gate.evaluateDistance(380.0, "laser", true, minMm, maxMm) }
        assertTrue(gate.evaluateDistance(380.0, "laser", true, minMm, maxMm).allowCapture)

        // Move genuinely too far (650 mm) and hold: smoothed distance crosses upper band -> pauses.
        var last = gate.evaluateDistance(650.0, "laser", true, minMm, maxMm)
        repeat(10) { last = gate.evaluateDistance(650.0, "laser", true, minMm, maxMm) }
        assertEquals(CaptureRangeGate.Status.TOO_FAR, last.status)
        assertFalse(last.allowCapture)

        // Move back inside focus band (380 mm): resumes cleanly.
        repeat(8) { last = gate.evaluateDistance(380.0, "laser", true, minMm, maxMm) }
        assertEquals(CaptureRangeGate.Status.IN_RANGE, last.status)
        assertTrue(last.allowCapture)
    }

    @Test
    fun disabledGateAllowsCaptureWhileStillReportingDistanceStatus() {
        val gate = CaptureRangeGate()
        repeat(5) { gate.evaluateDistance(850.0, "laser", gateEnabled = false, minMm = 260.0, maxMm = 500.0) }
        val ev = gate.evaluateDistance(850.0, "laser", gateEnabled = false, minMm = 260.0, maxMm = 500.0)
        assertEquals(CaptureRangeGate.Status.TOO_FAR, ev.status)
        assertTrue(ev.allowCapture)
    }

    @Test
    fun measuresMedianDepthFromFreehandLaserCamLayer() {
        val gate = CaptureRangeGate()
        val r = FrameResult()
        val buf = FloatArray(FrameResult.H.LEN + 3 + 5 * 3)
        buf.fill(Float.NaN, 0, FrameResult.H.LEN)
        val o = FrameResult.H.LEN
        buf[o] = FrameResult.Layer.LASER_CAM.toFloat()
        buf[o + 1] = 3f
        buf[o + 2] = 5f
        val zs = floatArrayOf(900f, 310f, 100f, 330f, 320f)
        for (i in zs.indices) {
            buf[o + 3 + i * 3] = 0f
            buf[o + 3 + i * 3 + 1] = 0f
            buf[o + 3 + i * 3 + 2] = zs[i]
        }
        r.data = buf
        r.length = buf.size
        val (dist, src) = gate.measureDistanceMm(r)
        assertEquals("laser", src)
        assertEquals(320.0, dist, 1e-6)
    }
}
