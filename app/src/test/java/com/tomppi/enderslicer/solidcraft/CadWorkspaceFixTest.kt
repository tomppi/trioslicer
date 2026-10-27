package com.tomppi.enderslicer.solidcraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The CAD workspace's cross-cutting fixes: a new design never overwrites an existing one, two
 * bodies whose readable file stems collide still get their own files, and the plate actions have
 * the sentences the audit asked for.
 */
class CadWorkspaceFixTest {

    private fun tempWorkspace(): Pair<CadWorkspace, File> {
        val root = Files.createTempDirectory("cad-workspace-fix").toFile()
        return CadWorkspace(root).ensureDirectories() to root
    }

    @Test
    fun aNewDesignGetsAFreeNameInsteadOfOverwritingTheExistingDesign() {
        val (workspace, root) = tempWorkspace()
        try {
            // The engine's default display name is the same for every new design.
            val first = workspace.uniqueDesignFile("Untitled")
            first.writeText("first design")
            val second = workspace.uniqueDesignFile("Untitled")
            assertEquals("untitled-2.solidcraft", second.name)
            assertNotEquals(first.absolutePath, second.absolutePath)
            // A design exists on disk as soon as it is created: New design saves immediately, so
            // the next call must see this one before it can pick a free name.
            second.writeText("second design")
            val third = workspace.uniqueDesignFile("Untitled")
            assertEquals("untitled-3.solidcraft", third.name)
            // The existing design is still on disk, untouched - the data-loss case.
            assertEquals("first design", first.readText())
            // A free name is used as it is.
            assertEquals("lid.solidcraft", workspace.uniqueDesignFile("Lid").name)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun bodiesWhoseSlugsCollideGetDistinctDisplayAndConvertedFiles() {
        val (workspace, root) = tempWorkspace()
        try {
            // The premise: the readable slug is not injective...
            assertEquals(CadWorkspace.slug("Body 1"), CadWorkspace.slug("Body.1"))
            assertEquals(CadWorkspace.slug("Body 1"), CadWorkspace.slug("Body/1"))
            // ...but the per-body file names are: the raw name's hash keeps them apart.
            val spaced = workspace.bodyFile("doc", "Body 1")
            val dotted = workspace.bodyFile("doc", "Body.1")
            val slashed = workspace.bodyFile("doc", "Body/1")
            assertNotEquals(spaced.name, dotted.name)
            assertNotEquals(spaced.name, slashed.name)
            assertNotEquals(dotted.name, slashed.name)
            assertNotEquals(
                workspace.convertedFile("doc", "Body 1").name,
                workspace.convertedFile("doc", "Body.1").name,
            )
            assertTrue(spaced.parentFile?.name == "display")
            assertTrue(workspace.convertedFile("doc", "Body 1").parentFile?.name == "converted")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun convertRefusesASplitLayoutByTheAuditSentence() {
        assertEquals(
            "the plate holds a split made from this document; press Rebuild plate before converting",
            CadConvert.DERIVED_LAYOUT_REFUSAL,
        )
        assertEquals(null, CadConvert.refusalFor(emptySet()))
        assertEquals(
            CadConvert.DERIVED_LAYOUT_REFUSAL,
            CadConvert.refusalFor(setOf("half-low", "half-high")),
        )
    }

    @Test
    fun aDeletedDesignIsNamedWhenTheConvertedPlateNeedsIt() {
        assertEquals(
            "the design file was deleted; the converted models are on the plate",
            CadDesignLibrary.missingDesignSentence(converted = true),
        )
        assertTrue(
            CadDesignLibrary.missingDesignSentence(converted = false).contains("design file is missing"),
        )
    }
}
