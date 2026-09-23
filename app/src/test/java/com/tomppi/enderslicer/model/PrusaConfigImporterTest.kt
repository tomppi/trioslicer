package com.tomppi.enderslicer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrusaConfigImporterTest {

    /**
     * The app's stored start/end G-code is what a profile without its own
     * templates must leave alone. Absent and empty are different answers, so the
     * importer reports the first as null - an import used to write "" over the
     * user's custom G-code.
     */
    @Test
    fun aProfileWithoutGcodeTemplatesReportsThemAsAbsent() {
        val result = PrusaConfigImporter.parse("[print]\nlayer_height = 0.2\n")

        assertNull(result.startGcode)
        assertNull(result.endGcode)
    }

    @Test
    fun anExplicitlyEmptyTemplateStaysEmpty() {
        val result = PrusaConfigImporter.parse("[printer]\nstart_gcode =\nend_gcode =\n")

        assertEquals("", result.startGcode)
        assertEquals("", result.endGcode)
    }

    @Test
    fun importsFullIniSections() {
        val text = """
            [print]
            print_settings_id = 0.20 mm NORMAL (0.4 mm nozzle)
            layer_height = 0.2
            first_layer_height = 0.3
            perimeters = 3
            top_solid_layers = 4
            bottom_solid_layers = 4
            fill_density = 15%
            fill_pattern = grid
            skirts = 2
            support_material = 1
            support_material_threshold_angle = 50
            support_material_pattern = rectilinear
            support_material_interface = 1
            support_material_interface_layers = 3
            print_speed = 70
            external_perimeter_speed = 30
            infill_speed = 60
            first_layer_speed = 25
            travel_speed = 180
            gcode_flavor = marlin2
            start_gcode = G28\nG1 Z5
            end_gcode = M84\nM104 S0

            [filament]
            filament_diameter = 1.75
            temperature = 205
            first_layer_temperature = 210
            bed_temperature = 55
            first_layer_bed_temperature = 55
            fan_speed = 90
            extrusion_multiplier = 1.05

            [printer]
            printer_settings_id = Ender-3 V2 (0.4 mm nozzle)
            bed_shape = 5x0,215x0,215x220,5x220
            nozzle_diameter = 0.4
            extruder_count = 1
            use_firmware_retraction = 0
            retraction_length = 0.8
            retraction_speed = 45
            retraction_min_travel = 2
            retract_lift = 0.2
        """.trimIndent()

        val result = PrusaConfigImporter.parse(text)

        assertEquals(0.2, result.settings.layerHeightMm, 1e-9)
        assertEquals(0.3, result.settings.firstLayerHeightMm, 1e-9)
        assertEquals(3, result.settings.perimeters)
        assertEquals(15.0, result.settings.fillDensityPercent, 1e-9)
        assertEquals("grid", result.settings.fillPattern)
        assertEquals(2, result.settings.skirtLoops)
        assertTrue(result.settings.supportMaterial)
        assertEquals(50.0, result.settings.supportThresholdAngleDegrees, 1e-9)
        assertEquals(3, result.settings.supportInterfaceLayers)
        assertEquals(70.0, result.settings.printSpeedMmPerSecond, 1e-9)
        assertEquals(30.0, result.settings.externalPerimeterSpeedMmPerSecond, 1e-9)
        assertEquals(205, result.settings.nozzleTemperatureC)
        assertEquals(55, result.settings.bedTemperatureC)
        assertEquals(90, result.settings.fanSpeedPercent)
        assertEquals(105.0, result.settings.extrusionMultiplierPercent, 1e-9)
        assertEquals(0.8, result.settings.retractionLengthMm, 1e-9)
        assertEquals(0.2, result.settings.retractLiftMm, 1e-9)
        assertFalse(result.settings.useFirmwareRetraction)

        assertEquals("G28\nG1 Z5", result.startGcode)
        assertEquals("M84\nM104 S0", result.endGcode)
        assertEquals(210.0, result.widthMm ?: -1.0, 1e-9)
        assertEquals(220.0, result.depthMm ?: -1.0, 1e-9)
        assertFalse(result.originAtCenter)
        assertEquals(0.4, result.nozzleSizeMm ?: -1.0, 1e-9)
        assertEquals(1, result.extruders)
        assertEquals(1.75, result.filamentDiameterMm ?: -1.0, 1e-9)
        assertEquals("marlin2", result.gcodeFlavor)
    }

    @Test
    fun centeredBedShapeDetectsCenterOrigin() {
        val result = PrusaConfigImporter.parse(
            "[printer]\nbed_shape = -115x-115,115x-115,115x115,-115x115",
        )
        assertTrue(result.originAtCenter)
        assertEquals(230.0, result.widthMm ?: -1.0, 1e-9)
        assertEquals(230.0, result.depthMm ?: -1.0, 1e-9)
    }

    @Test
    fun missingKeysFallBackToAppDefaults() {
        val result = PrusaConfigImporter.parse("[print]\nlayer_height = 0.35")
        assertEquals(0.35, result.settings.layerHeightMm, 1e-9)
        assertEquals(1, result.settings.skirtLoops)
        assertEquals("grid", result.settings.fillPattern)
        assertEquals(210, result.settings.nozzleTemperatureC)
        assertNotNull(result.settings)
    }
}
