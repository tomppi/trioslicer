package com.tomppi.enderslicer.data

import com.tomppi.enderslicer.model.ModelPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The workspace descriptor's CAD half: the design is the document, the display cache is the
 * frame the last session drew, and the print export is recorded so a later Slice can say
 * honestly whether it reused one.
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
        sliceStlPath = File(root, "solidcraft/workspace/exports/bracket-print.stl").absolutePath,
        sliceExportRevision = 11L,
        sliceExportAtMillis = 1_000L,
        sliceTriangles = 1_500,
    )

    @Test
    fun aCadDocumentRoundTripsWithItsDesignAndPrintExport() {
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
}
