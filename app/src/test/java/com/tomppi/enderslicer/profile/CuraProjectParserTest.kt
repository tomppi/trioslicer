package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CuraProjectParserTest {
    @Test
    fun userLayerOverridesQualityLayer() {
        val archive = zipOf(
            "Cura/version.ini" to "[versions]\ncura_version = 5.14.0-alpha.0\n",
            "Cura/Test.global.cfg" to """
                [general]
                name = Test printer
                [metadata]
                setting_version = 25
                [containers]
                0 = Test_user
                1 = Test_quality
            """.trimIndent(),
            "Cura/Test_user.inst.cfg" to "[general]\nname = Test_user\n[values]\nsupport_enable = True\n",
            "Cura/Test_quality.inst.cfg" to "[general]\nname = Test_quality\n[values]\nsupport_enable = False\n",
            "Cura/E0.extruder.cfg" to """
                [general]
                name = E0
                [containers]
                0 = E0_user
                1 = E0_quality
            """.trimIndent(),
            "Cura/E0_user.inst.cfg" to "[general]\nname = E0_user\n[values]\nspeed_print = 200\n",
            "Cura/E0_quality.inst.cfg" to "[general]\nname = E0_quality\n[values]\nspeed_print = 120\n",
        )

        val result = CuraProjectParser.parse(
            ByteArrayInputStream(archive),
            "test.3mf",
            SlicerSettings(),
        )

        assertTrue(result.mappedSettings.supportsEnabled)
        assertEquals(200.0, result.mappedSettings.printSpeedMmPerSecond, 0.0)
        assertEquals("5.14.0-alpha.0", result.curaVersion)
    }

    @Test
    fun importsProjectDefinitionsMaterialAndConcreteProfileValues() {
        val archive = zipOf(
            "Cura/version.ini" to "[versions]\ncura_version = 5.14.0-alpha.0\n",
            "Cura/Printer.global.cfg" to """
                [general]
                name = Modified Ender 3 V2
                [metadata]
                setting_version = 25
                [containers]
                0 = Printer_user
                1 = global_quality
                7 = creality_ender3
            """.trimIndent(),
            "Cura/Printer_user.inst.cfg" to "[values]\nsupport_enable = True\nsupport_type = everywhere\n",
            "Cura/global_quality.inst.cfg" to """
                [values]
                layer_height = 0.2
                layer_height_0 = 0.28
                material_bed_temperature = 60
                top_bottom_thickness = =layer_height_0+layer_height*3
            """.trimIndent(),
            "Cura/E0.extruder.cfg" to """
                [general]
                name = E0
                [containers]
                0 = E0_user
                1 = extruder_quality
                4 = custom_material_creality_ender3
                7 = creality_base_extruder_0
            """.trimIndent(),
            "Cura/E0_user.inst.cfg" to "[values]\nspeed_print = 200\n",
            "Cura/extruder_quality.inst.cfg" to """
                [values]
                speed_print = 120
                material_print_temperature = 210
                material_print_temperature_layer_0 = 235
                cool_fan_speed = 100
                slicing_tolerance = exclusive
                coasting_enable = True
                coasting_volume = 0.256
                line_width = =machine_nozzle_size
                wall_thickness = =line_width*2
                bottom_layers = =math.ceil(bottom_thickness / layer_height)
            """.trimIndent(),
            "Cura/creality_ender3.def.json" to "{}",
            "Cura/creality_base_extruder_0.def.json" to "{}",
            "Cura/custom_material.xml.fdm_material" to materialXml(),
        )

        val result = CuraProjectParser.parse(
            ByteArrayInputStream(archive),
            "reference.3mf",
            SlicerSettings(),
        )
        val profile = requireNotNull(result.engineProfile)

        assertTrue(profile.usesProjectDefinitions)
        assertEquals("creality_ender3.def.json", profile.machineDefinitionFileName)
        assertEquals("creality_base_extruder_0.def.json", profile.extruderDefinitionFileName)
        assertEquals("180", profile.extruderValues["material_standby_temperature"])
        assertEquals("210", profile.extruderValues["material_print_temperature"])
        assertEquals("235", profile.extruderValues["material_print_temperature_layer_0"])
        assertEquals("50", profile.extruderValues["material_bed_temperature"])
        assertEquals("60", profile.globalValues["material_bed_temperature"])
        assertEquals("exclusive", profile.extruderValues["slicing_tolerance"])
        assertEquals("0.88", profile.globalValues["top_bottom_thickness"])
        assertEquals("200", profile.extruderValues["speed_print"])
        assertFalse(profile.extruderValues.containsKey("bottom_layers"))
        assertTrue(profile.unresolvedExpressions.contains("extruder.bottom_layers"))
        assertTrue(result.mappedSettings.supportsEnabled)
        assertEquals("everywhere", result.mappedSettings.supportPlacement)
        assertEquals(60, result.mappedSettings.bedTemperatureC)
        assertEquals(210, result.mappedSettings.nozzleTemperatureC)
        assertEquals(200.0, result.mappedSettings.printSpeedMmPerSecond, 0.0)
    }

    private fun materialXml(): String = """
        <?xml version="1.0" encoding="utf-8"?>
        <fdmmaterial xmlns="http://www.ultimaker.com/material" version="1.3">
          <metadata>
            <name><brand>Custom</brand><material>PLA</material></name>
            <GUID>test-guid</GUID>
          </metadata>
          <properties><density>1.24</density><diameter>1.75</diameter></properties>
          <settings>
            <setting key="print temperature">200</setting>
            <setting key="heated bed temperature">50</setting>
            <setting key="standby temperature">175</setting>
            <machine>
              <machine_identifier manufacturer="Creality3D" product="creality_ender3" />
              <setting key="standby temperature">180</setting>
              <setting key="print cooling">100</setting>
              <setting key="retraction speed">45</setting>
            </machine>
          </settings>
        </fdmmaterial>
    """.trimIndent()

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
