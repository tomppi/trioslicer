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

    /**
     * Committing a split opens the joint maker, and does it from the success path only.
     *
     * The split is what defines the assembly axis, so the flow continues in the tool that
     * builds on it - but a split that was refused (busy, plane outside the model), that lost
     * its plate while it ran, or that failed must not land the user in a joint builder with
     * no pair. Watching the UI state for a new pair would fire on all four; the line after
     * the commit is reached by exactly one of them.
     */
    @Test
    fun aCommittedSplitOpensTheJointMakerOnItsOwnPath() {
        val split = body("fun splitModel(", "private fun cutPathNote")
        val commit = split.lastIndexOf("_uiState.update { commit(it) }")
        val open = split.indexOf("setSnapActive(true)")
        assertTrue("the committed split opens the joint maker", open >= 0)
        assertTrue("after the plate it made is committed", open > commit)
        assertTrue(
            "and inside the run, before the failure handler",
            open < split.indexOf(".onFailure {"),
        )
    }

    /**
     * The long-press card's two whole-model actions: the red X that removes the object
     * (labelled for the accessibility tree, since the tests and the uiautomator walks find
     * it by that name) and Split, which goes through the one entry point the model tools
     * use rather than a second copy of it.
     */
    @Test
    fun theLongPressCardRemovesAndSplitsThroughTheOneEntryPoint() {
        val card = source("ui/TransformGizmoOverlay.kt")
        assertTrue(
            "the card's X names what it deletes",
            card.contains("contentDescription = \"Remove model\""),
        )
        assertTrue("and is the danger colour", card.contains("MaterialTheme.colorScheme.error"))
        assertTrue("with a confirmation before it deletes", card.contains("AlertDialog("))

        val app = source("ui/EnderSlicerApp.kt")
        assertTrue(
            "the model tools and the card open the same Split tool",
            app.contains("onOpenCutUi = openCutUi"),
        )
        assertTrue("and the card goes through it", app.contains("openCutUi()"))
        assertTrue(
            "the card's removal is the view model's own removal path",
            app.contains("id?.let(viewModel::removeModel)"),
        )
    }

    /**
     * The pair's own source is the stage of record - and the only thing that makes a joint
     * editable after it has been applied.
     *
     * A pocket that has been cut cannot be un-cut, so a preview or an apply that began from
     * the meshes on the plate would union the beam a second time and cut the pocket again.
     * Both paths therefore read the pair's own halves: the recorded source when the pair has
     * one, and the halves the plate holds when it is being jointed for the first time. The
     * plain-halves path is the same source read the other way - every joint removed, the
     * halves staged as they are.
     */
    @Test
    fun everyJointBuildStartsFromThePairsOwnHalves() {
        val viewModel = source("ui/MainViewModel.kt")
        assertTrue(
            "one reader for the pair's own halves",
            viewModel.contains("private suspend fun pairOwnHalves(state: MainUiState)"),
        )
        val preview = body("private suspend fun computeSnapPreview(", "private fun SnapFitHalf.withOwnMesh")
        assertTrue("the preview booleans them", preview.contains("pairOwnHalves(state)"))
        assertTrue(preview.contains("lowFit.withOwnMesh(own.first, lowHalf.name)"))
        assertTrue(preview.contains("highFit.withOwnMesh(own.second, highHalf.name)"))
        assertTrue(
            "and a source it cannot read degrades to the pair with no joints, saying why",
            preview.contains("SNAP_RECORD_LOST_MESSAGE") &&
                preview.contains("snapJoints = emptyList()") &&
                preview.contains("snapRecord = null"),
        )

        val apply = body("fun applySnapJoint(", "private fun middleOf(")
        assertTrue("apply reads them too", apply.contains("pairOwnHalves(state)"))
        assertTrue(
            "and reuses the files a recorded pair already owns rather than writing new ones",
            apply.contains("record.lowSourcePath to record.highSourcePath"),
        )
        assertTrue("first writing them for a pair that has none", apply.contains("pairSourceFile()"))
        assertTrue(
            "an apply that does not commit takes the files it wrote with it",
            apply.contains("writtenSources.forEach { it.delete() }"),
        )
        assertTrue(
            "every joint removed: the source halves are the ones staged",
            apply.contains("val puttingPlainHalvesBack = state.snapPlainHalvesInstead"),
        )
        assertTrue("and the pair keeps its record", apply.contains("record = nextRecord"))
    }

    /**
     * A pair's files are deleted exactly when the pair is, and the cleanup that takes a
     * replaced object's staged copy must never take them: they are not an object's file.
     */
    @Test
    fun aPairsFilesGoOnlyWhenThePairDoes() {
        val viewModel = source("ui/MainViewModel.kt")
        assertTrue(
            "one place decides: the same two paths mean the pair survived",
            viewModel.contains("private fun releasePairSources(before: SnapRecord?, after: SnapRecord?)"),
        )
        val split = body("fun splitModel(", "private fun cutPathNote")
        assertTrue(
            "a split replaces the pair, so its files go",
            split.contains("releasePairSources(state.liveSnapRecord, null)"),
        )
        val remove = body("fun removeModel(", "fun splitModel(")
        assertTrue(
            "a half removed takes its pair's files",
            remove.contains("releasePairSources(state.liveSnapRecord, next.liveSnapRecord)"),
        )
        val restore = body("private fun restoreWorkspace(", "private fun workspaceSnapshot(state:")
        assertTrue("a restore puts the record back on the pair", restore.contains("record = record"))
        assertTrue(
            "and a pair it could not rebuild releases its files",
            restore.contains("deletePairSources(listOf(lowSource, highSource))"),
        )
    }

    /**
     * Opening the tool is where a record becomes joints again - and where a record whose
     * source has gone is dropped rather than trusted, with the panel saying why.
     */
    @Test
    fun openingTheToolRestoresTheRecordAndSaysWhenItCannot() {
        val open = body("fun setSnapActive(", "fun placeSnapJoint(")
        assertTrue("the recorded joints come back", open.contains("state::restoredJoints"))
        assertTrue(
            "only while the halves they were built from are still there",
            open.contains("recorded.sourcePaths.all { File(it).isFile }"),
        )
        assertTrue("and a record that cannot be used is dropped", open.contains("SNAP_RECORD_LOST_MESSAGE"))
        assertTrue("with its files", open.contains("deletePairSources(recorded.sourcePaths)"))
        assertTrue(
            "and the restored joints are built, or Apply would open disabled",
            open.contains("if (restored.isNotEmpty()) previewSnapJoint()"),
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

    /**
     * The panel's own numbers and qualifiers are claims about the geometry: the
     * worst-case strain note belongs to a beam with several teeth, not to the
     * number of joints on the seam, and the deflection room is on the side the
     * beam bends towards - not "below" it, which the default frame already made
     * untrue and an opposite-facing joint put on the other side again.
     */
    @Test
    fun thePanelsNumbersAreLabelledAsTheGeometryHasThem() {
        val app = source("ui/EnderSlicerApp.kt")
        assertTrue(
            "the strain note is keyed to the joint's own tooth count",
            app.contains("preview.joint.dimensions.barbCount > 1"),
        )
        assertFalse("and not to how many joints are on the seam", app.contains("preview.jointCount > 1"))
        assertFalse("the deflection room is not called \"below the beam\"", app.contains("mm below the beam"))
        assertTrue(
            "it is on the side the beam bends towards",
            app.contains("on the side the beam bends towards"),
        )
        assertTrue(
            "and the fit line is the joint's own readout",
            app.contains("one.joint.fitSummary"),
        )
        assertFalse(
            "not a format string built here that no test can reach",
            app.contains("%.1f mm beam, %.2f mm clearance\".format"),
        )
    }

    /**
     * N joints at N anchors put N hooks in the geometry, which one line of this function decided
     * against: every joint was built on the pair's OWN untouched halves, and each result then
     * REPLACED the running mesh, because a placement carries the mesh it was built from. Two
     * joints came out as one hook on the plate - the last one - while the panel listed both, and
     * Apply wrote a pair carrying only that one.
     */
    @Test
    fun everyJointIsBuiltOnTheHalvesTheJointsBeforeItLeft() {
        val placement = body("private fun placeEveryJoint(", "private fun contouredOrLesser(")
        assertTrue(
            "the running halves are what each joint is built on",
            placement.contains("val lowRunning = ready.lowHalf.copy(mesh = lowMesh)") &&
                placement.contains("val highRunning = ready.highHalf.copy(mesh = highMesh)"),
        )
        assertTrue(
            "and they are what SnapJoint.build is given",
            placement.contains("lowHalf = lowRunning,") && placement.contains("highHalf = highRunning,"),
        )
        assertFalse(
            "never the pair's untouched halves",
            placement.contains("lowHalf = ready.lowHalf,"),
        )
        assertTrue(
            "and every boolean runs on the running meshes, so the joints accumulate",
            placement.contains("engine.union(contoured.beamMesh, joint.unionSolid)") &&
                placement.contains("engine.subtract(contoured.socketMesh, joint.subtractSolid)") &&
                placement.contains("lowMesh = registered.mesh") &&
                placement.contains("highMesh = recessed.mesh"),
        )
        assertTrue(
            "with the beam's own half read off the running mesh, not the basis",
            placement.contains("val beamIsLow = contoured.beamMesh === lowRunning.mesh"),
        )
    }

    /**
     * A joint the material will not take is that joint's sentence, naming it, while the joints
     * that were built stay on the plate - and Apply refuses until it is moved or removed,
     * because a pair joined with fewer joints than the list names is not the pair previewed.
     */
    @Test
    fun oneJointsRefusalDoesNotTakeTheOthersWithIt() {
        val placement = body("private fun placeEveryJoint(", "private fun contouredOrLesser(")
        assertTrue(
            "every failure is recorded against its own joint",
            Regex("failed \\+= SnapJointFailure\\(").findAll(placement).count() >= 6,
        )
        assertTrue(
            "and the loop carries on with the rest",
            placement.contains("failed += SnapJointFailure(") && placement.contains("continue"),
        )
        assertTrue(
            "the preview carries the refusals",
            placement.contains("failed = failed,"),
        )
        assertTrue(
            "nothing at all built still refuses the preview with the first joint's own sentence",
            placement.contains("if (placed.isEmpty())") &&
                placement.contains("\"Joint \" + (first.index + 1) + \" \" + first.reason"),
        )

        val state = source("ui/MainUiState.kt")
        assertTrue(
            "the state reads them off the standing preview",
            state.contains("val snapFailedJoints: List<SnapJointFailure>") &&
                state.contains("snapShownMeshes?.failed.orEmpty()"),
        )
        assertTrue(
            "Apply refuses while one stands, naming it",
            state.contains("snapFailedJoints.isNotEmpty() ->") &&
                state.contains("Move it or remove it before joining."),
        )
        val apply = body("fun applySnapJoint(", "private fun middleOf(")
        assertTrue(
            "and the command says no as well, because a button is not the only way in",
            apply.contains("if (state.snapFailedJoints.isNotEmpty())"),
        )

        val app = source("ui/EnderSlicerApp.kt")
        assertTrue("the row says it in the joint's own place", app.contains("Could not be built: "))
        assertTrue(
            "and the button is off while any joint is refused",
            app.contains("state.snapFailedJoints.isEmpty()"),
        )
    }

    /**
     * Placing is deliberate: Add arms one tap, a tap with nothing armed selects, and Add and
     * Move cannot both be armed - a tap cannot both place a joint and move one.
     */
    @Test
    fun aTapOnlyPlacesAJointWhenAddWasPressedFirst() {
        val placement = body("fun placeSnapJoint(", "fun addSnapJoint()")
        assertTrue(
            "an armed Add places exactly one joint and disarms itself",
            placement.contains("state.snapAddingJoint ->") &&
                placement.contains("snapAddingJoint = false,"),
        )
        assertTrue(
            "and an unarmed tap selects the nearest joint instead",
            placement.contains("state.nearestSnapJoint(point)"),
        )
        assertTrue(
            "there is a control that arms it",
            viewModel.contains("fun addSnapJoint()") &&
                viewModel.contains("snapAddingJoint = true, snapMovingJoint = false,"),
        )
        assertTrue(
            "Move takes the Add arming down",
            body("fun moveSnapJoint(", "/** Takes one joint off").contains("snapAddingJoint = false,"),
        )

        val app = source("ui/EnderSlicerApp.kt")
        assertTrue("the button says what it does", app.contains("Text(\"Add joint\")"))
        assertTrue(
            "the line under the title says the tap will place it",
            app.contains("state.snapAddingJoint -> \"Tap the model to place it.\""),
        )
        assertFalse(
            "and nothing promises that a tap adds a joint any more",
            app.contains("Tap again to add another joint"),
        )
    }

    /**
     * The joint move: Move in the joint's own row IS the mode.
     *
     * There is no second menu and no Done button - the same press turns the
     * arrows on and, pressed again, off - so the wiring that matters is that
     * every way out of the mode is wired, and that a tap has nothing to do with
     * moving a joint any more. The arrows are drawn from the state that names
     * the joint, so they cannot be left standing over nothing.
     */
    @Test
    fun theJointMoveIsAToggleThatTakesItsArrowsWithIt() {
        val move = body("fun moveSnapJoint(", "fun dragSnapJoint(")
        assertTrue("Move is a mode, not a one-shot: the same press puts it away", move.contains("stopSnapMove()"))
        assertTrue("and pressing it on raises it", move.contains("snapMovingJoint = true,"))
        assertTrue("with Add's arming taken down", move.contains("snapAddingJoint = false,"))
        assertTrue("and the seam's own edge read for the drag", move.contains("readSnapMoveContour(index)"))

        assertTrue(
            "picking another joint ends the move with the selection",
            body("fun selectSnapJoint(", "fun moveSnapJoint(").contains("stopSnapMove()"),
        )
        assertTrue(
            "closing the panel drops the contour's read",
            body("fun setSnapActive(", "fun placeSnapJoint(").contains("snapContourJob?.cancel()"),
        )
        assertTrue(
            "arming a placement ends it",
            body("fun addSnapJoint(", "fun cancelSnapPlacement(").contains("snapMovingJoint = false,"),
        )
        assertTrue(
            "and a joint placed by tap does too",
            body("fun placeSnapJoint(", "fun placeSnapCutStroke(").contains("snapMovingJoint = false,"),
        )
        assertTrue(
            "and the style switch, which rebuilds the list",
            body("fun setSnapStyle(", "fun setSnapLibraryShape(").contains("stopSnapMove()"),
        )
        assertFalse(
            "a tap is not a place any more: the move is the drag's",
            body("fun placeSnapJoint(", "fun placeSnapCutStroke(")
                .contains("copy(anchorPointMm = point, anchorHalfId = tappedHalfId)"),
        )

        val drag = body("fun dragSnapJoint(", "private fun readSnapMoveContour(")
        assertTrue(
            "the drag is read in the half the finger is over",
            drag.contains("snapJointSlidTo(point, contour, fingerHalfId)"),
        )
        assertTrue("and re-arms the preview so the geometry follows", drag.contains("previewSnapJoint()"))

        val contour = body("private fun readSnapMoveContour(", "private fun stopSnapMove(")
        assertTrue("the contour is read off the pair's own halves", contour.contains("pairOwnHalves(state)"))
        assertTrue("in the joint's own half", contour.contains("SnapSeam.contour("))

        val state = source("ui/MainUiState.kt")
        assertTrue("the arrows stand on the joint, where it stands now", state.contains("val snapMoveGizmo: GizmoOverlay?"))
        assertTrue("and only while the move is live with a joint under it", state.contains("if (!snapActive || !snapMovingJoint) return@lazy null"))
        assertTrue("the drag is projected onto the seam's own edge", state.contains("contour.nearest(local, here)"))

        val app = source("ui/EnderSlicerApp.kt")
        assertTrue("the plate view is given the joint's own gizmo", app.contains("view.jointGizmo = state.snapMoveGizmo"))
        assertTrue("and the mode that owns a drag", app.contains("view.jointMoveActive = state.snapActive && state.snapMovingJoint"))
        assertTrue("the drag goes to the view model", app.contains("viewModel.dragSnapJoint("))
        assertTrue("the row's Move shows that it is on", app.contains("if (moving) {"))
        assertTrue(
            "and the panel says what to do, in its own voice",
            app.contains("\"seam; press Move again when it is where you want it.\""),
        )
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
