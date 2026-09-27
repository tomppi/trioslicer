package com.tomppi.enderslicer.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The configuration file, and the block klippy writes into it.
 *
 * The block is not decoration: it is where a PID calibration, a Z offset and every saved
 * bed mesh profile live, and the file it lives in is rewritten from the app's assets
 * whenever that is the only way to get a fix onto a printer. Getting this wrong costs a
 * user their calibration, which is minutes of a hot nozzle and a bed they then have to
 * re-level.
 */
class KlipperConfigFileTest {
    private val shipped = """
        [mcu]
        serial: __SERIAL__

        [virtual_sdcard]
        path: __GCODES__
    """.trimIndent()

    private val saved = """
        #*# <---------------------- SAVE_CONFIG ---------------------->
        #*# DO NOT EDIT THIS BLOCK OR BELOW. The contents are auto-generated.
        #*#
        #*# [extruder]
        #*# pid_Kp = 21.000
    """.trimIndent()

    @Test
    fun theTwoPathsAreFilledIn() {
        val resolved = KlipperConfigFile.resolve(shipped, "/data/printer-pty", "/data/gcodes")
        assertTrue(resolved.contains("serial: /data/printer-pty"))
        assertTrue(resolved.contains("path: /data/gcodes"))
        assertFalse(resolved.contains(KlipperConfigFile.SERIAL_PLACEHOLDER))
    }

    @Test
    fun theSavedBlockIsEverythingFromItsMarkerDown() {
        val text = "regular = 1\n" + saved + "\n"
        val block = KlipperConfigFile.savedBlock(text)
        assertTrue(block.startsWith(KlipperConfigFile.SAVED_MARKER))
        assertTrue(block.contains("pid_Kp = 21.000"))
        // And what is above it is the configuration itself.
        assertEquals("regular = 1", KlipperConfigFile.withoutSavedBlock(text).trim())
    }

    @Test
    fun aFileWithNothingSavedHasNoBlock() {
        assertEquals("", KlipperConfigFile.savedBlock(shipped))
        assertEquals(shipped, KlipperConfigFile.withoutSavedBlock(shipped))
    }

    @Test
    fun aNewDefaultKeepsWhatThePrinterSaved() {
        val running = KlipperConfigFile.resolve(shipped, "/old/pty", "/data/gcodes") + "\n" + saved + "\n"
        val newDefault = KlipperConfigFile.resolve(
            shipped.replace("serial: __SERIAL__", "serial: __SERIAL__\nrestart_method: command"),
            "/new/pty",
            "/data/gcodes",
        )
        val merged = KlipperConfigFile.withSavedValues(newDefault, running)
        assertTrue("the new option is there", merged.contains("restart_method: command"))
        assertTrue("the saved values survived", merged.contains("pid_Kp = 21.000"))
        assertTrue("with the new path", merged.contains("serial: /new/pty"))
        assertFalse("and nothing of the old file", merged.contains("/old/pty"))
        // The marker appears once: two would make klippy refuse the whole block.
        assertEquals(1, Regex(KlipperConfigFile.SAVED_MARKER).findAll(merged).count())
    }

    @Test
    fun aFileThatMatchesTheDefaultIsNotReportedAsDifferent() {
        val running = KlipperConfigFile.resolve(shipped, "/data/pty", "/data/gcodes") + "\n" + saved + "\n"
        val shippedResolved = KlipperConfigFile.resolve(shipped, "/data/pty", "/data/gcodes")
        // Saved values are what the printer has learned, not drift.
        assertFalse(KlipperConfigFile.differsFromShipped(running, shippedResolved))
    }

    @Test
    fun anEditedFileIsReportedAsDifferent() {
        val running = KlipperConfigFile.resolve(shipped, "/data/pty", "/data/gcodes")
            .replace("serial: /data/pty", "serial: /data/pty\nbaud: 250000")
        val shippedResolved = KlipperConfigFile.resolve(shipped, "/data/pty", "/data/gcodes")
        assertTrue(KlipperConfigFile.differsFromShipped(running, shippedResolved))
    }
}
