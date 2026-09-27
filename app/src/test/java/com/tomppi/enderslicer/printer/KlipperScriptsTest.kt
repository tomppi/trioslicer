package com.tomppi.enderslicer.printer

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * The G-code this app sends.
 *
 * The first test is the one that matters: a phone set to a language that writes decimals
 * with a comma used to turn every jog, Z nudge, extrusion and pressure advance into a
 * command klippy could not parse. It answered "Unable to parse move 'G1 Y10,000 F3000'"
 * and the buttons did nothing - which is exactly what the console was added to make
 * visible, and did.
 *
 * So the whole class of command is checked under a comma locale rather than the one the
 * test runner happens to have.
 */
class KlipperScriptsTest {
    private var defaultLocale: Locale = Locale.getDefault()

    @Before
    fun useACommaLocale() {
        defaultLocale = Locale.getDefault()
        // Finnish, which is what the phone this was found on is set to - and German
        // would do just as well.
        Locale.setDefault(Locale("fi", "FI"))
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(defaultLocale)
    }

    @Test
    fun aJogIsSentWithADotWhateverThePhoneIsSetTo() {
        assertEquals(
            listOf(
                "SAVE_GCODE_STATE NAME=trioslicer_jog",
                "G91",
                "G1 Y10.000 F3000",
                "RESTORE_GCODE_STATE NAME=trioslicer_jog",
            ).joinToString("\n"),
            KlipperScripts.jog("Y", 10.0, 3000),
        )
    }

    @Test
    fun aFractionalJogKeepsItsFraction() {
        assertEquals("G1 Z0.100 F3000", KlipperScripts.jog("Z", 0.1, 3000).lines()[2])
        assertEquals("G1 X-0.050 F3000", KlipperScripts.jog("X", -0.05, 3000).lines()[2])
    }

    @Test
    fun theZOffsetIsSignedAndFollowedByTheMove() {
        assertEquals("SET_GCODE_OFFSET Z_ADJUST=-0.010 MOVE=1", KlipperScripts.zOffset(-0.01, true))
        assertEquals("SET_GCODE_OFFSET Z_ADJUST=0.050", KlipperScripts.zOffset(0.05, false))
    }

    @Test
    fun extrusionIsRelativeAndRestoresTheState() {
        assertEquals(
            listOf(
                "SAVE_GCODE_STATE NAME=trioslicer_extrude",
                "M83",
                "G1 E10.00 F300",
                "RESTORE_GCODE_STATE NAME=trioslicer_extrude",
            ).joinToString("\n"),
            KlipperScripts.extrude(10.0, 300),
        )
    }

    @Test
    fun pressureAdvanceAndRetractionKeepTheirPrecision() {
        assertEquals("SET_PRESSURE_ADVANCE ADVANCE=0.0450", KlipperScripts.pressureAdvance(0.045))
        assertEquals("SET_RETRACTION RETRACT_LENGTH=1.20 RETRACT_SPEED=35.0", KlipperScripts.retraction(1.2, 35.0))
    }

    @Test
    fun noScriptEverCarriesAComma() {
        val scripts = listOf(
            KlipperScripts.jog("X", 0.1, 3000),
            KlipperScripts.jog("Y", -12.345, 1500),
            KlipperScripts.zOffset(-0.005, true),
            KlipperScripts.extrude(1.5, 120),
            KlipperScripts.pressureAdvance(0.0325),
            KlipperScripts.retraction(0.8, 30.0),
        )
        scripts.forEach { script ->
            assertFalse("a comma reached the printer in: " + script, script.contains(','))
        }
    }

    @Test
    fun theFanIsScaledToM106sOwnRange() {
        assertEquals("M106 S0", KlipperScripts.fan(0))
        assertEquals("M106 S127", KlipperScripts.fan(50))
        assertEquals("M106 S255", KlipperScripts.fan(100))
        // Above and below are clamped rather than sent: M106 takes a byte.
        assertEquals("M106 S255", KlipperScripts.fan(140))
    }

    @Test
    fun temperaturesAndFactorsArePlainIntegers() {
        assertEquals("M104 S210", KlipperScripts.hotend(210))
        assertEquals("M140 S60", KlipperScripts.bed(60))
        assertEquals("M220 S120", KlipperScripts.speedFactor(120))
        assertEquals("M221 S95", KlipperScripts.extrudeFactor(95))
        assertEquals("M104 S0\nM140 S0", KlipperScripts.coolDown())
    }

    @Test
    fun anotherHeaterIsAddressedByItsOwnName() {
        // [heater_generic chamber] is "SET_HEATER_TEMPERATURE HEATER=chamber".
        assertEquals(
            "SET_HEATER_TEMPERATURE HEATER=chamber TARGET=45",
            KlipperScripts.heaterTemperature("heater_generic chamber", 45),
        )
        assertEquals("PID_CALIBRATE HEATER=heater_bed TARGET=60", KlipperScripts.pidCalibrate("heater_bed", 60))
    }

    @Test
    fun homingNamesTheAxesItWasGiven() {
        assertEquals("G28", KlipperScripts.home())
        assertEquals("G28", KlipperScripts.home(""))
        assertEquals("G28 X Y", KlipperScripts.home("X Y"))
    }

    @Test
    fun aMeshProfileIsAnActionAndAName() {
        assertEquals("BED_MESH_PROFILE LOAD=default", KlipperScripts.meshProfile("LOAD", "default"))
        assertEquals("BED_MESH_PROFILE REMOVE=cold", KlipperScripts.meshProfile("REMOVE", "cold"))
    }
}
