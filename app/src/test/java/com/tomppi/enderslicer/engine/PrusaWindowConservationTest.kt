package com.tomppi.enderslicer.engine

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Verifies the windowed ribbon build conserves total extrusion (per kind-run). */
class PrusaWindowConservationTest {
    @Test
    fun conservation() {
        val file = File("C:/Users/FREDRIK/Documents/PrintShare/print_20260903_181942_143.gcode")
        assumeTrue("device gcode not present", file.isFile)
        val path = GcodeNozzlePathParser.parse(file, 2_000_000, GcodeDialect.PRUSA)
        val moves = path.moves
        val n = path.moveCount
        val stride = maxOf(1, (path.extrusionMoveCount + 159_999) / 160_000)
        // Windows: same-kind consecutive runs capped at stride.
        var windows = 0
        var windowDeltaSum = 0.0
        var rawDeltaSum = 0.0
        var i = 0
        while (i < n) {
            val oi = i * GcodeNozzlePath.VALUES_PER_MOVE
            val kind = moves[oi + GcodeNozzlePath.KIND]
            var j = i + 1
            while (j < n && j - i < stride && moves[j * GcodeNozzlePath.VALUES_PER_MOVE + GcodeNozzlePath.KIND] == kind) j++
            var windowDelta = 0.0
            for (m in i until j) {
                windowDelta += moves[m * GcodeNozzlePath.VALUES_PER_MOVE + GcodeNozzlePath.DELTA_E].toDouble()
            }
            windowDeltaSum += windowDelta
            windows++
            i = j
        }
        for (m in 0 until n) {
            rawDeltaSum += moves[m * GcodeNozzlePath.VALUES_PER_MOVE + GcodeNozzlePath.DELTA_E].toDouble()
        }
        println("WINDOW stride=" + stride + " windows=" + windows + " totalDeltaE=" + "%.4f".format(windowDeltaSum) + " raw=" + "%.4f".format(rawDeltaSum))
        check(windows < 200_000) { "too many windows: " + windows }
        check(kotlin.math.abs(windowDeltaSum - rawDeltaSum) < 0.01) { "deltaE not conserved" }
    }
}
