package com.tomppi.enderslicer.engine

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.sqrt

/** Statistics harness: compare Cura vs Prusa nozzle-path parse behavior on real device gcode. */
class PrusaNozzlePathStatsTest {
    @Test
    fun prusaStats() {
        val file = File("C:/Users/FREDRIK/Documents/PrintShare/print_20260903_181942_143.gcode")
        assumeTrue("device gcode not present on this machine", file.isFile)
        val path = GcodeNozzlePathParser.parse(file, 2_000_000, GcodeDialect.PRUSA)
        val moves = path.moves
        val n = path.moveCount
        var extrusion = 0
        var travel = 0
        var zOnly = 0
        var badWidth = 0
        var maxWidth = 0.0
        var minSpeed = Double.MAX_VALUE
        var maxSpeed = -1.0
        var layerHeights = sortedMapOf<Double, Int>()
        for (i in 0 until n) {
            val o = i * GcodeNozzlePath.VALUES_PER_MOVE
            val kind = moves[o + GcodeNozzlePath.KIND]
            val dx = moves[o + GcodeNozzlePath.X2] - moves[o + GcodeNozzlePath.X1]
            val dy = moves[o + GcodeNozzlePath.Y2] - moves[o + GcodeNozzlePath.Y1]
            val len = sqrt(dx * dx + dy * dy).toDouble()
            if (len < 0.001) zOnly++
            if (kind == GcodeNozzlePath.Kind.EXTRUSION.code) extrusion++ else travel++
            val deltaE = moves[o + GcodeNozzlePath.DELTA_E].toDouble()
            val lh = moves[o + GcodeNozzlePath.LAYER_HEIGHT].toDouble()
            layerHeights[lh] = (layerHeights[lh] ?: 0) + 1
            if (lh > 0.0001) {
                val width = if (len > 0.001) deltaE * 2.40566 / (len * lh) else 0.0
                if (width > 0.01) maxWidth = maxOf(maxWidth, width)
                if (width > 1.0 || width < 0.01) badWidth++
            }
            val sp = moves[o + GcodeNozzlePath.SPEED].toDouble()
            if (sp > 0.0) {
                minSpeed = minOf(minSpeed, sp)
                maxSpeed = maxOf(maxSpeed, sp)
            }
        }
        println("PRUSA-NOZZLE moves=" + n + " extrusion=" + extrusion + " travel=" + travel + " zOnly=" + zOnly)
        println("PRUSA-NOZZLE minSpeed=" + minSpeed + " maxSpeed=" + maxSpeed + " maxWidth=" + maxWidth + " badWidth=" + badWidth)
        println("PRUSA-NOZZLE layerHeights=" + layerHeights.entries.take(12).joinToString())
        println("PRUSA-NOZZLE truncated=" + path.truncated + " zRange=" + path.minZ + ".." + path.maxZ)
    }

    @Test
    fun curaStats() {
        val file = File("C:/Users/FREDRIK/Documents/PrintShare/print_20260903_180857_770.gcode")
        assumeTrue("cura gcode not present", file.isFile)
        val path = GcodeNozzlePathParser.parse(file, 2_000_000)
        val moves = path.moves
        val n = path.moveCount
        var extrusion = 0
        var travel = 0
        var zOnly = 0
        var maxWidth = 0.0
        var badWidth = 0
        var minSpeed = Double.MAX_VALUE
        var maxSpeed = -1.0
        var layerHeights = sortedMapOf<Double, Int>()
        for (i in 0 until n) {
            val o = i * GcodeNozzlePath.VALUES_PER_MOVE
            val kind = moves[o + GcodeNozzlePath.KIND]
            val dx = moves[o + GcodeNozzlePath.X2] - moves[o + GcodeNozzlePath.X1]
            val dy = moves[o + GcodeNozzlePath.Y2] - moves[o + GcodeNozzlePath.Y1]
            val len = sqrt(dx * dx + dy * dy).toDouble()
            if (len < 0.001) zOnly++
            if (kind == GcodeNozzlePath.Kind.EXTRUSION.code) extrusion++ else travel++
            val deltaE = moves[o + GcodeNozzlePath.DELTA_E].toDouble()
            val lh = moves[o + GcodeNozzlePath.LAYER_HEIGHT].toDouble()
            layerHeights[lh] = (layerHeights[lh] ?: 0) + 1
            if (lh > 0.0001) {
                val width = if (len > 0.001) deltaE * 2.40566 / (len * lh) else 0.0
                if (width > 0.01) maxWidth = maxOf(maxWidth, width)
                if (width > 1.0 || width < 0.01) badWidth++
            }
            val sp = moves[o + GcodeNozzlePath.SPEED].toDouble()
            if (sp > 0.0) {
                minSpeed = minOf(minSpeed, sp)
                maxSpeed = maxOf(maxSpeed, sp)
            }
        }
        println("CURA-NOZZLE moves=" + n + " extrusion=" + extrusion + " travel=" + travel + " zOnly=" + zOnly)
        println("CURA-NOZZLE minSpeed=" + minSpeed + " maxSpeed=" + maxSpeed + " maxWidth=" + maxWidth + " badWidth=" + badWidth)
        println("CURA-NOZZLE layerHeights=" + layerHeights.entries.take(12).joinToString())
        println("CURA-NOZZLE truncated=" + path.truncated + " zRange=" + path.minZ + ".." + path.maxZ)
    }
}