package com.tomppi.enderslicer.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Validates the new marker-driven parser against the real device gcode. */
class PrusaNozzlePathParserTest {
    private val file = File("C:/Users/FREDRIK/Documents/PrintShare/print_20260903_232908_247.gcode")

    @Test
    fun realDeviceGcode() {
        assumeTrue("device gcode not present", file.isFile)
        val path = PrusaNozzlePathParser.parse(file)
        println("PP moves=" + path.moveCount +
            " ext=" + path.extrusionMoveCount +
            " travel=" + path.travelMoveCount +
            " layers=" + path.layerCount +
            " bounds=" + "%.1f".format(path.minX) + ".." + "%.1f".format(path.maxX) +
            "," + "%.1f".format(path.minY) + ".." + "%.1f".format(path.maxY) +
            "," + "%.1f".format(path.minZ) + ".." + "%.1f".format(path.maxZ))
        // Same layer count as the LAYER_CHANGE markers (599).
        check(path.layerCount == 599) { "expected 599 layers, got " + path.layerCount }
        check(path.moveCount > 200_000) { "expected >200k moves" }
        // Marker-driven widths: parse a few moves and confirm width is 0.42-0.46.
        val moves = path.moves
        var widthsOk = 0
        var samples = 0
        for (m in 0 until path.moveCount) {
            val o = m * PrusaNozzlePath.VALUES_PER_MOVE
            if (moves[o + PrusaNozzlePath.KIND] == PrusaNozzlePath.Kind.EXTRUSION.code) {
                val w = moves[o + PrusaNozzlePath.WIDTH]
                if (w > 0.30f && w < 0.70f) widthsOk++
                samples++
            }
        }
        println("PP widths ok=" + widthsOk + "/" + samples)
        check(widthsOk > samples * 0.95f) { "widths outside marker range: " + widthsOk + "/" + samples }
    }

    @Test
    fun reportsMonotonicProgressFromZeroToOne() {
        val commands = buildString {
            appendLine(";LAYER_CHANGE")
            for (index in 1..150_000) {
                appendLine("G1 X$index Y${index * 2} Z0.2 F1200")
            }
        }
        val dir = kotlin.io.path.createTempDirectory("prusa-progress-test").toFile()
        val gcode = File(dir, "t.gcode").apply { writeText(commands) }
        val reports = mutableListOf<Float>()
        PrusaNozzlePathParser.parse(gcode) { reports += it }

        assertTrue("expected progress reports", reports.isNotEmpty())
        assertEquals(1f, reports.last())
        for (index in 1 until reports.size) {
            assertTrue(
                "progress must be monotonic (" + reports[index - 1] + " then " + reports[index] + ")",
                reports[index] >= reports[index - 1],
            )
        }
    }

    @Test
    fun samplesLongPrintsToTheMoveCapKeepingTheFirstAndLastMove() {
        val commands = buildString {
            appendLine(";LAYER_CHANGE")
            appendLine("G90")
            appendLine("M83")
            for (index in 1..100) {
                appendLine("G1 X${index} Y${index * 2} Z0.2 E0.1 F1200")
            }
        }
        val dir = kotlin.io.path.createTempDirectory("prusa-sampling-test").toFile()
        val gcode = File(dir, "t.gcode").apply { writeText(commands) }
        try {
            val path = PrusaNozzlePathParser.parse(gcode, maxMoves = 10)

            // The Cura preview samples to the cap; this one used to keep every
            // move while only flagging truncated.
            assertEquals(100, path.sourceMoveCount)
            assertEquals(10, path.moveCount)
            assertTrue(path.truncated)
            assertEquals(0, path.sourceMoveIndices.first())
            assertEquals(99, path.sourceMoveIndices.last())
            var previousSource = -1
            var previousX = Float.NEGATIVE_INFINITY
            for (index in 0 until path.moveCount) {
                val offset = index * PrusaNozzlePath.VALUES_PER_MOVE
                val x = path.moves[offset + PrusaNozzlePath.X2]
                val sourceIndex = path.sourceMoveIndices[index]
                assertTrue("Sampled moves must remain in print order", x > previousX)
                assertTrue("Source indices must remain in print order", sourceIndex > previousSource)
                previousX = x
                previousSource = sourceIndex
            }
            assertEquals(100f, previousX)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * The move count that sizes the sample must be the count of moves the preview
     * actually emits. The counting pass stopped at the PrusaSlicer spelling of the
     * end block while the emitter also stopped at the Bambu one, so a Bambu-envelope
     * print was sampled against a larger total - it kept fewer moves than the cap
     * allows and lost the final move, which is always supposed to be retained.
     */
    @Test
    fun theSampleIsSizedFromTheMovesThePreviewEmits() {
        val commands = buildString {
            appendLine("; CHANGE_LAYER")
            appendLine("; Z_HEIGHT: 0.2")
            appendLine("G90")
            appendLine("M83")
            for (index in 1..50) {
                appendLine("G1 X${index} Y${index * 2} Z0.2 E0.1 F1200")
            }
            appendLine("; FEATURE: Custom")
            for (index in 51..80) {
                appendLine("G1 X${index} Y${index * 2} Z50 E0.1 F1200")
            }
        }
        val dir = kotlin.io.path.createTempDirectory("prusa-bambu-region-test").toFile()
        val gcode = File(dir, "t.gcode").apply { writeText(commands) }
        try {
            val path = PrusaNozzlePathParser.parse(gcode, maxMoves = 10)

            assertEquals("the end block is not part of the print", 50, path.sourceMoveCount)
            assertEquals(10, path.moveCount)
            assertEquals(49, path.sourceMoveIndices.last())
            val lastOffset = (path.moveCount - 1) * PrusaNozzlePath.VALUES_PER_MOVE
            assertEquals(50f, path.moves[lastOffset + PrusaNozzlePath.X2], 1e-3f)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun markerWidthsTrackPrusa() {
        // Small synthetic gcode: verify width/height follow ;WIDTH:/;HEIGHT: markers.
        val dir = kotlin.io.path.createTempDirectory("prusa-marker-test").toFile()
        val gcode = File(dir, "t.gcode").apply {
            writeText(
                ";LAYER_CHANGE\n" +
                    ";WIDTH:0.42\n" +
                    ";HEIGHT:0.08\n" +
                    "G1 X0 Y0 Z0.08 F1200\n" +
                    "G1 X5 Y5 E0.5\n" +
                    ";WIDTH:0.50\n" +
                    "G1 X10 Y5 E0.6\n" +
                    ";LAYER_CHANGE\n" +
                    ";WIDTH:0.44\n" +
                    "G1 X10 Y10 Z0.16 E0.7\n",
            )
        }
        val path = PrusaNozzlePathParser.parse(gcode)
        val moves = path.moves
        check(path.layerCount == 2)
        // move[0] extrusion: width 0.42, height 0.08
        val m0 = 0
        check(moves[m0 * PrusaNozzlePath.VALUES_PER_MOVE + PrusaNozzlePath.WIDTH] == 0.42f)
        check(moves[m0 * PrusaNozzlePath.VALUES_PER_MOVE + PrusaNozzlePath.HEIGHT] == 0.08f)
        // Extrusion beyond the first window uses the following ;WIDTH: marker.
        val last = path.moveCount - 1
        check(moves[last * PrusaNozzlePath.VALUES_PER_MOVE + PrusaNozzlePath.WIDTH] == 0.44f)
    }
}