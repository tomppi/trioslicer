package com.tomppi.enderslicer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which start and end script a slice is given.
 *
 * The app drives two machines with one slicer, and they do not accept each other's scripts:
 * BED_MESH_CALIBRATE is a Klipper command a Marlin board answers "Unknown command" to, and
 * G29 L0 is UBL that klippy does not have. A single custom pair therefore sent whatever had
 * been written to whichever machine was selected - which is why each route has its own, and why
 * the flavour, not the last edit, decides.
 */
class GcodeRouteScriptsTest {
    private val marlinScript = "G28\nG29 L0\nG29 A"
    private val klipperScript = "G28\nBED_MESH_CALIBRATE"
    private val fallback = "the profile's own script"

    @Test
    fun eachRouteIsGivenItsOwnScript() {
        val marlin = SlicerSettings(
            gcodeFlavor = "Marlin",
            customStartGcodeEnabled = true,
            customStartGcode = marlinScript,
            customKlipperStartGcodeEnabled = true,
            customKlipperStartGcode = klipperScript,
        )
        assertEquals(marlinScript, marlin.resolveStartGcode(fallback))
        val klipper = marlin.copy(gcodeFlavor = "Klipper")
        assertEquals(klipperScript, klipper.resolveStartGcode(fallback))
    }

    @Test
    fun theOtherRoutesScriptIsNeverUsed() {
        // The bug: a Klipper profile with only the Marlin pair filled in - which is what one
        // field for both meant - must not be sent the Marlin script.
        val klipperWithMarlinScriptOnly = SlicerSettings(
            gcodeFlavor = "Klipper",
            customStartGcodeEnabled = true,
            customStartGcode = marlinScript,
            customEndGcodeEnabled = true,
            customEndGcode = "M84 X Y E",
        )
        assertEquals(fallback, klipperWithMarlinScriptOnly.resolveStartGcode(fallback))
        assertEquals(fallback, klipperWithMarlinScriptOnly.resolveEndGcode(fallback))
        // And the other way round.
        val marlinWithKlipperScriptOnly = SlicerSettings(
            gcodeFlavor = "Marlin",
            customKlipperStartGcodeEnabled = true,
            customKlipperStartGcode = klipperScript,
        )
        assertEquals(fallback, marlinWithKlipperScriptOnly.resolveStartGcode(fallback))
    }

    @Test
    fun anEnabledButEmptyScriptFallsBackRatherThanSendingNothing() {
        val empty = SlicerSettings(
            gcodeFlavor = "Klipper",
            customKlipperStartGcodeEnabled = true,
            customKlipperStartGcode = "",
        )
        assertEquals(fallback, empty.resolveStartGcode(fallback))
    }

    @Test
    fun aScriptWrittenBeforeTheSplitIsMovedToKlippersPair() {
        // Somebody slicing for Klipper before the pairs existed has their script in the shared
        // pair; leaving it there would send it to a Marlin printer on the next flavour change.
        val before = SlicerSettings(
            gcodeFlavor = "Klipper",
            customStartGcodeEnabled = true,
            customStartGcode = klipperScript,
            customEndGcodeEnabled = true,
            customEndGcode = "M84",
        )
        val after = before.migrateCustomScriptsToTheRoute()
        assertTrue(after.customKlipperStartGcodeEnabled)
        assertEquals(klipperScript, after.customKlipperStartGcode)
        assertFalse(after.customStartGcodeEnabled)
        assertEquals("", after.customStartGcode)
        assertEquals("M84", after.customKlipperEndGcode)
        // Idempotent: running it again changes nothing.
        assertEquals(after, after.migrateCustomScriptsToTheRoute())
    }

    @Test
    fun aMarlinProfileIsLeftExactlyAsItIs() {
        val marlin = SlicerSettings(
            gcodeFlavor = "Marlin",
            customStartGcodeEnabled = true,
            customStartGcode = marlinScript,
        )
        assertEquals(marlin, marlin.migrateCustomScriptsToTheRoute())
    }
}
