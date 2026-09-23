package com.tomppi.enderslicer.viewer

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The off-heap mesh path: large binary STLs are parsed into direct native
 * buffers so their vertex data never counts against the Java heap, and every
 * consumer keeps the same index/size API regardless of the storage path.
 */
class VertexDataOffHeapTest {

    @Test
    fun arrayPathRoundTrips() {
        val values = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)
        val data = VertexData.fromArray(values)
        assertEquals(6, data.size)
        assertEquals(2f, data[1], 0f)
        assertNotNull(data.arrayOrNull())
        assertNull(data.directOrNull())
        assertEquals(0L, data.nativeBytes)
        assertArrayEquals(values, data.toFloatArray(), 0f)
    }

    @Test
    fun directPathRoundTrips() {
        val values = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f)
        val direct = ByteBuffer.allocateDirect(6 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        values.forEach(direct::put)
        direct.position(0)
        val data = VertexData.fromDirect(direct)
        assertEquals(6, data.size)
        assertEquals(4f, data[3], 0f)
        assertNull(data.arrayOrNull())
        assertNotNull(data.directOrNull())
        assertEquals(24L, data.nativeBytes)
        assertArrayEquals(values, data.toFloatArray(), 0f)
    }

    @Test
    fun smallBinaryStlStaysOnHeap() {
        val file = binaryStl(triangleCount = 3, firstVertex = floatArrayOf(1f, 2f, 3f))
        val mesh = StlParser.parse(file, maxTriangles = 10)
        assertEquals(3, mesh.triangleCount)
        assertNotNull(mesh.interleavedVertices.arrayOrNull())
        assertEquals(0L, mesh.interleavedVertices.nativeBytes)
        assertEquals(1f, mesh.interleavedVertices[0], 0f)
        assertEquals(2f, mesh.interleavedVertices[1], 0f)
        assertEquals(3f, mesh.interleavedVertices[2], 0f)
    }

    @Test
    fun largeBinaryStlParsesOffHeap() {
        val triangleCount = OFF_HEAP_MIN_TRIANGLES + 1
        val file = binaryStl(triangleCount, floatArrayOf(10.25f, 20.5f, 30.75f))
        val mesh = StlParser.parse(file, maxTriangles = triangleCount + 10)
        assertEquals(triangleCount, mesh.triangleCount)
        assertNull(mesh.interleavedVertices.arrayOrNull())
        assertNotNull(mesh.interleavedVertices.directOrNull())
        assertTrue(mesh.interleavedVertices.nativeBytes > 0L)
        assertEquals(triangleCount * 18, mesh.interleavedVertices.size)
        assertEquals(10.25f, mesh.interleavedVertices[0], 0f)
        assertEquals(20.5f, mesh.interleavedVertices[1], 0f)
        assertEquals(30.75f, mesh.interleavedVertices[2], 0f)
    }

    private fun binaryStl(triangleCount: Int, firstVertex: FloatArray): File {
        val file = File.createTempFile("offheap-", ".stl")
        file.deleteOnExit()
        val buffer = ByteBuffer
            .allocate(84 + triangleCount * 50)
            .order(ByteOrder.LITTLE_ENDIAN)
        repeat(80) { buffer.put(0.toByte()) }
        buffer.putInt(triangleCount)
        repeat(triangleCount) { triangle ->
            repeat(3) { buffer.putFloat(0f) } // normal (advisory)
            repeat(9) { buffer.putFloat(0f) } // three vertices (3 floats each)
            buffer.putShort(0)
            if (triangle == 0) {
                // Position of the first vertex float: header (84) + normal (12).
                buffer.position(96)
                firstVertex.forEach(buffer::putFloat)
                buffer.position(84 + 50)
            }
        }
        file.writeBytes(buffer.array())
        return file
    }
}
