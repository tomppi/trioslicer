package com.tomppi.enderslicer.printer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three numbers that get called "the speed", in the units they are actually in.
 *
 * The unit of gcode_move.speed is the whole point. An earlier version of this test asserted mm/s
 * - the plausible reading, since everything else in klippy is mm/s - and passed, because a test
 * pins whatever the person writing it believed. What settled it was the C side of the same call:
 * cmd_G1 takes F and assigns it with no division (gcode_move.py:134-139), and F is mm per minute,
 * so the published figure is mm per minute. A printer reading 1500 for a file whose last move was
 * F1500 is the confirmation: that is 25 mm/s, and the limit is 300.
 */
class KlipperSpeedsTest {
    /** A status with only the fields a case cares about, so an absent one stays absent. */
    private fun speeds(
        feedrate: Double? = null,
        override: Double? = null,
        limit: Double? = null,
        live: Double? = null,
    ): KlipperSpeeds {
        val move = JSONObject()
        feedrate?.let { move.put("speed", it) }
        override?.let { move.put("speed_factor", it) }
        val toolhead = JSONObject()
        limit?.let { toolhead.put("max_velocity", it) }
        val report = JSONObject()
        live?.let { report.put("live_velocity", it) }
        val status = JSONObject()
            .put("gcode_move", move)
            .put("toolhead", toolhead)
            .put("motion_report", report)
        return KlipperSpeeds.of(KlipperPrinterState(connected = true).withStatus(status))
    }

    @Test
    fun theFilesFeedrateIsMillimetresPerMinute() {
        // F1500, which is what a slicer writes for 25 mm/s.
        val speeds = speeds(feedrate = 1500.0, override = 1.0, limit = 300.0, live = 24.8)
        assertEquals(1500.0, speeds.askedPerMinute!!, 1e-9)
        assertEquals(25.0, speeds.askedPerSecond!!, 1e-9)
        assertEquals(25.0, speeds.effectivePerSecond!!, 1e-9)
        assertEquals(24.8, speeds.live!!, 1e-9)
    }

    @Test
    fun aFileAskingFifteenHundredIsNotClampedByAThreeHundredLimit() {
        // The bug this replaces: 1500 read as mm/s, compared against a 300 mm/s limit, and the
        // screen told the user their print was clamped when it was running at a twelfth of it.
        val speeds = speeds(feedrate = 1500.0, override = 1.0, limit = 300.0)
        assertFalse("25 mm/s is nowhere near the limit", speeds.clamped)
    }

    @Test
    fun theOverrideMultipliesTheFilesFeedrate() {
        val speeds = speeds(feedrate = 1500.0, override = 1.5, limit = 300.0)
        assertEquals(2250.0, speeds.effectivePerMinute!!, 1e-9)
        assertEquals(37.5, speeds.effectivePerSecond!!, 1e-9)
        assertFalse(speeds.clamped)
    }

    @Test
    fun anOverridePastTheMachinesLimitIsSaidSoRatherThanHopedFor() {
        // F18000 is 300 mm/s, exactly the limit; 200% of that is past what klippy will give.
        val speeds = speeds(feedrate = 18000.0, override = 2.0, limit = 300.0)
        assertEquals(600.0, speeds.effectivePerSecond!!, 1e-9)
        assertTrue("200% is not twice as fast at this speed", speeds.clamped)
    }

    @Test
    fun anAbsentOverrideIsTheFilesOwnSpeedRatherThanZero() {
        // 1.0, not 0.0: klippy omits speed_factor until M220 is used, and reading that as zero
        // would show every print as stopped.
        val speeds = speeds(feedrate = 1800.0)
        assertEquals(1.0, speeds.override ?: 1.0, 1e-9)
        assertEquals(30.0, speeds.effectivePerSecond!!, 1e-9)
    }
}
