package com.tomppi.enderslicer.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

/**
 * The status store: what klippy pushes, merged into what the screens read.
 *
 * Every case here is a shape a real klippy sends, taken from a live one. The mistake
 * this guards against is the one that cost a day: a notification carries only what
 * changed, so an object replaced rather than merged loses the fields it did not mention.
 */
class KlipperObjectsTest {

    private fun status(json: String) = JSONObject(json)

    @Test
    fun aPartialUpdateKeepsTheFieldsItDidNotMention() {
        val first = mapOf("extruder" to status("""{"temperature": 24.5, "target": 0.0, "power": 0.1}"""))
        val merged = first.mergedWith(status("""{"extruder": {"temperature": 210.2}}"""))

        val extruder = merged.getValue("extruder")
        assertEquals(210.2, extruder.optDouble("temperature"), 0.001)
        // The whole point: the target and the power are still the ones from before.
        assertEquals(0.0, extruder.optDouble("target"), 0.001)
        assertEquals(0.1, extruder.optDouble("power"), 0.001)
    }

    @Test
    fun anUpdateThatChangesNothingKeepsTheSameObjects() {
        val held = status("""{"temperature": 24.5}""")
        val before = mapOf("extruder" to held)
        val after = before.mergedWith(status("""{"extruder": {"temperature": 24.5}}"""))
        // Identity, not equality: the state is compared by identity so that a screen is
        // not rebuilt for a notification that said nothing new.
        assertSame(held, after.getValue("extruder"))
    }

    @Test
    fun aNewObjectIsAddedAndOthersAreLeftAlone() {
        val before = mapOf("extruder" to status("""{"temperature": 1.0}"""))
        val after = before.mergedWith(status("""{"heater_bed": {"temperature": 60.0}}"""))
        assertEquals(1.0, after.getValue("extruder").optDouble("temperature"), 0.001)
        assertEquals(60.0, after.getValue("heater_bed").optDouble("temperature"), 0.001)
    }

    @Test
    fun theTypedFieldsStillMergeTheWayTheyDid() {
        val state = KlipperPrinterState(connected = true)
            .withStatus(status("""{"extruder": {"temperature": 24.5}}"""))
            .withStatus(status("""{"toolhead": {"position": [1.0, 2.0, 3.0, 0.0]}}"""))
            .withStatus(status("""{"extruder": {"temperature": 25.5}}"""))
        assertEquals(25.5, state.extruderTemperature!!, 0.001)
        assertEquals(listOf(1.0, 2.0, 3.0, 0.0), state.position)
    }

    @Test
    fun heatersAreOrderedHotendThenBedThenTheRest() {
        val state = KlipperPrinterState(connected = true).withStatus(
            status(
                """{
                  "heater_bed": {"temperature": 60.0, "target": 60.0},
                  "extruder": {"temperature": 210.0, "target": 210.0},
                  "temperature_sensor chamber": {"temperature": 31.0},
                  "heater_generic chamber_heater": {"temperature": 40.0, "target": 45.0}
                }""",
            ),
        )
        // Within the rest, by the name shown: "chamber" comes before "chamber heater".
        assertEquals(
            listOf("extruder", "heater_bed", "temperature_sensor chamber", "heater_generic chamber_heater"),
            state.heaters.map { it.name },
        )
        assertEquals("Hotend", state.heaters.first().label)
        assertEquals("Bed", state.heaters[1].label)
        // A plain sensor takes no target, which is what decides whether a screen offers
        // it one.
        assertFalse(state.heaters.first { it.name == "temperature_sensor chamber" }.isHeater)
        assertTrue(state.heaters.first { it.name == "heater_generic chamber_heater" }.isHeater)
    }

    @Test
    fun macrosComeFromTheConfigurationAndSkipWhatIsNotMeantToBePressed() {
        val state = KlipperPrinterState(connected = true).withStatus(
            status(
                """{"configfile": {"settings": {
                     "gcode_macro PAUSE": {"description": "Pause the print", "gcode": "..."},
                     "gcode_macro RESUME": {"gcode": "..."},
                     "gcode_macro _helper": {"gcode": "..."},
                     "gcode_macro M600": {"rename_existing": "M600.1", "gcode": "..."},
                     "extruder": {"nozzle_diameter": 0.4}
                   }}}""",
            ),
        )
        // The helper is called by other macros, and M600 replaces a command Klipper
        // already has: neither belongs in a list of things to run.
        assertEquals(listOf("PAUSE", "RESUME"), state.macros.map { it.name })
        assertEquals("Pause the print", state.macros.first { it.name == "PAUSE" }.description)
    }

    @Test
    fun theMeshIsReadFromTheProbedGrid() {
        val state = KlipperPrinterState(connected = true).withStatus(
            status(
                """{"bed_mesh": {
                     "profile_name": "default",
                     "mesh_min": [30.0, 30.0], "mesh_max": [190.0, 190.0],
                     "probed_matrix": [[0.1, -0.2], [0.3, 0.05]],
                     "profiles": [{"name": "default"}, {"name": "cold"}]
                   }}""",
            ),
        )
        val mesh = state.mesh!!
        assertTrue(mesh.isLoaded)
        assertEquals(2 to 2, mesh.shape)
        assertEquals(-0.2, mesh.minimum!!, 0.0001)
        assertEquals(0.3, mesh.maximum!!, 0.0001)
        assertEquals(listOf("default", "cold"), mesh.profiles)
    }

    @Test
    fun theMicroControllersCarryTheirOwnTiming() {
        val state = KlipperPrinterState(connected = true).withStatus(
            status(
                """{"mcu": {
                     "mcu_version": "v0.13.0",
                     "mcu_constants": {"CLOCK_FREQ": 72000000},
                     "last_stats": {"srtt": 0.004, "rttvar": 0.001, "rto": 0.025,
                                    "bytes_retransmit": 9, "mcu_awake": 0.2, "mcu_task_avg": 0.00002}
                   }}""",
            ),
        )
        val mcu = state.mcus.single()
        assertEquals("mcu", mcu.name)
        assertEquals("v0.13.0", mcu.version)
        assertEquals(0.004, mcu.roundTripSeconds!!, 1e-9)
        assertEquals(9, mcu.retransmits)
        assertEquals(72_000_000.0, mcu.frequency!!, 0.1)
    }

    @Test
    fun theHostStatisticsAreReadByName() {
        val state = KlipperPrinterState(connected = true).withStatus(
            status("""{"system_stats": {"sysload": 0.19, "cputime": 116.2, "memavail": 21851252}}"""),
        )
        val stats = state.systemStats!!
        assertEquals(0.19, stats.load!!, 1e-9)
        assertEquals(21_851_252.0, stats.memoryAvailable!!, 0.5)
    }

    @Test
    fun theEndstopsAreWhateverKlippyLastSaid() {
        val state = KlipperPrinterState(connected = true).withStatus(
            status("""{"query_endstops": {"last_query": {"x": "open", "y": "TRIGGERED", "z": "open"}}}"""),
        )
        assertEquals(mapOf("x" to "open", "y" to "TRIGGERED", "z" to "open"), state.endstops)
    }

    @Test
    fun layersAreOnlyThereWhenTheSlicerDeclaredThem() {
        val none = KlipperPrinterState(connected = true).withStatus(
            status("""{"print_stats": {"info": {"total_layer": null, "current_layer": null}}}"""),
        )
        assertNull(none.printLayers)

        val some = KlipperPrinterState(connected = true).withStatus(
            status("""{"print_stats": {"info": {"total_layer": 120, "current_layer": 40}}}"""),
        )
        assertEquals(KlipperLayers(40, 120), some.printLayers)
    }

    @Test
    fun theExcludedObjectsAreTheOnesKlippyLists() {
        val state = KlipperPrinterState(connected = true).withStatus(
            status(
                """{"exclude_object": {
                     "objects": [{"name": "cube", "center": [10, 10]}, {"name": "cone"}],
                     "excluded_objects": ["cone"],
                     "current_object": "cube"
                   }}""",
            ),
        )
        assertEquals(listOf("cube", "cone"), state.plateObjects)
        assertEquals(listOf("cone"), state.excludedObjects)
        assertEquals("cube", state.currentObject)
    }

    @Test
    fun theHostIsReadFromKlippysOwnInfo() {
        val host = KlipperHost.from(
            status(
                """{"software_version": "v0.13.0-0-g61c0c8d", "hostname": "localhost",
                    "cpu_info": "aarch64", "python_path": "/x/python", "config_file": "/x/printer.cfg",
                    "log_file": "/x/klippy.log", "process_id": 4711}""",
            ),
        )
        assertEquals("v0.13.0-0-g61c0c8d", host.softwareVersion)
        assertEquals(4711, host.processId)
        assertEquals("/x/printer.cfg", host.configFile)
    }
}
