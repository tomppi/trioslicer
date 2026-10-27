package com.tomppi.enderslicer.texturizer

import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mask remap has to reproduce BumpMesh's loader cleanup exactly: stlLoader.js drops non-finite and zero-area
 * triangles and compacts the geometry before the painted mask is applied. A naive mask an app writes would paint the
 * wrong faces from the first dropped triangle onward; 3DBenchy really loses 11.
 */
class BumpMeshMaskRemapTest {

    @Test
    fun aMaskIsRenumberedThroughTheLoadersDegenerateTriangleFilter() {
        val stl = tempStl(
            triangles = listOf(
                triangle(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f),   // 0 valid
                triangle(0f, 0f, 0f, 8f, 0f, 0f, 0f, 8f, 0f),     // 1 valid
                triangle(0f, 0f, 0f, Float.NaN, 0f, 0f, 0f, 4f, 0f), // 2 NaN -> dropped
                triangle(0f, 0f, 2f, 6f, 0f, 2f, 0f, 6f, 2f),     // 3 valid
                triangle(0f, 0f, 0f, 5f, 0f, 0f, 10f, 0f, 0f),    // 4 collinear -> dropped
                triangle(0f, 0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f),   // 5 valid
                triangle(2f, 2f, 2f, 4f, 2f, 2f, 2f, 4f, 2f),     // 6 valid
                triangle(0f, 0f, 0f, 0f, Float.POSITIVE_INFINITY, 0f, 0f, 0f, 3f), // 7 inf -> dropped
            ),
            headerTriangleCount = 8,
        )
        val mask = booleanArrayOf(true, false, true, true, false, true, false, true)

        val remap = BumpMeshMaskRemap.remap(stl, 8, mask)

        assertEquals("dropped triangles", 3, remap.droppedTriangles)
        assertEquals("kept triangles", 5, remap.mask.size)
        // kept source indices: 0,1,3,5,6 - and the mask follows them
        assertEquals(listOf(true, false, true, true, false), remap.mask.toList())
        assertEquals(5, BumpMeshMaskRemap.cleanedTriangleCount(stl, 8))
    }

    @Test
    fun aMaskSizedForAnotherMeshIsRefusedByName() {
        val stl = tempStl(listOf(triangle(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)), headerTriangleCount = 1)
        val error = runCatching { BumpMeshMaskRemap.remap(stl, 1, booleanArrayOf(true, false)) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("belongs to another mesh"))
    }

    @Test
    fun anStlWithADifferentTriangleCountIsRefusedByName() {
        val stl = tempStl(
            listOf(triangle(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f)),
            headerTriangleCount = 2,
        )
        val error = runCatching {
            BumpMeshMaskRemap.remap(stl, 2, booleanArrayOf(true, true))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("truncated"))
    }

    @Test
    fun aNonBinaryStlIsRefusedByName() {
        val ascii = File.createTempFile("mask-test", ".stl").apply {
            writeText("solid cube\nfacet normal 0 0 1\nendfacet\nendsolid cube\n")
            deleteOnExit()
        }
        val error = runCatching {
            BumpMeshMaskRemap.remap(ascii, 1, booleanArrayOf(true))
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error!!.message!!.contains("not a binary STL"))
    }

    private fun triangle(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
    ): FloatArray = floatArrayOf(0f, 0f, 1f, ax, ay, az, bx, by, bz, cx, cy, cz)

    private fun tempStl(triangles: List<FloatArray>, headerTriangleCount: Int): File {
        val file = File.createTempFile("mask-test", ".stl")
        file.deleteOnExit()
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.write(ByteArray(80))
            out.write(le32(headerTriangleCount))
            triangles.forEach { triangle ->
                triangle.forEach { value -> out.write(le32(value.toRawBits())) }
                out.writeShort(0)
            }
        }
        return file
    }

    private fun le32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
}
