package com.tomppi.enderslicer.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bringing the computer's configuration to this phone, whole.
 *
 * The user's words: "a way to copy the entire printer cfg and replace only the necessary parts
 * to make it work for the phone klipper". The parts that are necessary are the ones that
 * describe the computer rather than the printer - its serial port, its gcodes directory, its
 * second boards, its reset method - and everything else has to arrive byte for byte, including
 * the macros, the stepper sections and the values klippy saved on that side. These pin the
 * machinery that does it: the include closure, the adaptation, and the two facts a screen has
 * to be able to state before replacing a file - what changed, and what is lost with it.
 */
class KlipperAdoptTest {
    private val pty = "/data/data/com.tomppi.enderslicercura/files/printer-pty"
    private val gcodes = "/data/user/0/com.tomppi.enderslicercura/files/gcodes"

    /** The computer's own configuration, as his is: probe, macros, a second board, includes. */
    private val computer = """
        [include timelapse.cfg]
        [include KAMP_Settings.cfg]

        [mcu]
        serial: /dev/serial/by-id/usb-Klipper_stm32f103xe_1234-if00
        restart_method: command

        [mcu rpi]
        serial: /tmp/klipper_host_mcu

        [bltouch]
        sensor_pin: ^PB1
        control_pin: PB0
        z_offset: 1.630

        [screws_tilt_adjust]
        screw1: 70, 40

        [tmc2208 stepper_x]
        uart_pin: PC4

        [gcode_macro LOAD_FILAMENT]
        gcode:
            G91
            G1 E50 F300

        #*# <---------------------- SAVE_CONFIG ---------------------->
        #*# DO NOT EDIT THIS BLOCK OR BELOW. The contents are auto-generated.
        #*#
        #*# [bltouch]
        #*# z_offset = 1.630
    """.trimIndent()

    @Test
    fun theIncludeClosureIsWalkedToTheEnd() {
        val files = mapOf(
            "timelapse.cfg" to "",
            // The real KAMP settings file writes the ./ prefix and a trailing comment on every
            // include line. Reading those as unparseable is how a file that arrived gets its
            // includes commented out as missing.
            "KAMP_Settings.cfg" to """
                [include ./KAMP/Adaptive_Meshing.cfg]       # Include to enable adaptive meshing.
                [include ./KAMP/Line_Purge.cfg]             # Include to enable line purging.
            """.trimIndent(),
            "KAMP/Adaptive_Meshing.cfg" to "[gcode_macro BED_MESH_CALIBRATE]\ngcode:\n    ADAPTIVE_BED_MESH",
            "KAMP/Line_Purge.cfg" to "[gcode_macro LINE_PURGE]\ngcode:\n    PURGE",
        )
        val closure = KlipperConfigFile.includeClosure(computer) { files[it] }

        assertEquals(
            setOf(
                "timelapse.cfg",
                "KAMP_Settings.cfg",
                "KAMP/Adaptive_Meshing.cfg",
                "KAMP/Line_Purge.cfg",
            ),
            closure.keys,
        )
        assertTrue("the subdirectory's macros came with it", closure.getValue("KAMP/Line_Purge.cfg").contains("LINE_PURGE"))
    }

    @Test
    fun anIncludeThatCannotBeFetchedIsLeftOutAndSaid() {
        // The computer names a file Moonraker will not serve.
        val closure = KlipperConfigFile.includeClosure(computer) { name ->
            if (name == "timelapse.cfg") null else "[gcode_macro X]\ngcode:\n    X"
        }
        assertFalse("it is not in the closure", closure.containsKey("timelapse.cfg"))

        val rewrite = KlipperConfigFile.forDevice(
            text = computer,
            serialPath = pty,
            gcodeDirectory = gcodes,
            availableFiles = closure.keys + setOf("app.cfg", "KAMP_Settings.cfg"),
        )
        assertTrue(
            "so its include is commented out in the file that is written",
            rewrite.text.contains("# [include timelapse.cfg]"),
        )
        assertTrue(rewrite.warnings.any { it.contains("timelapse.cfg") })
        assertFalse(
            "while the include that did arrive stays",
            rewrite.text.contains("# [include KAMP_Settings.cfg]"),
        )
    }

    @Test
    fun theAdoptedFileKeepsEverythingButTheComputerItself() {
        val closure = KlipperConfigFile.includeClosure(computer) { "" }
        val rewrite = KlipperConfigFile.forDevice(
            text = computer,
            serialPath = pty,
            gcodeDirectory = gcodes,
            availableFiles = closure.keys + setOf("app.cfg", "KAMP_Settings.cfg"),
        )
        val adopted = KlipperConfigFile.withHostIncludes(
            KlipperConfigFile.withSavedValues(rewrite.text, computer),
        )

        // Replaced: how this host reaches the printer.
        assertTrue(adopted.contains("serial: $pty"))
        assertTrue(adopted.contains("path: $gcodes"))
        assertFalse("there is no Linux host board on a phone", adopted.contains("[mcu rpi]"))
        assertFalse("a pty has no baud, and klippy refuses an option it never reads", adopted.contains("restart_method"))
        assertFalse(adopted.contains("baud"))
        // And the app's own section include, which is what makes its buttons work.
        assertTrue(adopted.contains("[include app.cfg]"))

        // Kept: everything that describes the printer.
        assertTrue(adopted.contains("[bltouch]"))
        assertTrue(adopted.contains("z_offset: 1.630"))
        assertTrue(adopted.contains("[screws_tilt_adjust]"))
        assertTrue(adopted.contains("[tmc2208 stepper_x]"))
        assertTrue(adopted.contains("[gcode_macro LOAD_FILAMENT]"))
        // Including what klippy saved on that side: adopting is taking that printer's own
        // figures, and the phone's probe offset comes from here.
        assertTrue(adopted.contains("#*# z_offset = 1.630"))
    }

    @Test
    fun includesResolveTheWayKlippyResolvesThem() {
        assertEquals("KAMP/Line_Purge.cfg", KlipperConfigFile.includePath("", "./KAMP/Line_Purge.cfg"))
        assertEquals("KAMP/Line_Purge.cfg", KlipperConfigFile.includePath("KAMP_Settings.cfg", "./KAMP/Line_Purge.cfg"))
        assertEquals("macros.cfg", KlipperConfigFile.includePath("KAMP/KAMP_Settings.cfg", "../macros.cfg"))
        assertEquals("a/b/c.cfg", KlipperConfigFile.includePath("a/b/file.cfg", "c.cfg"))
        assertEquals("c.cfg", KlipperConfigFile.includePath("a/b", "../../c.cfg"))
    }

    @Test
    fun aCompanionCannotBeWrittenOutOfTheConfigurationDirectory() {
        assertNull(KlipperConfigFile.companionPath(".."))
        // The ".." segments are dropped rather than followed, so a name from a file this app
        // did not write cannot land outside the configuration's own directory.
        assertEquals("etc/passwd", KlipperConfigFile.companionPath("../../etc/passwd"))
        assertEquals("KAMP/Line_Purge.cfg", KlipperConfigFile.companionPath("KAMP/Line_Purge.cfg"))
        assertNull(KlipperConfigFile.companionPath(""))
    }

    @Test
    fun theValuesAPhoneWouldLoseAreNamed() {
        val phone = """
            [mcu]
            serial: /data/pty

            #*# <---------------------- SAVE_CONFIG ---------------------->
            #*# DO NOT EDIT THIS BLOCK OR BELOW. The contents are auto-generated.
            #*#
            #*# [bltouch]
            #*# z_offset = 0.0
            #*#
            #*# [extruder]
            #*# pid_Kp = 21.000
        """.trimIndent()
        assertEquals(
            listOf("[bltouch] z_offset = 0.0", "[extruder] pid_Kp = 21.000"),
            KlipperConfigFile.savedValues(phone),
        )
        assertTrue("a phone that has saved nothing says so by saying nothing", KlipperConfigFile.savedValues("[mcu]\nserial: /x\n").isEmpty())
    }

    @Test
    fun theTwoFilesAreCountedApartAsWellAsComparedAsSettings() {
        val a = "[mcu]\nserial: /dev/ttyUSB0\n[extruder]\nrotation_distance: 4.69\n"
        val b = "[mcu]\nserial: /data/pty\n[extruder]\nrotation_distance: 4.69\n"
        assertEquals("one line differs: the serial port", 2, KlipperConfigFile.lineDifferences(a, b))
        assertEquals(0, KlipperConfigFile.lineDifferences(a, a))
        assertEquals(
            "blank lines and indentation are not differences",
            0,
            KlipperConfigFile.lineDifferences("[mcu]\nserial: /x\n", "\n[mcu]\n  serial: /x\n\n"),
        )
    }
}
