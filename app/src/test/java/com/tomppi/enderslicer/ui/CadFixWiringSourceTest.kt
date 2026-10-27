package com.tomppi.enderslicer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The CAD-document fix round, as source contracts on the one ViewModel and one screen that own
 * it. Each test names the audit item it pins; behaviour is covered by the unit tests beside the
 * pure helpers (CadPersistSchedulerTest, CadWorkspaceFixTest, CadPartPublishTest, the state
 * round-trip test).
 */
class CadFixWiringSourceTest {

    private val main = File("src/main/java")

    private fun source(relative: String): String {
        val file = File(main, relative)
        assertTrue("missing " + file.path, file.isFile)
        return file.readText()
    }

    private fun viewModel(): String = source("com/tomppi/enderslicer/ui/MainViewModel.kt")
    private fun app(): String = source("com/tomppi/enderslicer/ui/EnderSlicerApp.kt")

    private fun body(from: String, until: String): String {
        val text = viewModel()
        val start = text.indexOf(from)
        assertTrue("MainViewModel no longer holds " + from, start >= 0)
        val end = text.indexOf(until, start + from.length)
        assertTrue("MainViewModel no longer holds " + until + " after " + from, end > start)
        return text.substring(start, end)
    }

    @Test
    fun thePersistenceWriterIsOneSelfClearingLoop() {
        val vm = viewModel()
        assertFalse("the field that was never cleared is gone", vm.contains("cadPersistJob"))
        assertFalse("the timestamp the old throttle kept is gone", vm.contains("cadLastPersistAt"))
        assertTrue("the ViewModel uses the single writer", vm.contains("CadPersistScheduler"))
        assertTrue(
            "every repaint and every forced save goes through it",
            vm.contains("cadPersistScheduler.schedule(force)"),
        )
    }

    @Test
    fun newDesignAndOpenNeverOverwriteAnExistingDesignFile() {
        val newDesign = body("fun newCadDesign()", "fun saveCadDesign()")
        assertTrue(
            "New design asks the workspace for a free name",
            newDesign.contains("workspace.uniqueDesignFile(cadDesignName(session))"),
        )
        val open = body("fun openCadDocument(uri: Uri)", "fun exportCadDocument(")
        assertTrue("Open asks the workspace for a free name", open.contains("workspace.uniqueDesignFile(baseName)"))
        assertFalse("Open no longer writes straight to designFile(name)", open.contains("workspace.designFile(name)"))
    }

    @Test
    fun connectRespectsAConvertedOrSplitPlate() {
        val connect = body("fun connectCadAgent()", "fun stopCadAgent()")
        assertTrue("Connect only republishes a live document", connect.contains("!existing.converted"))
        assertTrue(
            "and only when no derived layout is on the plate",
            connect.contains("!derivedLayoutOnPlate(existing)"),
        )
        assertTrue(
            "a derived layout is marked stale with the Rebuild sentence",
            connect.contains("Press Rebuild plate in the CAD agent sheet"),
        )
        assertTrue(
            "a deleted design is named instead of overwritten or silently skipped",
            connect.contains("CadDesignLibrary.missingDesignSentence"),
        )
    }

    @Test
    fun convertRefusesADerivedLayoutAndClearsThePlacementUndo() {
        val convert = body("fun convertCadToStl()", "fun newCadDesign()")
        assertTrue(
            "the refusal is CadConvert's sentence, not an inline copy",
            convert.contains("CadConvert.refusalFor(derived)"),
        )
        assertTrue(
            "the placement history recorded against the old objects is dropped",
            convert.contains("placementHistory.clear()"),
        )
        assertTrue(
            "the Undo row is dropped with the objects it belonged to",
            convert.contains(".afterPlateReplaced()"),
        )
        assertTrue(
            "a converted plate whose design was deleted says so by name",
            convert.contains("CadDesignLibrary.missingDesignSentence(document.converted)"),
        )
    }

    @Test
    fun aDerivedLayoutIsPersistedAndTreatedLikeALiveOneAfterRestore() {
        val state = File(main, "com/tomppi/enderslicer/data/WorkspaceStateStore.kt").readText()
        assertTrue("the descriptor carries the derived object ids", state.contains("val derivedObjectIds: List<String>"))
        assertTrue("and writes them", state.contains("\"derivedObjectIds\""))
        val vm = viewModel()
        assertTrue("the snapshot saves the tracked ids", vm.contains("derivedObjectIds = cadDerivedPlateIds.toList()"))
        assertTrue(
            "the restore seeds the tracked ids from the descriptor",
            vm.contains("snapshot.cad?.derivedObjectIds.orEmpty().forEach"),
        )
        assertTrue(
            "the repaint guard reads the persisted stale flag too",
            body("private suspend fun repaintCad(delta: DocumentDelta)", "markCadUpdating(true)").contains(
                "derivedLayoutOnPlate(previous)",
            ),
        )
        assertTrue(
            "Apply's jointed objects join the derived set Rebuild filters",
            vm.contains("cadDerivedPlateIds.addAll(listOf(lowObject.id, highObject.id))"),
        )
        assertTrue(
            "Rebuild reports the objects it actually drops",
            body("fun rebuildCadPlateFromDocument()", "fun openCadDocument(").contains(
                "models.count { it.id in cadDerivedPlateIds }",
            ),
        )
    }

    @Test
    fun aDocumentSwitchResetsTheConversationTheAgentAndTheQuestion() {
        val vm = viewModel()
        val helper = body("private fun resetInAppConversationForDocumentSwitch()", "/** Bumped when a CAD turn ends")
        assertTrue("the transcript is cleared", helper.contains("cadConversations.copy(inApp = emptyList())"))
        assertTrue("the agent's remembered turns are reset", helper.contains("inAppCadAgent?.reset()"))
        assertTrue("a pending question cannot outlive its document", helper.contains("clearPendingQuestion()"))
        for (action in listOf(
            "fun newCadDesign()" to "fun saveCadDesign()",
            "fun loadCadDesign(designPath: String)" to "private data class PreparedCadOpen",
            "fun openCadDocument(uri: Uri)" to "fun exportCadDocument(",
        )) {
            assertTrue(
                action.first + " resets the previous document's conversation",
                body(action.first, action.second).contains("resetInAppConversationForDocumentSwitch()"),
            )
        }
        val view = app()
        // The only remaining clear in the screen is the endpoint change (a different agent);
        // document switches are the ViewModel's, so they also reset the agent and announce it.
        assertEquals(
            "document switches are not cleared by the screen any more",
            1,
            Regex("cadConversations\\.copy\\(inApp = emptyList\\(\\)\\)").findAll(view).count(),
        )
    }

    @Test
    fun aFinishedActivityEndsTheTurnButNotTheEngine() {
        val cleared = body("override fun onCleared()", "fun startBlenderEngine()")
        assertTrue("the blocking agent loop is told to stop", cleared.contains("inAppCadAgent?.cancel()"))
        assertTrue("the foreground keeper ends with it", cleared.contains("InAppTurnKeeper.end(app)"))
        assertTrue("the authorship lock is released", cleared.contains("endInAppTurn()"))
        assertFalse("the engine is not stopped by a finished Activity", cleared.contains("SolidCraftEngine.stop()"))
    }

    @Test
    fun theAgentRendersTheCurrentPlateView() {
        val vm = viewModel()
        assertTrue("the view lives with the turn's owner", vm.contains("var cadPlateView: ModelSurfaceView?"))
        val view = app()
        assertTrue(
            "the screen publishes the view it creates",
            view.contains("viewModel.cadPlateView = view"),
        )
        assertTrue(
            "the turn looks the view up per call",
            view.contains("platePicture(viewModel.cadPlateView)"),
        )
        assertFalse(
            "a view captured when the turn started is the rotation bug",
            view.contains("val captureView = plateView"),
        )
    }

    @Test
    fun aLegacyWholeDocumentObjectIsDroppedByConvert() {
        val convert = body("fun convertCadToStl()", "fun newCadDesign()")
        assertTrue(
            "Convert hands the legacy object to the converter's replacement set",
            convert.contains("legacyObjectId = document.displayObjectId"),
        )
        val converter = File(main, "com/tomppi/enderslicer/solidcraft/CadConvert.kt").readText()
        assertTrue(
            "CadConvert.plan drops it from the plate (republish already does)",
            converter.contains("legacyObjectId: String? = null"),
        )
    }

    @Test
    fun theFirstEditAfterClearBuildPlateReadsTheWholeDocumentBack() {
        val vm = viewModel()
        assertTrue(
            "the composer knows whether it holds every body",
            vm.contains("private var cadComposerComplete = true"),
        )
        assertTrue(
            "clearing the plate leaves the composer incomplete",
            vm.contains("cadComposerComplete = false"),
        )
        assertTrue(
            "a full read restores it",
            vm.contains("cadComposerComplete = cadBodies.count > 0"),
        )
        assertTrue(
            "the repaint refuses to patch an incomplete composer",
            vm.contains("!cadComposerComplete || cadBodies.count == 0"),
        )
        assertTrue(
            "and the status names what that first edit did",
            vm.contains("the plate had been cleared, so the whole document was read back"),
        )
    }

    @Test
    fun convertAndRebuildAreRefusedWhileATurnRunsAndThePlateCanReadThat() {
        val vm = viewModel()
        assertTrue(
            "Convert is refused by the named reason",
            body("fun convertCadToStl()", "fun newCadDesign()").contains("state.cadConvertBlockedReason"),
        )
        assertTrue(
            "Rebuild is refused by the named reason",
            body("fun rebuildCadPlateFromDocument()", "fun openCadDocument(")
                .contains("cadRebuildBlockedReason"),
        )
        assertTrue(
            "the turn flag is mirrored into the plate's state, where the buttons read it",
            vm.contains("_uiState.update { it.copy(cadTurnRunning = value) }"),
        )
        val state = File(main, "com/tomppi/enderslicer/ui/MainUiState.kt").readText()
        assertTrue(state.contains("val cadTurnRunning: Boolean = false"))
        assertTrue(state.contains("val cadConvertBlockedReason: String?"))
        assertTrue(state.contains("val cadRebuildBlockedReason: String?"))
        assertTrue(state.contains("the CAD agent is working; stop the turn before converting"))
        assertTrue(state.contains("the CAD agent is working; stop the turn before rebuilding the plate"))
    }
}
