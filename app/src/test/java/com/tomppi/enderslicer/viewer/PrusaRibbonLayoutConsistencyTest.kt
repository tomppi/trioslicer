package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.engine.PrusaNozzlePathParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Locks the ribbon vertex-stream contract of the Prusa renderer against the
 * REAL 236k-move print: every chunk the geometry sink receives must hold exactly
 * one 18-vertex window per move (3/3/4/1 floats a vertex), the chunked build must
 * emit one window per extrusion move - the budget is a memory limit, never a
 * sampler - and the per-move prefix arrays must never request more data than was
 * emitted (a mismatch draws garbage past the buffers - visible as "model
 * disappears after a certain point" and scrambled speed colors).
 */
class PrusaRibbonLayoutConsistencyTest {

    // A real print to audit, not part of the repository: set -DrealGcode=<path> or
    // REAL_GCODE=<path>. The test skips itself when neither is set.
    private val gcode = File(System.getProperty("realGcode") ?: System.getenv("REAL_GCODE").orEmpty())

    @Test
    fun windowStreamsStayInLockstepWithPrefixes() {
        assumeTrue("real print gcode not available", gcode.isFile)
        val path = PrusaNozzlePathParser.parse(gcode)
        println("moves=" + path.moveCount + " extrusions=" + path.extrusionMoveCount + " layers=" + path.layerCount)

        // The recorder checks every chunk's shape as it arrives; the counts below
        // check that nothing was sampled away between the parser and the sink.
        val recorder = RibbonChunkRecorder()
        val builder = PrusaRibbonBuilder(path)
        val staging = RibbonChunkStaging(recorder)
        builder.build(staging)
        staging.finish()
        val emitted = recorder.windows
        println("emitted windows=" + emitted)

        assertEquals("one window per extrusion move", path.extrusionMoveCount, emitted)
        assertEquals("build and sink disagree", emitted, builder.ribbonWindows)
        assertEquals("travel lines", builder.travelSegments, recorder.travelSegments)

        // The prefix for the final move must equal the emitted window count,
        // and no intermediate prefix may exceed it (disappearing tail bug).
        assertEquals("final prefix", emitted, builder.ribbonPrefix[path.moveCount])
        var previous = 0
        for (k in 1..path.moveCount) {
            val current = builder.ribbonPrefix[k]
            assertTrue("prefix " + k + " regressed: " + current + " < " + previous, current >= previous)
            assertTrue("prefix " + k + " exceeds emitted windows: " + current + " > " + emitted, current <= emitted)
            previous = current
        }
        assertEquals("final travel prefix", recorder.travelSegments, builder.travelPrefix[path.moveCount])
    }
}
