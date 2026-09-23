package com.tomppi.enderslicer.engine

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GcodeSanitizerTemperatureStateTest {
    @Test
    fun allowsTemporaryZeroTargetWhenNozzleIsReheatedBeforeExtrusion() {
        val file = temporaryGcode(
            """
            ;FLAVOR:Marlin
            ;TIME:0
            ;Filament used: 0m
            ;LAYER_COUNT:2
            ;LAYER:0
            M104 S0
            M109 S235
            M82
            G92 E0
            G1 X1 Y1 E1
            ;MESH:model.stl
            G1 X10 Y10 Z0.2 E2
            ;MESH:NONMESH
            ;LAYER:1
            ;MESH:model.stl
            G1 X11 Y11 Z0.4 E3
            ;MESH:NONMESH
            M104 S0
            ;TIME_ELAPSED:10
            """.trimIndent(),
        )

        val result = runCatching { GcodeSanitizer.validateAndRepair(file) }
        assertTrue(result.isSuccess)
    }

    @Test
    fun rejectsExtrusionWhileLatestExplicitTargetIsCold() {
        val file = temporaryGcode(
            """
            ;FLAVOR:Marlin
            ;TIME:0
            ;Filament used: 0m
            ;LAYER_COUNT:3
            M82
            G92 E0
            M104 S210
            ;LAYER:0
            ;MESH:model.stl
            G1 X10 Y10 Z0.2 E1
            ;MESH:NONMESH
            M104 S0
            ;LAYER:1
            ;MESH:model.stl
            G1 X11 Y11 Z0.4 E2
            """.trimIndent(),
        )

        val error = runCatching { GcodeSanitizer.validateAndRepair(file) }.exceptionOrNull()
        assertTrue(error is GcodeSanitizer.UnsafeGcodeException)
        assertTrue(error?.message.orEmpty().contains("while extruding"))
        assertTrue(error?.message.orEmpty().contains("target set at line"))
    }

    private fun temporaryGcode(content: String): File {
        return kotlin.io.path.createTempFile("enderslicer-temperature-test", ".gcode")
            .toFile()
            .apply { writeText(content) }
    }
}
