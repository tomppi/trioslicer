package com.tomppi.enderslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GcodeModalStateTest {
    @Test
    fun coldRelativeExtrusionAfterM83IsRejected() {
        val file = temporaryGcode(
            """
            ;FLAVOR:Marlin
            ;LAYER_COUNT:1
            M83
            G92 X0 Y0 Z0 E100
            M104 S0
            ;LAYER:0
            ;MESH:model.stl
            G1 X1 E0.5 F1200
            ;TIME_ELAPSED:1
            """.trimIndent(),
        )

        val error = runCatching { GcodeSanitizer.validateAndRepair(file) }.exceptionOrNull()

        assertTrue(error is GcodeSanitizer.UnsafeGcodeException)
        assertTrue(error?.message.orEmpty().contains("Unsafe nozzle target 0 C"))
    }

    @Test
    fun sanitizerAndPreviewTreatRelativeExtrusionAfterM83AndG91() {
        val file = temporaryGcode(
            """
            ;FLAVOR:Marlin
            ;LAYER_COUNT:1
            M83
            G92 X0 Y0 Z0 E100
            M104 S210
            ;LAYER:0
            ;TYPE:WALL-OUTER
            ;MESH:model.stl
            G91
            G1 X5 Z0.2 E0.5 F1200
            G1 X5 E0.5 F1200
            ;TIME_ELAPSED:1
            M104 S0
            """.trimIndent(),
        )

        val summary = GcodeSanitizer.validateAndRepair(file)
        val preview = GcodeLayerPreviewParser.parse(file)

        assertEquals(1.0, summary.totalFilamentMillimeters, 0.0001)
        assertEquals(2, preview.totalSegmentCount)
        assertEquals(10f, preview.maxX, 0f)
    }

    @Test
    fun sanitizerAndPreviewKeepERelativeAfterG90FollowingM83() {
        val file = temporaryGcode(
            """
            ;FLAVOR:Marlin
            ;LAYER_COUNT:1
            M83
            G92 X0 Y0 Z0 E100
            M104 S210
            ;LAYER:0
            ;TYPE:WALL-OUTER
            ;MESH:model.stl
            G90
            G1 X5 Z0.2 E0.5 F1200
            G1 X10 E101 F1200
            ;TIME_ELAPSED:1
            M104 S0
            """.trimIndent(),
        )

        val summary = GcodeSanitizer.validateAndRepair(file)
        val preview = GcodeLayerPreviewParser.parse(file)
        val segment = preview.layers.single().segments

        // Marlin: M83 keeps E relative, so G90 only affects XYZ. Both moves
        // extrude (0.5 + 101) and the preview records each as a segment.
        assertEquals(101.5, summary.totalFilamentMillimeters, 0.0001)
        assertEquals(2, preview.totalSegmentCount)
        assertEquals(5f, segment[2], 0f)
        assertEquals(10f, segment[8], 0f)
    }

    private fun temporaryGcode(content: String): File {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-modal-state").toFile()
        return File(directory, "output.gcode").apply { writeText(content) }
    }
}
