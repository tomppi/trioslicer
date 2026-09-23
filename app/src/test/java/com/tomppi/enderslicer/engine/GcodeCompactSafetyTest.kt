package com.tomppi.enderslicer.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GcodeCompactSafetyTest {
    @Test
    fun sanitizerRejectsCompactSubminimumTemperatureBeforeExtrusion() {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-compact-safety").toFile()
        val file = File(directory, "compact.gcode").apply {
            writeText(
                listOf(
                    ";LAYER_COUNT:1",
                    "M82",
                    "M104S137.4",
                    ";LAYER:0",
                    ";MESH:model.stl",
                    "G1X10Y20Z0.2E1",
                    ";TIME_ELAPSED:1",
                ).joinToString("\n"),
            )
        }

        val error = runCatching { GcodeSanitizer.validateAndRepair(file) }.exceptionOrNull()
        assertTrue(error is GcodeSanitizer.UnsafeGcodeException)
        assertTrue(error?.message.orEmpty().contains("137.4"))
    }
}
