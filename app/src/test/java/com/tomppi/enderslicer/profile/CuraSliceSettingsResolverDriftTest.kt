package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class CuraSliceSettingsResolverDriftTest {
    private val printer = PrinterDefinition(
        name = "Modified Ender 3 V2",
        widthMm = 230.0,
        depthMm = 230.0,
        heightMm = 250.0,
        buildPlateShape = "rectangular",
        originAtCenter = false,
        heatedBed = true,
        heatedBuildVolume = false,
        gcodeFlavor = "Marlin",
        extruders = 1,
        nozzleSizeMm = 0.4,
        filamentDiameterMm = 1.75,
        printheadXMinMm = -26.0,
        printheadYMinMm = -32.0,
        printheadXMaxMm = 32.0,
        printheadYMaxMm = 34.0,
        gantryHeightMm = 25.0,
    )

    @Test
    fun importedProjectDefinitionsMissingEngineDriftSettingsStillResolve() {
        val resolved = CuraSliceSettingsResolver.resolve(
            profile = driftStrippedProfile(),
            printer = printer,
            settings = uiSettings(),
            startGcode = "G28",
            endGcode = "M104 S0",
        )

        assertEquals("0", resolved.extruderValues["wall_x_inset"])
        assertEquals("0", resolved.extruderValues["support_base_inside_width"])
        assertEquals("0", resolved.extruderValues["support_base_outside_width"])
        assertEquals("false", resolved.extruderValues["support_outer_brim_enable"])
        assertEquals("4", resolved.extruderValues["support_inside_base_curve_magnitude"])
        assertEquals("0", resolved.extruderValues["support_inside_base_height"])
        assertEquals("4", resolved.extruderValues["support_outside_base_curve_magnitude"])
        assertEquals("0", resolved.extruderValues["support_outside_base_height"])
    }

    @Test
    fun importedDriftSettingValueWinsOverSeededDefault() {
        val profile = driftStrippedProfile(
            extraRawExtruderValues = mapOf("wall_x_inset" to "0.5"),
        )

        val resolved = CuraSliceSettingsResolver.resolve(
            profile = profile,
            printer = printer,
            settings = uiSettings(),
            startGcode = "G28",
            endGcode = "M104 S0",
        )

        assertEquals("0.5", resolved.extruderValues["wall_x_inset"])
    }

    private fun uiSettings() = SlicerSettings(
        layerHeightMm = 0.20,
        initialLayerHeightMm = 0.28,
        lineWidthMm = 0.40,
        printSpeedMmPerSecond = 120.0,
        wallSpeedMmPerSecond = 60.0,
        outerWallSpeedMmPerSecond = 30.0,
        innerWallSpeedMmPerSecond = 60.0,
        infillSpeedMmPerSecond = 120.0,
        topBottomSpeedMmPerSecond = 60.0,
        nozzleTemperatureC = 200,
        initialNozzleTemperatureC = 220,
        supportsEnabled = true,
        supportPlacement = "everywhere",
        supportStructure = "tree",
        supportAngleDegrees = 56.0,
        supportDensityPercent = 0.0,
        supportInterfaceEnabled = true,
        supportInterfaceDensityPercent = 33.333,
        supportZDistanceMm = 0.2,
        supportXyDistanceMm = 0.8,
        adhesionType = "none",
        overriddenSettingKeys = emptySet(),
    )

    private fun driftStrippedProfile(
        extraRawExtruderValues: Map<String, String> = emptyMap(),
    ): CuraEngineProfile = CuraEngineProfile(
        rawGlobalValues = linkedMapOf(
            "layer_height" to "0.2",
            "layer_height_0" to "0.28",
            "top_bottom_thickness" to "=layer_height_0+layer_height*3",
            "wall_thickness" to "=line_width*2",
            "support_enable" to "True",
            "support_type" to "everywhere",
            "support_structure" to "tree",
            "material_bed_temperature" to "60",
            "adhesion_type" to "none",
        ),
        rawExtruderValues = linkedMapOf(
            "machine_nozzle_size" to "0.4",
            "material_diameter" to "1.75",
            "line_width" to "=machine_nozzle_size",
            "layer_height" to "0.2",
            "speed_print" to "120",
            "speed_infill" to "=speed_print",
            "speed_wall" to "=speed_print / 2",
            "speed_topbottom" to "=speed_print / 2",
            "infill_sparse_density" to "10",
            "infill_pattern" to "cubic",
            "infill_line_width" to "=line_width",
            "infill_line_distance" to "=0 if infill_sparse_density == 0 else (infill_line_width * 100) / infill_sparse_density * (3 if infill_pattern == 'cubic' else 1)",
            "material_print_temperature" to "200",
            "material_print_temperature_layer_0" to "220",
            "cool_min_temperature" to "=material_print_temperature",
            "cool_fan_speed" to "100",
            "cool_fan_speed_0" to "0",
            "cool_fan_full_at_height" to "=layer_height_0 + layer_height * 2",
            "cool_fan_full_layer" to "=max(1, int(math.floor((cool_fan_full_at_height - resolveOrValue('layer_height_0')) / resolveOrValue('layer_height')) + 2))",
            "top_bottom_thickness" to "=layer_height_0+layer_height*3",
            "top_thickness" to "=top_bottom_thickness",
            "bottom_thickness" to "=top_bottom_thickness",
            "top_layers" to "=0 if infill_sparse_density == 100 else math.ceil(round(top_thickness / resolveOrValue('layer_height'), 4))",
            "bottom_layers" to "=999999 if infill_sparse_density == 100 and not magic_spiralize else math.ceil(round(bottom_thickness / resolveOrValue('layer_height'), 4))",
            "initial_bottom_layers" to "=bottom_layers",
            "wall_line_width_0" to "=line_width",
            "wall_line_width_x" to "=line_width",
            "wall_line_count" to "=1 if magic_spiralize else max(1, round((wall_thickness - wall_line_width_0) / wall_line_width_x) + 1) if wall_thickness != 0 else 0",
            "wall_thickness" to "=line_width*2",
            "support_infill_rate" to "=0 if support_enable and support_structure == 'tree' else 20",
            "support_interface_enable" to "=True",
            "support_roof_enable" to "=support_interface_enable",
            "support_bottom_enable" to "=support_interface_enable",
            "support_interface_density" to "=33.333",
            "support_z_distance" to "=layer_height if layer_height >= 0.16 else layer_height * 2",
            "support_xy_distance" to "=wall_line_width_0 * 2",
            "support_angle" to "56",
        ).apply { putAll(extraRawExtruderValues) },
        definitionFiles = loadDriftStrippedDefinitions(),
        machineDefinitionFileName = "creality_ender3.def.json",
        extruderDefinitionFileName = "creality_base_extruder_0.def.json",
    )

    private fun loadDriftStrippedDefinitions(): Map<String, String> {
        val directory = sequenceOf(
            File("app/src/main/assets/cura/definitions"),
            File("src/main/assets/cura/definitions"),
        ).firstOrNull(File::isDirectory)
            ?: error("Pinned Cura definition directory was not found")
        val names = listOf(
            "fdmprinter.def.json",
            "fdmextruder.def.json",
            "creality_base.def.json",
            "creality_base_extruder_0.def.json",
            "creality_ender3.def.json",
        )
        return names.associateWith { name ->
            val content = File(directory, name).readText()
            if (name == "fdmprinter.def.json") stripEngineDriftSettings(content) else content
        }
    }

    private fun stripEngineDriftSettings(fdmprinter: String): String {
        val root = JSONObject(fdmprinter)
        val settings = root.getJSONObject("settings")
        settings.getJSONObject("shell").getJSONObject("children").apply {
            remove("wall_x_inset")
        }
        settings.getJSONObject("support").getJSONObject("children").apply {
            remove("support_base_inside_width")
            remove("support_base_outside_width")
            remove("support_outer_brim_enable")
            remove("support_inside_base_curve_magnitude")
            remove("support_inside_base_height")
            remove("support_outside_base_curve_magnitude")
            remove("support_outside_base_height")
        }
        return root.toString()
    }
}
