package com.tomppi.enderslicer.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snap fit's wiring, which its state cannot show by itself.
 *
 * Four of the defects the audits found are about which call a path makes, not about what the
 * state then holds: the placement history a replaced plate must forget, the scope a preview
 * job must live in, the line a refusal must reach, and the clearance the arranger must leave.
 * Each is pinned here against the function that owns it, so a later edit that drops the call
 * fails a test instead of quietly restoring the defect.
 */
class SnapWiringContractTest {
    private val viewModel = source("ui/MainViewModel.kt")

    /**
     * A split or a join replaces the objects the placement history was recorded against.
     * Undoing one of its steps would put a whole-model, pre-split placement onto a half, so
     * both paths clear it - and both go through [MainUiState.afterPlateReplaced] for the
     * flags the Undo control reads.
     */
    @Test
    fun aPlateASplitOrAJoinReplacedCannotBeUndoneOntoTheOldObject() {
        val split = body("fun splitModel(", "private fun cutPathNote")
        assertTrue("the split clears the placement history", split.contains("placementHistory.clear()"))
        assertTrue("and drops the undo flags with the session", split.contains("afterPlateReplaced()"))

        val apply = body("fun applySnapJoint(", "private fun middleOf(")
        assertTrue("a join clears it too", apply.contains("placementHistory.clear()"))
        assertTrue(apply.contains("afterPlateReplaced()"))
    }

    /**
     * A scope built as viewModelScope.coroutineContext + Job() REPLACES the view model's
     * job, so onCleared never cancels it. The preview job is a child of the view model's own
     * scope instead.
     */
    /**
     * Apply stages the pair back in its OWN frame - the assembled frame a split
     * leaves and every joint is measured in. Staging the previewed meshes as they
     * stand gave each half the packer's frame: their mating planes sat 15 and 0
     * apart, so the pair the tool had just made read as two parts side by side
     * ("the two parts lie side by side along Z, not across it"), and where the
     * planes do agree the halves are still tens of millimetres apart across the
     * seam, so the next joint's pocket cut nothing out of the mate.
     */
    @Test
    fun applyStagesThePairInItsOwnFrame() {
        val apply = body("fun applySnapJoint(", "private fun middleOf(")
        assertTrue("the staging is what puts both halves back in the pair's frame", apply.contains("SnapApply.stage("))
        assertTrue("from the halves that carry that frame", apply.contains("lowHalf = fitLow"))
        assertTrue(
            "and the pair records the plane the staged meshes carry",
            apply.contains("lowFaceMm = stagedPair.lowFaceMm"),
        )
        assertTrue(apply.contains("highFaceMm = stagedPair.highFaceMm"))
        assertTrue(
            "with the orientation each half already had",
            apply.contains("cutHalfObject(name, mesh, file, state, linear)"),
        )
    }

    @Test
    fun thePreviewJobIsAChildOfTheViewModelScope() {
        assertFalse(
            "a scope built with + Job() replaces the view model's own job",
            viewModel.contains("viewModelScope.coroutineContext + Job()"),
        )
        assertFalse(viewModel.contains("snapPreviewScope"))
        val preview = body("private fun previewSnapJoint(", "private suspend fun computeSnapPreview(")
        assertTrue(preview.contains("snapPreviewJob = viewModelScope.launch"))
    }

    /**
     * Apply's refusals used to write only the status line, which is the title of the plate's
     * folded notice card. The panel is open in front of the user while it runs, so every snap
     * refusal goes to the line the panel shows.
     */
    @Test
    fun everySnapRefusalReachesThePanelsOwnLine() {
        val apply = body("fun applySnapJoint(", "private fun middleOf(")
        assertTrue(apply.contains("showSnapFailure(IllegalStateException(reason))"))
        assertTrue(apply.contains("showSnapFailure(IllegalStateException(SNAP_PAIR_STALE_MESSAGE))"))
        assertTrue("and a failure thrown while applying reports there too", apply.contains("showSnapFailure(error)"))
        val preview = body("private fun previewSnapJoint(", "private suspend fun computeSnapPreview(")
        assertTrue("and a preview that throws reports there too", preview.contains("onFailure(::showSnapFailure)"))
    }

    /**
     * The app arranges its own plates, so arranged() has to pack for the app's own sequential
     * print check: with the user's gap alone, every plate of two or more objects that Arrange
     * had just made was refused by Slice as too close for the print head.
     */
    @Test
    fun theArrangerLeavesTheHeadClearanceWhenPrintingOneAtATime() {
        val arranged = body("private fun arranged(", "/** Arranges the plate on demand")
        assertTrue(arranged.contains("SequentialPrintCheck.headClearanceMm(settings)"))
        assertTrue(arranged.contains("neighborClearanceMm = if (platePreferences.sequential)"))
    }

    /**
     * The three paths that replace or empty the plate without rebuilding the state's snap
     * pair drop the session, because a previewed half is geometry for an object that just
     * left.
     */
    @Test
    fun theThreePlateReplacingPathsDropTheSnapSession() {
        assertTrue(
            "clearBuildPlate empties the plate",
            body("fun clearBuildPlate(", "/**\n     * Lays the plate out again").contains(".withoutSnap()"),
        )
        assertTrue(
            "arrangePlate replaces every object",
            body("fun arrangePlate(", "fun setPlatePreferences(").contains(".withoutSnap()"),
        )
        assertTrue(
            "removeModel takes an object off it",
            body("fun removeModel(", "fun splitModel(").contains(".withoutSnap()"),
        )
    }

    /** The descriptor writes the pair, and the restore puts it back on the plate. */
    @Test
    fun aRestoredDescriptorPutsItsPairBackOnThePlate() {
        val restore = body("private fun restoreWorkspace(", "private fun workspaceSnapshot(state:")
        assertTrue("the restore reads the saved pair", restore.contains("snapshot.snap"))
        assertTrue("and offers it on the plate it rebuilt", restore.contains(".withSnapPair("))

        val snapshot = body("private fun workspaceSnapshot(state:", "private fun MainUiState.toSnapshotSnap")
        assertTrue("which is the pair the descriptor saves", snapshot.contains("snap = state.toSnapshotSnap()"))
    }

    /** The text between [from] and the next [to], with both anchors checked. */
    private fun body(from: String, to: String): String {
        val start = viewModel.indexOf(from)
        assertTrue("MainViewModel.kt no longer holds " + from, start >= 0)
        val end = viewModel.indexOf(to, start + from.length)
        assertTrue("MainViewModel.kt no longer holds " + to + " after " + from, end > start)
        return viewModel.substring(start, end)
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/tomppi/enderslicer/$relative"),
            File("app/src/main/java/com/tomppi/enderslicer/$relative"),
        )
        val file = candidates.firstOrNull(File::isFile)
            ?: error("Unable to locate source file for $relative from " + File(".").absolutePath)
        return file.readText()
    }
}
