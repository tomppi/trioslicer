package com.tomppi.enderslicer.engine

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.sqrt

/** Compare chord-based vs arc-length-based widths for stride windows. */
class PrusaCornerWidthTest {
    @Test
    fun corner() {
        val file = File("C:/Users/FREDRIK/Documents/PrintShare/print_20260903_181942_143.gcode")
        assumeTrue("device gcode not present", file.isFile)
        val path = GcodeNozzlePathParser.parse(file, 2_000_000, GcodeDialect.PRUSA)
        val moves = path.moves
        val n = path.moveCount
        val stride = maxOf(1, (path.extrusionMoveCount + 159_999) / 160_000)
        val area = Math.PI * 0.875 * 0.875
        // grouping: windows by chord as renderer does now; measure chordLen vs arcLen
        var chordWidthSum = 0.0; var arcWidthSum = 0.0; var cnt = 0
        var inflated = 0
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
                val chord = sqrt((ex - sx) * (ex - sx) + (ey - sy) * (ey - sy))
                var arc = 0.0; var d = 0.0
                for (m in i until j) {
                    val mo = m * GcodeNozzlePath.VALUES_PER_MOVE
                    val ax = moves[mo + GcodeNozzlePath.X1]; val ay = moves[mo + GcodeNozzlePath.Y1]
                    val bx = moves[mo + GcodeNozzlePath.X2]; val by = moves[mo + GcodeNozzlePath.Y2]
                    arc += sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
                    d += moves[mo + GcodeNozzlePath.DELTA_E].toDouble()
                }
                val lh = moves[lo + GcodeNozzlePath.LAYER_HEIGHT].toDouble()
                val height = if (lh > 0.02 && lh <= 2.0) lh else 0.08
                val chordW = if (chord > 1e-4 && d > 0) d * area / chord / height else 0.4
                val arcW = if (arc > 1e-4 && d > 0) d * area / arc / height else 0.4
                chordWidthSum += chordW; arcWidthSum += arcW; cnt++
                if (chordW > arcW * 1.15) inflated++
            }
            i = j
        }
        println("CORNER avgChord=" + "%.3f".format(chordWidthSum / cnt) +
            " avgArc=" + "%.3f".format(arcWidthSum / cnt) +
            " inflated(>15%)=$inflated of $cnt (" + "%.1f".format(100.0 * inflated / cnt) + "%)")
    }
}
