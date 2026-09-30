package com.tomppi.enderslicer.printer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The configuration the app hands klippy.
 *
 * This file is an asset, so nothing compiles it and nothing notices when it drifts:
 * an unsubstituted placeholder is a printer that will not connect, and a section the
 * phone cannot support is a klippy that will not start. Both have happened, which is
 * what this is for.
 */
class PrinterConfigAssetTest {

    private val config = File("src/main/assets/klipper-host/printer.cfg").readText()

    @Test
    fun bothPlaceholdersTheAppSubstitutesArePresent() {
        assertTrue("__SERIAL__ is what writePrinterConfig replaces with the pty",
            config.contains("__SERIAL__"))
        assertTrue("__GCODES__ is what it replaces with the gcode directory",
            config.contains("__GCODES__"))
    }

    @Test
    fun serialPlaceholderIsTheMicroControllerPort() {
        val serial = config.lineSequence().first { it.startsWith("serial:") }
        assertEquals("serial: __SERIAL__", serial.trim())
    }

    @Test
    fun sectionsAPhoneCannotSupportStayDisabled() {
        // No SPI accelerometer on this host and none on the phone.
        assertFalse(config.contains("\n[adxl345]"))
        assertFalse(config.contains("\n[resonance_tester]"))
        // The Sonic Pad's Linux host MCU: there is no klipper-mcu service here.
        assertFalse(config.contains("\n[mcu rpi]"))
        // A missing include is fatal, and this file is not shipped.
        assertFalse(config.contains("\n[include timelapse.cfg]"))
    }

    @Test
    fun thePrinterSectionsThatMustStayActiveAre() {
        for (section in listOf("[mcu]", "[printer]", "[bltouch]", "[input_shaper]", "[virtual_sdcard]")) {
            assertTrue("$section is required", config.contains("\n$section\n"))
        }
    }

    @Test
    fun theBoardIsResetByCommandOnThisConnection() {
        // klippy reads restart_method on this connection: only /dev/rpmsg_* and
        // /tmp/klipper_host_* are pipe ports with no baud rate. Toggling DTR for the arduino
        // method cannot reach the board through the app's pty, so the board is asked to reset
        // itself. An earlier revision of this file asserted the opposite and cost the phone
        // route its only working FIRMWARE_RESTART.
        assertTrue(
            "the shipped [mcu] asks the board to reset itself",
            config.contains("restart_method: command"),
        )
    }
}
