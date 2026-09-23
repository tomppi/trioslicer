package com.tomppi.enderslicer.engine

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.sqrt

/** Audit with the renderer's EXACT width formula (fallback height = 0.2, not 0.08). */
class RealGcodeTrueFatTest {
    // A real print to audit, not part of the repository: set -DrealGcode=<path> or
    // REAL_GCODE=<path>. The test skips itself when neither is set.
    private val file = File(System.getProperty("realGcode") ?: System.getenv("REAL_GCODE").orEmpty())

    @Test
    fun audit() {
        assumeTrue("device gcode not present", file.isFile)
        val path = GcodeNozzlePathParser.parse(file, 2_000_000, GcodeDialect.PRUSA)
        val moves = path.moves
        val n = path.moveCount
        val stride = maxOf(1, (path.extrusionMoveCount + 159_999) / 160_000)
        val area = Math.PI * 0.875 * 0.875
        // renderer: height = parsed>0.02&&<=2.0 ? parsed : beadHeight(0.2); width clamp 0.4*0.4..0.4*4
        var fat = 0; var thin = 0; var windows = 0; var maxW = 0.0
        val buckets = sortedMapOf<Double, Int>()
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
                var d = 0.0
                for (m in i until j) d += moves[m * GcodeNozzlePath.VALUES_PER_MOVE + GcodeNozzlePath.DELTA_E].toDouble()
                val lh = moves[lo + GcodeNozzlePath.LAYER_HEIGHT].toDouble()
                val height = if (lh > 0.02 && lh <= 2.0) lh else 0.2
                val raw = if (chord > 1e-4 && d > 0) d * area / chord / height else 0.4
                val w = if (chord < 0.05) 0.0 else raw.coerceIn(0.16, 1.6)
                val key = Math.round(w * 20.0) / 20.0
                buckets[key] = (buckets[key] ?: 0) + 1
                maxW = maxOf(maxW, w)
                if (w > 0.6) fat++ else if (w < 0.34 && w > 0.0) thin++
            }
            i = j
        }
        println("TRUE windows=" + windows + " fat=" + fat + " thin=" + thin + " maxW=" + "%.2f".format(maxW))
        for ((k, v) in buckets) if (k >= 0.55) println("TRUE FAT " + "%.2f".format(k) + " -> " + v)
    }
}
