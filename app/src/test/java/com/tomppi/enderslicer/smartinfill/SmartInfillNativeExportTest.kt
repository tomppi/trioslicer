package com.tomppi.enderslicer.smartinfill

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SmartInfillNativeExportTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun modifierArchiveUsesTheWebViewNaming() {
        val directory = temporaryFolder.newFolder("handoff")
        val file = SmartInfillNativeExport.writeModifierArchive(
            directory = directory,
            sourceName = "bench hook v3.stl",
            archive = byteArrayOf(1, 2, 3, 4),
            // Centred on X/Y with the base at Z = 0: nothing to move.
            analyzedSource = File(directory, "analyzed.stl").apply {
                writeBytes(binaryStl(-1f, 0f, 0f, 1f, 0f, 0f))
            },
        )
        assertEquals("bench_hook_v3_smart_infill_modifiers.zip", file.name)
        assertEquals(4, file.readBytes().size)
    }

    @Test
    fun partTopoShapeUsesTheWebViewNaming() {
        val directory = temporaryFolder.newFolder("handoff")
        val source = File(directory, "analyzed.stl")
        source.writeBytes(binaryStl(-5f, -5f, 0f, 5f, 5f, 10f))
        val file = SmartInfillNativeExport.writeOptimizedShape(
            directory = directory,
            sourceName = "part.stl",
            shape = binaryStl(1f, 2f, 3f, 4f, 5f, 6f),
            analyzedSource = source,
        )
        assertEquals("part_optimized.stl", file.name)
        // Already in the local frame, so the body is written unchanged.
        val vertex = firstVertexOfFile(file)
        assertEquals(1f, vertex[0], 1e-3f)
        assertEquals(2f, vertex[1], 1e-3f)
        assertEquals(3f, vertex[2], 1e-3f)
    }

    @Test
    fun thePartTopoBodyIsRecenteredForTheImport() {
        val directory = temporaryFolder.newFolder("topo")
        // A placed model whose centre is (10, 20) with its base at Z = 0: the
        // import adds exactly that back, so the body must not carry it.
        val source = File(directory, "analyzed.stl")
        source.writeBytes(binaryStl(0f, 0f, 0f, 20f, 40f, 10f))

        val file = SmartInfillNativeExport.writeOptimizedShape(
            directory = directory,
            sourceName = "part.stl",
            shape = binaryStl(14f, 24f, 4f, 34f, 60f, 30f),
            analyzedSource = source,
        )

        val vertex = firstVertexOfFile(file)
        assertEquals(4f, vertex[0], 1e-3f)
        assertEquals(4f, vertex[1], 1e-3f)
        assertEquals(4f, vertex[2], 1e-3f)
    }

    @Test
    fun safeBaseNameSurvivesAnEmptyOrOddName() {
        assertEquals("model", SmartInfillNativeExport.safeBaseName(""))
        assertEquals("model", SmartInfillNativeExport.safeBaseName("   "))
        assertEquals("a_b.c", SmartInfillNativeExport.safeBaseName("a b.c"))
        assertEquals("nested", SmartInfillNativeExport.safeBaseName("dir/nested.stl"))
    }

    @Test
    fun anEmptyExportIsRefused() {
        val directory = temporaryFolder.newFolder("empty")
        val failure = runCatching {
            SmartInfillNativeExport.writeModifierArchive(
                directory,
                "part.stl",
                ByteArray(0),
                File(directory, "analyzed.stl"),
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun theNativeArchiveIsRecenteredForThePackageContract() {
        val directory = temporaryFolder.newFolder("recentered")
        // A placed model: bounds X[0,20], Y[0,40], Z[0,10], so the centre is
        // (10, 20) and the base is Z = 0 — exactly what the store measures to
        // place the volumes again when it stages them for the slice.
        val source = File(directory, "analyzed.stl")
        source.writeBytes(binaryStl(0f, 0f, 0f, 20f, 40f, 10f))
        val archive = zipOf("modifier_30pct.stl", binaryStl(14f, 24f, 4f, 34f, 60f, 30f))

        val file = SmartInfillNativeExport.writeModifierArchive(
            directory = directory,
            sourceName = "s-hook.STL",
            archive = archive,
            analyzedSource = source,
        )

        assertEquals("s-hook" + SmartInfillNativeExport.MODIFIER_SUFFIX, file.name)
        val vertex = firstVertexOf(file.readBytes(), "modifier_30pct.stl")
        assertEquals("14 - 10", 4f, vertex[0], 1e-3f)
        assertEquals("24 - 20", 4f, vertex[1], 1e-3f)
        assertEquals("4 - 0", 4f, vertex[2], 1e-3f)
    }

    @Test
    fun aModelAlreadyInTheLocalFrameIsLeftAlone() {
        val directory = temporaryFolder.newFolder("centered")
        val source = File(directory, "analyzed.stl")
        source.writeBytes(binaryStl(-5f, -5f, 0f, 5f, 5f, 10f))
        val archive = zipOf("modifier_30pct.stl", binaryStl(1f, 2f, 3f, 4f, 5f, 6f))

        val file = SmartInfillNativeExport.writeModifierArchive(
            directory = directory,
            sourceName = "part.stl",
            archive = archive,
            analyzedSource = source,
        )

        val vertex = firstVertexOf(file.readBytes(), "modifier_30pct.stl")
        assertEquals(1f, vertex[0], 1e-3f)
        assertEquals(2f, vertex[1], 1e-3f)
        assertEquals(3f, vertex[2], 1e-3f)
    }

    @Test
    fun aVolumeThatIsNotABinaryStlIsRefused() {
        val directory = temporaryFolder.newFolder("broken")
        val source = File(directory, "analyzed.stl")
        source.writeBytes(binaryStl(0f, 0f, 0f, 20f, 40f, 10f))
        val archive = zipOf("modifier_30pct.stl", ByteArray(20))

        val failure = runCatching {
            SmartInfillNativeExport.writeModifierArchive(
                directory = directory,
                sourceName = "part.stl",
                archive = archive,
                analyzedSource = source,
            )
        }.exceptionOrNull()

        assertTrue("expected a refusal, got $failure", failure is IllegalArgumentException)
    }

    private fun firstVertexOfFile(file: File): FloatArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(STL_HEADER + 12)
        return floatArrayOf(buffer.float, buffer.float, buffer.float)
    }

    /** One triangle, the whole binary layout: header, count, normal, three vertices. */
    private fun binaryStl(
        x0: Float,
        y0: Float,
        z0: Float,
        x1: Float,
        y1: Float,
        z1: Float,
    ): ByteArray {
        val bytes = ByteArray(STL_HEADER + STL_TRIANGLE)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(80)
        buffer.putInt(1)
        buffer.position(STL_HEADER + 12)
        buffer.putFloat(x0).putFloat(y0).putFloat(z0)
        buffer.putFloat(x1).putFloat(y1).putFloat(z1)
        buffer.putFloat(x1).putFloat(y1).putFloat(z1)
        return bytes
    }

    private fun zipOf(name: String, body: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(body)
            zip.closeEntry()
        }
        return output.toByteArray()
    }

    private fun firstVertexOf(archive: ByteArray, name: String): FloatArray {
        ZipInputStream(ByteArrayInputStream(archive)).use { input ->
            var entry = input.nextEntry
            while (entry != null) {
                val bytes = input.readBytes()
                if (entry.name == name) {
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    buffer.position(STL_HEADER + 12)
                    return floatArrayOf(buffer.float, buffer.float, buffer.float)
                }
                entry = input.nextEntry
            }
        }
        error("$name is missing from the archive")
    }

    private companion object {
        const val STL_HEADER = 84
        const val STL_TRIANGLE = 50
    }
}