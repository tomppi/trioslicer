package com.tomppi.enderslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * What a preview budget must not do: leave the path full of holes.
 *
 * Past the parser's move budget a move used to be skipped, and a skipped move is a gap in the
 * middle of a wall. A slice longer than the budget therefore rendered as beads with holes between
 * them - plates with air where the plastic should be. The budget is a memory limit, and the way to
 * spend it is fewer, longer moves: a dropped move is merged into the next kept one, so the drawn
 * path covers exactly the distance the file describes.
 */
class NozzlePathDecimationTest {
    /** A straight wall of [moves] one-millimetre extruding moves. */
    private fun wall(moves: Int): File {
        val file = File.createTempFile("nozzle-path-wall", ".gcode")
        val text = StringBuilder()
        text.append(";FLAVOR:Marlin\n;Layer height: 0.2\nM82\nG92 E0\nG28\n")
        var z = 0.0
        var e = 0.0
        for (index in 0 until moves) {
            if (index % 100 == 0) {
                z += 0.2
                text.append("G0 X0 Y0 Z").append(String.format(Locale.US, "%.3f", z)).append("\n")
            }
            e += 0.0333
            text.append("G1 X").append(index + 1).append(" Y0 E")
                .append(String.format(Locale.US, "%.4f", e)).append("\n")
        }
        file.writeText(text.toString())
        return file
    }

    private fun drawnLength(path: GcodeNozzlePath): Double {
        var total = 0.0
        for (index in 0 until path.moveCount) {
            val offset = index * GcodeNozzlePath.VALUES_PER_MOVE
            val dx = path.moves[offset + GcodeNozzlePath.X2] - path.moves[offset + GcodeNozzlePath.X1]
            val dy = path.moves[offset + GcodeNozzlePath.Y2] - path.moves[offset + GcodeNozzlePath.Y1]
            total += kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
        }
        return total
    }

    @Test
    fun aTightBudgetStillDrawsTheWholeWall() {
        val path = GcodeNozzlePathParser.parse(wall(400), maxMoves = 100)
        assertTrue("the budget was not applied: " + path.moveCount, path.moveCount <= 100)
        assertEquals("the drawn path lost distance", 400.0, drawnLength(path), 0.5)
    }

    @Test
    fun theDrawnMovesCarryTheFilamentOfTheOnesTheyStandFor() {
        val path = GcodeNozzlePathParser.parse(wall(400), maxMoves = 100)
        var filament = 0.0
        for (index in 0 until path.moveCount) {
            val offset = index * GcodeNozzlePath.VALUES_PER_MOVE
            filament += path.moves[offset + GcodeNozzlePath.DELTA_E]
        }
        assertEquals(13.32, filament, 0.25)
    }
}
