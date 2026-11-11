package com.tomppi.enderslicer.splat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The splat library's names and listing rules, where no Android context is needed. */
class SplatLibraryTest {

    @Test
    fun theFormatsMatchTheRenderer() {
        for (extension in listOf("sog", "ply", "spz", "splat", "ksplat")) {
            assertTrue(extension, SplatLibrary.isSupported("scene." + extension))
            assertTrue(extension, SplatLibrary.isSupported("SCENE." + extension.uppercase()))
        }
        assertFalse(SplatLibrary.isSupported("scene.step"))
        assertFalse(SplatLibrary.isSupported("scene"))
    }

    @Test
    fun anImportedNameStaysInsideTheFolder() {
        assertEquals("scene.sog", SplatLibrary.safeName("scene.sog"))
        assertEquals("scene.ply", SplatLibrary.safeName("../../scene.ply"))
        assertEquals("scene.sog", SplatLibrary.safeName("/tmp/a/b/scene.sog"))
        assertEquals("my_scene.sog", SplatLibrary.safeName("my scene.sog"))
        assertNull("a traversal with no name left is refused", SplatLibrary.safeName("../.."))
        assertNull("an unsupported extension is refused", SplatLibrary.safeName("scene.step"))
        assertNull("empty is refused", SplatLibrary.safeName("   "))
        assertNull(SplatLibrary.safeName(null))
    }

    @Test
    fun theLongestNameIsBounded() {
        val long = "a".repeat(500) + ".sog"
        assertEquals(120, SplatLibrary.safeName(long)!!.length)
    }

    @Test
    fun anExistingNameIsNotOverwritten() {
        val folder = File.createTempFile("splats", "test").parentFile!!.let {
            File(it, "splat-library-test-" + System.nanoTime()).apply { mkdirs() }
        }
        try {
            assertFalse(File(folder, "scene.sog").exists())
            val first = SplatLibrary.uniqueFile(folder, "scene.sog")
            assertEquals("scene.sog", first.name)
            first.writeText("x")
            assertEquals("scene-1.sog", SplatLibrary.uniqueFile(folder, "scene.sog").name)
            File(folder, "scene-1.sog").writeText("y")
            assertEquals("scene-2.sog", SplatLibrary.uniqueFile(folder, "scene.sog").name)
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun theToolbarTitleDropsTheExtension() {
        assertEquals("reindeer", SplatLibrary.titleOf(File("reindeer.sog")))
        assertEquals("no extension", SplatLibrary.titleOf(File("no extension")))
    }
}
