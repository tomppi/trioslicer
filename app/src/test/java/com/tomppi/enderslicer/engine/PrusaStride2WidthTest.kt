package com.tomppi.enderslicer.engine

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.sqrt

/** Compare stride=2 windowing (device print) with/without collinear splitting. */
class PrusaStride2WidthTest {
    @Test
    fun stride2() {
        val file = File("C:/Users/FREDRIK/Documents/PrintShare/print_20260903_181942_143.gcode")
        assumeTrue("device gcode not present", file.isFile)
        val path = GcodeNozzlePathParser.parse(file, 2_000_000, GcodeDialect.PRUSA)
        val moves = path.moves
        val n = path.moveCount
        val area = Math.PI * 0.875 * 0.875
        // (a) plain stride-2 windows (what the device renderer does today)
        val widthsA = widthDistribution(moves, n, 2, false, area)
        // (b) stride-2 windows split at direction reversals (>45deg)
        val widthsB = widthDistribution(moves, n, 2, true, area)
        println("A plain: avg=" + "%.3f".format(widthsA.first) + " n=" + widthsA.second)
        for (e in widthsA.third) if (e.key >= 0.6) println("A FAT " + e.key + " -> " + e.value)
        println("B split: avg=" + "%.3f".format(widthsB.first) + " n=" + widthsB.second)
        for (e in widthsB.third) if (e.key >= 0.6) println("B FAT " + e.key + " -> " + e.value)
    }

    private fun widthDistribution(moves: FloatArray, n: Int, stride: Int, splitTurn: Boolean, area: Double): Triple<Double, Int, Map<Double, Int>> {
        val buckets = sortedMapOf<Double, Int>()
        var sum = 0.0; var cnt = 0
        var i = 0
        while (i < n) {
            val oi = i * GcodeNozzlePath.VALUES_PER_MOVE
            val kind = moves[oi + GcodeNozzlePath.KIND]
            var j = i + 1
            if (splitTurn) {
                // first move direction
                var fdx = moves[oi + GcodeNozzlePath.X2] - moves[oi + GcodeNozzlePath.X1]
                var fdy = moves[oi + GcodeNozzlePath.Y2] - moves[oi + GcodeNozzlePath.Y1]
                val fl = sqrt(fdx * fdx + fdy * fdy)
                if (fl > 1e-7f) { fdx /= fl; fdy /= fl }
                while (j < n && j - i < stride && moves[j * GcodeNozzlePath.VALUES_PER_MOVE + GcodeNozzlePath.KIND] == kind) {
                    val jo = j * GcodeNozzlePath.VALUES_PER_MOVE
                    var ndx = moves[jo + GcodeNozzlePath.X2] - moves[jo + GcodeNozzlePath.X1]
                    var ndy = moves[jo + GcodeNozzlePath.Y2] - moves[jo + GcodeNozzlePath.Y1]
                    val nl = sqrt(ndx * ndx + ndy * ndy)
                    if (nl > 1e-7f && fdx * ndx / nl + fdy * ndy / nl < 0.65f) break // turn > ~49deg
                    j++
                }
            } else {
                while (j < n && j - i < stride && moves[j * GcodeNozzlePath.VALUES_PER_MOVE + GcodeNozzlePath.KIND] == kind) j++
            }
            if (kind == GcodeNozzlePath.Kind.EXTRUSION.code) {
                val lo = (j - 1) * GcodeNozzlePath.VALUES_PER_MOVE
                val sx = moves[oi + GcodeNozzlePath.X1].toDouble(); val sy = moves[oi + GcodeNozzlePath.Y1].toDouble()
                val ex = moves[lo + GcodeNozzlePath.X2].toDouble(); val ey = moves[lo + GcodeNozzlePath.Y2].toDouble()
                val chord = sqrt((ex - sx) * (ex - sx) + (ey - sy) * (ey - sy))
                var d = 0.0
                for (m in i until j) d += moves[m * GcodeNozzlePath.VALUES_PER_MOVE + GcodeNozzlePath.DELTA_E].toDouble()
                val lh = moves[lo + GcodeNozzlePath.LAYER_HEIGHT].toDouble()
                val height = if (lh > 0.02 && lh <= 2.0) lh else 0.08
                val width = if (chord > 1e-4 && d > 0) d * area / chord / height else 0.4
                val w = if (chord < 0.05) 0.0 else width.coerceIn(0.4 * 0.4, 0.4 * 4.0)
                val key = Math.round(w * 20.0) / 20.0
                buckets[key] = (buckets[key] ?: 0) + 1
                sum += w; cnt++
            }
            i = j
        }
        return Triple(sum / maxOf(1, cnt), cnt, buckets)
    }
}
