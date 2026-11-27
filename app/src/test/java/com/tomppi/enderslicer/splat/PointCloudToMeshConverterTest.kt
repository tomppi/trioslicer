package com.tomppi.enderslicer.splat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class PointCloudToMeshConverterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun directTriangleMeshPlyConvertsCleanlyToBinaryStl() {
        val ply = File(tmp.root, "tri.ply")
        val header = """
            ply
            format binary_little_endian 1.0
            element vertex 3
            property float x
            property float y
            property float z
            element face 1
            property list uchar int vertex_indices
            end_header
        """.trimIndent() + "\n"
        val hdrBytes = header.toByteArray(Charsets.US_ASCII)
        val payload = ByteBuffer.allocate(3 * 12 + 1 + 12).order(ByteOrder.LITTLE_ENDIAN)
        payload.putFloat(0f).putFloat(0f).putFloat(0f)
        payload.putFloat(10f).putFloat(0f).putFloat(0f)
        payload.putFloat(0f).putFloat(10f).putFloat(0f)
        payload.put(3.toByte()).putInt(0).putInt(1).putInt(2)
        ply.writeBytes(hdrBytes + payload.array())

        val outStl = File(tmp.root, "tri.stl")
        val res = PointCloudToMeshConverter.tryConvertTriangleMeshPlyToStl(ply, outStl)
        assertNotNull(res)
        assertEquals(1, res!!.triangles)
        assertTrue(res.directMeshCopy)
        assertEquals(84L + 50L, outStl.length())
    }

    @Test
    fun pcaNormalEstimationFindsPlaneNormal() {
        val n = 64
        val xyz = FloatArray(n * 3)
        var k = 0
        for (ix in 0 until 8) {
            for (iy in 0 until 8) {
                xyz[k * 3] = (ix - 3.5f) * 0.01f
                xyz[k * 3 + 1] = (iy - 3.5f) * 0.01f
                xyz[k * 3 + 2] = 0.05f
                k++
            }
        }
        val cloud = PointCloudToMeshConverter.OrientedCloud(
            xyzM = xyz,
            nxyz = FloatArray(n * 3),
            rgb = ByteArray(n * 3),
            count = n,
            inputCount = n,
            hasNormals = false,
        )
        val oriented = PointCloudToMeshConverter.estimateNormalsPca(cloud)
        assertTrue(oriented.hasNormals)
        var aligned = 0
        for (i in 0 until n) {
            if (abs(oriented.nxyz[i * 3 + 2]) > 0.95f) aligned++
        }
        assertTrue("Expected most plane normals along +/-Z, got $aligned/$n", aligned >= n - 4)
    }

    @Test
    fun gaussianSplatShortestAxisNormalMatchesThinAxis() {
        // Identity rotation, thin along Z (s2 smallest) -> normal should be (0, 0, 1).
        val n = PointCloudToMeshConverter.shortestAxisNormal(1f, 0f, 0f, 0f, 0.05f, 0.04f, 0.002f)
        assertEquals(0f, n[0], 1e-4f)
        assertEquals(0f, n[1], 1e-4f)
        assertEquals(1f, n[2], 1e-4f)
    }
}
