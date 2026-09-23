package com.tomppi.enderslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GcodeCommandFormattingTest {
    @Test
    fun previewAcceptsLowercaseTabbedCommands() {
        val file = File(kotlin.io.path.createTempDirectory("enderslicer-format").toFile(), "lower.gcode").apply {
            writeText(
                listOf(
                    ";LAYER:0",
                    ";TYPE:WALL-OUTER",
                    "m82",
                    "g92\te0",
                    "g1\tx0\ty0\tz0.2\tf1200",
                    "g1\tx10\ty0\te1",
                ).joinToString("\n"),
            )
        }

        val preview = GcodeLayerPreviewParser.parse(file)
        assertEquals(1, preview.layers.size)
        assertEquals(1, preview.layers.single().segmentCount)
        assertEquals(20f, preview.maxSpeedMmPerSecond, 0f)
    }

    @Test
    fun parserAcceptsRepRapLineNumberAndChecksumSuffix() {
        val parsed = requireNotNull(GcodeCommand.parse("N42 g1\tX10\te1.5*77"))
        assertEquals("G1", parsed.opcode)
        assertEquals(10.0, parsed.value('X') ?: Double.NaN, 0.0)
        assertEquals(1.5, parsed.value('E') ?: Double.NaN, 0.0)
        assertTrue(parsed.value('Y') == null)
    }

    @Test
    fun parserAcceptsCompactOpcodeAndParameters() {
        val parsed = requireNotNull(GcodeCommand.parse("N42G1X10.5Y-2E1.25*77"))
        assertEquals("G1", parsed.opcode)
        assertEquals(10.5, parsed.value('X') ?: Double.NaN, 0.0)
        assertEquals(-2.0, parsed.value('Y') ?: Double.NaN, 0.0)
        assertEquals(1.25, parsed.value('E') ?: Double.NaN, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun compactUnsafeCustomCommandIsBlocked() {
        GcodeLayerEventProcessor.commands(
            LayerEvent(
                id = "compact-home",
                layerNumber = 0,
                zMm = 0.2f,
                type = LayerEventType.CUSTOM_GCODE,
                text = "G28X0",
            ),
        )
    }
}
