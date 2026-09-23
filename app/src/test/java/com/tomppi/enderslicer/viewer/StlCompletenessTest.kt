package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Telling a whole STL from one that is still being written.
 *
 * The handoff imports models the embedded Blender engine drops into its export
 * directory, and it used to decide "finished" by watching the size hold still.
 * A writer that pauses mid-file defeats that, and the app then imports a mesh
 * cut off part-way through a triangle - which looks like a perfectly ordinary
 * smaller mesh to everything downstream.
 */
class StlCompletenessTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun binaryStl(triangles: Int, declared: Int? = null, extraBytes: Int = 0): File {
        val body = ByteArray(triangles * 50)
        val header = ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN)
        header.put(ByteArray(80))
        header.putInt(declared ?: triangles)
        val file = folder.newFile()
        file.outputStream().use {
            it.write(header.array())
            it.write(body)
            if (extraBytes > 0) it.write(ByteArray(extraBytes))
        }
        return file
    }

    private fun ascii(terminated: Boolean): File {
        val file = folder.newFile()
        val facet = "facet normal 0 0 1\n  outer loop\n    vertex 0 0 0\n    vertex 1 0 0\n    vertex 0 1 0\n  endloop\nendfacet\n"
        file.writeText("solid test\n$facet" + if (terminated) "endsolid test\n" else "")
        return file
    }

    @Test
    fun aBinaryStlWhoseLengthMatchesItsTriangleCountIsWhole() {
        assertTrue(StlParser.isComplete(binaryStl(triangles = 12)))
    }

    @Test
    fun aBinaryStlCutShortMidTriangleIsNotWhole() {
        // What the poller actually saw: the header promising more triangles
        // than the file yet contained.
        assertFalse(StlParser.isComplete(binaryStl(triangles = 12, extraBytes = -0, declared = 400)))
    }

    @Test
    fun aBinaryStlWithTrailingBytesIsNotWhole() {
        assertFalse(StlParser.isComplete(binaryStl(triangles = 12, extraBytes = 7)))
    }

    @Test
    fun anAsciiStlIsWholeOnlyOnceItEnds() {
        assertTrue(StlParser.isComplete(ascii(terminated = true)))
        assertFalse(StlParser.isComplete(ascii(terminated = false)))
    }

    @Test
    fun aFileTooShortToBeAnStlIsNotWhole() {
        val tiny = folder.newFile().apply { writeText("solid") }
        assertFalse(StlParser.isComplete(tiny))
    }

    @Test
    fun aMissingFileIsNotWhole() {
        assertFalse(StlParser.isComplete(File(folder.root, "not-here.stl")))
    }

    @Test
    fun anEmptyFileIsNotWhole() {
        assertFalse(StlParser.isComplete(folder.newFile()))
    }
}
