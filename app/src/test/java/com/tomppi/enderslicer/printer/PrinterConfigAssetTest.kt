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

    /**
     * The one option this file must NOT carry, and the mistake it caused.
     *
     * klippy reads restart_method only when a baud rate was set. The app's connection is a
     * pty, which has none - the vendored mcu.py treats a port resolving into /dev/pts as a
     * pipe, exactly as upstream treats /dev/rpmsg_ and /tmp/klipper_host_ - so the option is
     * never read, and klippy's strict unused-option check refuses the whole configuration:
     *
     *     configparser.Error: Option 'restart_method' is not valid in section 'mcu'
     *
     * That is fatal. Every phone host stopped there and never started. The reset is not lost
     * by removing it: klippy's own default with no baud rate is 'command', which is the reset
     * this connection needs. An earlier revision of this file asserted the opposite and is
     * why the phone route could not run at all.
     */
    @Test
    fun theShippedConfigCarriesNoOptionOnlyASerialPortCanUse() {
        val mcu = config.substringAfter("[mcu]").substringBefore("\n[")
        assertFalse("restart_method is never read on a pty, and is fatal there", mcu.contains("restart_method"))
        assertFalse("and neither is a baud rate", Regex("\\bbaud\\b").containsMatchIn(mcu))
    }
}
