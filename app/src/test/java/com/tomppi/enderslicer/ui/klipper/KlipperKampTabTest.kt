package com.tomppi.enderslicer.ui.klipper

import com.tomppi.enderslicer.printer.KlipperWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The KAMP screen reads its values by object name - and only gets them if that name is one the
 * app subscribes to.
 *
 * It was not, until this test existed. The screen asked the state for "_KAMP_Settings", which is
 * the name SET_GCODE_VARIABLE takes, while the object klippy publishes is
 * "gcode_macro _KAMP_Settings". The lookup answered null on every printer, so the screen told
 * the user their machine had no KAMP while it was running it - and no compiler could see the
 * difference between two strings.
 *
 * The same file holds what the fields send: klippy stores ast.literal_eval(VALUE), so what
 * reaches SET_GCODE_VARIABLE has to be a plain number whatever the keyboard produced.
 */
class KlipperKampTabTest {
    @Test
    fun theScreenReadsAnObjectTheAppWatches() {
        assertTrue(
            "the KAMP screen reads $KAMP_OBJECT, which is not in the watch list",
            KAMP_OBJECT in KlipperWatch.always,
        )
    }

    @Test
    fun aValueGoesOutAsAPlainNumberWithADot() {
        // A comma decimal - what the app's other fields accept for locale keyboards - is a
        // tuple to Python's literal_eval, so it is rewritten rather than forwarded.
        assertEquals("0.5", kampValue("0,5"))
        assertEquals("0.5", kampValue("0.5"))
        assertEquals("5", kampValue(" 5 "))
        assertEquals("-1.25", kampValue("-1,25"))
    }

    @Test
    fun nothingPythonWouldReadAsSomethingElseIsAccepted() {
        listOf("(0, 5)", "\"5\"", "inf", "Infinity", "NaN", "True", "5 mm", "1.2.3", "").forEach { typed ->
            assertNull("$typed must never reach SET_GCODE_VARIABLE", kampValue(typed))
        }
    }
}
