package com.tomppi.enderslicer.viewer

import java.nio.FloatBuffer
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the shared ribbon geometry both nozzle-path renderers now use.
 *
 * The Cura view used to offset every window by its OWN perpendicular, so
 * neighbouring windows never met at a corner: notches and skewed bead ends
 * along every wall (the Prusa view already mitred its joints). Two properties
 * keep that fixed:
 *
 *  1. a joint inside a run mitres the two tangents, while a run end caps with
 *     its own tangent, and
 *  2. windows on both sides of a joint use the SAME normal, so their corner
 *     coordinates coincide exactly and the wall is one strip.
 */
class RibbonPathGeometryTest {

    private val width = 0.44f
    private val height = 0.2f

    private fun strip(
        sx: Float, sy: Float, ex: Float, ey: Float,
        startNx: Float, startNy: Float,
        endNx: Float, endNy: Float,
    ): FloatArray {
        val vertex = DirectFloatSink(64)
        RibbonPathGeometry.addStrip(
            vertex,
            DirectFloatSink(64),
            DirectFloatSink(64),
            DirectFloatSink(64),
            sx, sy, 0f, ex, ey, 0f,
            width, height,
            startNx, startNy, endNx, endNy,
            floatArrayOf(1f, 1f, 1f, 1f),
            1f, 1f, 1f,
        )
        val buffer: FloatBuffer = vertex.toFloatBuffer()
        val out = FloatArray(buffer.limit())
        buffer.position(0)
        buffer.get(out)
        return out
    }

    /** Top-face corner of a window: vertices 0..5, 3 floats each. */
    private fun corner(v: FloatArray, vertex: Int): Triple<Float, Float, Float> {
        val o = vertex * 3
        return Triple(v[o], v[o + 1], v[o + 2])
    }

    @Test
    fun straightChainMitresToThePerpendicular() {
        val dir = RibbonPathGeometry.unitDirection(1f, 0f)!!
        val n = RibbonPathGeometry.jointNormal(dir, dir)
        assertEquals(0f, n.first, 1e-5f)
        assertEquals(1f, n.second, 1e-5f)
    }

    @Test
    fun rightAngleJointMitresBetweenBothTangents() {
        val inDir = RibbonPathGeometry.unitDirection(1f, 0f)!!
        val outDir = RibbonPathGeometry.unitDirection(0f, 1f)!!
        val n = RibbonPathGeometry.jointNormal(inDir, outDir)
        val expected = 1f / sqrt(2f)
        assertEquals(-expected, n.first, 1e-5f)
        assertEquals(expected, n.second, 1e-5f)
        // Not the incoming or the outgoing perpendicular: a real miter.
        assertNotEquals(0f, n.first, 1e-3f)
        assertNotEquals(-1f, n.first, 1e-3f)
    }

    @Test
    fun runEndCapsWithItsOwnTangent() {
        val inDir = RibbonPathGeometry.unitDirection(1f, 0f)!!
        val capped = RibbonPathGeometry.jointNormal(inDir, null)
        assertEquals(0f, capped.first, 1e-5f)
        assertEquals(1f, capped.second, 1e-5f)
        // And the mirror: a chain start caps the same way.
        val startCap = RibbonPathGeometry.jointNormal(null, inDir)
        assertEquals(0f, startCap.first, 1e-5f)
        assertEquals(1f, startCap.second, 1e-5f)
    }

    @Test
    fun degenerateJointFallsBackToAStableAxis() {
        val n = RibbonPathGeometry.jointNormal(null, null)
        assertEquals(1f, n.first, 1e-5f)
        assertEquals(0f, n.second, 1e-5f)
    }

    @Test
    fun chainedWindowsShareTheirCornerCoordinates() {
        // Two collinear windows meeting at x = 1: this is the Cura case that used
        // to leave a notch, because each window used its own perpendicular.
        val dir = RibbonPathGeometry.unitDirection(1f, 0f)!!
        val startCap = RibbonPathGeometry.jointNormal(null, dir)
        val joint = RibbonPathGeometry.jointNormal(dir, dir)
        val endCap = RibbonPathGeometry.jointNormal(dir, null)

        val a = strip(0f, 0f, 1f, 0f, startCap.first, startCap.second, joint.first, joint.second)
        val b = strip(1f, 0f, 2f, 0f, joint.first, joint.second, endCap.first, endCap.second)

        // Window a: c = vertex 2, d = vertex 5 on the top face.
        // Window b: a = vertex 0, b = vertex 1.
        val aC = corner(a, 2)
        val aD = corner(a, 5)
        val bB = corner(b, 1)
        val bA = corner(b, 0)
        assertEquals("x", aC.first, bB.first, 1e-5f)
        assertEquals("y", aC.second, bB.second, 1e-5f)
        assertEquals("x", aD.first, bA.first, 1e-5f)
        assertEquals("y", aD.second, bA.second, 1e-5f)
        // The shared corners really are the mitre offsets, not zero.
        assertEquals(width * 0.5f, aC.second, 1e-5f)
        assertEquals(-width * 0.5f, aD.second, 1e-5f)
        assertTrue("height carried on the top face", aC.third > height - 1e-5f)
    }
}
