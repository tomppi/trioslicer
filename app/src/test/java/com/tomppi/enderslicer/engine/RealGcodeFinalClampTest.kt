package com.tomppi.enderslicer.engine

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.sqrt

/** Audit with the FINAL renderer clamp (fine-layer 0.6x..1.15x). */
class RealGcodeFinalClampTest {
    private val file = File("C:/Users/FREDRIK/Documents/PrintShare/print_20260903_230906_815.gcode")

    @Test
    fun audit() {
        assumeTrue("device gcode not present", file.isFile)
        val path = GcodeNozzlePathParser.parse(file, 2_000_000, GcodeDialect.PRUSA)
        val moves = path.moves
        val n = path.moveCount
        val stride = 2
        val area = Math.PI * 0.875 * 0.875
        var fat = 0; var thin = 0; var windows = 0; var maxW = 0.0
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
                val w = if (chord < 0.05) 0.0 else {
                    val maxR = if (height <= 0.12) 1.15 else 4.0
                    val minR = if (height <= 0.12) 0.60 else 0.4
                    raw.coerceIn(0.4 * minR, 0.4 * maxR)
                }
                maxW = maxOf(maxW, w)
                if (w > 0.6) fat++ else if (w < 0.34 && w > 0.0) thin++
            }
            i = j
        }
        println("FINAL windows=" + windows + " fat=" + fat + " thin=" + thin + " maxW=" + "%.2f".format(maxW))
    }
}
