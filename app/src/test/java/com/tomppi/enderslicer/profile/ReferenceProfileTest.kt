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

class ReferenceProfileTest {
    @Test
    fun importsReferenceCuraprofile() {
        val archive = zipOf(
            "custom_quality.inst.cfg" to """
                [general]
                version = 4
                name = Ender 3 V2 reference
                definition = creality_base

                [metadata]
                type = quality_changes
                quality_type = low
                setting_version = 25

                [values]
                speed_print = 120
                support_enable = False
                retraction_amount = 1.5
            """.trimIndent(),
        )

        val result = CuraProfileParser.parse(
            ByteArrayInputStream(archive),
            "ender3v2_reference.curaprofile",
            SlicerSettings(),
        )

        assertEquals(120.0, result.mappedSettings.printSpeedMmPerSecond, 0.0)
        assertFalse(result.mappedSettings.supportsEnabled)
        assertEquals(1.5, result.mappedSettings.retractionDistanceMm, 0.0)
    }

    @Test
    fun projectUserOverridesWinAndUblGcodeIsPreserved() {
        val archive = zipOf(
            "Cura/version.ini" to "[versions]\ncura_version = 5.14.0-alpha.0\n",
            "Cura/Test.global.cfg" to """
                [general]
                name = Modified Ender 3 V2
                [containers]
                0 = Test_user
                1 = Test_quality
            """.trimIndent(),
            "Cura/Test_user.inst.cfg" to """
                [general]
                name = Test_user
                [values]
                support_enable = True
                support_type = everywhere
                machine_start_gcode = G28
                ${'\t'}G29 L0
                ${'\t'}G29 A
            """.trimIndent(),
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
            "reference.3mf",
            SlicerSettings(),
        )

        assertEquals("5.14.0-alpha.0", result.curaVersion)
        assertEquals(200.0, result.mappedSettings.printSpeedMmPerSecond, 0.0)
        assertTrue(result.mappedSettings.supportsEnabled)
        assertEquals("everywhere", result.mappedSettings.supportPlacement)
        assertTrue(result.startGcode.orEmpty().contains("G29 L0"))
        assertTrue(result.startGcode.orEmpty().contains("G29 A"))
    }

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
