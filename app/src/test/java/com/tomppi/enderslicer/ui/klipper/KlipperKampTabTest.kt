package com.tomppi.enderslicer.ui.klipper

import com.tomppi.enderslicer.printer.KlipperWatch
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
 */
class KlipperKampTabTest {
    @Test
    fun theScreenReadsAnObjectTheAppWatches() {
        assertTrue(
            "the KAMP screen reads $KAMP_OBJECT, which is not in the watch list",
            KAMP_OBJECT in KlipperWatch.always,
        )
    }
}
