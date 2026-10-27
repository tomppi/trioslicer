package com.tomppi.enderslicer.data

import com.tomppi.enderslicer.model.ModelPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The workspace descriptor's CAD half: the design is the document, the per-body display files
 * are the frame the last session drew, and the per-part mapping names the plate objects that
 * draw it.
 */
class WorkspaceCadStateTest {

    private fun workspace(): Pair<WorkspaceStateStore, File> {
        val root = Files.createTempDirectory("workspace-cad").toFile()
        File(root, "models").mkdirs()
        return WorkspaceStateStore(root) to root
    }

    private fun displayCache(root: File): File {
        val file = File(File(root, "models"), "solidcraft-bracket-display.stl")
        file.writeBytes(ByteArray(84))
        return file
    }

    private fun snapshot(root: File, cache: File, cad: WorkspaceStateStore.CadState?): WorkspaceStateStore.Snapshot =
        WorkspaceStateStore.Snapshot(
            modelPath = cache.absolutePath,
            modelDisplayName = "Bracket",
            placement = ModelPlacement(
                centerXmm = 100.0,
                centerYmm = 100.0,
                baseZmm = 0.0,
                source = "SolidCraft document coordinates",
            ),
            configurationFingerprint = "0".repeat(64),
            models = listOf(
                WorkspaceStateStore.Entry(
                    modelPath = cache.absolutePath,
                    modelDisplayName = "Bracket",
                    placement = ModelPlacement(
                        centerXmm = 100.0,
                        centerYmm = 100.0,
                        baseZmm = 0.0,
                        source = "SolidCraft document coordinates",
                    ),
                    id = "cad-object",
                ),
            ),
            cad = cad,
        )

    private fun cadState(root: File) = WorkspaceStateStore.CadState(
        name = "Bracket",
        designPath = File(root, "solidcraft/workspace/designs/bracket.solidcraft").absolutePath,
        sourcePath = "bracket.step",
        displayMeshPath = File(File(root, "models"), "solidcraft-bracket-display.stl").absolutePath,
        displayRevision = 12L,
        triangleCount = 1_480,
        bodyCount = 2,
        displayObjectId = "cad-object",
    )

    @Test
    fun aCadDocumentRoundTripsWithItsDesign() {
        val (store, root) = workspace()
        try {
            val cache = displayCache(root)
            val expected = cadState(root)
            store.save(snapshot(root, cache, expected))
            val loaded = store.load()!!
            assertEquals(expected, loaded.cad)
            assertEquals("Bracket", loaded.cad?.name)
            assertEquals(2, loaded.cad?.bodyCount)
            assertTrue(loaded.cad!!.designPath.endsWith("bracket.solidcraft"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aDescriptorWithoutACadDocumentStillLoadsAndItsCadIsNull() {
        val (store, root) = workspace()
        try {
            val cache = displayCache(root)
            store.save(snapshot(root, cache, null))
            assertNull(store.load()!!.cad)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aDesignOutsideTheAppsFilesDirectoryIsRefused() {
        val (store, root) = workspace()
        try {
            val cache = displayCache(root)
            val outside = cadState(root).copy(designPath = "/tmp/not-ours/bracket.solidcraft")
            assertThrows(IllegalArgumentException::class.java) {
                store.save(snapshot(root, cache, outside))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun theStaleFlagAndItsReasonSurviveARelaunch() {
        val (store, root) = workspace()
        try {
            val cache = displayCache(root)
            val stale = cadState(root).copy(
                derivedStale = true,
                staleReason = "the plate holds a split made from the previous shape",
            )
            store.save(snapshot(root, cache, stale))
            val loaded = store.load()!!.cad!!
            assertTrue(loaded.derivedStale)
            assertEquals(stale.staleReason, loaded.staleReason)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun everyPartAndTheConvertedPlateSurviveARelaunch() {
        val (store, root) = workspace()
        try {
            val cache = displayCache(root)
            val parts = listOf(
                WorkspaceStateStore.CadState.PartState("Case", "cad:Case"),
                WorkspaceStateStore.CadState.PartState("Lid", "cad:Lid"),
            )
            val convertedParts = listOf(
                WorkspaceStateStore.CadState.PartState("Case", "stl:Case"),
                WorkspaceStateStore.CadState.PartState("Lid", "stl:Lid"),
            )
            val state = cadState(root).copy(
                displayObjectId = null,
                parts = parts,
                converted = true,
                convertedRevision = 7L,
                convertedParts = convertedParts,
            )
            store.save(snapshot(root, cache, state))
            val loaded = store.load()!!.cad!!
            assertEquals(parts, loaded.parts)
            assertEquals(convertedParts, loaded.convertedParts)
            assertTrue(loaded.converted)
            assertEquals(7L, loaded.convertedRevision)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aDescriptorWrittenBeforePerPartObjectsStillLoadsItsSingleObject() {
        val (store, root) = workspace()
        try {
            val cache = displayCache(root)
            // The 1.9.6 record: no parts, one display object and its cache, the print export.
            val legacy = cadState(root)
            store.save(snapshot(root, cache, legacy))
            val loaded = store.load()!!.cad!!
            assertEquals("cad-object", loaded.displayObjectId)
            assertTrue(loaded.parts.isEmpty())
            assertTrue(loaded.convertedParts.isEmpty())
            assertFalse(loaded.converted)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun theSplitLayoutsObjectIdsSurviveARelaunch() {
        val (store, root) = workspace()
        try {
            val cache = displayCache(root)
            // The two halves a split made from the document: they are plate objects, not the
            // document's parts, so only this list can tell a restored plate they were derived.
            val split = cadState(root).copy(
                derivedStale = true,
                staleReason = "the plate holds a split made from the previous shape",
                derivedObjectIds = listOf("half-low", "half-high"),
            )
            store.save(snapshot(root, cache, split))
            val loaded = store.load()!!.cad!!
            assertEquals(listOf("half-low", "half-high"), loaded.derivedObjectIds)
            assertTrue(loaded.derivedStale)
        } finally {
            root.deleteRecursively()
        }
    }
}
