package com.tomppi.enderslicer.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CuraDefinitionResolverTypeValidationTest {
    @Test
    fun rejectsNumericBooleanOutsideZeroOrOne() {
        val error = runCatching {
            CuraDefinitionResolver.resolve(
                definitionFiles = definitions("=1 + 1"),
                machineDefinitionFileName = "machine.def.json",
                extruderDefinitionFileName = "extruder.def.json",
                globalOverrides = emptyMap(),
                extruderOverrides = emptyMap(),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("key=zig_zaggify_infill"))
        assertTrue(error?.message.orEmpty().contains("declared=bool"))
    }

    @Test
    fun acceptsNumericBooleanZeroOrOneWithoutFlatteningExpression() {
        val result = CuraDefinitionResolver.resolve(
            definitionFiles = definitions("=1"),
            machineDefinitionFileName = "machine.def.json",
            extruderDefinitionFileName = "extruder.def.json",
            globalOverrides = emptyMap(),
            extruderOverrides = emptyMap(),
        )

        assertTrue(result.globalValues["zig_zaggify_infill"] in setOf("1", "true"))
    }

    @Test
    fun inheritedTypeStillValidatesChildExpression() {
        val parent = """
            {"settings":{"infill":{"type":"category","children":{
              "zig_zaggify_infill":{"type":"bool","default_value":false}
            }}}}
        """.trimIndent()
        val child = """
            {"inherits":"parent","settings":{"infill":{"type":"category","children":{
              "zig_zaggify_infill":{"value":"=2"}
            }}}}
        """.trimIndent()
        val extruder = """{"settings":{}}"""

        val error = runCatching {
            CuraDefinitionResolver.resolve(
                definitionFiles = mapOf(
                    "parent.def.json" to parent,
                    "machine.def.json" to child,
                    "extruder.def.json" to extruder,
                ),
                machineDefinitionFileName = "machine.def.json",
                extruderDefinitionFileName = "extruder.def.json",
                globalOverrides = emptyMap(),
                extruderOverrides = emptyMap(),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("declared=bool"))
    }

    @Test
    fun enumValidationAcceptsDeclaredKeysAndMappedValues() {
        val machine = """
            {"settings":{"machine":{"type":"category","children":{
              "machine_gcode_flavor":{
                "type":"enum",
                "default_value":"Marlin",
                "options":{
                  "RepRap (Marlin/Sprinter)":"Marlin",
                  "RepRap (RepRap)":"RepRap"
                }
              },
              "flooring_pattern":{
                "type":"enum",
                "default_value":"lines",
                "options":{
                  "lines":"Lines",
                  "concentric":"Concentric"
                }
              }
            }}}}
        """.trimIndent()

        val result = CuraDefinitionResolver.resolve(
            definitionFiles = mapOf(
                "machine.def.json" to machine,
                "extruder.def.json" to """{"settings":{}}""",
            ),
            machineDefinitionFileName = "machine.def.json",
            extruderDefinitionFileName = "extruder.def.json",
            globalOverrides = emptyMap(),
            extruderOverrides = emptyMap(),
        )

        assertEquals("Marlin", result.globalValues["machine_gcode_flavor"])
        assertEquals("lines", result.globalValues["flooring_pattern"])
    }

    @Test
    fun metadataOnlyChildrenPreserveInheritedDefaultsAndExpressions() {
        val parent = """
            {"settings":{"quality":{"type":"category","children":{
              "layer_height":{"type":"float","default_value":0.2},
              "layer_height_0":{"type":"float","default_value":0.28},
              "top_bottom_thickness":{
                "type":"float",
                "default_value":0.8,
                "value":"=layer_height_0+layer_height*3"
              }
            }}}}
        """.trimIndent()
        val child = """
            {"inherits":"parent","settings":{"quality":{"type":"category","children":{
              "layer_height_0":{"type":"float"},
              "top_bottom_thickness":{"type":"float"}
            }}}}
        """.trimIndent()

        val result = CuraDefinitionResolver.resolve(
            definitionFiles = mapOf(
                "parent.def.json" to parent,
                "machine.def.json" to child,
                "extruder.def.json" to """{"settings":{}}""",
            ),
            machineDefinitionFileName = "machine.def.json",
            extruderDefinitionFileName = "extruder.def.json",
            globalOverrides = emptyMap(),
            extruderOverrides = emptyMap(),
        )

        assertEquals(0.28, result.globalValues.getValue("layer_height_0").toDouble(), 1e-7)
        assertEquals(0.88, result.globalValues.getValue("top_bottom_thickness").toDouble(), 1e-7)
    }

    private fun definitions(expression: String): Map<String, String> = mapOf(
        "machine.def.json" to """
            {"settings":{"infill":{"type":"category","children":{
              "zig_zaggify_infill":{"type":"bool","default_value":false,"value":"$expression"}
            }}}}
        """.trimIndent(),
        "extruder.def.json" to """{"settings":{}}""",
    )
}
