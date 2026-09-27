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

    @Test
    fun aHostConfigurationIsMadeToRunOnThisDevice() {
        val host = """
            [mcu]
            serial: /dev/serial/by-id/usb-1a86_USB_Serial-if00-port0
            restart_method: command

            [virtual_sdcard]
            path: /home/pi/printer_data/gcodes

            [extruder]
            rotation_distance: 4.69
            dir_pin: !PB3
        """.trimIndent()
        val rewrite = KlipperConfigFile.forDevice(host, "/data/pty", "/data/gcodes")
        assertTrue(rewrite.text.contains("serial: /data/pty"))
        assertTrue(rewrite.text.contains("path: /data/gcodes"))
        // klippy rejects this option outright for the pty connection the app gives it.
        assertFalse(rewrite.text.contains("restart_method"))
        // And the printer's own settings are none of the app's business.
        assertTrue(rewrite.text.contains("rotation_distance: 4.69"))
        assertTrue(rewrite.text.contains("dir_pin: !PB3"))
        assertEquals(3, rewrite.changes.size)
        assertTrue(rewrite.changes.any { it.contains("restart_method") })
    }

    @Test
    fun aConfigurationWithNoVirtualSdCardGetsOne() {
        val rewrite = KlipperConfigFile.forDevice("[mcu]\nserial: /dev/ttyUSB0\n", "/data/pty", "/data/gcodes")
        assertTrue(rewrite.text.contains("[virtual_sdcard]"))
        assertTrue(rewrite.text.contains("path: /data/gcodes"))
        assertTrue(rewrite.changes.any { it.contains("[virtual_sdcard]") })
    }

    @Test
    fun aPathInAnotherSectionIsNotTheVirtualSdCard() {
        val config = """
            [mcu]
            serial: /dev/ttyUSB0

            [virtual_sdcard]
            path: /home/pi/gcodes

            [save_variables]
            filename: /home/pi/variables.cfg
        """.trimIndent()
        val rewrite = KlipperConfigFile.forDevice(config, "/data/pty", "/data/gcodes")
        assertTrue(rewrite.text.contains("path: /data/gcodes"))
        // Only the option inside [virtual_sdcard]: another section's path is its own.
        assertTrue(rewrite.text.contains("filename: /home/pi/variables.cfg"))
    }

    @Test
    fun aSecondMicroControllerIsRemovedAndSaid() {
        val config = """
            [mcu]
            serial: /dev/ttyUSB0

            [mcu rpi]
            serial: /tmp/klipper_host_mcu

            [fan]
            pin: PA0
        """.trimIndent()
        val rewrite = KlipperConfigFile.forDevice(config, "/data/pty", "/data/gcodes")
        assertFalse("the host board section should be gone", rewrite.text.contains("[mcu rpi]"))
        assertFalse(rewrite.text.contains("klipper_host_mcu"))
        // What comes after it is not: removing a section must not remove the file.
        assertTrue(rewrite.text.contains("[fan]"))
        assertTrue(rewrite.text.contains("pin: PA0"))
        assertTrue(rewrite.changes.any { it.contains("[mcu rpi]") })
        assertTrue(rewrite.text.contains("serial: /data/pty"))
    }

    @Test
    fun anIncludeThatWasNotBroughtIsCommentedOutAndNamed() {
        val config = """
            [include mainsail.cfg]
            [include macros/*.cfg]
            [mcu]
            serial: /dev/ttyUSB0
        """.trimIndent()
        val rewrite = KlipperConfigFile.forDevice(
            config, "/data/pty", "/data/gcodes", availableFiles = setOf("MAINSAIL.CFG"),
        )
        // One came along, so it stays; the other would stop klippy from starting.
        assertTrue(rewrite.text.contains("[include mainsail.cfg]"))
        assertTrue(rewrite.text.contains("# [include macros/*.cfg]"))
        assertEquals(listOf("mainsail.cfg", "macros/*.cfg"), KlipperConfigFile.includesOf(config))
        assertTrue(rewrite.warnings.any { it.contains("macros/*.cfg") })
    }

    @Test
    fun whatThisDeviceCannotSupplyIsNamed() {
        val bare = "[mcu]\nserial: /dev/ttyUSB0\n"
        val rewrite = KlipperConfigFile.forDevice(bare, "/data/pty", "/data/gcodes")
        val warnings = rewrite.warnings.joinToString("\n")
        assertTrue(warnings.contains("[pause_resume]"))
        assertTrue(warnings.contains("[exclude_object]"))
        assertTrue(warnings.contains("PAUSE"))
    }

    @Test
    fun aCanBusConfigurationIsRefusedWithTheReason() {
        val canbus = "[mcu]\ncanbus_uuid: 11aa22bb33cc\n"
        val rewrite = KlipperConfigFile.forDevice(canbus, "/data/pty", "/data/gcodes")
        assertTrue(rewrite.warnings.any { it.contains("CAN") })
    }

    @Test
    fun whateverWasSavedIsKept() {
        val brought = "[mcu]\nserial: /dev/ttyUSB0\n" + "\n" + saved + "\n"
        val rewrite = KlipperConfigFile.forDevice(brought, "/data/pty", "/data/gcodes")
        // The saved block is klippy's; it is dropped here because the caller puts the
        // printer's own saved values back over whatever it writes.
        assertFalse(rewrite.text.contains(KlipperConfigFile.SAVED_MARKER))
    }

    @Test
    fun inputShapingIsWrittenIntoTheConfiguration() {
        val config = """
            [input_shaper]
            shaper_type_x = mzv
            shaper_freq_x = 89.8

            [bltouch]
            z_offset: 0
        """.trimIndent()
        val written = KlipperConfigFile.withInputShaper(
            config,
            listOf(
                KlipperConfigFile.ShaperSetting("x", "ei", 41.5, dampingRatio = 0.05),
                KlipperConfigFile.ShaperSetting("y", "mzv", 35.2),
            ),
        )
        assertTrue(written.contains("shaper_type_x = ei"))
        assertTrue(written.contains("shaper_freq_x = 41.5"))
        assertTrue(written.contains("damping_ratio_x = 0.050"))
        // A setting for an axis the section did not have is added to it, not to the file.
        assertTrue(written.contains("shaper_type_y = mzv"))
        val bltouchAt = written.indexOf("[bltouch]")
        assertTrue("shaping must stay inside its own section", written.indexOf("shaper_type_y") < bltouchAt)
        assertTrue(written.contains("z_offset: 0"))
    }

    @Test
    fun aConfigurationWithNoShapingSectionGetsOne() {
        val written = KlipperConfigFile.withInputShaper(
            "[mcu]\nserial: /dev/ttyUSB0\n",
            listOf(KlipperConfigFile.ShaperSetting("x", "mzv", 89.8)),
        )
        assertTrue(written.contains("[input_shaper]"))
        assertTrue(written.contains("shaper_type_x = mzv"))
        assertTrue(written.contains("shaper_freq_x = 89.8"))
    }

    @Test
    fun writingShapingKeepsWhatKlippySaved() {
        val config = "[input_shaper]\nshaper_type_x = zv\n" + "\n" + saved + "\n"
        val written = KlipperConfigFile.withInputShaper(
            config,
            listOf(KlipperConfigFile.ShaperSetting("x", "mzv", 60.0)),
        )
        assertTrue("the calibration survives", written.contains("pid_Kp = 21.000"))
        assertEquals(1, Regex(KlipperConfigFile.SAVED_MARKER).findAll(written).count())
    }

    @Test
    fun theAppsOwnIncludeIsAddedOnceAndAboveTheSavedBlock() {
        val config = "[mcu]\nserial: /dev/ttyUSB0\n" + "\n" + saved + "\n"
        val included = KlipperConfigFile.withAppInclude(config)
        assertTrue(included.contains("[include app.cfg]"))
        assertTrue(
            "the include must not land inside klippy's saved block",
            included.indexOf("[include app.cfg]") < included.indexOf(KlipperConfigFile.SAVED_MARKER),
        )
        assertTrue(included.contains("pid_Kp = 21.000"))
        // Idempotent, because this runs at every start of the host.
        assertEquals(included, KlipperConfigFile.withAppInclude(included))
    }

    @Test
    fun aConfigurationThatAlreadyIncludesItIsLeftAlone() {
        val config = "[include app.cfg]\n[mcu]\nserial: /dev/ttyUSB0\n"
        assertEquals(config, KlipperConfigFile.withAppInclude(config))
    }
}
