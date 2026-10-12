package com.tomppi.enderslicer.engine

import java.io.File
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * What a preview budget may fold, and what it may not.
 *
 * Past its budget the parser replaces moves with longer ones, because a ribbon move costs
 * roughly 800 bytes and the phone has to hold the whole path. The one straight move drawn for a
 * fold may only stand for moves that genuinely continue it - the same kind of move, in the same
 * direction. A fold closed at a move that turns draws a chord across the corner, and a fold
 * closed at a move that extrudes after a travel paints a bead where the printer only travelled:
 * on the reported Ghost Dog slice that put 513 beads over more than 5 mm of travel each, the
 * longest over 97 mm, which is what the slivers across the part were.
 *
 * The check is on the source moves: everything a drawn move stands for has to lie on the line
 * that was drawn, and the drawn moves have to cover every source move exactly once, in order.
 */
class NozzlePathFoldInvariantTest {

    /** How far a source move may sit from the straight line drawn for it, in millimetres. */
    private val driftMm = 0.2

    /** The reported print, when it is on this machine: -DrealGcode=<path> or REAL_GCODE. */
    private val realGcode = File(System.getProperty("realGcode") ?: System.getenv("REAL_GCODE").orEmpty())

    /** A serpentine past the budget: straight lines of extrusions with a travel between them. */
    private fun serpentine(layers: Int = 30, lines: Int = 25, steps: Int = 60): File {
        val file = File.createTempFile("nozzle-path-serpentine", ".gcode").also { it.deleteOnExit() }
        file.bufferedWriter().use { out ->
            out.append(";FLAVOR:Marlin\n;Layer height: 0.2\nM82\nG92 E0\nG28\nG0 X0 Y0 Z0.2\n")
            var e = 0L
            var y = 0
            for (layer in 0 until layers) {
                if (layer > 0) {
                    out.append("G0 Z").append(String.format(Locale.US, "%.2f", layer * 0.2 + 0.2)).append(" F600\n")
                }
                for (line in 0 until lines) {
                    y++
                    val from = if (line % 2 == 0) 0 else steps
                    val to = if (line % 2 == 0) steps else 0
                    out.append("G0 X" + from + " Y" + y + " F6000\n")
                    for (step in 1..steps) {
                        e += 100
                        out.append("G1 X" + (from + (to - from) * step / steps) + " Y" + y)
                        out.append(" E").append(String.format(Locale.US, "%.3f", e / 1000.0)).append("\n")
                    }
                }
            }
        }
        return file
    }

    private fun x(path: GcodeNozzlePath, index: Int, field: Int) =
        path.moves[index * GcodeNozzlePath.VALUES_PER_MOVE + field].toDouble()

    private fun pointDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val vx = bx - ax
        val vy = by - ay
        val denominator = vx * vx + vy * vy
        if (denominator <= 1e-12) return hypot(px - ax, py - ay)
        val t = (((px - ax) * vx + (py - ay) * vy) / denominator).coerceIn(0.0, 1.0)
        return hypot(px - (ax + vx * t), py - (ay + vy * t))
    }

    private fun directionDot(path: GcodeNozzlePath, a: Int, b: Int): Double {
        val adx = x(path, a, GcodeNozzlePath.X2) - x(path, a, GcodeNozzlePath.X1)
        val ady = x(path, a, GcodeNozzlePath.Y2) - x(path, a, GcodeNozzlePath.Y1)
        val bdx = x(path, b, GcodeNozzlePath.X2) - x(path, b, GcodeNozzlePath.X1)
        val bdy = x(path, b, GcodeNozzlePath.Y2) - x(path, b, GcodeNozzlePath.Y1)
        val al = hypot(adx, ady)
        val bl = hypot(bdx, bdy)
        if (al <= 1e-9 || bl <= 1e-9) return 1.0
        return (adx * bdx + ady * bdy) / (al * bl)
    }

    /**
     * Fails when a drawn move stands for source moves its own line does not cover, and returns
     * the worst distance it saw.
     */
    private fun assertFoldsAreHonest(folded: GcodeNozzlePath, exact: GcodeNozzlePath): Double {
        assertTrue("the test file has to be past the budget", folded.truncated)
        assertTrue("the budget has to fold something", folded.moveCount < folded.sourceMoveCount)
        assertEquals("the exact parse has to hold every source move", folded.sourceMoveCount, exact.moveCount)

        var previousSource = -1
        var worstDrift = 0.0
        for (drawn in 0 until folded.moveCount) {
            val last = folded.sourceMoveIndices[drawn]
            val first = previousSource + 1
            assertTrue("source moves have to be covered in order", last >= first)
            val ax = x(folded, drawn, GcodeNozzlePath.X1)
            val ay = x(folded, drawn, GcodeNozzlePath.Y1)
            val bx = x(folded, drawn, GcodeNozzlePath.X2)
            val by = x(folded, drawn, GcodeNozzlePath.Y2)
            val kind = folded.moves[drawn * GcodeNozzlePath.VALUES_PER_MOVE + GcodeNozzlePath.KIND]
            assertEquals("span start x", x(exact, first, GcodeNozzlePath.X1), ax, 1e-3)
            assertEquals("span start y", x(exact, first, GcodeNozzlePath.Y1), ay, 1e-3)
            assertEquals("span end x", x(exact, last, GcodeNozzlePath.X2), bx, 1e-3)
            assertEquals("span end y", x(exact, last, GcodeNozzlePath.Y2), by, 1e-3)
            for (source in first..last) {
                val sourceKind = exact.moves[source * GcodeNozzlePath.VALUES_PER_MOVE + GcodeNozzlePath.KIND]
                assertTrue(
                    "a drawn move may not stand for another kind of move (span " + first + ".." + last + ")",
                    sourceKind == kind,
                )
                // The stored moves are floats; the parser compared the doubles it read. A
                // direction exactly on the fold threshold can round either way here.
                assertTrue(
                    "a folded move has to continue the first one (span " + first + ".." + last + ")",
                    directionDot(exact, first, source) >= 0.995 - 1e-4,
                )
                val drift = max(
                    pointDistance(x(exact, source, GcodeNozzlePath.X1), x(exact, source, GcodeNozzlePath.Y1), ax, ay, bx, by),
                    pointDistance(x(exact, source, GcodeNozzlePath.X2), x(exact, source, GcodeNozzlePath.Y2), ax, ay, bx, by),
                )
                worstDrift = max(worstDrift, drift)
                assertTrue(
                    "a source move sits " + String.format(Locale.US, "%.3f", drift) +
                        " mm off the line drawn for it (span " + first + ".." + last + ")",
                    drift <= driftMm,
                )
            }
            previousSource = last
        }
        assertEquals("every source move has to be covered", exact.moveCount, previousSource + 1)
        return worstDrift
    }

    @Test
    fun aTightBudgetFoldsOnlyMovesTheDrawnLineCovers() {
        val file = serpentine()
        val folded = GcodeNozzlePathParser.parse(file, maxMoves = 9_000)
        val exact = GcodeNozzlePathParser.parse(file, maxMoves = Int.MAX_VALUE)
        val worst = assertFoldsAreHonest(folded, exact)
        println(
            "serpentine: source=" + folded.sourceMoveCount + " drawn=" + folded.moveCount +
                String.format(Locale.US, " worst drift %.4f mm", worst),
        )
    }

    @Test
    fun theReportedPrintFoldsHonestly() {
        assumeTrue("real print gcode not available", realGcode.isFile)
        val folded = GcodeNozzlePathParser.parse(realGcode)
        val exact = GcodeNozzlePathParser.parse(realGcode, maxMoves = Int.MAX_VALUE)
        val worst = assertFoldsAreHonest(folded, exact)
        println(
            "reported print: source=" + folded.sourceMoveCount + " drawn=" + folded.moveCount +
                " extrusions=" + folded.extrusionMoveCount +
                String.format(Locale.US, " worst drift %.4f mm", worst),
        )
    }
}
