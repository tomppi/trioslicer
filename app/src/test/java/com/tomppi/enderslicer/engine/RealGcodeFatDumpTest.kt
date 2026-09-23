package com.tomppi.enderslicer.engine

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.sqrt

/** Dump the fat window examples on the real gcode. */
class RealGcodeFatDumpTest {
    private val file = File("C:/Users/FREDRIK/Documents/PrintShare/print_20260903_230906_815.gcode")

    @Test
    fun dump() {
        assumeTrue("device gcode not present", file.isFile)
        val path = GcodeNozzlePathParser.parse(file, 2_000_000, GcodeDialect.PRUSA)
        val moves = path.moves
        val n = path.moveCount
        val stride = 2
        val area = Math.PI * 0.875 * 0.875
        var shown = 0
        var i = 0
        while (i < n && shown < 40) {
            val oi = i * GcodeNozzlePath.VALUES_PER_MOVE
            val kind = moves[oi + GcodeNozzlePath.KIND]
            var j = i + 1
            while (j < n && j - i < stride && moves[j * GcodeNozzlePath.VALUES_PER_MOVE + GcodeNozzlePath.KIND] == kind) j++
            if (kind == GcodeNozzlePath.Kind.EXTRUSION.code) {
                val lo = (j - 1) * GcodeNozzlePath.VALUES_PER_MOVE
                val sx = moves[oi + GcodeNozzlePath.X1]; val sy = moves[oi + GcodeNozzlePath.Y1]; val sz = moves[oi + GcodeNozzlePath.Z1]
                val ex = moves[lo + GcodeNozzlePath.X2]; val ey = moves[lo + GcodeNozzlePath.Y2]; val ez = moves[lo + GcodeNozzlePath.Z2]
                val chord = sqrt(((ex - sx) * (ex - sx) + (ey - sy) * (ey - sy)).toDouble())
                var d = 0.0
                var arc = 0.0
                for (m in i until j) {
                    val mo = m * GcodeNozzlePath.VALUES_PER_MOVE
                    val ax = moves[mo + GcodeNozzlePath.X1]; val ay = moves[mo + GcodeNozzlePath.Y1]
                    val bx = moves[mo + GcodeNozzlePath.X2]; val by = moves[mo + GcodeNozzlePath.Y2]
                    arc += sqrt(((bx - ax) * (bx - ax) + (by - ay) * (by - ay)).toDouble())
                    d += moves[mo + GcodeNozzlePath.DELTA_E].toDouble()
                }
                val lh = moves[lo + GcodeNozzlePath.LAYER_HEIGHT].toDouble()
                val height = if (lh > 0.02 && lh <= 2.0) lh else 0.08
                val w = if (chord > 1e-4 && d > 0) d * area / chord / height else 0.4
                if (w > 0.6) {
                    println("FAT i=$i from=%.1f,%.1f,%.1f to=%.1f,%.1f,%.1f chord=%.2f arc=%.2f dE=%.3f h=%.2f w=%.2f".format(sx, sy, sz, ex, ey, ez, chord, arc, d, height, w))
                    shown++
                }
            }
            i = j
        }
        println("FAT shown=$shown")
    }
}
