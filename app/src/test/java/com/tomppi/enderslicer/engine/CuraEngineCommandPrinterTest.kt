package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CuraEngineCommandPrinterTest {
    @Test
    fun fallbackCommandUsesCustomMachineGcodeAndAdvancedSettings() {
        val printer = printer(width = 100.0, depth = 100.0, originAtCenter = false)
        val settings = SlicerSettings(
            printerName = "Other printer",
            machineWidthMm = 320.0,
            machineDepthMm = 330.0,
            machineHeightMm = 410.0,
            buildPlateShape = "elliptic",
            originAtCenter = true,
            heatedBed = true,
            gcodeFlavor = "RepRap",
            nozzleSizeMm = 0.6,
            filamentDiameterMm = 2.85,
            customStartGcodeEnabled = true,
            customStartGcode = "G28\nM117 custom start",
            customEndGcodeEnabled = true,
            customEndGcode = "M104 S0\nM84",
            slicingTolerance = "inclusive",
            zSeamType = "back",
            zSeamXmm = 12.5,
            zSeamYmm = 300.0,
            zSeamRelative = true,
            zSeamCorner = "z_seam_corner_weighted",
            coastingEnabled = true,
            coastingVolumeMm3 = 0.09,
            coastingMinimumVolumeMm3 = 1.4,
            coastingSpeedPercent = 95.0,
        )

        val values = commandSettings(build(printer, settings))

        assertEquals("Other printer", values["machine_name"])
        assertEquals("320.0", values["machine_width"])
        assertEquals("330.0", values["machine_depth"])
        assertEquals("410.0", values["machine_height"])
        assertEquals("elliptic", values["machine_shape"])
        assertEquals("true", values["machine_center_is_zero"])
        assertEquals("true", values["machine_heated_bed"])
        assertEquals("RepRap", values["machine_gcode_flavor"])
        assertEquals("G28\nM117 custom start", values["machine_start_gcode"])
        assertEquals("M104 S0\nM84", values["machine_end_gcode"])
        assertEquals("0.6", values["machine_nozzle_size"])
        assertEquals("2.85", values["material_diameter"])
        assertEquals("inclusive", values["slicing_tolerance"])
        assertEquals("back", values["z_seam_type"])
        assertEquals("12.5", values["z_seam_x"])
        assertEquals("300.0", values["z_seam_y"])
        assertEquals("true", values["z_seam_relative"])
        assertEquals("z_seam_corner_weighted", values["z_seam_corner"])
        assertEquals("true", values["coasting_enable"])
        assertEquals("0.09", values["coasting_volume"])
        assertEquals("1.4", values["coasting_min_volume"])
        assertEquals("95.0", values["coasting_speed"])
        assertEquals("false", values["center_object"])
        assertEquals("0.0", values["mesh_position_x"])
        assertTrue(build(printer, settings).contains("-l"))
    }

    @Test
    fun standaloneFallbackMirrorsInterfaceIntoEngineReadRoofBottomKeys() {
        val settings = SlicerSettings(
            machineWidthMm = 230.0,
            machineDepthMm = 230.0,
            originAtCenter = false,
            supportsEnabled = true,
            supportStructure = "tree",
            supportPlacement = "everywhere",
            supportDensityPercent = 20.0,
            supportPattern = "grid",
            supportInterfaceEnabled = true,
            supportInterfaceDensityPercent = 33.333,
            supportInterfaceSpeedMmPerSecond = 30.0,
            layerHeightMm = 0.2,
            lineWidthMm = 0.4,
        )

        val values = commandSettings(build(printer(width = 230.0, depth = 230.0, originAtCenter = false), settings))

        assertEquals("-115.0", values["mesh_position_x"])
        assertEquals("-115.0", values["mesh_position_y"])
        assertEquals("0.0", values["support_infill_rate"])
        assertEquals("true", values["support_interface_enable"])
        assertEquals("33.333", values["support_interface_density"])
        assertEquals("0.8", values["support_interface_height"])
        assertEquals("grid", values["support_interface_pattern"])
        assertEquals("2.4000240002400024", values["support_roof_line_distance"])
        assertEquals("30.0", values["speed_support_interface"])
        // CuraEngine only reads the per-extruder roof/bottom children; the
        // standalone transport must mirror the interface enable/density/height
        // into them or the support generator silently uses their defaults
        // (get<...> reads directly from the settings container).
        assertEquals("true", values["support_roof_enable"])
        assertEquals("true", values["support_bottom_enable"])
        assertEquals("33.333", values["support_roof_density"])
        assertEquals("33.333", values["support_bottom_density"])
        assertEquals("0.8", values["support_roof_height"])
        assertEquals("0.8", values["support_bottom_height"])
        assertEquals("30.0", values["speed_support_roof"])
        assertEquals("30.0", values["speed_support_bottom"])
        assertEquals("0.2", values["support_top_distance"])
        assertEquals("0.2", values["support_bottom_distance"])
    }

    @Test
    fun standaloneFallbackKeepsTheUsersSupportInterfaceThickness() {
        val settings = SlicerSettings(
            machineWidthMm = 230.0,
            machineDepthMm = 230.0,
            originAtCenter = false,
            supportsEnabled = true,
            supportInterfaceEnabled = true,
            supportInterfaceHeightMm = 0.6,
            layerHeightMm = 0.2,
            lineWidthMm = 0.4,
        )

        val values = commandSettings(build(printer(width = 230.0, depth = 230.0, originAtCenter = false), settings))

        // layerHeight * 4 = 0.8 used to overwrite the value the UI field set.
        assertEquals("0.6", values["support_interface_height"])
        assertEquals("0.6", values["support_roof_height"])
        assertEquals("0.6", values["support_bottom_height"])
        // The hardcoded pattern/extruder/width settings stay as they are.
        assertEquals("grid", values["support_interface_pattern"])
        assertEquals("grid", values["support_roof_pattern"])
        assertEquals("grid", values["support_bottom_pattern"])
        assertEquals("0.4", values["support_roof_line_width"])
        assertEquals("0.4", values["support_bottom_line_width"])
    }

    private fun build(
        printer: PrinterDefinition,
        settings: SlicerSettings,
    ): List<String> = CuraEngineCommand.build(
        executablePath = "/tmp/CuraEngine",
        definitionsDirectory = "/tmp/definitions",
        machineDefinitionPath = "/tmp/machine.def.json",
        extruderDefinitionPath = "/tmp/extruder.def.json",
        modelPath = "/tmp/model.stl",
        outputPath = "/tmp/output.gcode",
        printer = printer,
        settings = settings,
        startGcode = "G28 ; fallback",
        endGcode = "M84 ; fallback",
        profile = null,
    )

    private fun printer(width: Double, depth: Double, originAtCenter: Boolean) = PrinterDefinition(
        name = "Base",
        widthMm = width,
        depthMm = depth,
        heightMm = 100.0,
        buildPlateShape = "rectangular",
        originAtCenter = originAtCenter,
        heatedBed = false,
        heatedBuildVolume = false,
        gcodeFlavor = "Marlin",
        extruders = 1,
        nozzleSizeMm = 0.4,
        filamentDiameterMm = 1.75,
        printheadXMinMm = -10.0,
        printheadYMinMm = -10.0,
        printheadXMaxMm = 10.0,
        printheadYMaxMm = 10.0,
        gantryHeightMm = 20.0,
    )

    private fun commandSettings(command: List<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < command.size - 1) {
            if (command[index] == "-s") {
                val setting = command[index + 1]
                val separator = setting.indexOf('=')
                if (separator > 0) result[setting.substring(0, separator)] = setting.substring(separator + 1)
                index += 2
            } else {
                index += 1
            }
        }
        return result
    }
}
