package com.tomppi.enderslicer.engine

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.sqrt

/** Window chord distribution on the exact device gcode (renderer path: stride 2 + turn split). */
class RealGcodeWindowLengthsTest {
    private val file = File("C:/Users/FREDRIK/Documents/PrintShare/print_20260903_232908_247.gcode")

    @Test
    fun lengths() {
        assumeTrue("device gcode not present", file.isFile)
        val path = GcodeNozzlePathParser.parse(file, 2_000_000, GcodeDialect.PRUSA)
        val moves = path.moves
        val n = path.moveCount
        val stride = 2
        var windows = 0
        var sub005 = 0
        var sub01 = 0
        var sub02 = 0
        var sumChord = 0.0
        var i = 0
        while (i < n) {
            val oi = i * GcodeNozzlePath.VALUES_PER_MOVE
            val kind = moves[oi + GcodeNozzlePath.KIND]
            var j = i + 1
            var fdx = moves[oi + GcodeNozzlePath.X2] - moves[oi + GcodeNozzlePath.X1]
            var fdy = moves[oi + GcodeNozzlePath.Y2] - moves[oi + GcodeNozzlePath.Y1]
            val fl = sqrt(fdx * fdx + fdy * fdy)
            if (fl > 1e-7f) { fdx /= fl; fdy /= fl }
            while (j < n && j - i < stride && moves[j * GcodeNozzlePath.VALUES_PER_MOVE + GcodeNozzlePath.KIND] == kind) {
                val jo = j * GcodeNozzlePath.VALUES_PER_MOVE
                var ndx = moves[jo + GcodeNozzlePath.X2] - moves[jo + GcodeNozzlePath.X1]
                var ndy = moves[jo + GcodeNozzlePath.Y2] - moves[jo + GcodeNozzlePath.Y1]
                val nl = sqrt(ndx * ndx + ndy * ndy)
                if (nl > 1e-7f && fdx * ndx / nl + fdy * ndy / nl < 0.65f) break
                j++
            }
            if (kind == GcodeNozzlePath.Kind.EXTRUSION.code) {
                windows++
                val lo = (j - 1) * GcodeNozzlePath.VALUES_PER_MOVE
                val sx = moves[oi + GcodeNozzlePath.X1].toDouble(); val sy = moves[oi + GcodeNozzlePath.Y1].toDouble()
                val ex = moves[lo + GcodeNozzlePath.X2].toDouble(); val ey = moves[lo + GcodeNozzlePath.Y2].toDouble()
                val chord = sqrt((ex - sx) * (ex - sx) + (ey - sy) * (ey - sy))
                sumChord += chord
                if (chord < 0.05) sub005++
                if (chord < 0.1) sub01++
                if (chord < 0.2) sub02++
            }
            i = j
        }
        println("WINDOWS total=" + windows + " avgChord=" + "%.3f".format(sumChord / windows) +
            " <0.05=" + sub005 + " (" + "%.1f".format(100.0 * sub005 / windows) + "%)" +
            " <0.10=" + sub01 + " (" + "%.1f".format(100.0 * sub01 / windows) + "%)" +
            " <0.20=" + sub02 + " (" + "%.1f".format(100.0 * sub02 / windows) + "%)")
    }
}
