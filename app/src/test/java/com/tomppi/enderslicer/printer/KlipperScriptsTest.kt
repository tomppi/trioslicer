package com.tomppi.enderslicer.printer

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        // Not M104 S0 and M140 S0: those name two heaters, and the button says everything -
        // see coolingEverythingDownIsNotTwoHeatersWorthOfCommands.
        assertEquals("TURN_OFF_HEATERS", KlipperScripts.coolDown())
    }

    @Test
    fun anotherHeaterIsAddressedByItsOwnName() {
        // [heater_generic chamber] is SET_HEATER_TEMPERATURE HEATER=chamber, quoted because
        // klippy re-parses the parameters with shlex.
        assertEquals(
            "SET_HEATER_TEMPERATURE HEATER=\"chamber\" TARGET=45",
            KlipperScripts.heaterTemperature("heater_generic chamber", 45),
        )
        assertEquals(
            "PID_CALIBRATE HEATER=\"heater_bed\" TARGET=60",
            KlipperScripts.pidCalibrate("heater_bed", 60),
        )
    }

    @Test
    fun homingNamesTheAxesItWasGiven() {
        assertEquals("G28", KlipperScripts.home())
        assertEquals("G28", KlipperScripts.home(""))
        assertEquals("G28 X Y", KlipperScripts.home("X Y"))
    }

    @Test
    fun aMeshProfileIsAnActionAndAName() {
        // Quoted, because klippy re-parses a non-traditional command with shlex: a profile
        // called "cold bed" unquoted arrives as two words and klippy answers "Malformed
        // command". shlex strips the quotes, so a plain name is unaffected.
        assertEquals("BED_MESH_PROFILE LOAD=\"default\"", KlipperScripts.meshProfile("LOAD", "default"))
        assertEquals("BED_MESH_PROFILE REMOVE=\"cold\"", KlipperScripts.meshProfile("REMOVE", "cold"))
        assertEquals(
            "BED_MESH_PROFILE LOAD=\"cold bed\"",
            KlipperScripts.meshProfile("LOAD", "cold bed"),
        )
        // A quote in the name is dropped rather than allowed to close the quoting early.
        assertEquals("BED_MESH_PROFILE LOAD=\"ab\"", KlipperScripts.meshProfile("LOAD", "a\"b"))
    }

    @Test
    fun aTemperatureFanIsAddressedByItsOwnCommand() {
        // klippy registers SET_HEATER_TEMPERATURE only for its Heater objects; a fan answers
        // with "The value 'chamber' is not valid for HEATER".
        assertEquals(
            "SET_TEMPERATURE_FAN_TARGET TEMPERATURE_FAN=\"chamber\" TARGET=40",
            KlipperScripts.heaterTemperature("temperature_fan chamber", 40),
        )
        assertEquals(
            "SET_HEATER_TEMPERATURE HEATER=\"chamber\" TARGET=40",
            KlipperScripts.heaterTemperature("heater_generic chamber", 40),
        )
        assertEquals(
            "SET_HEATER_TEMPERATURE HEATER=\"heater_bed\" TARGET=60",
            KlipperScripts.heaterTemperature("heater_bed", 60),
        )
        // And PID calibration is keyed by the short name too.
        assertEquals(
            "PID_CALIBRATE HEATER=\"chamber\" TARGET=200",
            KlipperScripts.pidCalibrate("heater_generic chamber", 200),
        )
    }

    @Test
    fun theSteppersAreEnabledByNameBecauseThereIsNoM17() {
        // klippy registers M18 and M84 and nothing else; M17 was answered "Unknown command"
        // and enabled nothing, while the console showed it as a command that had been sent.
        assertEquals(
            "SET_STEPPER_ENABLE STEPPER=\"stepper_x\" ENABLE=1\n" +
                "SET_STEPPER_ENABLE STEPPER=\"stepper_y\" ENABLE=1",
            KlipperScripts.enableSteppers(listOf("stepper_x", "stepper_y")),
        )
    }

    @Test
    fun coolingEverythingDownIsNotTwoHeatersWorthOfCommands() {
        // M104 and M140 name the extruder and the bed; a chamber heater or a second extruder
        // would keep running under a button that says everything.
        assertEquals("TURN_OFF_HEATERS", KlipperScripts.coolDown())
    }

    @Test
    fun theLiveLimitsCarryOnlyWhatWasGiven() {
        assertEquals(
            "SET_VELOCITY_LIMIT ACCEL=1500.000",
            KlipperScripts.velocityLimit(maxAccel = 1500.0),
        )
        assertEquals(
            "SET_VELOCITY_LIMIT VELOCITY=120.000 SQUARE_CORNER_VELOCITY=6.500",
            KlipperScripts.velocityLimit(maxVelocity = 120.0, squareCornerVelocity = 6.5),
        )
        // klippy reports the current limits when the command carries none, so sending one
        // without a value would look like it worked and change nothing.
        runCatching { KlipperScripts.velocityLimit() }.let { result ->
            assertTrue("a limit with no values is a mistake", result.isFailure)
        }
    }

    @Test
    fun aNamedFanIsAddressedByItsOwnName() {
        assertEquals(
            "SET_FAN_SPEED FAN=\"extruder_partfan\" SPEED=0.500",
            KlipperScripts.genericFan("extruder_partfan", 0.5),
        )
        // The name is a mux value, so it is quoted like every other one the app sends.
        assertEquals(
            "SET_FAN_SPEED FAN=\"a fan with spaces\" SPEED=1.000",
            KlipperScripts.genericFan("a fan with spaces", 1.4),
        )
    }

    @Test
    fun startingAPrintClearsWhatTheLastOneLeftBehind() {
        val reset = KlipperScripts.resetLiveOverrides()
        assertTrue(reset.contains("SET_GCODE_OFFSET Z=0"))
        assertTrue(reset.contains("M220 S100"))
        assertTrue(reset.contains("M221 S100"))
    }

    @Test
    fun theExtruderCalibrationIsKlippersOwnProcedure() {
        // Mark, push a known length, measure: a millimetre a second, relative, in a saved
        // state so the file's own modes are untouched.
        val command = KlipperScripts.extrudeForCalibration(100.0)
        assertTrue(command.contains("G1 E100.0 F60"))
        assertTrue(command.contains("M83"))
        assertTrue(command.contains("SAVE_GCODE_STATE"))
    }

    @Test
    fun theRotationDistanceIsCorrectedByWhatActuallyMoved() {
        // 100 asked for, 90 delivered: the motor turns 10 per cent too little per millimetre.
        assertEquals(4.221, KlipperScripts.correctedRotationDistance(4.69, 100.0, 90.0)!!, 1e-9)
        assertEquals(5.211, KlipperScripts.correctedRotationDistance(4.69, 100.0, 111.1)!!, 1e-9)
        // Klipper asks for three places.
        assertEquals(4.237, KlipperScripts.correctedRotationDistance(4.69, 100.0, 90.34)!!, 1e-9)
        assertNull(KlipperScripts.correctedRotationDistance(0.0, 100.0, 90.0))
        assertNull(KlipperScripts.correctedRotationDistance(4.69, 100.0, 0.0))
    }

    @Test
    fun rotationDistanceIsAddressedByTheExtrudersOwnName() {
        assertEquals(
            "SET_EXTRUDER_ROTATION_DISTANCE EXTRUDER=\"extruder\" DISTANCE=4.237",
            KlipperScripts.rotationDistance("extruder", 4.237),
        )
        // With no distance, klippy reports the value in use.
        assertEquals(
            "SET_EXTRUDER_ROTATION_DISTANCE EXTRUDER=\"extruder\"",
            KlipperScripts.rotationDistance("extruder"),
        )
    }
}
