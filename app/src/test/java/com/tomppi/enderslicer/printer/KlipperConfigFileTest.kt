package com.tomppi.enderslicer.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
    fun sectionsAreAddedWithoutTouchingWhatIsThere() {
        val existing = "[mcu]\nserial: /dev/ttyUSB0\n\n[gcode_macro PAUSE]\ngcode:\n    PAUSE_BASE\n"
        val added = KlipperConfigFile.withSections(
            existing,
            listOf("[gcode_macro M600]\ngcode:\n    PAUSE"),
        )
        assertTrue("the file is kept", added.contains("serial: /dev/ttyUSB0"))
        assertTrue("the macro already there is kept", added.contains("[gcode_macro PAUSE]"))
        assertTrue("the new one is added", added.contains("[gcode_macro M600]"))
        // Adding nothing changes nothing: the caller works out what is missing, and a screen
        // that asks twice must not append the same macro twice.
        assertEquals(existing, KlipperConfigFile.withSections(existing, emptyList()))
    }

    @Test
    fun addedSectionsGoInBeforeKlippysSavedBlock() {
        val withBlock = "[mcu]\nserial: /dev/ttyUSB0\n\n" +
            "#*# <---------------------- SAVE_CONFIG ---------------------->\n" +
            "#*# [bltouch]\n" +
            "#*# z_offset = 1.830\n"
        val added = KlipperConfigFile.withSections(withBlock, listOf("[gcode_macro M600]\ngcode:\n    PAUSE"))
        val macro = added.indexOf("[gcode_macro M600]")
        val block = added.indexOf("#*# <---------------------- SAVE_CONFIG")
        assertTrue("the macro is in the body", macro in 0 until block)
    }

@Test
    fun aDuplicateOptionLeftByAnOlderWriterIsCleanedUpNotLeftBehind() {
        // klippy reads the LAST value in a section, so replacing the first and leaving a stale
        // duplicate below it would make a saved measurement look like it did nothing - which is
        // exactly what the old writer left on the printer this app was built for.
        val config = """
            [input_shaper]
            shaper_type_x = mzv
            shaper_freq_x = 41.7
            shaper_type_y = mzv
            shaper_freq_y = 35.2
            shaper_type_x = mzv
            shaper_freq_x = 89.8
            shaper_type_y = mzv
            shaper_freq_y = 44.3
        """.trimIndent()
        // Both axes, because a save writes both: an axis nobody measured keeps whatever it had,
        // duplicates and all, and that is the caller's business rather than this function's.
        val written = KlipperConfigFile.withInputShaper(
            config,
            listOf(
                KlipperConfigFile.ShaperSetting("X", "mzv", 89.8, 0.1),
                KlipperConfigFile.ShaperSetting("Y", "mzv", 52.5, 0.1),
            ),
        )
        assertEquals(1, written.split("shaper_freq_y").size - 1)
        assertTrue(written.contains("shaper_freq_y = 52.5"))
        assertEquals(1, written.split("shaper_freq_x").size - 1)
        assertTrue("the value written last time survives", written.contains("shaper_freq_x = 89.8"))
        assertEquals(1, written.split("shaper_type_x").size - 1)
    }

    @Test
    fun restoringTheShippedConfigurationKeepsTheSavedCalibrations() {
        // The bug this pins: klippy loads the file and the saved block together, and where
        // both define an option the *file* wins - the block's copy is commented out at load.
        // A pristine body spliced in front of a saved block therefore reverts every option
        // they share: the probe's Z offset, the PID terms, the shaper frequencies. The values
        // the dialog promises to keep were the ones being thrown away.
        //
        // The body's copies are commented out instead, which is what SAVE_CONFIG does when it
        // writes the block, so the saved values are the ones that take effect.
        val shipped = "[bltouch]\nz_offset: 0\n\n[extruder]\npid_Kp: 29.291\n"
        val existing = "[bltouch]\nz_offset: 0.0\n\n" +
            "#*# <---------------------- SAVE_CONFIG ---------------------->\n" +
            "#*# DO NOT EDIT THIS BLOCK OR BELOW. The contents are auto-generated.\n" +
            "#*#\n" +
            "#*# [bltouch]\n" +
            "#*# z_offset = 1.830\n" +
            "#*#\n" +
            "#*# [extruder]\n" +
            "#*# pid_Kp = 33.500\n"
        val restored = KlipperConfigFile.withSavedValues(shipped, existing)
        // The shipped copies are commented, so klippy does not let them win.
        assertTrue("the shipped z_offset must not win", restored.contains("# z_offset: 0"))
        assertTrue("the shipped pid_Kp must not win", restored.contains("# pid_Kp: 29.291"))
        // And the saved values are still there, untouched.
        assertTrue(restored.contains("#*# z_offset = 1.830"))
        assertTrue(restored.contains("#*# pid_Kp = 33.500"))
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
        // klippy reads this option for the app's connection - only /dev/rpmsg_* and
        // /tmp/klipper_host_* are treated otherwise - and with it absent it falls through to
        // toggling DTR, which a bridge that moves bytes cannot deliver. So it stays, as the
        // one reset this connection can do.
        assertTrue(rewrite.text.contains("restart_method: command"))
        // And the printer's own settings are none of the app's business.
        assertTrue(rewrite.text.contains("rotation_distance: 4.69"))
        assertTrue(rewrite.text.contains("dir_pin: !PB3"))
        // Two changes, because this file already said "command": a value that is already
        // right is not a change, and a screen that listed it as one would be lying about
        // what it did to the user's file.
        assertEquals(2, rewrite.changes.size)
        assertTrue(rewrite.changes.any { it.contains("micro-controller") })
    }

    @Test
    fun aFileThatSaysNothingAboutResettingGetsTheOneResetThatWorks() {
        // No restart_method at all: klippy falls through to toggling DTR, which this
        // connection cannot deliver, so the app writes the method that asks the board to
        // reset itself - and says that it did.
        val without = "[mcu]\nserial: /dev/ttyUSB0\n"
        val rewrite = KlipperConfigFile.forDevice(without, "/data/pty", "/data/gcodes")
        assertTrue(rewrite.text.contains("restart_method: command"))
        assertTrue(rewrite.changes.any { it.contains("reset the board by command") })
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

    @Test
    fun theAppsOwnIncludeIsNotAnEdit() {
        // The Machine screen asks whether the configuration is still the one this version
        // ships. The printer's file carries the app's include and the reference copy does not,
        // so comparing them raw answered "yes, it has been edited" for every printer.
        val shipped = "[mcu]\nserial: /dev/ttyUSB0\n"
        val running = KlipperConfigFile.withAppInclude(shipped)
        assertFalse(KlipperConfigFile.differsFromShipped(running, shipped))
        // An edit is still an edit.
        val edited = running.replace("serial: /dev/ttyUSB0", "serial: /dev/ttyACM0")
        assertTrue(KlipperConfigFile.differsFromShipped(edited, shipped))
    }

    @Test
    fun anImportedConfigurationLosesTheBaudRateItCannotUse() {
        val brought = """
            [mcu]
            serial: /dev/ttyUSB0
            baud: 250000
            restart_method: command

            [printer]
            kinematics: cartesian
        """.trimIndent()
        val rewrite = KlipperConfigFile.forDevice(brought, "/data/pty", "/data/gcodes")
        // baud describes a line rate, and a pseudo-terminal has none: it is removed because
        // it means nothing here, not because klippy would refuse it.
        assertFalse("baud describes a rate this connection does not have", rewrite.text.contains("baud:"))
        assertTrue(rewrite.text.contains("restart_method: command"))
        assertTrue(rewrite.text.contains("serial: /data/pty"))
    }

    //
    // The sync: the settings the two hosts' configurations have to agree about, and the lines
    // they have to differ in.
    //
    // The pair below is the one this was built for. One printer, two hosts: klippy on the phone,
    // reached through the pty the app bridges the board through, and klippy on a computer,
    // reached over the real serial port. The files must differ in exactly the lines that say how
    // each host gets to the machine, and must agree about the machine itself - and they drifted:
    // the probe offset, a PID term and the bed mesh grid were saved on one host and the other
    // had older ones, and the extruder's rotation distance and pressure advance were tuned on
    // one and never carried across.
    //

    /** The phone's own configuration: the app's host, and what it has saved about the printer. */
    private val phoneConfig: String = listOf(
        "# !Ender-3 V2 ABL",
        "[mcu]",
        "serial: /data/user/0/com.tomppi.enderslicercura/files/printer-pty",
        "",
        "[virtual_sdcard]",
        "path: /data/user/0/com.tomppi.enderslicercura/files/gcodes",
        "",
        "[extruder]",
        "step_pin: PB4",
        "dir_pin: !PB3",
        "rotation_distance: 4.69",
        "pressure_advance: 0.025",
        "pressure_advance_smooth_time: 0.03",
        "",
        "[bltouch]",
        "sensor_pin: ^PB1",
        "#z_offset: 0",
        "",
        "[printer]",
        "kinematics: cartesian",
        "max_velocity: 300",
        "max_accel: 3000",
        "square_corner_velocity: 5.0",
        "max_z_velocity: 5",
        "max_z_accel: 100",
        "",
        "[include app.cfg]",
        "",
        "#*# <---------------------- SAVE_CONFIG ---------------------->",
        "#*# DO NOT EDIT THIS BLOCK OR BELOW. The contents are auto-generated.",
        "#*#",
        "#*# [extruder]",
        "#*# pid_kp = 33.500",
        "#*# pid_ki = 1.566",
        "#*#",
        "#*# [bltouch]",
        "#*# z_offset = 1.750",
        "#*#",
        "#*# [bed_mesh default]",
        "#*# version = 1",
        "#*# points=",
        "#*# \t  0.130000, 0.115000, 0.095000",
        "#*# \t  0.040000, 0.037500, 0.032500",
        "#*# \t  0.005000, 0.035000, -0.007500",
        "#*# x_count = 3",
        "#*# y_count = 3",
        "#*# algo = bicubic",
    ).joinToString("\n") + "\n"

    /** The computer's: the same printer, and the lines that are the computer's own. */
    private val pcConfig: String = listOf(
        "# !Ender-3 V2 ABL",
        "[mcu]",
        "serial: /dev/serial/by-id/usb-1a86_USB_Serial-if00-port0",
        "restart_method: command",
        "",
        "[mcu rpi]",
        "serial: /tmp/klipper_host_mcu",
        "",
        "[virtual_sdcard]",
        "path: /home/tomppi/printer_data/gcodes",
        "",
        "[extruder]",
        "step_pin: PB4",
        "dir_pin: !PB3",
        "rotation_distance: 4.643",
        "pressure_advance: 0.1094",
        "pressure_advance_smooth_time: 0.03",
        "",
        "[bltouch]",
        "sensor_pin: ^PB1",
        "#z_offset: 0",
        "",
        "[printer]",
        "kinematics: cartesian",
        "max_velocity: 300",
        "max_accel: 4000",
        "square_corner_velocity: 5.0",
        "max_z_velocity: 5",
        "max_z_accel: 100",
        "",
        "[include timelapse.cfg]",
        "",
        "#*# <---------------------- SAVE_CONFIG ---------------------->",
        "#*# DO NOT EDIT THIS BLOCK OR BELOW. The contents are auto-generated.",
        "#*#",
        "#*# [extruder]",
        "#*# pid_kp = 27.479",
        "#*# pid_ki = 1.566",
        "#*#",
        "#*# [bltouch]",
        "#*# z_offset = 1.760",
        "#*#",
        "#*# [bed_mesh default]",
        "#*# version = 1",
        "#*# points=",
        "#*# \t  0.120000, 0.110000, 0.090000",
        "#*# \t  0.040000, 0.037500, 0.032500",
        "#*# \t  0.005000, 0.035000, -0.007500",
        "#*# x_count = 3",
        "#*# y_count = 3",
        "#*# algo = bicubic",
    ).joinToString("\n") + "\n"

    /** The difference reported for one option, or null when nothing was reported for it. */
    private fun List<KlipperConfigFile.Difference>.of(section: String, option: String) =
        firstOrNull { it.section == section && it.option == option }

    /** A line at the same place, byte for byte, in the file a sync produced. */
    private fun assertSameLine(before: List<String>, after: List<String>, line: String) {
        val at = before.indexOf(line)
        assertTrue("the fixture must contain: " + line, at >= 0)
        assertEquals("this line must come through byte for byte", line, after[at])
    }

    @Test
    fun theSettingsThatDriftedAreFoundInTheBlockAndInTheBody() {
        val drifted = KlipperConfigFile.differences(phoneConfig, pcConfig)
        // The probe offset is one of the things klippy saves, so it drifts in its block - and
        // the body's copy of it is commented out, which is what SAVE_CONFIG leaves behind.
        assertEquals(
            "z_offset",
            "1.750" to "1.760",
            drifted.of("bltouch", "z_offset")?.let { it.valueA to it.valueB },
        )
        // Pressure advance is written in the body, where the app puts what klippy will not save.
        assertEquals(
            "pressure_advance",
            "0.025" to "0.1094",
            drifted.of("extruder", "pressure_advance")?.let { it.valueA to it.valueB },
        )
        assertEquals(
            "rotation_distance",
            "4.69" to "4.643",
            drifted.of("extruder", "rotation_distance")?.let { it.valueA to it.valueB },
        )
        assertEquals(
            "max_accel",
            "3000" to "4000",
            drifted.of("printer", "max_accel")?.let { it.valueA to it.valueB },
        )
        assertEquals(
            "a saved PID term",
            "33.500" to "27.479",
            drifted.of("extruder", "pid_kp")?.let { it.valueA to it.valueB },
        )
        // A bed mesh profile's grid runs over several lines, and the grid is the value.
        val grid = drifted.of("bed_mesh default", "points")
        assertNotNull("a saved bed mesh grid drifts too", grid)
        assertTrue(grid!!.valueA.startsWith("0.130000, 0.115000, 0.095000"))
        assertTrue(grid.valueB.startsWith("0.120000, 0.110000, 0.090000"))
    }

    @Test
    fun howEachHostReachesThePrinterIsNotDrift() {
        val drifted = KlipperConfigFile.differences(phoneConfig, pcConfig)
        // These differ between the two files by design. They are what makes one of them the
        // phone's and the other the computer's, so a difference in them is not drift and a copy
        // must never act on one.
        assertNull("the serial port", drifted.of("mcu", "serial"))
        assertNull("the restart method", drifted.of("mcu", "restart_method"))
        assertNull("the gcode directory", drifted.of("virtual_sdcard", "path"))
        assertNull("the computer's host board", drifted.of("mcu rpi", "serial"))
        assertTrue("no [mcu] option may be listed", drifted.none { it.section.startsWith("mcu") })
        assertTrue("kinematics is the printer's, but not this whitelist's", drifted.none { it.option == "kinematics" })
        assertTrue(drifted.none { it.section.startsWith("include") })
    }

    @Test
    fun aSyncMovesTheDriftedValuesAndNothingElse() {
        val synced = KlipperConfigFile.withSynced(phoneConfig, pcConfig)
        val before = phoneConfig.split("\n")
        val after = synced.split("\n")
        assertEquals("a sync may not add or remove a line", before.size, after.size)
        val changed = before.indices.filter { before[it] != after[it] }
        // Exactly the values of the settings that drifted, in the order the file has them, and
        // not one line more.
        assertEquals(
            "only the values of the settings that differ may move",
            listOf(
                "rotation_distance: 4.643",
                "pressure_advance: 0.1094",
                "max_accel: 4000",
                "#*# pid_kp = 27.479",
                "#*# z_offset = 1.760",
                "#*# \t  0.120000, 0.110000, 0.090000",
            ),
            changed.map { after[it] },
        )
        // A saved value moves in the block and a body option in the body: the place klippy
        // reads each of them from.
        assertTrue(synced.contains("#*# z_offset = 1.760"))
        assertTrue(synced.contains("pressure_advance: 0.1094"))
        assertFalse("the body's own line is not the block's", synced.contains("z_offset: 1.760"))
        // And every line that says how this host reaches the machine is untouched, in place.
        listOf(
            "serial: /data/user/0/com.tomppi.enderslicercura/files/printer-pty",
            "path: /data/user/0/com.tomppi.enderslicercura/files/gcodes",
            "[include app.cfg]",
            "step_pin: PB4",
            "dir_pin: !PB3",
            "sensor_pin: ^PB1",
            "kinematics: cartesian",
            "#z_offset: 0",
            "#*# DO NOT EDIT THIS BLOCK OR BELOW. The contents are auto-generated.",
        ).forEach { line -> assertSameLine(before, after, line) }
    }

    @Test
    fun aSyncTheOtherWayLeavesTheComputersOwnLinesAlone() {
        val synced = KlipperConfigFile.withSynced(pcConfig, phoneConfig)
        assertTrue("the phone's pressure advance went across", synced.contains("pressure_advance: 0.025"))
        assertTrue("and the offset it saved", synced.contains("#*# z_offset = 1.750"))
        assertTrue(synced.contains("#*# pid_kp = 33.500"))
        assertTrue(synced.contains("#*# \t  0.130000, 0.115000, 0.095000"))
        val before = pcConfig.split("\n")
        val after = synced.split("\n")
        assertEquals(before.size, after.size)
        listOf(
            "serial: /dev/serial/by-id/usb-1a86_USB_Serial-if00-port0",
            "restart_method: command",
            "[mcu rpi]",
            "serial: /tmp/klipper_host_mcu",
            "path: /home/tomppi/printer_data/gcodes",
            "[include timelapse.cfg]",
        ).forEach { line -> assertSameLine(before, after, line) }
    }

    @Test
    fun shapingKeptInTheBodyIsCopiedLikeTheRest() {
        // The real pair keeps shaping in the body - this app writes it there - while klippy saves
        // it into the block. A whitelist that only knew the block offered the other host nothing,
        // and a measured frequency is the one calibration worth copying between two hosts.
        // Its own pair rather than the shared fixtures: they carry what the other tests need, and
        // this is about a section those do not have.
        val here = listOf(
            "[printer]",
            "max_accel: 3000",
            "",
            "[input_shaper]",
            "shaper_type_y = mzv",
            "shaper_freq_y = 52.5",
        ).joinToString("\n")
        val there = here.replace("52.5", "44.3")

        val drifted = KlipperConfigFile.differences(here, there)
        assertEquals("52.5", drifted.of("input_shaper", "shaper_freq_y")?.valueA)
        assertEquals("44.3", drifted.of("input_shaper", "shaper_freq_y")?.valueB)

        val synced = KlipperConfigFile.withSynced(here, there)
        assertTrue("the measured frequency moved", synced.contains("shaper_freq_y = 44.3"))
        assertEquals("and the one it replaced is gone", 0, synced.split("shaper_freq_y = 52.5").size - 1)
        assertEquals("no line added or lost", here.split("\n").size, synced.split("\n").size)
    }

    @Test
    fun aValueBothHostsAgreeAboutIsNotReportedAndDoesNotMove() {
        val drifted = KlipperConfigFile.differences(phoneConfig, pcConfig)
        assertNull("the same PID term on both", drifted.of("extruder", "pid_ki"))
        assertNull("the same smooth time on both", drifted.of("extruder", "pressure_advance_smooth_time"))
        assertNull("the same velocity limit on both", drifted.of("printer", "max_velocity"))
        assertNull("the same grid shape on both", drifted.of("bed_mesh default", "x_count"))
        // Nothing reported for them, so nothing about them moves.
        val synced = KlipperConfigFile.withSynced(phoneConfig, pcConfig)
        assertTrue(synced.contains("#*# pid_ki = 1.566"))
        assertTrue(synced.contains("pressure_advance_smooth_time: 0.03"))
        assertTrue(synced.contains("max_velocity: 300"))
        assertTrue(synced.contains("#*# x_count = 3"))
        // And a file synced from itself is the file it was, byte for byte.
        assertEquals(phoneConfig, KlipperConfigFile.withSynced(phoneConfig, phoneConfig))
        assertEquals(pcConfig, KlipperConfigFile.withSynced(pcConfig, pcConfig))
    }
}


