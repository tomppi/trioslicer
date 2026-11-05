package com.tomppi.enderslicer.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The view model's wiring, which its state cannot show by itself.
 *
 * Four of the defects the audit found are about which call a path makes and which fields a
 * builder writes: the extra-settings mutators that never invalidated the published slice, the
 * rebuild and connect publishes that never persisted the descriptor, the ScanRuler enter run
 * that wrote the fresh run's state after being cancelled, and the import pre-save that omitted
 * the pair record and the CAD document. Each is pinned against the function that owns it, so a
 * later edit that drops the call fails a test instead of quietly restoring the defect.
 */
class MainViewModelWiringContractTest {
    private val viewModel = source("ui/MainViewModel.kt")

    /**
     * The catalogue extras are slice inputs for every engine, so a change drops the published
     * G-code like every other settings mutator - the message is the same one the rest use.
     */
    @Test
    fun anExtraSettingsChangeInvalidatesThePublishedSlice() {
        val invalidated = "withoutPublishedSlice(\"Settings changed; slice again to export G-code\")"
        val add = body("fun setExtraSetting(engine: SlicerEngine, key: String, value: String)", "private val curaExtraSpecs")
        assertTrue("adding one drops the published slice", add.contains(invalidated))
        val remove = body("fun removeExtraSetting(engine: SlicerEngine, key: String)", "The stored sidebar for an engine")
        assertTrue("and removing one does too", remove.contains(invalidated))
    }

    /**
     * Rebuild plate is the deliberate way to take the document's new shape, and the connect
     * republish rewrites the plate's parts: both leave the descriptor naming what the plate
     * no longer holds unless they write it, so a relaunch silently restores the stale layout.
     */
    @Test
    fun aRebuiltPlateAndAConnectRepublishPersistTheWorkspace() {
        val rebuild = body("fun rebuildCadPlateFromDocument(", "fun openCadDocument(")
        assertTrue("the rebuild publishes the document's parts", rebuild.contains("publishCadParts("))
        assertTrue(
            "and writes the descriptor a relaunch restores",
            rebuild.indexOf("scheduleCadPersist(force = true)") > rebuild.indexOf("publishCadParts("),
        )

        val connect = body("fun connectCadAgent(", "fun stopCadAgent(")
        assertTrue("the connect republish publishes too", connect.contains("publishCadParts("))
        assertTrue(
            "and persists after it, like the other publish sites",
            connect.indexOf("scheduleCadPersist(force = true)") > connect.indexOf("publishCadParts("),
        )
    }

    /**
     * A superseded enter run is still draining its cancellation when the fresh run has set the
     * loading flag and cleared the failure text; its late writes may not touch either.
     */
    @Test
    fun aSupersededScanRulerEnterCannotOverwriteTheFreshRun() {
        val enter = body("private fun enterScanRulerView(", "private fun newScanRulerSession(")
        assertTrue(enter.contains("val enter = ++scanRulerEnterGeneration"))
        assertEquals(
            "the empty-plate failure text and the finally's loading clear are both guarded",
            2,
            Regex("enter == scanRulerEnterGeneration").findAll(enter).count(),
        )
        assertTrue(
            "the finally clears the loading flag only for the run that is current",
            Regex("finally \\{\\s*if \\(enter == scanRulerEnterGeneration\\)").containsMatchIn(enter),
        )
        assertEquals(
            "and every catch reports through the guarded write with its own generation",
            3,
            Regex("showScanRulerFailure\\(\\s*enter,").findAll(enter).count(),
        )
        val failure = body("private fun showScanRulerFailure(", "fun selectModel(")
        assertTrue(
            "the failure write itself refuses a stale run",
            failure.contains("if (enter != scanRulerEnterGeneration) return"),
        )
    }

    /**
     * The import's pre-save is the only descriptor until the state update lands: a crash in
     * that window must not lose the pair record or the CAD document, so it carries them the
     * same way the plate's own descriptor does.
     */
    @Test
    fun theImportPreSaveCarriesThePairRecordAndTheCadDocument() {
        val preSave = body("private fun workspaceSnapshot(\n        source: StlMesh,", "private fun PlateObject.toWorkspaceEntry")
        assertTrue("the pair record travels with the new model", preSave.contains("snap = state.toSnapshotSnap()"))
        assertTrue("and the CAD document with it", preSave.contains("cad = state.toSnapshotCad()"))

        val plate = body("private fun workspaceSnapshot(state: MainUiState)", "The descriptor for an import")
        assertTrue("both descriptors write the same pair", plate.contains("snap = state.toSnapshotSnap()"))
        assertTrue("and the same document", plate.contains("cad = state.toSnapshotCad()"))
    }

    private fun body(from: String, to: String): String {
        val start = viewModel.indexOf(from)
        assertTrue("MainViewModel.kt no longer holds " + from, start >= 0)
        val end = viewModel.indexOf(to, start + from.length)
        assertTrue("MainViewModel.kt no longer holds " + to + " after " + from, end > start)
        return viewModel.substring(start, end)
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/tomppi/enderslicer/" + relative),
            File("app/src/main/java/com/tomppi/enderslicer/" + relative),
        )
        val file = candidates.firstOrNull(File::isFile)
            ?: error("Unable to locate source file for " + relative + " from " + File(".").absolutePath)
        return file.readText()
    }
}
