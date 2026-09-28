package com.tomppi.enderslicer.printer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three numbers that get called "the speed", in the units they are actually in.
 *
 * klippy publishes gcode_move.speed in mm/s and speed_factor as a ratio, so the two multiplied
 * are what the file's feedrate becomes - and a screen that shows only the first tells the user
 * their override did nothing. These pin the arithmetic and the units, because a factor of 100
 * read as a percentage multiplies a feedrate by a hundred and nobody would see it in a diff.
 */
class KlipperSpeedsTest {
    private fun status(json: String): JSONObject = JSONObject(json)

    private fun speeds(json: String): KlipperSpeeds =
        KlipperSpeeds.of(KlipperPrinterState(connected = true).withStatus(status(json)))

    @Test
    fun theOverrideMultipliesTheFilesFeedrate() {
        val speeds = speeds(
            """{"gcode_move": {"speed": 40.0, "speed_factor": 1.5},
                "toolhead": {"max_velocity": 300.0},
                """ + """"motion_report": {"live_velocity": 59.9}}""",
        )
        assertEquals(40.0, speeds.askedFor!!, 1e-9)
        assertEquals(1.5, speeds.override!!, 1e-9)
        assertEquals(60.0, speeds.effective!!, 1e-9)
        // Feedrates are written in mm per minute; this is the figure the file would carry.
        assertEquals(3600.0, speeds.effectivePerMinute!!, 1e-9)
        assertEquals(59.9, speeds.live!!, 1e-9)
        assertFalse(speeds.clamped)
    }

    @Test
    fun anAbsentOverrideIsTheFilesOwnSpeedRatherThanZero() {
        // 1.0, not 0.0: klippy omits speed_factor until M220 is used, and reading that as zero
        // would show every print as stopped.
        val speeds = speeds("""{"gcode_move": {"speed": 30.0}}""")
        assertEquals(1.0, speeds.override ?: 1.0, 1e-9)
        assertEquals(30.0, speeds.effective!!, 1e-9)
    }

    @Test
    fun anOverridePastTheMachinesLimitIsSaidSoRatherThanHopedFor() {
        val speeds = speeds(
            """{"gcode_move": {"speed": 200.0, "speed_factor": 2.0},
                "toolhead": {"max_velocity": 300.0}}""",
        )
        assertEquals(400.0, speeds.effective!!, 1e-9)
        assertTrue("200% is not twice as fast once max_velocity is reached", speeds.clamped)
    }

    @Test
    fun aPrinterThatSaysNothingReadsAsUnknownRatherThanZero() {
        val speeds = speeds("""{"gcode_move": {}}""")
        assertNull(speeds.askedFor)
        assertNull(speeds.effective)
        assertNull(speeds.effectivePerMinute)
        assertNull(speeds.live)
        assertFalse("nothing to compare, so nothing to warn about", speeds.clamped)
    }
}
