package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.engine.PrusaNozzlePathParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Locks the ribbon vertex-stream contract of the Prusa renderer against the
 * REAL 236k-move print: the geometry, normal, color and ambient streams must
 * all have exactly one 18-vertex window per emitted window, and the per-move
 * prefix arrays must never request more data than was emitted (a mismatch
 * draws garbage past the buffers - visible as "model disappears after a
 * certain point" and scrambled speed colors).
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

        val renderer = PrusaNozzlePathRenderer()
        renderer.buildPathBuffers(path)

        val positions = renderer.ribbonPositions!!
        val normals = renderer.ribbonNormals!!
        val colors = renderer.ribbonColors!!
        val ambient = renderer.ribbonAmbient!!
        val emitted = positions.limit() / (RibbonPathGeometry.WINDOW_VERTICES * 3)
        println("emitted windows=" + emitted)

        // Stream lengths: one window = 18 verts; 3/3/4/1 floats per vertex.
        assertEquals("positions", emitted * 18 * 3, positions.limit())
        assertEquals("normals", emitted * 18 * 3, normals.limit())
        assertEquals("colors", emitted * 18 * 4, colors.limit())
        assertEquals("ambient", emitted * 18 * 1, ambient.limit())

        // The prefix for the final move must equal the emitted window count,
        // and no intermediate prefix may exceed it (disappearing tail bug).
        assertEquals("final prefix", emitted, renderer.ribbonPrefix[path.moveCount])
        var previous = 0
        for (k in 1..path.moveCount) {
            val current = renderer.ribbonPrefix[k]
            assertTrue("prefix " + k + " regressed: " + current + " < " + previous, current >= previous)
            assertTrue("prefix " + k + " exceeds emitted windows: " + current + " > " + emitted, current <= emitted)
            previous = current
        }

        // Speed colors are the one nozzle-path color mode: the color stream
        // holds one RGBA per window vertex and never exceeds the build.
        assertEquals("colors complete", emitted * 18 * 4, renderer.ribbonColors!!.limit())
        assertEquals("positions complete", emitted * 18 * 3, renderer.ribbonPositions!!.limit())
        assertEquals("ambient complete", emitted * 18, renderer.ribbonAmbient!!.limit())
        assertEquals("final prefix", emitted, renderer.ribbonPrefix[path.moveCount])
    }
}
