package com.tomppi.enderslicer.viewer

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

object StlMeshWriter {
    data class ResolvedSliceSource(
        val modelFile: File,
        val transform: StlSliceTransform,
    )

    /**
     * Writes the displayed mesh exactly as before for standalone/fallback slices.
     * When ModelPlacement retained original geometry, a second internal STL and
     * affine sidecar are staged for resolved Cura slicing. This lets CuraEngine
     * transform the original STL directly instead of slicing vertices that were
     * already rounded after placement.
     */
    fun writeBinary(mesh: StlMesh, destination: File) {
        validateVertices(mesh.interleavedVertices, mesh.triangleCount)
        writeBinaryVertices(mesh.interleavedVertices, mesh.triangleCount, destination)

        val sourceVertices = mesh.slicingSourceInterleavedVertices
        val transform = mesh.slicingTransform
        val sourceFile = sourceFileFor(destination)
        val transformFile = transformFileFor(destination)
        sourceFile.delete()
        transformFile.delete()

        if (sourceVertices != null && transform != null) {
            validateVertices(sourceVertices, mesh.triangleCount)
            writeBinaryVertices(sourceVertices, mesh.triangleCount, sourceFile)
            transformFile.writeText(
                JSONObject()
                    .put("version", 1)
                    .put("linear", JSONArray(transform.linear))
                    .put("translationXmm", transform.translationXmm)
                    .put("translationYmm", transform.translationYmm)
                    .put("translationZmm", transform.translationZmm)
                    .toString(),
            )
            check(transformFile.isFile && transformFile.length() > 0L) {
                "Unable to write the original-model slice transform"
            }
        }
    }

    fun resolvedSliceSource(stagedDisplayedFile: File): ResolvedSliceSource? {
        val sourceFile = sourceFileFor(stagedDisplayedFile)
        val transformFile = transformFileFor(stagedDisplayedFile)
        if (!sourceFile.isFile || sourceFile.length() < STL_HEADER_BYTES || !transformFile.isFile) return null

        val root = JSONObject(transformFile.readText())
        require(root.getInt("version") == 1) { "Unsupported staged STL transform version" }
        val values = root.getJSONArray("linear")
        require(values.length() == 9) { "Staged STL transform must contain nine linear values" }
        val transform = StlSliceTransform(
            linear = List(9) { index -> values.getDouble(index) },
            translationXmm = root.getDouble("translationXmm"),
            translationYmm = root.getDouble("translationYmm"),
            translationZmm = root.getDouble("translationZmm"),
        )
        return ResolvedSliceSource(sourceFile, transform)
    }

    private fun writeBinaryVertices(vertices: VertexData, triangleCount: Int, destination: File) {
        destination.parentFile?.mkdirs()
        FileOutputStream(destination).channel.use { channel ->
            val buffer = ByteBuffer.allocateDirect(WRITE_BUFFER_BYTES).order(ByteOrder.LITTLE_ENDIAN)

            fun flush() {
                buffer.flip()
                writeFully(channel, buffer)
                buffer.clear()
            }

            fun requireBytes(byteCount: Int) {
                if (buffer.remaining() < byteCount) flush()
            }

            requireBytes(80 + Int.SIZE_BYTES)
            repeat(80) { buffer.put(0.toByte()) }
            buffer.putInt(triangleCount)

            var index = 0
            repeat(triangleCount) {
                requireBytes(STL_TRIANGLE_BYTES.toInt())
                buffer.putFloat(vertices[index + 3])
                buffer.putFloat(vertices[index + 4])
                buffer.putFloat(vertices[index + 5])
                repeat(3) { vertex ->
                    val base = index + vertex * 6
                    buffer.putFloat(vertices[base])
                    buffer.putFloat(vertices[base + 1])
                    buffer.putFloat(vertices[base + 2])
                }
                buffer.putShort(0.toShort())
                index += 18
            }
            if (buffer.position() > 0) flush()
            channel.force(true)
        }
        val expectedBytes = STL_HEADER_BYTES + triangleCount.toLong() * STL_TRIANGLE_BYTES
        check(destination.isFile && destination.length() == expectedBytes) {
            "Unable to write the staged STL"
        }
    }

    private fun writeFully(channel: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            check(channel.write(buffer) > 0) { "Unable to write the staged STL" }
        }
    }

    private fun validateVertices(vertices: VertexData, triangleCount: Int) {
        require(triangleCount > 0) { "Cannot write an empty STL mesh" }
        require(vertices.size == Math.multiplyExact(triangleCount, 18)) {
            "STL mesh vertex data does not match its triangle count"
        }
        for (index in 0 until vertices.size) {
            require(vertices[index].isFinite()) { "STL mesh contains a non-finite value" }
        }
    }

    private fun sourceFileFor(destination: File): File = File(
        destination.parentFile,
        "${destination.nameWithoutExtension}.slice-source.stl",
    )

    private fun transformFileFor(destination: File): File = File(
        destination.parentFile,
        "${destination.nameWithoutExtension}.slice-transform.json",
    )

    private const val WRITE_BUFFER_BYTES = 64 * 1024
    private const val STL_HEADER_BYTES = 84L
    private const val STL_TRIANGLE_BYTES = 50L
}
