package com.tomppi.enderslicer.engine

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.sqrt

/** Resolve the rendered bead width for real Prusa windows like the renderer does. */
class PrusaBeadWidthAuditTest {
    @Test
    fun widths() {
        val file = File("C:/Users/FREDRIK/Documents/PrintShare/print_20260903_181942_143.gcode")
        assumeTrue("device gcode not present", file.isFile)
        val path = GcodeNozzlePathParser.parse(file, 2_000_000, GcodeDialect.PRUSA)
        val moves = path.moves
        val n = path.moveCount
        val stride = maxOf(1, (path.extrusionMoveCount + 159_999) / 160_000)
        val area = Math.PI * 0.875 * 0.875 // 1.75mm filament
        // bucket the rendered widths of extrusion windows
        val buckets = sortedMapOf<Double, Int>()
        var sum = 0.0; var cnt = 0
        var i = 0
        while (i < n) {
            val oi = i * GcodeNozzlePath.VALUES_PER_MOVE
            val kind = moves[oi + GcodeNozzlePath.KIND]
            var j = i + 1
            while (j < n && j - i < stride && moves[j * GcodeNozzlePath.VALUES_PER_MOVE + GcodeNozzlePath.KIND] == kind) j++
            if (kind == GcodeNozzlePath.Kind.EXTRUSION.code) {
                val lo = (j - 1) * GcodeNozzlePath.VALUES_PER_MOVE
                val sx = moves[oi + GcodeNozzlePath.X1].toDouble(); val sy = moves[oi + GcodeNozzlePath.Y1].toDouble()
                val ex = moves[lo + GcodeNozzlePath.X2].toDouble(); val ey = moves[lo + GcodeNozzlePath.Y2].toDouble()
                val len = sqrt((ex - sx) * (ex - sx) + (ey - sy) * (ey - sy))
                var d = 0.0
                for (m in i until j) d += moves[m * GcodeNozzlePath.VALUES_PER_MOVE + GcodeNozzlePath.DELTA_E].toDouble()
                val lh = moves[lo + GcodeNozzlePath.LAYER_HEIGHT].toDouble()
                val height = if (lh > 0.02 && lh <= 2.0) lh else 0.08
                val width = if (len > 1e-4 && d > 0) d * area / len / height else 0.4
                val w = if (len < 0.05) 0.0 else width.coerceIn(0.4 * 0.4, 0.4 * 4.0)
                val key = (Math.round(w * 20.0) / 20.0)
                buckets[key] = (buckets[key] ?: 0) + 1
                sum += w; cnt++
            }
            i = j
        }
        println("WIDTHS avg=" + "%.3f".format(sum / cnt) + " n=" + cnt)
        for ((k, v) in buckets) println("WIDTH " + "%.2f".format(k) + " -> " + v)
    }
}
