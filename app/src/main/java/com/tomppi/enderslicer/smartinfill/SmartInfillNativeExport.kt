package com.tomppi.enderslicer.smartinfill

import com.tomppi.enderslicer.mesh.MeshTriangleLimits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The native result handed to the slice pipeline.
 *
 * A native optimization produces the same two things the WebView transport did
 * — a `modifier_NNpct.stl` ZIP for graded/binary infill, or one body STL for
 * Part Topo — so it travels through SmartInfillPackageStore unchanged. The file
 * names mirror the WebView bridge's, which keeps exported packages recognisable
 * whichever engine produced them.
 */
object SmartInfillNativeExport {
    const val MODIFIER_SUFFIX = "_smart_infill_modifiers.zip"
    const val SHAPE_SUFFIX = "_optimized.stl"

    /**
     * A file-name-safe base for the model: the base name only, without a model
     * extension, so a nested or oddly named import cannot steer the handoff
     * file out of its directory.
     */
    fun safeBaseName(sourceName: String): String {
        val base = sourceName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .trim()
        val stripped = MODEL_EXTENSIONS
            .firstOrNull { base.endsWith(it, ignoreCase = true) }
            ?.let { base.dropLast(it.length) }
            ?: base
        return stripped.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "model" }
    }

    private val MODEL_EXTENSIONS = listOf(".stl", ".3mf")

    /**
     * Writes the modifier archive for the Smart Infill store to import.
     *
     * The package contract — the one [SmartInfillPackage.stageModifiers]
     * implements at slice time — says an archive's volumes are in filaSim's
     * local frame: centered on X and Y and grounded at Z. The WebView producer
     * worked that way; the native engine works in the analyzed STL's own
     * (already placed) coordinates, so the volumes are recentered here and the
     * store's staging puts them back on the plate. Without this the staged
     * volume is shifted a second time and the slicer refuses the model as
     * outside the build volume.
     */
    fun writeModifierArchive(
        directory: File,
        sourceName: String,
        archive: ByteArray,
        analyzedSource: File,
    ): File {
        require(archive.isNotEmpty()) { "The Smart Infill modifier archive is empty" }
        val recentered = recenter(archive, analyzedSource)
        return write(
            directory = directory,
            name = safeBaseName(sourceName) + MODIFIER_SUFFIX,
            bytes = recentered,
        )
    }

    /**
     * Writes the Part Topo body for the model import path.
     *
     * The same frame rule applies: the import places the incoming body by
     * adding the analyzed model's centre and base back, because the WebView
     * producer handed its body over in filaSim's local frame. The engine works
     * in the placed frame, so the body is recentered here and the import puts it
     * back exactly where the analyzed model was.
     */
    fun writeOptimizedShape(
        directory: File,
        sourceName: String,
        shape: ByteArray,
        analyzedSource: File,
    ): File {
        require(shape.isNotEmpty()) { "The Smart Infill Part Topo shape is empty" }
        val recentered = translateStl(shape, shiftFor(analyzedSource))
        return write(
            directory = directory,
            name = safeBaseName(sourceName) + SHAPE_SUFFIX,
            bytes = recentered,
        )
    }

    /**
     * Shifts every STL in [archive] into filaSim's local frame. The bounds come
     * from the analyzed STL, which is exactly what the store measures to place
     * the volumes again, so the two cancel to the float.
     */
    internal fun recenter(archive: ByteArray, analyzedSource: File): ByteArray {
        val shift = shiftFor(analyzedSource)
        if (shift.all { it == 0f }) return archive

        val output = ByteArrayOutputStream(archive.size)
        ZipInputStream(ByteArrayInputStream(archive)).use { input ->
            ZipOutputStream(output).use { zip ->
                var entry = input.nextEntry
                while (entry != null) {
                    val bytes = input.readBytes()
                    val body = if (entry.name.endsWith(".stl", ignoreCase = true)) {
                        translateStl(bytes, shift)
                    } else {
                        bytes
                    }
                    zip.putNextEntry(ZipEntry(entry.name))
                    zip.write(body)
                    zip.closeEntry()
                    entry = input.nextEntry
                }
            }
        }
        return output.toByteArray()
    }

    /** The translation into filaSim's local frame for an analyzed model. */
    private fun shiftFor(analyzedSource: File): FloatArray {
        val bounds = binaryStlBounds(analyzedSource, MeshTriangleLimits.current())
        return floatArrayOf(
            -bounds.centerX.toFloat(),
            -bounds.centerY.toFloat(),
            -bounds.minZ.toFloat(),
        )
    }

    /** Moves every vertex of a binary STL by [shift], in place; normals do not move. */
    private fun translateStl(bytes: ByteArray, shift: FloatArray): ByteArray {
        require(bytes.size >= STL_HEADER_BYTES) { "A Smart Infill volume is not a binary STL" }
        val triangles = ByteBuffer.wrap(bytes, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int
        require(84L + triangles.toLong() * STL_TRIANGLE_BYTES == bytes.size.toLong()) {
            "A Smart Infill volume has an invalid binary STL length"
        }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (triangle in 0 until triangles) {
            val base = 84 + triangle * STL_TRIANGLE_BYTES.toInt()
            for (vertex in 0 until 3) {
                val at = base + 12 + vertex * 12
                buffer.putFloat(at, buffer.getFloat(at) + shift[0])
                buffer.putFloat(at + 4, buffer.getFloat(at + 4) + shift[1])
                buffer.putFloat(at + 8, buffer.getFloat(at + 8) + shift[2])
            }
        }
        return bytes
    }

    private const val STL_HEADER_BYTES = 84L
    private const val STL_TRIANGLE_BYTES = 50L

    private fun write(directory: File, name: String, bytes: ByteArray): File {
        require(directory.exists() || directory.mkdirs()) {
            "Unable to create the Smart Infill handoff directory"
        }
        val target = File(directory, name)
        target.writeBytes(bytes)
        return target
    }
}
