package com.tomppi.splatproto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import kotlin.math.exp
import kotlin.math.ln

class Cross3dgsPipelineTest {

    @Test
    fun colmapBinaryWriterExportsCamerasImagesAndRealSparsePoints() {
        val dir = createTempDirectory("colmap-bin-test").toFile()
        try {
            File(dir, "cameras.txt").writeText(
                "# Camera list\n1 OPENCV 640 480 432.5 431.0 320.5 240.5 0.05 -0.04 0.001 -0.002\n",
            )
            File(dir, "images.txt").writeText(
                "# Image list\n1 1.0 0.0 0.0 0.0 0.1 0.2 0.3 1 p_0000.jpg\n10.0 20.0 1\n",
            )
            File(dir, "points3D.txt").writeText(
                "# 3D points\n" +
                    "1 0.5 -0.25 1.5 200 100 50 0.42 1 0 2 3\n" +
                    "2 -0.3 0.75 2.0 10 20 30 0.15 1 1\n",
            )

            val count = ColmapBinaryWriter.ensureBinaryWithCount(dir)
            assertEquals(2, count)
            assertEquals(2, ColmapBinaryWriter.readBinaryPointsCount(dir))

            // Verify cameras.bin converted OPENCV (4) -> PINHOLE (1) with 4 params.
            val camBytes = File(dir, "cameras.bin").readBytes()
            val camBuf = ByteBuffer.wrap(camBytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1L, camBuf.long)
            assertEquals(1, camBuf.int)
            assertEquals(1, camBuf.int) // PINHOLE
            assertEquals(640L, camBuf.long)
            assertEquals(480L, camBuf.long)
            assertEquals(432.5, camBuf.double, 1e-9)
            assertEquals(431.0, camBuf.double, 1e-9)
            assertEquals(320.5, camBuf.double, 1e-9)
            assertEquals(240.5, camBuf.double, 1e-9)
            assertEquals(0, camBuf.remaining())

            // Verify points3D.bin holds both real points and RGB.
            val ptBytes = File(dir, "points3D.bin").readBytes()
            val ptBuf = ByteBuffer.wrap(ptBytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(2L, ptBuf.long)
            assertEquals(1L, ptBuf.long)
            assertEquals(0.5, ptBuf.double, 1e-9)
            assertEquals(-0.25, ptBuf.double, 1e-9)
            assertEquals(1.5, ptBuf.double, 1e-9)
            assertEquals(200, ptBuf.get().toInt() and 0xFF)
            assertEquals(100, ptBuf.get().toInt() and 0xFF)
            assertEquals(50, ptBuf.get().toInt() and 0xFF)
            assertEquals(0.42, ptBuf.double, 1e-9)
            assertEquals(0L, ptBuf.long)
            // Second point
            assertEquals(2L, ptBuf.long)
            assertEquals(-0.3, ptBuf.double, 1e-9)
            assertEquals(0.75, ptBuf.double, 1e-9)
            assertEquals(2.0, ptBuf.double, 1e-9)
            assertEquals(10, ptBuf.get().toInt() and 0xFF)
            assertEquals(20, ptBuf.get().toInt() and 0xFF)
            assertEquals(30, ptBuf.get().toInt() and 0xFF)
            assertEquals(0.15, ptBuf.double, 1e-9)
            assertEquals(0L, ptBuf.long)
            assertEquals(0, ptBuf.remaining())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun checkpointWriterUsesSoALayoutWithLinearScaleAndOpacity() {
        val dir = createTempDirectory("chk-test").toFile()
        try {
            val ply = File(dir, "in.ply")
            val chk = File(dir, "out.d3gs")
            val header = buildString {
                append("ply\nformat binary_little_endian 1.0\nelement vertex 2\n")
                append("property float x\nproperty float y\nproperty float z\n")
                append("property float scale_0\nproperty float scale_1\nproperty float scale_2\n")
                append("property float rot_0\nproperty float rot_1\nproperty float rot_2\nproperty float rot_3\n")
                append("property float opacity\n")
                append("property float f_dc_0\nproperty float f_dc_1\nproperty float f_dc_2\n")
                append("end_header\n")
            }
            val data = ByteBuffer.allocate(2 * 14 * 4).order(ByteOrder.LITTLE_ENDIAN)
            // Gaussian 0: pos=(1,2,3), logScale=ln(0.01), rot=(2,0,0,0), logit(0.5)=0, dc=(0.1,0.2,0.3)
            data.putFloat(1f).putFloat(2f).putFloat(3f)
            data.putFloat(ln(0.01f)).putFloat(ln(0.02f)).putFloat(ln(0.03f))
            data.putFloat(2f).putFloat(0f).putFloat(0f).putFloat(0f)
            data.putFloat(0f)
            data.putFloat(0.1f).putFloat(0.2f).putFloat(0.3f)
            // Gaussian 1: pos=(4,5,6), logScale=ln(0.005), rot=(0,1,0,0), logit=2.2, dc=(-0.1,0.0,0.5)
            data.putFloat(4f).putFloat(5f).putFloat(6f)
            data.putFloat(ln(0.005f)).putFloat(ln(0.005f)).putFloat(ln(0.005f))
            data.putFloat(0f).putFloat(1f).putFloat(0f).putFloat(0f)
            data.putFloat(2.2f)
            data.putFloat(-0.1f).putFloat(0.0f).putFloat(0.5f)
            FileOutputStream(ply).use { os ->
                os.write(header.toByteArray(Charsets.US_ASCII))
                os.write(data.array())
            }

            val n = Cross3dgsCheckpoint.fromPlyCount(ply, chk)
            assertEquals(2, n)
            assertEquals(2, Cross3dgsCheckpoint.readCount(chk))

            val raw = chk.readBytes()
            // Exact size: 8 magic + 8 count + 2 * (4*16 + 12*16) = 16 + 2 * 256 = 528 bytes
            assertEquals(16 + 2 * (4 + 12) * 16, raw.size)
            val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(8).also { bb.get(it) }
            assertEquals("D3GSCHK1", String(magic, Charsets.US_ASCII))
            assertEquals(2L, bb.long)

            // SoA 1: ALL means first!
            assertEquals(1f, bb.float, 1e-6f); assertEquals(2f, bb.float, 1e-6f); assertEquals(3f, bb.float, 1e-6f); assertEquals(0f, bb.float, 1e-6f)
            assertEquals(4f, bb.float, 1e-6f); assertEquals(5f, bb.float, 1e-6f); assertEquals(6f, bb.float, 1e-6f); assertEquals(0f, bb.float, 1e-6f)
            // SoA 2: ALL scales_opacity (LINEAR scale + LINEAR opacity!)
            assertEquals(0.01f, bb.float, 1e-5f); assertEquals(0.02f, bb.float, 1e-5f); assertEquals(0.03f, bb.float, 1e-5f); assertEquals(0.5f, bb.float, 1e-5f)
            val expectedAlpha = (1.0f / (1.0f + exp(-2.2f)))
            assertEquals(0.005f, bb.float, 1e-5f); assertEquals(0.005f, bb.float, 1e-5f); assertEquals(0.005f, bb.float, 1e-5f); assertEquals(expectedAlpha, bb.float, 1e-4f)
            // SoA 3: ALL normalized rotations
            assertEquals(1f, bb.float, 1e-6f); assertEquals(0f, bb.float, 1e-6f); assertEquals(0f, bb.float, 1e-6f); assertEquals(0f, bb.float, 1e-6f)
            assertEquals(0f, bb.float, 1e-6f); assertEquals(1f, bb.float, 1e-6f); assertEquals(0f, bb.float, 1e-6f); assertEquals(0f, bb.float, 1e-6f)
            // SoA 4: ALL sh_dc
            assertEquals(0.1f, bb.float, 1e-6f); assertEquals(0.2f, bb.float, 1e-6f); assertEquals(0.3f, bb.float, 1e-6f); assertEquals(0f, bb.float, 1e-6f)
            assertEquals(-0.1f, bb.float, 1e-6f); assertEquals(0.0f, bb.float, 1e-6f); assertEquals(0.5f, bb.float, 1e-6f); assertEquals(0f, bb.float, 1e-6f)
            // Remaining 2 * 12 * 16 bytes of sh_rest must all be zero.
            assertEquals(2 * 12 * 16, bb.remaining())
            while (bb.hasRemaining()) assertTrue(bb.float == 0f)
        } finally {
            dir.deleteRecursively()
        }
    }
}
