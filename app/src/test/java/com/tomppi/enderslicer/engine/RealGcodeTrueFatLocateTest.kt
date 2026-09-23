package com.tomppi.enderslicer.engine

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.sqrt

/** Where are the remaining true-fat windows? Z distribution + examples. */
class RealGcodeTrueFatLocateTest {
    // A real print to audit, not part of the repository: set -DrealGcode=<path> or
    // REAL_GCODE=<path>. The test skips itself when neither is set.
    private val file = File(System.getProperty("realGcode") ?: System.getenv("REAL_GCODE").orEmpty())

    @Test
    fun locate() {
        assumeTrue("device gcode not present", file.isFile)
        val path = GcodeNozzlePathParser.parse(file, 2_000_000, GcodeDialect.PRUSA)
        val moves = path.moves
        val n = path.moveCount
        val stride = 2
        val area = Math.PI * 0.875 * 0.875
        val zOfFat = sortedMapOf<Int, Int>()
        var shown = 0
        var i = 0
        while (i < n && shown < 25) {
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
                val lo = (j - 1) * GcodeNozzlePath.VALUES_PER_MOVE
                val sx = moves[oi + GcodeNozzlePath.X1].toDouble(); val sy = moves[oi + GcodeNozzlePath.Y1].toDouble()
                val ex = moves[lo + GcodeNozzlePath.X2].toDouble(); val ey = moves[lo + GcodeNozzlePath.Y2].toDouble()
                val chord = sqrt((ex - sx) * (ex - sx) + (ey - sy) * (ey - sy))
                var d = 0.0
                for (m in i until j) d += moves[m * GcodeNozzlePath.VALUES_PER_MOVE + GcodeNozzlePath.DELTA_E].toDouble()
                val lh = moves[lo + GcodeNozzlePath.LAYER_HEIGHT].toDouble()
                val height = if (lh > 0.02 && lh <= 2.0) lh else 0.2
                val raw = if (chord > 1e-4 && d > 0) d * area / chord / height else 0.4
                val w = if (chord < 0.05) 0.0 else raw.coerceIn(0.16, 1.6)
                if (w > 0.6) {
                    val z = moves[lo + GcodeNozzlePath.Z2].toInt()
                    zOfFat[z] = (zOfFat[z] ?: 0) + 1
                    if (shown < 25) {
                        println("TFT i=$i from=%.1f,%.1f,%.2f to=%.1f,%.1f,%.2f chord=%.2f dE=%.3f lh=%.2f w=%.2f".format(sx, sy, moves[oi + GcodeNozzlePath.Z1].toDouble(), ex, ey, moves[lo + GcodeNozzlePath.Z2].toDouble(), chord, d, lh, w))
                        shown++
                    }
                }
            }
            i = j
        }
        println("TFT top-z: " + zOfFat.entries.sortedByDescending { it.value }.take(12).joinToString())
    }
}
