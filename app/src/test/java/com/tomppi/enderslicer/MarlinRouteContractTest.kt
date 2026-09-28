package com.tomppi.enderslicer

import com.tomppi.enderslicer.data.BuiltInGcode
import com.tomppi.enderslicer.engine.CalibrationFirmwareEncoder
import com.tomppi.enderslicer.engine.LayerEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Marlin side of this app, pinned.
 *
 * The app slices for two machines: a printer on the far end of OctoPrint, which is usually
 * Marlin, and the Klipper host inside the app. The Klipper work is allowed to add to its own
 * side and not to change the other one - and "not change" is a claim that needs evidence,
 * because the failure it guards against already happened once: the default start G-code was
 * shared, and replacing it with Klipper's left a Marlin printer being sent BED_MESH_CALIBRATE
 * and no longer loading its UBL mesh.
 *
 * So the two texts the Marlin route had before any of that work are kept as resources, byte for
 * byte, and compared here. A change to them - even one that only adds indentation, which is how
 * this test caught its own author - fails.
 */
class MarlinRouteContractTest {
    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("marlin/$name")) {
            "the Marlin reference text is missing from the test resources"
        }.bufferedReader().use { it.readText() }

    @Test
    fun theMarlinStartGcodeIsWhatItAlwaysWas() {
        assertEquals(resource("start-gcode.txt"), BuiltInGcode.START_MARLIN)
    }

    @Test
    fun theMarlinEndGcodeIsWhatItAlwaysWas() {
        assertEquals(resource("end-gcode.txt"), BuiltInGcode.END_MARLIN)
    }

    @Test
    fun aMarlinProfileIsGivenMarlinGcode() {
        assertEquals(BuiltInGcode.START_MARLIN, BuiltInGcode.startGcodeFor("Marlin"))
        assertEquals(BuiltInGcode.END_MARLIN, BuiltInGcode.endGcodeFor("Marlin"))
        // The app's own default flavour, and anything it does not recognise, is not Klipper.
        assertEquals(BuiltInGcode.START_MARLIN, BuiltInGcode.startGcodeFor("RepRap"))
        assertEquals(BuiltInGcode.START_MARLIN, BuiltInGcode.startGcodeFor(""))
    }

    /**
     * The commands that exist on this app's Klipper host and nowhere in Marlin.
     *
     * A file carrying one of these on the Marlin route is a command its printer will answer
     * "Unknown command" to and skip - which is silent, and exactly the sort of thing this test
     * exists to make loud.
     */
    private val klipperOnly = listOf(
        "PAUSE", "RESUME", "CLEAR_PAUSE", "BED_MESH_CALIBRATE", "BED_MESH_PROFILE",
        "SET_VELOCITY_LIMIT", "SET_PRESSURE_ADVANCE", "SET_EXTRUDER_ROTATION_DISTANCE",
        "SET_GCODE_OFFSET", "EXCLUDE_OBJECT", "SET_INPUT_SHAPER",
    )

    @Test
    fun noLayerEventWritesAKlipperCommandInAMarlinFile() {
        val dialects = listOf("Marlin", "RepRapFirmware", "Smoothie", "")
        for (flavor in dialects) {
            val encoder = CalibrationFirmwareEncoder.fromFlavor(flavor)
            for (type in LayerEventType.entries) {
                val lines = runCatching {
                    encoder.commands(type, layerNumber = 5, value = 200.0, secondaryValue = 50.0)
                }.getOrDefault(emptyList())
                for (line in lines) {
                    val opcode = line.trim().substringBefore(' ')
                    assertFalse(
                        "a $flavor file must not carry $opcode (from $type)",
                        klipperOnly.any { it == opcode },
                    )
                }
            }
        }
    }

    @Test
    fun theKlipperDialectGetsKlipperCommands() {
        // The other half of the same contract: the Klipper side really does differ, so this
        // is a gate and not a claim that both sides are alike.
        val klipper = CalibrationFirmwareEncoder.fromFlavor("Klipper")
        assertTrue(klipper.commands(LayerEventType.PAUSE, 5).contains("PAUSE"))
        assertFalse(klipper.commands(LayerEventType.PAUSE, 5).contains("M0"))
        val marlin = CalibrationFirmwareEncoder.fromFlavor("Marlin")
        assertTrue(marlin.commands(LayerEventType.PAUSE, 5).contains("M0"))
        assertEquals("M0", marlin.pauseCommand())
        assertEquals("M600", marlin.commands(LayerEventType.FILAMENT_CHANGE, 5).single())
    }
}
