package com.tomppi.enderslicer.engine

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.sqrt

/** How many stride windows are shorter than RIBBON_MIN_SEGMENT_MM (0.05)? */
class PrusaMicroSegmentTest {
    @Test
    fun microWindows() {
        val file = File("C:/Users/FREDRIK/Documents/PrintShare/print_20260903_181942_143.gcode")
        assumeTrue("device gcode not present", file.isFile)
        val path = GcodeNozzlePathParser.parse(file, 2_000_000, GcodeDialect.PRUSA)
        val moves = path.moves
        val n = path.moveCount
        val stride = maxOf(1, (path.extrusionMoveCount + 159_999) / 160_000)
        var windows = 0
        var micro = 0
        var i = 0
        while (i < n) {
            val oi = i * GcodeNozzlePath.VALUES_PER_MOVE
            val kind = moves[oi + GcodeNozzlePath.KIND]
            var j = i + 1
            while (j < n && j - i < stride && moves[j * GcodeNozzlePath.VALUES_PER_MOVE + GcodeNozzlePath.KIND] == kind) j++
            if (kind == GcodeNozzlePath.Kind.EXTRUSION.code) {
                windows++
                val lo = (j - 1) * GcodeNozzlePath.VALUES_PER_MOVE
                val sx = moves[oi + GcodeNozzlePath.X1]; val sy = moves[oi + GcodeNozzlePath.Y1]
                val ex = moves[lo + GcodeNozzlePath.X2]; val ey = moves[lo + GcodeNozzlePath.Y2]
                val len = sqrt((ex - sx) * (ex - sx) + (ey - sy) * (ey - sy)).toDouble()
                if (len < 0.05) micro++
            }
            i = j
        }
        println("MICRO windows=" + windows + " sub0.05=" + micro + " (" + "%.1f".format(100.0 * micro / maxOf(1, windows)) + "%)")
    }
}
