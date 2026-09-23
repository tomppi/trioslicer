package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wireframe layout the renderer draws with GL_LINES: three segments per
 * triangle, and each vertex carrying the colour of the triangle it came from.
 */
class WireframeBufferTest {

    private val red = floatArrayOf(1f, 0f, 0f)
    private val blue = floatArrayOf(0f, 0f, 1f)

    @Test
    fun aSoupTriangleBecomesItsThreeEdges() {
        val positions = floatArrayOf(
            0f, 0f, 0f,
            1f, 0f, 0f,
            0f, 1f, 0f,
        )
        val wireframe = WireframeBuffer.soup(positions, red)

        assertEquals(1, wireframe.triangleCount)
        assertEquals(6, wireframe.vertexCount)
        assertArrayEquals(
            floatArrayOf(
                0f, 0f, 0f, 1f, 0f, 0f,
                1f, 0f, 0f, 0f, 1f, 0f,
                0f, 1f, 0f, 0f, 0f, 0f,
            ),
            wireframe.positions,
            0f,
        )
        assertEquals(18, wireframe.colors.size)
        for (index in 0 until 18 step 3) {
            assertEquals(1f, wireframe.colors[index], 0f)
            assertEquals(0f, wireframe.colors[index + 1], 0f)
        }
    }

    @Test
    fun everyTriangleKeepsItsOwnColour() {
        val positions = floatArrayOf(
            0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f,
            0f, 0f, 1f, 1f, 0f, 1f, 0f, 1f, 1f,
        )
        val wireframe = WireframeBuffer.soup(positions, red + blue)

        assertEquals(2, wireframe.triangleCount)
        assertEquals(12, wireframe.vertexCount)
        // The first triangle's six vertices are all red ...
        assertEquals(1f, wireframe.colors[0], 0f)
        assertEquals(0f, wireframe.colors[1], 0f)
        assertEquals(1f, wireframe.colors[15], 0f)
        assertEquals(0f, wireframe.colors[16], 0f)
        // ... and the second triangle's are all blue.
        assertEquals(0f, wireframe.colors[18], 0f)
        assertEquals(0f, wireframe.colors[19], 0f)
        assertEquals(1f, wireframe.colors[20], 0f)
        assertEquals(1f, wireframe.colors[35], 0f)
        // Its geometry follows the first: its first vertex is (0, 0, 1).
        assertEquals(0f, wireframe.positions[18], 0f)
        assertEquals(0f, wireframe.positions[19], 0f)
        assertEquals(1f, wireframe.positions[20], 0f)
    }

    @Test
    fun anIndexedMeshFollowsItsIndices() {
        val positions = floatArrayOf(
            0f, 0f, 0f,
            1f, 0f, 0f,
            0f, 1f, 0f,
        )
        val wireframe = WireframeBuffer.indexed(positions, intArrayOf(2, 0, 1), red)

        assertArrayEquals(
            floatArrayOf(
                0f, 1f, 0f, 0f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f, 0f,
                1f, 0f, 0f, 0f, 1f, 0f,
            ),
            wireframe.positions,
            0f,
        )
    }

    @Test
    fun aTriangleWithAMissingVertexIsDroppedInsteadOfDrawnAtTheOrigin() {
        val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
        val indices = intArrayOf(0, 1, 99, 0, 1, 2)

        val wireframe = WireframeBuffer.indexed(positions, indices, red + blue)

        assertEquals("only the complete triangle is drawn", 1, wireframe.triangleCount)
        // The survivor is the second triangle, in blue: v0 = (0,0,0), v1 = (1,0,0).
        assertEquals(0f, wireframe.positions[0], 0f)
        assertEquals(1f, wireframe.positions[3], 0f)
        assertEquals("its own colour, not the dropped triangle's", 0f, wireframe.colors[0], 0f)
        assertEquals(0f, wireframe.colors[1], 0f)
        assertEquals(1f, wireframe.colors[2], 0f)
    }

    @Test
    fun emptyInputYieldsAnEmptyBuffer() {
        assertTrue(WireframeBuffer.soup(FloatArray(0), FloatArray(0)).isEmpty)
        assertTrue(WireframeBuffer.indexed(FloatArray(0), IntArray(0), FloatArray(0)).isEmpty)
        assertEquals(0, WireframeBuffer.soup(FloatArray(0), FloatArray(0)).vertexCount)
    }

    @Test
    fun aTrailingPartialTriangleIsIgnored() {
        val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 9f, 9f)
        val wireframe = WireframeBuffer.soup(positions, red)

        assertEquals(1, wireframe.triangleCount)
    }

    @Test
    fun concatJoinsTheRegionsIntoOneDraw() {
        val first = WireframeBuffer.soup(
            floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f),
            red,
        )
        val second = WireframeBuffer.soup(
            floatArrayOf(0f, 0f, 1f, 1f, 0f, 1f, 0f, 1f, 1f),
            blue,
        )

        val joined = WireframeBuffer.concat(listOf(first, second))

        assertEquals(2, joined.triangleCount)
        assertEquals(12, joined.vertexCount)
        assertEquals("the first region keeps its colour", 1f, joined.colors[0], 0f)
        assertEquals("the second keeps its own", 1f, joined.colors[20], 0f)
        assertEquals(1f, joined.positions[20], 0f)
    }

    @Test
    fun concatOfNothingIsEmpty() {
        assertTrue(WireframeBuffer.concat(emptyList()).isEmpty)
    }

    @Test
    fun trianglesExpandAnIndexedMeshToOneTrianglePerShape() {
        val positions = floatArrayOf(
            0f, 0f, 0f,
            1f, 0f, 0f,
            0f, 1f, 0f,
        )
        val wireframe = WireframeBuffer.triangles(positions, intArrayOf(2, 0, 1), red)

        assertEquals(1, wireframe.triangleCount)
        assertEquals("three vertices, not six: this is a surface", 3, wireframe.vertexCount)
        assertArrayEquals(
            floatArrayOf(0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f),
            wireframe.positions,
            0f,
        )
        assertEquals(9, wireframe.colors.size)
        for (index in 0 until 9 step 3) {
            assertEquals(1f, wireframe.colors[index], 0f)
        }
    }

    @Test
    fun joiningSurfacesKeepsTheSurfaceShape() {
        val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
        val first = WireframeBuffer.triangles(positions, intArrayOf(0, 1, 2), red)
        val second = WireframeBuffer.triangles(positions, intArrayOf(0, 1, 2), blue)

        val joined = WireframeBuffer.concat(listOf(first, second))

        assertEquals(2, joined.triangleCount)
        assertEquals("a surface joined to a surface is still a surface", 6, joined.vertexCount)
    }

    @Test
    fun surfacesAndEdgesRefuseToBeJoined() {
        val positions = floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)
        val surface = WireframeBuffer.triangles(positions, intArrayOf(0, 1, 2), red)
        val edges = WireframeBuffer.indexed(positions, intArrayOf(0, 1, 2), red)

        val failure = runCatching { WireframeBuffer.concat(listOf(surface, edges)) }.exceptionOrNull()

        assertTrue("expected a refusal, got $failure", failure is IllegalArgumentException)
    }
}