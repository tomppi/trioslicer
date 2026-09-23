package com.tomppi.enderslicer.viewer

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StlParserLimitTest {
    @Test
    fun exactBinaryLayoutIsDetectedWithoutLoadingWholeFile() {
        val file = createBinaryShell(3)
        try {
            assertEquals(3L, StlParser.binaryTriangleCount(file))
        } finally {
            file.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun binaryModelAboveSelectedLimitIsRejectedBeforeMeshAllocation() {
        val file = createBinaryShell(100_001)
        try {
            val error = try {
                StlParser.parse(file, "too-dense.stl", maxTriangles = 100_000)
                fail("Expected the selected triangle limit to reject the STL")
                error("unreachable")
            } catch (expected: IllegalArgumentException) {
                expected
            }
            assertTrue(error.message.orEmpty().contains("current limit"))
        } finally {
            file.parentFile?.deleteRecursively()
        }
    }

    private fun createBinaryShell(triangles: Int): File {
        val directory = createTempDirectory("stl-limit-test").toFile()
        val file = File(directory, "model.stl")
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(84L + triangles.toLong() * 50L)
            output.seek(80L)
            output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(triangles).array())
        }
        return file
    }
}
