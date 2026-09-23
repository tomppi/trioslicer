package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NozzlePathBoundsTest {

    private fun vertices(points: List<FloatArray>): FloatArray {
        val out = FloatArray(points.size * 3)
        points.forEachIndexed { i, p -> p.copyInto(out, i * 3) }
        return out
    }

    @Test
    fun `empty and single vertex return null`() {
        assertNull(NozzlePathBounds.printedBounds(FloatArray(0)))
        assertNull(NozzlePathBounds.printedBounds(floatArrayOf(1f, 2f, 3f)))
    }

    @Test
    fun `two vertices keep their exact bounds`() {
        val bounds = NozzlePathBounds.printedBounds(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f))!!
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), bounds, 0f)
    }

    @Test
    fun `coincident points keep their exact bounds`() {
        val points = FloatArray(300) { if (it % 3 == 0) 50f else if (it % 3 == 1) 60f else 10f }
        assertArrayEquals(floatArrayOf(50f, 60f, 10f, 50f, 60f, 10f), NozzlePathBounds.printedBounds(points)!!, 0f)
    }

    @Test
    fun `purge line near the plate corner is trimmed away`() {
        val points = ArrayList<FloatArray>()
        for (i in 0 until 5000) {
            points.add(floatArrayOf(100f + (i % 100) * 0.2f, 100f + ((i / 100) % 50) * 0.2f, (i % 25) * 0.2f))
        }
        for (i in 0 until 10) points.add(floatArrayOf(2f + i * 2f, 3f, 0.3f))
        val bounds = NozzlePathBounds.printedBounds(vertices(points))!!
        assertEquals(100f, bounds[0], 0.5f)
        assertEquals(100f, bounds[1], 0.5f)
        assertEquals(119.6f, bounds[3], 0.5f)
        assertEquals(109.8f, bounds[4], 0.5f)
        assertEquals(0f, bounds[2], 1e-4f)
        assertEquals(4.8f, bounds[5], 0.1f)
    }

    @Test
    fun `two distant bodies are both retained`() {
        val points = ArrayList<FloatArray>()
        for (i in 0 until 6000) {
            points.add(floatArrayOf(10f + (i % 100) * 0.1f, 10f + ((i / 100) % 60) * 0.1f, 0.2f))
        }
        for (i in 0 until 4000) {
            points.add(floatArrayOf(80f + (i % 100) * 0.1f, 80f + ((i / 100) % 40) * 0.1f, 0.2f))
        }
        val bounds = NozzlePathBounds.printedBounds(vertices(points))!!
        assertEquals(10f, bounds[0], 0.2f)
        assertEquals(10f, bounds[1], 0.2f)
        assertEquals(89.5f, bounds[3], 0.2f)
        assertEquals(83.9f, bounds[4], 0.2f)
    }

    @Test
    fun `thin diagonal model survives trimming`() {
        val points = ArrayList<FloatArray>()
        for (i in 0 until 200) points.add(floatArrayOf(5f + i * 0.5f, 5f + i * 0.5f, 1f))
        val bounds = NozzlePathBounds.printedBounds(vertices(points))!!
        assertTrue(bounds[0] >= 5f && bounds[0] <= 7.5f)
        assertTrue(bounds[3] >= 102f && bounds[3] <= 104.5f)
        assertEquals(1f, bounds[2], 0f)
        assertEquals(1f, bounds[5], 0f)
    }
}
