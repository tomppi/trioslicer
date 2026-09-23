package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CuraSliceSettingsResolverTest {
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
    fun importedBaselineWinsOverDuplicateWrongScopeValues() {
        val resolved = resolve(profile(), importedUiSettings())

        assertNumeric(resolved.globalValues, "layer_height", 0.20)
        assertNumeric(resolved.extruderValues, "layer_height", 0.20)
        assertEquals("none", resolved.globalValues["adhesion_type"])
        assertEquals("none", resolved.extruderValues["adhesion_type"])
        assertNumeric(resolved.extruderValues, "speed_print", 120.0)
        assertNumeric(resolved.extruderValues, "material_print_temperature", 200.0)
        assertNumeric(resolved.extruderValues, "material_print_temperature_layer_0", 220.0)
        assertNumeric(resolved.extruderValues, "cool_min_temperature", 200.0)
        assertTrue(resolved.expressionCount > 0)
    }

    @Test
    fun explicitEditsRecalculateTheWholeDependentChain() {
        val settings = importedUiSettings().copy(
            layerHeightMm = 0.30,
            printSpeedMmPerSecond = 150.0,
            nozzleTemperatureC = 215,
            adhesionType = "brim",
            overriddenSettingKeys = setOf(
                SlicerSettings.Keys.LAYER_HEIGHT,
                SlicerSettings.Keys.PRINT_SPEED,
                SlicerSettings.Keys.NOZZLE_TEMPERATURE,
                SlicerSettings.Keys.ADHESION_TYPE,
            ),
        )

        val resolved = resolve(profile(), settings)

        assertNumeric(resolved.globalValues, "layer_height", 0.30)
        assertNumeric(resolved.extruderValues, "layer_height", 0.30)
        assertEquals("brim", resolved.globalValues["adhesion_type"])
        assertEquals("brim", resolved.extruderValues["adhesion_type"])
        assertNumeric(resolved.extruderValues, "speed_print", 150.0)
        assertNumeric(resolved.extruderValues, "speed_infill", 150.0)
        assertNumeric(resolved.extruderValues, "speed_wall", 75.0)
        assertNumeric(resolved.extruderValues, "speed_topbottom", 75.0)
        assertNumeric(resolved.extruderValues, "material_print_temperature", 215.0)
        assertNumeric(resolved.extruderValues, "cool_min_temperature", 215.0)
        assertNumeric(resolved.extruderValues, "top_bottom_thickness", 1.18)
        assertNumeric(resolved.extruderValues, "top_layers", 4.0)
        assertNumeric(resolved.extruderValues, "bottom_layers", 4.0)
        assertNumeric(resolved.extruderValues, "support_z_distance", 0.30)
        assertNumeric(resolved.modelValues, "support_z_distance", 0.30)
    }

    @Test
    fun formulaOwnedValuesRemainFormulaOwnedWhenUnrelatedSettingsChange() {
        val settings = importedUiSettings().copy(
            adhesionType = "brim",
            overriddenSettingKeys = setOf(SlicerSettings.Keys.ADHESION_TYPE),
        )

        val resolved = resolve(profile(), settings)

        assertEquals("brim", resolved.globalValues["adhesion_type"])
        assertNumeric(resolved.globalValues, "layer_height", 0.20)
        assertNumeric(resolved.extruderValues, "speed_print", 120.0)
        assertNumeric(resolved.extruderValues, "support_infill_rate", 0.0)
        assertNumeric(resolved.extruderValues, "support_interface_density", 33.333)
        assertEquals("true", resolved.modelValues["support_interface_enable"]?.lowercase())
        assertEquals("true", resolved.modelValues["support_roof_enable"]?.lowercase())
        assertNumeric(resolved.modelValues, "support_xy_distance", 0.8)
    }

    private fun importedUiSettings() = SlicerSettings(
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

    private fun resolve(
        profile: CuraEngineProfile,
        settings: SlicerSettings,
    ): CuraSliceSettingsResolver.Result = CuraSliceSettingsResolver.resolve(
        profile = profile,
        printer = printer,
        settings = settings,
        startGcode = "G28",
        endGcode = "M104 S0",
    )

    private fun profile(): CuraEngineProfile = CuraEngineProfile(
        globalValues = mapOf(
            "layer_height" to "0.2",
            "adhesion_type" to "none",
        ),
        extruderValues = mapOf(
            "layer_height" to "0.1",
            "adhesion_type" to "brim",
            "speed_print" to "120",
        ),
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
            "layer_height" to "0.1",
            "adhesion_type" to "brim",
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
        ),
        definitionFiles = loadDefinitions(),
        machineDefinitionFileName = "creality_ender3.def.json",
        extruderDefinitionFileName = "creality_base_extruder_0.def.json",
    )

    private fun assertNumeric(values: Map<String, String>, key: String, expected: Double) {
        val raw = values[key] ?: error("Missing resolved setting: $key")
        val actual = raw.toDoubleOrNull() ?: error("Resolved setting is not numeric: $key=$raw")
        assertEquals("Unexpected resolved value for $key", expected, actual, 1e-7)
    }

    private fun loadDefinitions(): Map<String, String> {
        val directory = sequenceOf(
            File("app/src/main/assets/cura/definitions"),
            File("src/main/assets/cura/definitions"),
        ).firstOrNull(File::isDirectory)
            ?: error("Pinned Cura definition directory was not found")
        return listOf(
            "fdmprinter.def.json",
            "fdmextruder.def.json",
            "creality_base.def.json",
            "creality_base_extruder_0.def.json",
            "creality_ender3.def.json",
        ).associateWith { name -> File(directory, name).readText() }
    }
}
