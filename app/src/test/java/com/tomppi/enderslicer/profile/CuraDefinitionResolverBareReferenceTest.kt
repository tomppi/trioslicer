package com.tomppi.enderslicer.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cura definitions store plain child references such as
 * cool_fan_speed_min = "cool_fan_speed" or speed_roofing = "speed_topbottom".
 * CuraEngine never evaluates these (it reads the child key at its
 * default_value), and previously neither did this resolver, so the child
 * silently won its definition default. These tests pin the resolver's
 * variable-reference evaluation of bare identifiers.
 */
class CuraDefinitionResolverBareReferenceTest {

    @Test
    fun fanSpeedMinMaxFollowResolvedParentValue() {
        val resolved = resolve(
            extruderOverrides = linkedMapOf(
                "cool_fan_speed" to "40",
            ),
        )

        assertEquals("40", resolved.extruderValues["cool_fan_speed_min"])
        assertEquals("40", resolved.extruderValues["cool_fan_speed_max"])
    }

    @Test
    fun speedRoofingFollowsSpeedTopbottom() {
        val resolved = resolve(
            extruderOverrides = linkedMapOf(
                "speed_topbottom" to "60",
            ),
        )

        assertEquals("60", resolved.extruderValues["speed_roofing"])
        assertEquals("60", resolved.extruderValues["speed_flooring"])
    }

    @Test
    fun retractionRetractSpeedFollowsRetractionSpeed() {
        val resolved = resolve(
            extruderOverrides = linkedMapOf(
                "retraction_speed" to "120",
            ),
        )

        assertEquals("120", resolved.extruderValues["retraction_retract_speed"])
        assertEquals("120", resolved.extruderValues["retraction_prime_speed"])
    }

    @Test
    fun wallMaterialFlowFollowsMaterialFlow() {
        val resolved = resolve(
            extruderOverrides = linkedMapOf(
                "material_flow" to "105",
            ),
        )

        assertEquals("105", resolved.extruderValues["wall_0_material_flow"])
        assertEquals("105", resolved.extruderValues["skin_material_flow"])
        assertEquals("105", resolved.extruderValues["infill_material_flow"])
        assertEquals("105", resolved.extruderValues["support_material_flow"])
    }

    @Test
    fun booleanLiteralOverrideIsNotTreatedAsBareReference() {
        // creality_base.def.json overrides fill_outline_gaps with the literal
        // value "false" (no default_value). A bare-identifier interpretation
        // would resolve the variable "false", fail, and drop the key from the
        // resolved output, breaking the engine's Settings::get later.
        val resolved = resolve(extruderOverrides = emptyMap())

        assertTrue(resolved.extruderValues.containsKey("fill_outline_gaps"))
    }

    @Test
    fun bareCoolMinTemperatureFollowsParent() {
        val resolved = resolve(
            extruderOverrides = linkedMapOf(
                "material_print_temperature" to "215",
            ),
        )

        // cool_min_temperature is a bare reference to material_print_temperature.
        assertEquals("215", resolved.extruderValues["cool_min_temperature"])
    }

    private fun resolve(
        globalOverrides: Map<String, String> = emptyMap(),
        extruderOverrides: Map<String, String>,
    ): CuraDefinitionResolver.Result = CuraDefinitionResolver.resolve(
        definitionFiles = loadDefinitions(),
        machineDefinitionFileName = "creality_ender3.def.json",
        extruderDefinitionFileName = "creality_base_extruder_0.def.json",
        globalOverrides = linkedMapOf(
            "machine_name" to "Modified Ender 3 V2",
            "machine_width" to "230",
            "machine_depth" to "230",
            "machine_height" to "250",
            "machine_shape" to "rectangular",
            "machine_center_is_zero" to "false",
            "machine_heated_bed" to "true",
            "machine_heated_build_volume" to "false",
            "machine_extruder_count" to "1",
            "machine_gcode_flavor" to "Marlin",
        ).apply { putAll(globalOverrides) },
        extruderOverrides = linkedMapOf(
            "extruder_nr" to "0",
            "machine_nozzle_size" to "0.4",
            "material_diameter" to "1.75",
            "line_width" to "=machine_nozzle_size",
            "material_print_temperature" to "=210",
        ).apply { putAll(extruderOverrides) },
    )

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
