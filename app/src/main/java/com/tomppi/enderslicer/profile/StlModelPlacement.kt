package com.tomppi.enderslicer.profile

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

internal object StlModelPlacement {
    private const val MAX_FILE_BYTES = 160 * 1024 * 1024
    private const val MAX_TRIANGLES = 1_500_000

    fun buildPlateOffsetMm(modelFile: File): Double {
        val minimumZ = minimumZMm(modelFile)
        return if (abs(minimumZ) < 1e-7) 0.0 else -minimumZ
    }

    fun minimumZMm(modelFile: File): Double {
        require(modelFile.isFile && modelFile.length() > 0L) {
            "Resolved Cura STL is missing or empty: ${modelFile.absolutePath}"
        }
        require(modelFile.length() <= MAX_FILE_BYTES) {
            "Resolved Cura STL is larger than ${MAX_FILE_BYTES / 1024 / 1024} MiB"
        }

        val bytes = modelFile.readBytes()
        val minimumZ = if (looksLikeBinary(bytes)) {
            binaryMinimumZ(bytes)
        } else {
            asciiMinimumZ(bytes.toString(Charsets.UTF_8))
        }
        require(minimumZ.isFinite()) { "Unable to calculate the STL minimum Z coordinate" }
        return minimumZ
    }

    private fun looksLikeBinary(bytes: ByteArray): Boolean {
        if (bytes.size < 84) return false
        val triangleCount = ByteBuffer.wrap(bytes, 80, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int
            .toLong() and 0xffffffffL
        if (triangleCount < 1L) return false
        val expectedSize = 84L + triangleCount * 50L
        return expectedSize == bytes.size.toLong()
    }

    private fun binaryMinimumZ(bytes: ByteArray): Double {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(80)
        val triangleCount = buffer.int.toLong() and 0xffffffffL
        require(triangleCount in 1L..MAX_TRIANGLES.toLong()) {
            "Invalid binary STL triangle count: $triangleCount"
        }

        var minimumZ = Double.POSITIVE_INFINITY
        repeat(triangleCount.toInt()) {
            buffer.position(buffer.position() + 12) // facet normal
            repeat(3) {
                val x = buffer.float
                val y = buffer.float
                val z = buffer.float
                require(x.isFinite() && y.isFinite() && z.isFinite()) {
                    "STL contains non-finite coordinates"
                }
                minimumZ = minOf(minimumZ, z.toDouble())
            }
            buffer.short // attribute byte count
        }
        return minimumZ
    }

    private fun asciiMinimumZ(text: String): Double {
        var minimumZ = Double.POSITIVE_INFINITY
        var vertexFound = false
        text.lineSequence().forEach { rawLine ->
            val tokens = rawLine.trim().split(Regex("\\s+"))
            if (tokens.size >= 4 && tokens[0].equals("vertex", ignoreCase = true)) {
                val z = tokens[3].toDoubleOrNull() ?: error("Invalid ASCII STL vertex Z coordinate")
                require(z.isFinite()) { "STL contains a non-finite Z coordinate" }
                minimumZ = minOf(minimumZ, z)
                vertexFound = true
            }
        }
        require(vertexFound) { "No vertices were found in the ASCII STL" }
        return minimumZ
    }
}
