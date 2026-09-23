package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.OrcaSliceSettings
import com.tomppi.enderslicer.model.PrinterDefinition
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the option names the OrcaSlicer console actually accepts.
 *
 * This fork renamed most of PrusaSlicer's options, and a wrong name is not an error the engine
 * reports: the console applies the overrides with `ignore_nonexistent`, so an unknown key is
 * dropped and the preset's own value is used instead. That makes a silent typo look like a
 * setting that "did not stick", which is why every name in the writer is asserted here.
 */
class OrcaConfigWriterTest {

    private val printer = PrinterDefinition(
        name = "Modified Ender 3 V2",
        widthMm = 220.0, depthMm = 220.0, heightMm = 250.0,
        buildPlateShape = "rectangular", originAtCenter = false,
        heatedBed = true, heatedBuildVolume = false,
        gcodeFlavor = "Marlin", extruders = 1,
        nozzleSizeMm = 0.4, filamentDiameterMm = 1.75,
        printheadXMinMm = -26.0, printheadYMinMm = -32.0,
        printheadXMaxMm = 32.0, printheadYMaxMm = 34.0,
        gantryHeightMm = 25.0,
    )

    private val settings = OrcaSliceSettings(
        layerHeightMm = 0.2,
        firstLayerHeightMm = 0.24,
        wallLoops = 3,
        topShellLayers = 4,
        bottomShellLayers = 5,
        sparseInfillDensityPercent = 15.0,
        sparseInfillPattern = "grid",
        skirtLoops = 2,
        brimWidthMm = 4.0,
        supportEnabled = true,
        supportThresholdAngleDegrees = 55,
        supportBasePattern = "rectilinear",
        supportInterfaceTopLayers = 2,
        innerWallSpeedMmPerSecond = 40.0,
        outerWallSpeedMmPerSecond = 25.0,
        initialLayerSpeedMmPerSecond = 15.0,
        sparseInfillSpeedMmPerSecond = 50.0,
        internalSolidInfillSpeedMmPerSecond = 45.0,
        travelSpeedMmPerSecond = 150.0,
        nozzleTemperatureC = 210,
        initialLayerNozzleTemperatureC = 215,
        hotPlateTemperatureC = 60,
        initialLayerHotPlateTemperatureC = 65,
        fanMaxSpeedPercent = 100,
        fanMinSpeedPercent = 30,
        filamentType = "PLA",
        filamentFlowRatioPercent = 98.0,
        retractionLengthMm = 0.8,
        retractionSpeedMmPerSecond = 35.0,
        zHopMm = 0.4,
    )

    private fun rendered() = OrcaConfigWriter.render(
        settings = settings,
        printer = printer,
        startGcode = "G28\nG1 Z5 F5000",
        endGcode = "M104 S0\nM84",
    )

    @Test
    fun writesTheEnginesOwnOptionNames() {
        val print = rendered().print

        assertTrue(print, print.contains("wall_loops = 3\n"))
        assertTrue(print, print.contains("sparse_infill_density = 15%\n"))
        assertTrue(print, print.contains("top_shell_layers = 4\n"))
        assertTrue(print, print.contains("support_base_pattern = rectilinear\n"))
        assertTrue(print, print.contains("internal_solid_infill_speed = 45\n"))
        // The PrusaSlicer spellings the engine does not know. Checked per line: the engine's
        // own "sparse_infill_density" contains "fill_density" as a substring.
        val keys = print.lineSequence().map { it.substringBefore(" =").trim() }.toSet()
        assertFalse(print, "perimeters" in keys)
        assertFalse(print, "fill_density" in keys)
        assertFalse(print, "bed_temperature" in keys)
    }

    @Test
    fun theBedIsDescribedInTheEnginesPointForm() {
        assertEquals(
            "printable_area = 0x0,220x0,220x220,0x220\n",
            rendered().printer.lineSequence().first { it.startsWith("printable_area") } + "\n",
        )
    }

    @Test
    fun arcsStayOffForTheAppsLinearMovePipeline() {
        assertTrue(rendered().print.contains("enable_arc_fitting = 0\n"))
    }

    @Test
    fun gcodeBlocksTravelAsOneEscapedLine() {
        val printerIni = rendered().printer

        assertTrue(printerIni, printerIni.contains("machine_start_gcode = G28\\nG1 Z5 F5000\n"))
        assertEquals(
            "a gcode block must not add real line breaks",
            1,
            printerIni.lineSequence().count { it.startsWith("machine_start_gcode") },
        )
    }

    @Test
    fun temperaturesAndRetractionGoToTheirOwningBuckets() {
        val files = OrcaConfigWriter.write(
            directory = File.createTempFile("orca-writer", ".dir").let { it.delete(); it },
            settings = settings,
            printer = printer,
            startGcode = "G28",
            endGcode = "M84",
        )

        val filament = files.filament.readText()
        assertTrue(filament, filament.contains("nozzle_temperature = 210\n"))
        assertTrue(filament, filament.contains("nozzle_temperature_initial_layer = 215\n"))
        assertTrue(filament, filament.contains("hot_plate_temp = 60\n"))
        assertTrue(filament, filament.contains("fan_min_speed = 30\n"))
        assertTrue(filament, filament.contains("filament_flow_ratio = 0.98\n"))
        assertTrue(filament, filament.contains("filament_diameter = 1.75\n"))

        val printerIni = files.printer.readText()
        assertTrue(printerIni, printerIni.contains("retraction_length = 0.8\n"))
        assertTrue(printerIni, printerIni.contains("retraction_speed = 35\n"))
        assertTrue(printerIni, printerIni.contains("z_hop = 0.4\n"))
        assertTrue(printerIni, printerIni.contains("gcode_flavor = marlin\n"))
        assertTrue(printerIni, printerIni.contains("nozzle_diameter = 0.4\n"))
    }

    @Test
    fun extraKeysReachThePrintBucket() {
        val rendered = OrcaConfigWriter.render(
            settings = settings.copy(extraKeys = mapOf("sparse_infill_anchor" to "30%")),
            printer = printer,
            startGcode = "G28",
            endGcode = "M84",
        )

        assertTrue(rendered.print, rendered.print.contains("sparse_infill_anchor = 30%\n"))
    }
}
