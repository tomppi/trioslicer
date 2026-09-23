package com.tomppi.enderslicer.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Blender handoff copies the loaded model where the embedded engine can open
 * it. Two properties matter: the model lands byte-identical under the name the
 * Blender side reads, and a second handoff replaces the first instead of
 * leaving a stale model behind.
 */
class BlenderModelHandoffTest {

    private fun tempRoot(): File = Files.createTempDirectory("blender-handoff").toFile()

    private fun sourceFile(dir: File, name: String, payload: ByteArray): File =
        File(dir, name).apply { writeBytes(payload) }

    @Test
    fun publishesModelAndSidecar() {
        val root = tempRoot()
        val sourceDir = tempRoot()
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val source = sourceFile(sourceDir, "benchy.stl", payload)

        val target = BlenderModelHandoff.publish(root, source)

        assertEquals(File(BlenderModelHandoff.importsDir(root), "current.stl"), target)
        assertTrue("published model exists", target.isFile)
        assertArrayEquals(payload, target.readBytes())

        val info = File(BlenderModelHandoff.importsDir(root), "current.json").readText()
        assertTrue("sidecar names the source", info.contains("\"sourceName\":\"benchy.stl\""))
        assertTrue("sidecar carries the size", info.contains("\"bytes\":5"))
        assertTrue("sidecar names the published file", info.contains("\"model\":\"current.stl\""))
    }

    @Test
    fun republishingReplacesThePreviousModel() {
        val root = tempRoot()
        val sourceDir = tempRoot()
        val first = sourceFile(sourceDir, "first.stl", byteArrayOf(9, 9, 9))
        val second = sourceFile(sourceDir, "second.stl", byteArrayOf(7, 7))

        BlenderModelHandoff.publish(root, first)
        val target = BlenderModelHandoff.publish(root, second)

        assertArrayEquals(byteArrayOf(7, 7), target.readBytes())
        val info = File(BlenderModelHandoff.importsDir(root), "current.json").readText()
        assertTrue("sidecar tracks the newest source", info.contains("second.stl"))
        assertTrue("no staging file left behind", !File(target.parentFile, "current.stl.part").exists())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnEmptyModel() {
        val root = tempRoot()
        val empty = File(tempRoot(), "empty.stl").apply { writeBytes(ByteArray(0)) }
        BlenderModelHandoff.publish(root, empty)
    }

    private fun assertArrayEquals(expected: ByteArray, actual: ByteArray) =
        assertEquals(expected.toList(), actual.toList())
}
