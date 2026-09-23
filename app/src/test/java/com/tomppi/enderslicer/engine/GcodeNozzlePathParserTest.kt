package com.tomppi.enderslicer.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GcodeNozzlePathParserTest {
    @Test
    fun parsesOrderedTravelExtrusionAndRelativeZMoves() {
        val file = temporaryGcode(
            """
            G90
            M82
            G92 X0 Y0 Z0 E0
            G0 X10 Y0 Z0.2 F6000
            G1 X10 Y10 E1 F1200
            G91
            M83
            G0 X-2 Y0 Z0.1 F3000
            G1 X0 Y2 E0.3 F900
            """.trimIndent(),
        )

        val path = GcodeNozzlePathParser.parse(file)

        assertEquals(4, path.moveCount)
        assertEquals(2, path.travelMoveCount)
        assertEquals(2, path.extrusionMoveCount)
        assertEquals(listOf(0, 1, 2, 3), path.sourceMoveIndices.toList())
        assertEquals(0f, path.minX)
        assertEquals(10f, path.maxX)
        assertEquals(0f, path.minZ)
        assertEquals(0.3f, path.maxZ, 0.0001f)
        assertFalse(path.truncated)

        val last = 3 * GcodeNozzlePath.VALUES_PER_MOVE
        assertEquals(8f, path.moves[last + GcodeNozzlePath.X2])
        assertEquals(12f, path.moves[last + GcodeNozzlePath.Y2])
        assertEquals(0.3f, path.moves[last + GcodeNozzlePath.Z2], 0.0001f)
        assertEquals(GcodeNozzlePath.Kind.EXTRUSION.code, path.moves[last + GcodeNozzlePath.KIND])
    }

    @Test
    fun samplesAcrossTheWholePrintAndRetainsFirstAndLastSourceIndices() {
        val commands = buildString {
            appendLine("G90")
            appendLine("M83")
            for (index in 1..100) {
                appendLine("G1 X$index Y${index * 2} Z${index / 10.0} E0.1 F1200")
            }
        }
        val path = GcodeNozzlePathParser.parse(temporaryGcode(commands), maxMoves = 10)

        assertEquals(100, path.sourceMoveCount)
        assertEquals(10, path.moveCount)
        assertTrue(path.truncated)
        assertEquals(0, path.sourceMoveIndices.first())
        assertEquals(99, path.sourceMoveIndices.last())
        var previousSource = -1
        var previousX = Float.NEGATIVE_INFINITY
        for (index in 0 until path.moveCount) {
            val offset = index * GcodeNozzlePath.VALUES_PER_MOVE
            val x = path.moves[offset + GcodeNozzlePath.X2]
            val sourceIndex = path.sourceMoveIndices[index]
            assertTrue("Sampled moves must remain in print order", x > previousX)
            assertTrue("Source indices must remain in print order", sourceIndex > previousSource)
            previousX = x
            previousSource = sourceIndex
        }
        assertEquals(100f, previousX)
    }

    @Test
    fun rejectsArcsInsteadOfDrawingAFalseRoute() {
        val failure = runCatching {
            GcodeNozzlePathParser.parse(
                temporaryGcode(
                    """
                    G90
                    G2 X10 Y10 I5 J0 F1200
                    G1 X20 Y20
                    """.trimIndent(),
                ),
            )
        }.exceptionOrNull()

        assertTrue(requireNotNull(failure).message.orEmpty().contains("G2/G3"))
    }

    @Test
    fun reportsMonotonicProgressFromZeroToOne() {
        val commands = buildString {
            appendLine("G90")
            appendLine("M83")
            for (index in 1..150_000) {
                appendLine("G1 X$index Y${index * 2} Z${index / 100.0} E0.1 F1200")
            }
        }
        val file = temporaryGcode(commands)
        val reports = mutableListOf<Float>()
        GcodeNozzlePathParser.parse(file, GcodeDialect.CURA) { reports += it }

        assertTrue("expected progress reports", reports.isNotEmpty())
        assertEquals(1f, reports.last())
        for (index in 1 until reports.size) {
            assertTrue(
                "progress must be monotonic (" + reports[index - 1] + " then " + reports[index] + ")",
                reports[index] >= reports[index - 1],
            )
        }
    }

    private fun temporaryGcode(contents: String): File =
        File.createTempFile("nozzle-path-test", ".gcode").apply {
            writeText(contents)
            deleteOnExit()
        }
}
