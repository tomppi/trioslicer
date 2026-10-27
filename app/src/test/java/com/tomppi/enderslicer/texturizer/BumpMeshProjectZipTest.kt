package com.tomppi.enderslicer.texturizer

import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The .bumpmesh project envelope the page's own import reads: settings.json, model.stl and an optional mask.json. */
class BumpMeshProjectZipTest {

    @Test
    fun theZipCarriesTheSettingsTheModelAndTheMaskShapeThePageReads() {
        val model = tempStl(listOf(triangle(0f, 0f, 0f, 2f, 0f, 0f, 0f, 2f, 0f)))
        val destination = File.createTempFile("project", ".bumpmesh").apply { deleteOnExit() }
        val settings = BumpMeshProjectFiles.settingsJson(5, 12f, 0.6f, 0.5f, 0.4f, 8_000_000)
        val maskJson = BumpMeshProjectFiles.maskJson(booleanArrayOf(true, false, true))

        BumpMeshProjectFiles.buildZip(destination, model, settings, maskJson)

        val entries = readZip(destination)
        assertEquals(setOf("settings.json", "model.stl", "mask.json"), entries.keys)
        assertEquals(model.readBytes().toList(), entries["model.stl"]!!.toList())
        val settingsText = String(entries["settings.json"]!!, Charsets.UTF_8)
        listOf(
            "\"mappingMode\":5",
            "\"scaleUnit\":\"mm\"",
            "\"scaleU\":12",
            "\"scaleV\":12",
            "\"amplitude\":0.6",
            "\"textureHeight\":0.6",
            "\"textureSmoothing\":0.5",
            "\"refineLength\":0.4",
            "\"maxTriangles\":8000000",
            "\"boundaryFalloff\":1.0",
            "\"boundaryFalloffCurve\":\"ease\"",
            "\"preserveUntextured\":true",
        ).forEach { snippet -> assertTrue("settings.json missing " + snippet + " in " + settingsText, settingsText.contains(snippet)) }
        assertEquals(
            "{\"selectionMode\":true,\"excluded\":[0,2]}",
            String(entries["mask.json"]!!, Charsets.UTF_8),
        )
    }

    @Test
    fun aProjectWithoutAMaskCarriesOnlyTheModelAndSettings() {
        val model = tempStl(listOf(triangle(0f, 0f, 0f, 2f, 0f, 0f, 0f, 2f, 0f)))
        val destination = File.createTempFile("project", ".bumpmesh").apply { deleteOnExit() }
        BumpMeshProjectFiles.buildZip(
            destination = destination,
            modelFile = model,
            settingsJson = BumpMeshProjectFiles.settingsJson(5, 12f, 0.6f, 0.5f, 0.4f, 1_500_000),
            maskJson = null,
        )
        val entries = readZip(destination)
        assertEquals(setOf("settings.json", "model.stl"), entries.keys)
    }

    private fun readZip(file: File): Map<String, ByteArray> {
        val result = LinkedHashMap<String, ByteArray>()
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
            }
        }
        return result
    }

    private fun triangle(
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float,
    ): FloatArray = floatArrayOf(0f, 0f, 1f, ax, ay, az, bx, by, bz, cx, cy, cz)

    private fun tempStl(triangles: List<FloatArray>): File {
        val file = File.createTempFile("project-model", ".stl")
        file.deleteOnExit()
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.write(ByteArray(80))
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(triangles.size).array())
            triangles.forEach { triangle ->
                triangle.forEach { value ->
                    out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toRawBits()).array())
                }
                out.writeShort(0)
            }
        }
        return file
    }
}
