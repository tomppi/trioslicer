package com.tomppi.enderslicer.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModellingPreviewExportSignatureTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun isEmptyWhenTheEngineHasProducedNothing() {
        assertEquals("", blenderExportSignature(folder.root))
        assertEquals("", blenderExportSignature(File(folder.root, "exports")))
    }

    @Test
    fun ignoresEverythingThatIsNotAnStl() {
        val exports = folder.root.resolve("exports").apply { mkdirs() }
        File(exports, "preview.png").writeText("png")
        File(exports, "scene.blend").writeText("blend")

        assertEquals("", blenderExportSignature(folder.root))
    }

    @Test
    fun namesTheNewestStlWithItsLengthAndTime() {
        val exports = folder.root.resolve("exports").apply { mkdirs() }
        val older = File(exports, "look-a.stl").apply { writeText("aaa"); setLastModified(1_000L) }
        val newer = File(exports, "section.stl").apply { writeText("bbbbbb"); setLastModified(2_000L) }

        val signature = blenderExportSignature(folder.root)

        assertTrue(signature.startsWith("section.stl|6|"))
        assertNotEquals(older.name + "|3|1000", signature)
        assertTrue(newer.lastModified() > older.lastModified())
    }

    @Test
    fun changesWhenTheEngineRewritesTheSameExport() {
        val exports = folder.root.resolve("exports").apply { mkdirs() }
        val export = File(exports, "model.stl").apply { writeText("aa"); setLastModified(1_000L) }
        val before = blenderExportSignature(folder.root)

        export.writeText("aaaaaaaa")
        export.setLastModified(3_000L)
        val after = blenderExportSignature(folder.root)

        assertNotEquals("a rewritten export must be seen as a new revision", before, after)
        assertTrue(after.startsWith("model.stl|8|"))
    }
}
