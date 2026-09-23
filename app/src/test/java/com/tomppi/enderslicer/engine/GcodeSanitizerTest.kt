package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GcodeSanitizerTest {
    @Test
    fun rejectsNozzleShutdownBeforeFinalLayer() {
        val file = temporaryGcode(
            """
            ;TIME:6666
            ;Filament used: 0m
            ;MINX:2.14748e+06
            ;MINY:2.14748e+06
            ;MINZ:2.14748e+06
            ;MAXX:-2.14748e+06
            ;MAXY:-2.14748e+06
            ;MAXZ:-2.14748e+06
            ;LAYER_COUNT:3
            M104 S210
            ;LAYER:0
            ;MESH:model.stl
            G1 X10 Y20 Z0.2 E1
            M104 S0
            ;LAYER:1
            G1 X11 Y21 Z0.4 E2
            ;LAYER:2
            M104 S0
            ;TIME_ELAPSED:12.4
            """.trimIndent(),
        )

        val error = runCatching { GcodeSanitizer.validateAndRepair(file) }.exceptionOrNull()
        assertTrue(error is GcodeSanitizer.UnsafeGcodeException)
        assertTrue(error?.message.orEmpty().contains("layer 1"))
    }

    @Test
    fun rejectsNonzeroSubminimumTargetDuringExtrusion() {
        val file = temporaryGcode(
            """
            ;TIME:2
            ;Filament used: 0m
            ;LAYER_COUNT:2
            M82
            M104 S210
            ;LAYER:0
            ;MESH:model.stl
            G1 X10 Y20 Z0.2 E1
            ;LAYER:1
            M104 S137.4
            G1 X11 Y21 Z0.4 E2
            ;TIME_ELAPSED:2
            """.trimIndent(),
        )

        val error = runCatching { GcodeSanitizer.validateAndRepair(file) }.exceptionOrNull()
        assertTrue(error is GcodeSanitizer.UnsafeGcodeException)
        assertTrue(error?.message.orEmpty().contains("137.4"))
        assertTrue(error?.message.orEmpty().contains("layer 1"))
    }

    @Test
    fun rejectsLowercaseTabSeparatedUnsafeExtrusion() {
        val file = temporaryGcode(
            listOf(
                ";FLAVOR:Marlin",
                ";LAYER_COUNT:1",
                "m82",
                "m104\ts137.4",
                ";LAYER:0",
                ";MESH:model.stl",
                "g1\tx10\ty20\tz0.2\te1",
                ";TIME_ELAPSED:1",
            ).joinToString("\n"),
        )

        val error = runCatching { GcodeSanitizer.validateAndRepair(file) }.exceptionOrNull()
        assertTrue(error is GcodeSanitizer.UnsafeGcodeException)
        assertTrue(error?.message.orEmpty().contains("137.4"))
    }

    @Test
    fun repairsBoundsFromCompleteModelSegmentsAndFilamentFromWholePrint() {
        val file = temporaryGcode(
            """
            ;FLAVOR:Marlin
            ;TIME:6666
            ;Filament used: 0m
            ;MINX:2.14748e+06
            ;MINY:2.14748e+06
            ;MINZ:2.14748e+06
            ;MAXX:-2.14748e+06
            ;MAXY:-2.14748e+06
            ;MAXZ:-2.14748e+06
            M82
            M104 S210
            G92 E0
            G1 X1 Y1 E30
            G92 E0
            ;LAYER_COUNT:2
            ;LAYER:0
            ;TYPE:SKIRT
            G1 X5 Y5 Z0.2 E0.5
            ;TYPE:SUPPORT
            G1 X6 Y6 Z0.2 E1.0
            ;MESH:model.stl
            G0 X80 Y90 Z0.2
            G1 X100 Y110 Z0.2 E2.0
            ;TYPE:SUPPORT-INTERFACE
            ;MESH:NONMESH
            G1 X7 Y7 Z0.4 E2.5
            ;MESH:model.stl
            G1 X120 Y130 Z0.4 E3.5
            ;TIME_ELAPSED:42.2
            ;LAYER:1
            M104 S0
            G1 X0 Y20 Z35
            """.trimIndent(),
        )

        val summary = GcodeSanitizer.validateAndRepair(file)
        val output = file.readText()

        assertEquals(2, summary.layerCount)
        assertEquals(43, summary.estimatedSeconds)
        assertEquals(2.0, summary.filamentMillimeters, 0.0001)
        assertEquals(3.5, summary.totalFilamentMillimeters, 0.0001)
        assertEquals(7.0, summary.minX!!, 0.0)
        assertEquals(7.0, summary.minY!!, 0.0)
        assertEquals(0.2, summary.minZ!!, 0.0)
        assertEquals(120.0, summary.maxX!!, 0.0)
        assertEquals(130.0, summary.maxY!!, 0.0)
        assertEquals(0.4, summary.maxZ!!, 0.0)
        assertTrue(output.startsWith(";FLAVOR:Marlin\r\n;ENDERSLICER_VERSION:${BuildConfig.VERSION_NAME}\r\n"))
        assertTrue(output.contains(";ENDERSLICER_COORDINATE_TRANSPORT:original-stl-full-affine-pre-round"))
        assertTrue(output.contains(";ENDERSLICER_SETTINGS_TRANSPORT:fallback-command"))
        assertTrue(output.contains(";TIME:43"))
        assertTrue(output.contains(";Filament used: 0.0035m"))
        assertTrue(output.contains(";MINX:7"))
        assertTrue(output.contains(";MAXY:130"))
        assertTrue(output.contains(";MAXZ:0.4"))

        GcodeSanitizer.validateAndRepair(file)
        assertEquals(1, file.readText().lineSequence().count { it.startsWith(";ENDERSLICER_VERSION:") })
        assertEquals(1, file.readText().lineSequence().count { it.startsWith(";ENDERSLICER_SETTINGS_TRANSPORT:") })
    }

    @Test
    fun detectsResolvedJsonTransportFromSidecar() {
        val file = temporaryGcode(
            """
            ;FLAVOR:Marlin
            ;TIME:1
            ;Filament used: 0m
            ;LAYER_COUNT:1
            M104 S210
            ;LAYER:0
            ;MESH:model.stl
            G1 X1 Y2 Z0.2 E1
            ;TIME_ELAPSED:1
            """.trimIndent(),
        )
        File(file.parentFile, "resolved-settings.json").writeText("{}")

        GcodeSanitizer.validateAndRepair(file)

        assertTrue(file.readText().contains(";ENDERSLICER_SETTINGS_TRANSPORT:resolved-json"))
    }

    @Test
    fun rewritesEveryLineWithPrinterCompatibleCrLfEndings() {
        val file = temporaryGcode(
            """
            ;TIME:1
            ;Filament used: 0m
            ;LAYER_COUNT:1
            M104 S210
            ;LAYER:0
            ;MESH:model.stl
            G1 X1 Y2 Z0.2 E1
            ;TIME_ELAPSED:1
            """.trimIndent(),
        )

        GcodeSanitizer.validateAndRepair(file)
        val output = file.readBytes()
        var lineFeedCount = 0
        var loneLineFeedCount = 0
        output.forEachIndexed { index, byte ->
            if (byte == '\n'.code.toByte()) {
                lineFeedCount++
                if (index == 0 || output[index - 1] != '\r'.code.toByte()) loneLineFeedCount++
            }
        }

        assertTrue(lineFeedCount > 0)
        assertEquals(0, loneLineFeedCount)
        assertTrue(output.size >= 2)
        assertEquals('\r'.code.toByte(), output[output.lastIndex - 1])
        assertEquals('\n'.code.toByte(), output[output.lastIndex])
    }

    private fun temporaryGcode(content: String): File {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-test").toFile()
        return File(directory, "output.gcode").apply { writeText(content) }
    }
}
