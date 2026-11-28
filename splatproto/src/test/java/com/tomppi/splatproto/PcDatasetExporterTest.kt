package com.tomppi.splatproto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

class PcDatasetExporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun exportsStandardColmapZipPreservingOpenCvDistortion() {
        val scanDir = File(tmp.root, "20261128-090000").apply { mkdirs() }
        val imagesDir = File(scanDir, "images").apply { mkdirs() }
        val masksDir = File(scanDir, "masks").apply { mkdirs() }
        val sparseDir = File(scanDir, "sparse/0").apply { mkdirs() }
        val exportsDir = File(scanDir, "exports").apply { mkdirs() }

        File(imagesDir, "s0000.jpg").writeBytes(ByteArray(128) { it.toByte() })
        File(imagesDir, "s0001.jpg").writeBytes(ByteArray(128) { (it * 3).toByte() })
        File(masksDir, "s0000.jpg.png").writeBytes(ByteArray(64) { 0xFF.toByte() })
        File(scanDir, "scan.json").writeText("""{"format":1,"mode":"splat_only"}""")
        File(scanDir, "dense.ply").writeBytes(ByteArray(256) { 0x42 })
        File(exportsDir, "laser-only.ply").writeBytes(ByteArray(256) { 0x33 })

        // OPENCV camera model (id 4) with 8 params: fx fy cx cy k1 k2 p1 p2
        File(sparseDir, "cameras.txt").writeText(
            "# Camera list\n1 OPENCV 1920 1440 1500.0 1500.0 960.0 720.0 0.05 -0.04 0.001 -0.002\n",
        )
        File(sparseDir, "images.txt").writeText(
            "# Image list\n1 1.0 0.0 0.0 0.0 0.0 0.0 0.0 1 s0000.jpg\n\n2 1.0 0.0 0.0 0.0 0.1 0.0 0.0 1 s0001.jpg\n\n",
        )
        File(sparseDir, "points3D.txt").writeText(
            "# 3D points\n1 0.1 0.2 0.3 200 180 160 0.4\n",
        )

        val outZip = File(tmp.root, "export.zip")
        val stats = PcDatasetExporter.writeDatasetZipToFile(Scan(scanDir), outZip)
        assertEquals(2, stats.images)
        assertEquals(1, stats.masks)
        assertTrue(stats.hasColmap)
        assertTrue(stats.hasDensePly)
        assertEquals(1, stats.extraClouds)

        ZipFile(outZip).use { zf ->
            val names = zf.entries().toList().map { it.name }.toSet()
            assertTrue("README.txt" in names)
            assertTrue("images/s0000.jpg" in names)
            assertTrue("images/s0001.jpg" in names)
            assertTrue("masks/s0000.jpg.png" in names)
            assertTrue("sparse/0/cameras.txt" in names)
            assertTrue("sparse/0/cameras.bin" in names)
            assertTrue("sparse/0/images.bin" in names)
            assertTrue("sparse/0/points3D.bin" in names)
            assertTrue("dense.ply" in names)
            assertTrue("exports/laser-only.ply" in names)
            assertTrue("scan.json" in names)

            // Verify cameras.bin preserved OPENCV (model_id == 4) with all 8 double params
            val camBytes = zf.getInputStream(zf.getEntry("sparse/0/cameras.bin")).readBytes()
            val bb = ByteBuffer.wrap(camBytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(1L, bb.long) // num_cameras
            assertEquals(1, bb.int)   // camera_id
            assertEquals(4, bb.int)   // model_id == OPENCV (preserved, NOT stripped to PINHOLE)
            assertEquals(1920L, bb.long)
            assertEquals(1440L, bb.long)
            assertEquals(1500.0, bb.double, 1e-6)
            assertEquals(1500.0, bb.double, 1e-6)
            assertEquals(960.0, bb.double, 1e-6)
            assertEquals(720.0, bb.double, 1e-6)
            assertEquals(0.05, bb.double, 1e-6)
        }
    }

    @Test
    fun exportsPhotosOnlyScanWithoutSparseModel() {
        val scanDir = File(tmp.root, "20261128-090500").apply { mkdirs() }
        val imagesDir = File(scanDir, "images").apply { mkdirs() }
        File(imagesDir, "s0000.jpg").writeBytes(ByteArray(100) { 1 })
        File(scanDir, "scan.json").writeText("""{"format":1,"mode":"splat_only"}""")

        val outZip = File(tmp.root, "photos.zip")
        val stats = PcDatasetExporter.writeDatasetZipToFile(Scan(scanDir), outZip)
        assertEquals(1, stats.images)
        assertFalse(stats.hasColmap)
        assertFalse(stats.hasDensePly)

        ZipFile(outZip).use { zf ->
            val names = zf.entries().toList().map { it.name }.toSet()
            assertTrue("images/s0000.jpg" in names)
            assertTrue("README.txt" in names)
            assertFalse("sparse/0/cameras.bin" in names)
        }
    }
}
