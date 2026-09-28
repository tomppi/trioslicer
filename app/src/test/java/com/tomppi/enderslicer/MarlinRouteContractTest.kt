package com.tomppi.enderslicer

import com.tomppi.enderslicer.data.BuiltInGcode
import com.tomppi.enderslicer.engine.LayerEventType
import com.tomppi.enderslicer.engine.gcode.GcodeRoute
import com.tomppi.enderslicer.engine.gcode.UnsupportedFirmwareCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Marlin side of this app, pinned to TrioSlicer 1.4.0.
 *
 * The app slices for two machines: a printer at the far end of OctoPrint, which is usually
 * Marlin, and the Klipper host running inside the app. Klipper was added later and is allowed to
 * add to its own route and not to change this one - and "not changed" is a claim that needs
 * evidence, because the failure it guards against happened: the default start G-code was shared,
 * so replacing it with Klipper's left a Marlin printer being sent BED_MESH_CALIBRATE and no
 * longer loading its UBL mesh.
 *
 * So the two texts the Marlin route had at 1.4.0 are kept as resources, byte for byte, and every
 * layer event's output is kept as a golden file. A change to either fails here. That is a prompt
 * rather than a prohibition: when something genuinely affects Marlin, the fix lands in
 * FrozenGcodeRoute and the resources are updated in the same commit, with the reason recorded -
 * which is the thing that was missing when the route changed by accident.
 */
class MarlinRouteContractTest {
    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("marlin/$name")) {
            "the Marlin reference for $name is missing from the test resources"
        }.bufferedReader().use { it.readText() }

    private val marlin = GcodeRoute.forFlavor("Marlin")

    @Test
    fun theMarlinStartGcodeIsWhatItAlwaysWas() {
        assertEquals(resource("start-gcode.txt"), marlin.startGcode())
        assertEquals(resource("start-gcode.txt"), BuiltInGcode.startGcodeFor("Marlin"))
    }

    @Test
    fun theMarlinEndGcodeIsWhatItAlwaysWas() {
        assertEquals(resource("end-gcode.txt"), marlin.endGcode())
    }

    @Test
    fun everyLayerEventIsWhatItAlwaysWas() {
        val golden = resource("layer-events.txt")
            .lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val (head, expected) = line.split("::", limit = 2).map(String::trim)
                val parts = head.split(" ", limit = 2)
                val type = LayerEventType.valueOf(parts[0])
                val arguments = parts.getOrNull(1)?.split("/")?.map(String::toDouble) ?: emptyList()
                Triple(type, arguments, expected.split("|").map(String::trim))
            }
            .toList()
        assertTrue("the golden file is empty", golden.isNotEmpty())
        for ((type, arguments, expected) in golden) {
            val produced = marlin.commands(
                type = type,
                layerNumber = 5,
                value = arguments.getOrNull(0),
                secondaryValue = arguments.getOrNull(1),
                text = if (type == LayerEventType.CUSTOM_GCODE) "G1 X1\nG1 X2" else "hello",
            )
            assertEquals("$type changed on the Marlin route", expected, produced)
        }
    }

    @Test
    fun theEndGcodeDoesNotClaimToSpareZOnKlipper() {
        // Marlin's M84 takes axis words and sparing Z is the point of them. Klipper's takes
        // none and releases every stepper, so an M84 X Y E there would be a comment that lies.
        assertTrue(marlin.endGcode().contains("M84 X Y E"))
        val klipper = GcodeRoute.forFlavor("Klipper")
        assertTrue(klipper.endGcode().contains("M84"))
        assertFalse(klipper.endGcode().contains("M84 X Y E"))
    }

    @Test
    fun theRoutesThatAreNotKlipperAllTakeTheFrozenPath() {
        for (flavor in listOf("Marlin", "RepRap (Marlin/Sprinter)", "RepRapFirmware", "Duet", "Custom", "")) {
            assertEquals(resource("start-gcode.txt"), GcodeRoute.forFlavor(flavor).startGcode())
            assertEquals(resource("end-gcode.txt"), GcodeRoute.forFlavor(flavor).endGcode())
            assertEquals("M0", GcodeRoute.forFlavor(flavor).pauseCommand())
        }
    }

    /**
     * The commands that exist on this app's Klipper host and nowhere in Marlin.
     *
     * A file carrying one of these on the Marlin route is a command its printer answers
     * "Unknown command" to and skips, which is silent - the sort of thing this test is for.
     */
    private val klipperOnly = listOf(
        "PAUSE", "RESUME", "CLEAR_PAUSE", "BED_MESH_CALIBRATE", "BED_MESH_PROFILE",
        "SET_VELOCITY_LIMIT", "SET_PRESSURE_ADVANCE", "SET_EXTRUDER_ROTATION_DISTANCE",
        "SET_GCODE_OFFSET", "EXCLUDE_OBJECT", "SET_INPUT_SHAPER", "SET_RETRACTION",
    )

    @Test
    fun noLayerEventWritesAKlipperCommandInAMarlinFile() {
        for (flavor in listOf("Marlin", "RepRapFirmware", "Smoothie", "Custom", "")) {
            val route = GcodeRoute.forFlavor(flavor)
            for (type in LayerEventType.entries) {
                val lines = runCatching {
                    route.commands(type, layerNumber = 5, value = 200.0, secondaryValue = 50.0)
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
    fun anEventIsEitherAnsweredOrRefusedNeverSilentlyEmpty() {
        // What keeps a refusal visible: Klipper has no junction deviation and a generic flavour
        // has no verified retraction, and both say so rather than emitting nothing.
        // A value each event actually accepts: a retraction of 210 mm is refused by the range
        // check, which is a different refusal from "this machine has no such command".
        val values = mapOf(
            LayerEventType.NOZZLE_TEMPERATURE to 210.0,
            LayerEventType.BED_TEMPERATURE to 60.0,
            LayerEventType.FAN_SPEED to 50.0,
            LayerEventType.SPEED_FACTOR to 150.0,
            LayerEventType.FLOW_FACTOR to 110.0,
            LayerEventType.RETRACTION to 1.2,
            LayerEventType.PRESSURE_ADVANCE to 0.12,
            LayerEventType.JUNCTION_DEVIATION to 0.02,
        )
        for (flavor in listOf("Marlin", "Klipper", "RepRapFirmware", "Custom")) {
            val route = GcodeRoute.forFlavor(flavor)
            for (type in LayerEventType.entries) {
                val lines = runCatching {
                    route.commands(
                        type = type,
                        layerNumber = 5,
                        value = values[type],
                        secondaryValue = if (type == LayerEventType.RETRACTION) 35.0 else null,
                        // A custom event carries its own G-code; empty text is nothing to send,
                        // which is the caller's choice rather than a route falling silent.
                        text = "G1 X1",
                    )
                }.fold(onSuccess = { it }, onFailure = { error ->
                    assertTrue(
                        "$flavor refused $type with ${error::class.simpleName}",
                        error is UnsupportedFirmwareCommand,
                    )
                    return@fold emptyList()
                })
                if (lines.isNotEmpty()) {
                    assertTrue("$flavor answered $type with a blank line", lines.none { it.isBlank() })
                }
            }
        }
    }

    @Test
    fun theKlipperRouteIsItsOwn() {
        // The other half of the contract: the Klipper route really does differ, so this is a
        // gate rather than a claim that both sides are alike.
        val klipper = GcodeRoute.forFlavor("Klipper")
        // The route reports the flavour as the profile spells it, not a normalised one.
        assertEquals("klipper", GcodeRoute.forFlavor("klipper").flavor)
        assertTrue(klipper.commands(LayerEventType.PAUSE, 5).contains("PAUSE"))
        assertFalse(klipper.commands(LayerEventType.PAUSE, 5).contains("M0"))
        assertEquals("PAUSE", klipper.pauseCommand())
        assertEquals(resource("start-gcode.txt").isEmpty(), false)
        assertFalse(
            "the Klipper start G-code must not be the Marlin one",
            klipper.startGcode() == marlin.startGcode(),
        )
    }

    @Test
    fun aProfileIsMovedToTheDefaultForItsOwnRouteAndNoFurther() {
        // A Klipper profile still holding the old Marlin text is what the app itself stored.
        assertEquals(
            GcodeRoute.forFlavor("Klipper").startGcode(),
            GcodeRoute.migrateStart(resource("start-gcode.txt"), "Klipper"),
        )
        // A Marlin profile keeps its UBL lines: nothing about the Klipper route reaches it.
        assertEquals(
            resource("start-gcode.txt"),
            GcodeRoute.migrateStart(resource("start-gcode.txt"), "Marlin"),
        )
        // And a hand-edited script is nobody's business but the user's.
        val mine = "G28\nG1 Z5 F3000\n"
        assertEquals(mine, GcodeRoute.migrateStart(mine, "Klipper"))
        assertEquals(mine, GcodeRoute.migrateStart(mine, "Marlin"))
    }
}
