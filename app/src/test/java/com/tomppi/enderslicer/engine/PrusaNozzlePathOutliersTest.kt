package com.tomppi.enderslicer.engine

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.sqrt

/** Inspect the longest moves remaining in the Prusa-filtered nozzle path. */
class PrusaNozzlePathOutliersTest {
    @Test
    fun outliers() {
        val file = File("C:/Users/FREDRIK/Documents/PrintShare/print_20260903_181942_143.gcode")
        assumeTrue("device gcode not present", file.isFile)
        val path = GcodeNozzlePathParser.parse(file, 2_000_000, GcodeDialect.PRUSA)
        val moves = path.moves
        val n = path.moveCount
        data class Long(val i: Int, val len: Double, val kind: Int, val sx: Float, val sy: Float, val sz: Float, val ex: Float, val ey: Float, val ez: Float, val speed: Float)
        val longs = ArrayList<Long>()
        for (i in 0 until n) {
            val o = i * GcodeNozzlePath.VALUES_PER_MOVE
            val dx = moves[o + GcodeNozzlePath.X2] - moves[o + GcodeNozzlePath.X1]
            val dy = moves[o + GcodeNozzlePath.Y2] - moves[o + GcodeNozzlePath.Y1]
            val len = sqrt(dx * dx + dy * dy).toDouble()
            if (len > 40.0) longs.add(
                Long(i, len, moves[o + GcodeNozzlePath.KIND].toInt(),
                    moves[o + GcodeNozzlePath.X1], moves[o + GcodeNozzlePath.Y1], moves[o + GcodeNozzlePath.Z1],
                    moves[o + GcodeNozzlePath.X2], moves[o + GcodeNozzlePath.Y2], moves[o + GcodeNozzlePath.Z2],
                    moves[o + GcodeNozzlePath.SPEED]))
        }
        println("OUTLIERS count=" + longs.size)
        for (l in longs.take(30)) {
            println("OUT i=" + l.i + " len=" + "%.1f".format(l.len) + " kind=" + l.kind +
                " from=" + l.sx + "," + l.sy + "," + l.sz + " to=" + l.ex + "," + l.ey + "," + l.ez + " spd=" + l.speed)
        }
        // Also: what fraction of moves are near the plate edges (x < 30 or x > 190)?
        var edge = 0
        for (i in 0 until n) {
            val o = i * GcodeNozzlePath.VALUES_PER_MOVE
            val sx = moves[o + GcodeNozzlePath.X1]; val ex = moves[o + GcodeNozzlePath.X2]
            if (sx < 30f || ex < 30f || sx > 190f || ex > 190f) edge++
        }
        println("EDGE moves=" + edge + " of " + n)
    }
}
