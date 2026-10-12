package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.model.PlateObject
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.viewer.MeshFixtures
import com.tomppi.enderslicer.viewer.MeshSolidBuilder
import com.tomppi.enderslicer.viewer.SnapFacing
import com.tomppi.enderslicer.viewer.SnapFitJoint
import com.tomppi.enderslicer.viewer.SnapJoint
import com.tomppi.enderslicer.viewer.SnapTightness
import com.tomppi.enderslicer.viewer.StlMesh
import com.tomppi.enderslicer.viewer.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snap fit's state, without a device in the loop.
 *
 * The anchor is the part worth pinning down, now that it is read in the half it
 * landed on: the plate's packer moves the halves apart after a split, so the
 * same plate point is a different spot in each half's own frame, and the joint
 * is built in the halves' frames around the faces the split carried - not
 * around wherever the packer left them. Everything else here is what the
 * panel's own controls read: which halves are still there, whether the preview
 * in hand is still current, and what Apply says when it cannot run.
 */
class SnapStateTest {
    private val printer = PrinterDefinition(
        name = "Modified Ender 3 V2",
        widthMm = 220.0,
        depthMm = 220.0,
        heightMm = 250.0,
        buildPlateShape = "rectangular",
        originAtCenter = false,
        heatedBed = true,
        heatedBuildVolume = false,
        gcodeFlavor = "Marlin",
        extruders = 1,
        nozzleSizeMm = 0.4,
        filamentDiameterMm = 1.75,
        printheadXMinMm = -26.0,
        printheadYMinMm = -32.0,
        printheadXMaxMm = 32.0,
        printheadYMaxMm = 34.0,
        gantryHeightMm = 25.0,
    )

    @Test
    fun theTapIsReadInTheHalfItLandedOn() {
        val state = splitState()

        val onLow = state.copy(snapActive = true).withJoint(Vec3(3f, 4f, 31f))
        assertEquals("the low half's own frame is the plate's", Vec3(3f, 4f, 31f), onLow.snapAnchorLocalMm)

        // The same physical tap after the plate moved that half up 10 mm: the
        // plate point moves with it, the half's own coordinates do not.
        val moved = onLow.copy(
            models = listOf(onLow.models[0].movedBy(0f, 0f, 10f), onLow.models[1]),
        ).withAnchor(Vec3(3f, 4f, 41f))
        assertEquals("and the tap follows the half, not the plate", Vec3(3f, 4f, 31f), moved.snapAnchorLocalMm)

        // A tap on the high half reads in the high half's frame, which the
        // packer has moved away from the low half's: the plate point that is
        // (3, 4, 31) in the low half's frame is (3, 4, 38) on the plate once
        // the high half has been pushed 7 mm further up.
        val separated = state.copy(
            models = listOf(state.models[0], state.models[1].movedBy(0f, 0f, 7f)),
            snapActive = true,
        ).withJoint(Vec3(3f, 4f, 38f), "high")
        assertEquals("a tap on the high half reads in its own frame", Vec3(3f, 4f, 31f), separated.snapAnchorLocalMm)
    }

    @Test
    fun theJointIsBuiltFromEachHalfsOwnFaceAndThePlateCannotMoveIt() {
        // The bug this guards: the joint's frame used to sit at the midpoint of
        // the gap between the two halves as placed, so the packer's separation
        // put the features in mid-air. The beam stays with the half it roots in
        // and the pocket travels with the half it is cut from.
        val touching = splitState().copy(snapActive = true).withJoint(Vec3(20f, 20f, 31f))
        val apart = touching.copy(models = listOf(touching.models[0], touching.models[1].movedBy(0f, 0f, 7f)))

        val touchingJoint = jointFor(touching)
        val apartJoint = jointFor(apart)

        assertEquals(
            "the beam stays rooted in its own half",
            touchingJoint.unionSolid.bounds.minZ,
            apartJoint.unionSolid.bounds.minZ,
            1e-3f,
        )
        assertEquals(
            "and the pocket moves with the half it is cut from",
            touchingJoint.subtractSolid.bounds.maxZ + 7f,
            apartJoint.subtractSolid.bounds.maxZ,
            1e-3f,
        )
        assertEquals(
            "the joint's dimensions do not depend on the gap either",
            touchingJoint.dimensions,
            apartJoint.dimensions,
        )
    }

    @Test
    fun thereIsNoAnchorWithoutBothHalvesAndNoAnchorBeforeATap() {
        val state = splitState().copy(snapActive = true, snapJoints = emptyList(), snapSelectedJoint = -1)
        assertNull("nothing is read before the first tap", state.snapAnchorLocalMm)

        val missing = state.copy(models = state.models.dropLast(1)).withJoint(Vec3(1f, 2f, 3f))
        assertNull("and no ghost once a half has left the plate", missing.snapGhost)
        assertTrue("which is what the panel reads to say so", !missing.snapAvailable)
    }

    @Test
    fun thePairIsWhatTheSplitLeftBehind() {
        val state = splitState()

        assertTrue(state.snapAvailable)
        assertEquals("the low half carries the plane the split ran", 20f, state.snapLowFaceMm)
        assertEquals("and so does the high half", 20f, state.snapHighFaceMm)
        assertEquals(20f, state.snapLowFitHalf!!.faceMm, 1e-3f)
        assertNull("nothing is blocked once the pair is there", state.snapBlockedReason)

        val unsplit = state.copy(snapLowHalfId = null, snapHighHalfId = null)
        assertTrue(!unsplit.snapAvailable)
        assertEquals(
            "Split a model first: the snap fit joins the two halves a split made.",
            unsplit.snapBlockedReason,
        )
    }

    @Test
    fun theRecordedPairIsFollowedThroughThePlateOrder() {
        // The packer moves the halves around after a split, so the pair is two
        // ids resolved against wherever the plate has them now - and the places
        // are what a commit needs, because the packer returns fresh objects.
        val state = splitState()
        val pair = state.snapPairIndices as SnapPairLookup.Found
        assertEquals(0, pair.lowIndex)
        assertEquals(1, pair.highIndex)

        val swapped = state.copy(models = listOf(state.models[1], state.models[0]))
        val moved = swapped.snapPairIndices as SnapPairLookup.Found
        assertEquals("the low half is where the plate has it now", 1, moved.lowIndex)
        assertEquals("and so is the high one", 0, moved.highIndex)
    }

    @Test
    fun aPairThePlateNoLongerHoldsIsStaleAndSaysSoInsteadOfThrowing() {
        val state = splitState()
        assertTrue("the plate starts with the pair on it", state.snapPairIndices is SnapPairLookup.Found)

        // A half deleted, a re-split, a restored workspace: the two ids the
        // split recorded are simply not on the plate any more.
        val deleted = state.copy(models = listOf(state.models.first()))
        assertEquals(SnapPairLookup.Stale, deleted.snapPairIndices)
        assertEquals(SNAP_PAIR_STALE_MESSAGE, SnapPairLookup.Stale.reason)
        assertEquals("and the panel says the same sentence", SNAP_PAIR_STALE_MESSAGE, deleted.snapBlockedReason)

        // The whole plate swapped out at once - every object replaced.
        val replaced = state.copy(models = state.models.map { it.copy(id = it.id + "-again") })
        assertEquals(SnapPairLookup.Stale, replaced.snapPairIndices)
        assertEquals(SNAP_PAIR_STALE_MESSAGE, replaced.snapBlockedReason)

        // And a plate that was never split keeps the older, more useful sentence.
        val unsplit = state.copy(snapLowHalfId = null, snapHighHalfId = null)
        assertEquals(SnapPairLookup.Stale, unsplit.snapPairIndices)
        assertEquals(
            "Split a model first: the snap fit joins the two halves a split made.",
            unsplit.snapBlockedReason,
        )
    }

    @Test
    fun aPreviewStandsOnlyWhileTheInputsThatMadeItAreCurrent() {
        val state = splitState().copy(snapActive = true).withJoint(Vec3(3f, 4f, 31f))
        val preview = previewOf(state)

        assertEquals("the previewed halves are what the plate shows", preview, state.copy(snapPreview = preview).snapShownMeshes)
        assertNull(
            "and a scale that has moved on is not previewed with",
            state.copy(snapPreview = preview, snapScale = 1.25f).snapShownMeshes,
        )
        assertNull(
            "nor a tap that has moved on",
            state.copy(snapPreview = preview).withAnchor(Vec3(9f, 4f, 31f)).snapShownMeshes,
        )
        assertNull(
            "nor a joint flipped to the other half",
            state.copy(
                snapPreview = preview,
                snapJoints = listOf(state.snapJoints.first().copy(beamHalf = SnapJoint.JointHalf.HIGH)),
            ).snapShownMeshes,
        )
        assertNull(
            "nor a ladder switched to the full joint",
            state.copy(snapPreview = preview, snapFullJoint = true).snapShownMeshes,
        )
        assertNull("nothing previewed, nothing shown", state.snapShownMeshes)
    }

    /**
     * The halves are the preview's geometry, so a placement change under a standing
     * preview makes it describe a plate that no longer exists. Without the halves in the
     * key, a long-press rotate left the preview current: the model view snapped back to
     * the un-rotated half, and Apply committed the pre-rotation geometry.
     */
    @Test
    fun aHalfThatMovedUnderThePreviewIsNotPreviewedWith() {
        val state = splitState().copy(snapActive = true).withJoint(Vec3(20f, 20f, 31f))
        val standing = state.copy(snapPreview = previewOf(state))
        assertNotNull("the preview stands while the halves do", standing.snapShownMeshes)

        val rotated = standing.copy(
            models = listOf(
                standing.models[0].withPlacement(
                    standing.models[0].placement.rotated(ModelPlacement.Axis.Y, 15.0),
                ),
                standing.models[1],
            ),
        )

        assertNull("a half rotated under it is not what it describes", rotated.snapShownMeshes)
    }

    /**
     * A pair that has left the plate cannot be joined, and the panel has to say the same
     * thing with its button disabled as it says in words. The enabled state used to stay on
     * - the preview was still non-null - while [MainUiState.snapBlockedReason] said stale, so
     * a tap did nothing visible at all.
     */
    @Test
    fun aPairThatLeftThePlateDisablesJoinForTheSameReasonThePanelGives() {
        val state = splitState().copy(snapActive = true).withJoint(Vec3(20f, 20f, 31f))
        val standing = state.copy(snapPreview = previewOf(state))
        assertNotNull(standing.snapShownMeshes)

        val gone = standing.copy(models = listOf(standing.models[0]))

        assertNull("no halves, nothing to apply", gone.snapShownMeshes)
        assertEquals(
            "and the disabled button and the sentence agree",
            SNAP_PAIR_STALE_MESSAGE,
            gone.snapBlockedReason,
        )
    }

    /**
     * A pair a descriptor saved - or a split just committed - is offered by
     * [MainUiState.withSnapPair] on exactly the same terms as the split's own pair. This is
     * the state half of item one: the store test pins the file, this pins what the restore
     * does with it.
     */
    @Test
    fun aPairTheDescriptorSavedIsOfferedAgainOnTheRestoredPlate() {
        val plate = splitState().withSnapPair("low", "high", ModelPlacement.Axis.Z, 20f, 20f)

        assertTrue("the restored pair offers the tool again", plate.snapAvailable)
        assertTrue(plate.snapHalvesPresent)
        assertEquals("with the plane the split carried", 20f, plate.snapLowFitHalf!!.faceMm, 1e-3f)
        assertEquals(20f, plate.snapHighFitHalf!!.faceMm, 1e-3f)
        assertNull("and nothing is blocked while a joint is placed", plate.snapBlockedReason)

        val none = plate.withSnapPair(null, null, ModelPlacement.Axis.Z, null, null)
        assertTrue(!none.snapAvailable)
        assertEquals(
            "Split a model first: the snap fit joins the two halves a split made.",
            none.snapBlockedReason,
        )
    }


    /**
     * What a tap does when neither Add nor Move is armed: it selects, and only selects.
     *
     * The bug this pins is the one the user hit while turning the model: every tap that landed
     * on a half placed a joint, so moving and rotating a pair left a trail of hooks behind it.
     * A tap now belongs to the model, and the joint nearest the finger is what it may select.
     */
    @Test
    fun aTapWithNothingArmedSelectsTheJointNearestIt() {
        val state = splitState().copy(snapActive = true).withJoint(Vec3(6f, 20f, 20f))
            .let { it.copy(snapJoints = it.snapJoints + spec(Vec3(34f, 20f, 20f), "low")) }

        assertEquals("two joints on the seam", 2, state.snapJoints.size)
        assertEquals("a tap by the first selects it", 0, state.nearestSnapJoint(Vec3(7f, 20f, 21f)))
        assertEquals("a tap by the second selects that one", 1, state.nearestSnapJoint(Vec3(33f, 20f, 21f)))
        assertEquals(
            "and a tap nowhere near either still selects the closest, not nothing",
            0,
            state.nearestSnapJoint(Vec3(0f, 20f, 20f)),
        )
        assertEquals(
            "nothing to select, nothing selected",
            -1,
            state.copy(snapJoints = emptyList()).nearestSnapJoint(Vec3(6f, 20f, 20f)),
        )
    }

    @Test
    fun placingIsArmedAndDisarmedByTheState() {
        val session = splitState().copy(snapActive = true)
        val withJoint = session.withJoint(Vec3(6f, 20f, 20f)).copy(snapAddingJoint = true)

        assertFalse("nothing is armed by opening the tool", session.snapAddingJoint)
        assertTrue("Add arms the next tap", session.copy(snapAddingJoint = true).snapAddingJoint)
        assertFalse("closing the tool arms nothing", withJoint.withoutSnap().snapAddingJoint)
        assertFalse(
            "and neither does taking a joint off the list",
            withJoint.withoutSnapJoint(0).snapAddingJoint,
        )
    }

    /**
     * A joint the material refused is named, and the others still stand.
     *
     * Dropping the whole preview for one joint's refusal threw away geometry the user had
     * already placed; showing the list as if every joint were on the plate is the same loss
     * with the alarm switched off. Apply refuses while a refusal stands, because the pair that
     * would be written is the list minus one.
     */
    @Test
    fun aJointThatCouldNotBeBuiltIsNamedWhileTheOthersStand() {
        val state = splitState().copy(snapActive = true).withJoint(Vec3(6f, 20f, 20f))
            .let { it.copy(snapJoints = it.snapJoints + spec(Vec3(34f, 20f, 20f), "low")) }
        val preview = SnapPreview(
            lowMesh = state.models[0].mesh,
            highMesh = state.models[1].mesh,
            joints = emptyList(),
            specs = state.snapJoints,
            lowHalfId = state.snapLowHalfId,
            highHalfId = state.snapHighHalfId,
            lowPlacement = state.snapLowHalf?.placement,
            highPlacement = state.snapHighHalf?.placement,
            scale = state.snapScale,
            fullJoint = state.snapFullJoint,
            socketRamp = state.snapSocketRamp,
            hookLengthMm = state.snapHookLengthMm,
            hookThicknessMm = state.snapHookThicknessMm,
            hookLipMm = state.snapHookLipMm,
            repairNote = null,
            failed = listOf(
                SnapJointFailure(1, "has no material where it was placed for its beam to root in"),
            ),
        )
        val standing = state.copy(snapPreview = preview)

        assertEquals("the preview is the state's own", preview, standing.snapShownMeshes)
        assertEquals("the joint that refused is named", 1, standing.snapFailedJoints.single().index)
        assertTrue(
            "and the reason is the one that came back",
            standing.snapFailedJoints.single().reason.contains("no material"),
        )
        assertTrue(
            "Apply says why it will not run",
            standing.snapBlockedReason.orEmpty().contains("Joint 2"),
        )
        assertTrue(
            "and the sentence says what to do about it",
            standing.snapBlockedReason.orEmpty().contains("Move it or remove it"),
        )
    }

    /**
     * The state a split or a join commits: no preview, no joint list and no placement undo -
     * every step the history holds belongs to the objects the operation just replaced.
     */
    @Test
    fun aReplacedPlateDropsTheSessionAndThePlacementUndo() {
        val state = splitState().copy(
            snapActive = true,
            snapPreview = null,
            canUndoPlacement = true,
            undoPlacementLabel = "Model rotated 15°",
        )

        val replaced = state.afterPlateReplaced().withSnapPair("low", "high", ModelPlacement.Axis.Z, 20f, 20f)

        assertTrue(!replaced.snapActive)
        assertNull(replaced.snapPreview)
        assertTrue(replaced.snapJoints.isEmpty())
        assertTrue("the old object's undo is gone", !replaced.canUndoPlacement)
        assertNull(replaced.undoPlacementLabel)
        assertTrue("and the new pair is still on offer", replaced.snapAvailable)
    }

    @Test
    fun theGhostIsTheJointDrawnAndGoesOnceThePlateShowsIt() {
        val state = splitState().copy(snapActive = true).withJoint(Vec3(20f, 20f, 31f))

        val ghost = state.snapGhost
        assertNotNull("where the joint will go, there is a ghost", ghost)
        assertTrue("and it is geometry", ghost!!.totalVertexCount > 0)
        assertNull("and no ghost before a tap", state.copy(snapJoints = emptyList(), snapSelectedJoint = -1).snapGhost)
        assertNull("and none while the tool is closed", state.copy(snapActive = false).snapGhost)
    }

    @Test
    fun closingTheSessionDropsThePreviewButKeepsThePair() {
        val state = splitState().copy(snapActive = true).withJoint(Vec3(1f, 2f, 3f))

        val closed = state.withoutSnap()

        assertTrue(!closed.snapActive)
        assertNull(closed.snapAnchorPoint)
        assertNull(closed.snapAnchorHalfId)
        assertNull(closed.snapPreview)
        assertNull(closed.snapFailure)
        assertTrue("and the tool can be opened again without splitting twice", closed.snapAvailable)
    }

    @Test
    fun theAnchorIsBlockedWithAReasonUntilTheUserTaps() {
        val state = splitState().copy(snapActive = true, snapJoints = emptyList(), snapSelectedJoint = -1)

        assertEquals(
            "Place a joint on the seam: Add joint, then tap the model.",
            state.snapBlockedReason,
        )
    }

    @Test
    fun removingOneJointLeavesTheOtherExactlyAsItWas() {
        val first = spec(Vec3(10f, 20f, 31f))
        val second = spec(Vec3(30f, 20f, 31f)).copy(tightness = SnapTightness.TIGHT, barbs = 2)
        val state = splitState().copy(
            snapActive = true,
            snapJoints = listOf(first, second),
            snapSelectedJoint = 0,
        )

        val afterFirst = state.withoutSnapJoint(0)

        assertEquals("one joint went", 1, afterFirst.snapJoints.size)
        assertEquals("and it is the other one, untouched", second, afterFirst.snapJoints.single())
        assertEquals("which is what the panel now acts on", 0, afterFirst.snapSelectedJoint)
        assertNull("and nothing stale is left previewed", afterFirst.snapPreview)

        val empty = afterFirst.withoutSnapJoint(0)
        assertTrue("the last one can go too", empty.snapJoints.isEmpty())
        assertEquals("with nothing selected", -1, empty.snapSelectedJoint)
        assertEquals("and the tool asks for a tap again", "Place a joint on the seam: Add joint, then tap the model.", empty.snapBlockedReason)
    }

    @Test
    fun aJointWithNoSizesOfItsOwnTakesThePanelsOwn() {
        // A lip joint saved before the wall and depth controls existed carries
        // none of the four sizes, and a record is read back leniently, so it comes
        // back with all four null. The panel's own values are what the build must
        // use: without that the sliders moved, the readout kept the old wall, and
        // Apply staged geometry nobody asked for.
        val state = splitState().copy(
            snapActive = true,
            snapLipEngagementMm = 1.1f,
            snapLipToothHeightMm = 1.4f,
            snapLipWallMm = 3f,
            snapLipDepthMm = 2f,
        ).withJoint(Vec3(20f, 20f, 31f))
        val bare = state.snapJoints.first().copy(
            lipEngagementMm = null,
            lipToothHeightMm = null,
            lipWallMm = null,
            lipRecessExtraMm = null,
        )

        val built = state.snapParametersFor(bare)
        assertEquals("the wall the panel shows is the wall the build gets", 3f, built.lipWallOverrideMm ?: 0f, 1e-4f)
        assertEquals("and its depth", 2f, built.lipRecessExtraOverrideMm ?: 0f, 1e-4f)
        assertEquals("and the engagement", 1.1f, built.lipEngagementOverrideMm ?: 0f, 1e-4f)
        assertEquals("and the tooth", 1.4f, built.lipToothHeightOverrideMm ?: 0f, 1e-4f)

        // A joint that carries its own keeps it: the panel's value is a fallback, not a rule.
        val own = bare.copy(lipWallMm = 1.5f)
        assertEquals(
            "a joint's own wall still wins",
            1.5f,
            state.snapParametersFor(own).lipWallOverrideMm ?: 0f,
            1e-4f,
        )
    }

    @Test
    fun theTwoStepsAreTheSameJointWithLessClearance() {
        // The stepping is clearance and nothing else: a tight joint has the same
        // beam at the same place, with 0.06 mm less room in its sockets.
        val state = splitState().copy(snapActive = true).withJoint(Vec3(20f, 20f, 31f))
        val loose = state.snapParametersFor(state.snapJoints.first())
        val tight = state.snapParametersFor(state.snapJoints.first().copy(tightness = SnapTightness.TIGHT))

        assertEquals(
            "exactly one step less around the beam",
            loose.lipClearanceMm - SnapTightness.TIGHT.stepMm,
            tight.lipClearanceMm,
            1e-6f,
        )
        assertEquals(
            "and around the key",
            loose.keyClearanceMm - SnapTightness.TIGHT.stepMm,
            tight.keyClearanceMm,
            1e-6f,
        )
        assertEquals("the hook's length is untouched", loose.beamLengthMm, tight.beamLengthMm, 0f)
        assertEquals("so is its thickness", loose.beamThicknessMm, tight.beamThicknessMm, 0f)
        assertEquals("and its lip", loose.lipDepthMm, tight.lipDepthMm, 0f)
        assertEquals("and the ramp's angle", loose.rampAngleDeg, tight.rampAngleDeg, 0f)
        assertEquals("and the teeth", loose.barbCount, tight.barbCount)
        assertEquals("and the way it faces", loose.facing, tight.facing)
        assertEquals(
            "the mating face keeps its own clearance: it is not a fit dimension",
            loose.matingClearanceMm,
            tight.matingClearanceMm,
            0f,
        )
    }

    /**
     * A pair that carries a record opens on the joints it remembers, read out of the pair's
     * OWN frame through the half each tap landed on.
     *
     * The own frame is the point. The packer moves the halves between sessions - that is what
     * a restore of a packed plate is - so a remembered PLATE point would land somewhere else
     * on the seam, while an own-frame one lands where it was put. The half travels as a side
     * and not as an id for the same reason: every apply replaces both objects.
     */
    @Test
    fun theRecordedJointsComeBackOnWhicheverHalfTheyWereTappedOn() {
        val state = splitState().copy(snapActive = true)
        val original = state.snapJoints.single()
        val record = recordOf(state)
        val restored = state.withSnapPair(
            lowHalfId = "low",
            highHalfId = "high",
            axis = ModelPlacement.Axis.Z,
            lowFaceMm = 20f,
            highFaceMm = 20f,
            record = record,
        )

        val back = restored.restoredJoints(record).single()
        assertEquals(
            "the same plate point, read back out through the same half",
            original.anchorPointMm,
            back.anchorPointMm,
        )
        assertEquals("named by the half it landed on", original.anchorHalfId, back.anchorHalfId)
        assertEquals("with the choices it was recorded with", SnapTightness.TIGHT, back.tightness)
        assertEquals(SnapJoint.JointHalf.HIGH, back.beamHalf)
        assertEquals(3, back.barbs)
        assertEquals(SnapFacing.OPPOSITE, back.facing)
        assertTrue("and the pair knows it has a record", restored.liveSnapRecord != null)

        // The plate moves the low half up 7 mm - the packer's own doing, the source frame
        // untouched - and the remembered tap follows the half it was made on.
        val moved = restored.copy(models = listOf(restored.models[0].movedBy(0f, 0f, 7f), restored.models[1]))
        val movedSpec = moved.restoredJoints(record).single()
        assertEquals("the tap follows the half", 38f, movedSpec.anchorPointMm.z, 1e-3f)
        assertEquals("and still names that half", "low", movedSpec.anchorHalfId)
        assertEquals("with the choices it was recorded with", SnapTightness.TIGHT, movedSpec.tightness)
        assertEquals(SnapJoint.JointHalf.HIGH, movedSpec.beamHalf)
        assertEquals(3, movedSpec.barbs)
        assertEquals(SnapFacing.OPPOSITE, movedSpec.facing)
    }

    /**
     * The record is the pair's, not the plate's: a half that leaves takes it with it, and a
     * pair with no joints left is what "put the plain halves back" is offered on.
     */
    @Test
    fun theRecordLivesExactlyAsLongAsThePairDoes() {
        val state = splitState().copy(snapActive = true)
        val record = recordOf(state)
        val held = state.withSnapPair("low", "high", ModelPlacement.Axis.Z, 20f, 20f, record = record)

        assertNotNull("the pair holds its record", held.liveSnapRecord)
        assertFalse(
            "and Apply is not the plain-halves way while a joint is on the seam",
            held.snapPlainHalvesInstead,
        )

        val withoutJoints = held.copy(snapJoints = emptyList(), snapSelectedJoint = -1)
        assertTrue("every joint removed: Apply puts the plain halves back", withoutJoints.snapPlainHalvesInstead)
        assertNull("and the panel does not ask for a tap", withoutJoints.snapBlockedReason)

        val halfGone = held.copy(models = listOf(held.models.first()))
        assertNull("a half that left takes the record with it", halfGone.liveSnapRecord)

        val noRecord = held.copy(snapRecord = null)
        assertNull(noRecord.liveSnapRecord)
        assertFalse(
            "and a pair that never recorded anything cannot clear joints it does not know",
            noRecord.copy(snapJoints = emptyList()).snapPlainHalvesInstead,
        )
        assertEquals(
            "it asks for a tap, exactly as it always did",
            "Place a joint on the seam: Add joint, then tap the model.",
            noRecord.copy(snapJoints = emptyList()).snapBlockedReason,
        )
    }

    /** The record [state]'s selected joint would leave behind, in the pair's own frame. */
    private fun recordOf(state: MainUiState): SnapRecord = SnapRecord(
        lowSourcePath = "pair-low.stl",
        highSourcePath = "pair-high.stl",
        scale = 1f,
        fullJoint = false,
        socketRamp = false,
        hookLengthMm = null,
        hookThicknessMm = null,
        hookLipMm = null,
        joints = listOf(
            SnapJointRecord(
                anchorLocalMm = requireNotNull(state.snapAnchorLocalMm),
                onLowHalf = true,
                beamHalf = SnapJoint.JointHalf.HIGH,
                tightness = SnapTightness.TIGHT,
                barbs = 3,
                facing = SnapFacing.OPPOSITE,
            ),
        ),
    )

    /** The preview [state] would have produced, carrying the halves and placements it was
     * built from the way the ViewModel's own construction does.
     */
    private fun previewOf(state: MainUiState): SnapPreview = SnapPreview(
        lowMesh = state.snapLowHalf!!.mesh,
        highMesh = state.snapHighHalf!!.mesh,
        joints = listOf(
            SnapPlacedJoint(
                spec = state.snapJoints.first(),
                joint = jointFor(state),
                beamInChosenHalf = true,
                beamHalfName = "low",
                socketHalfName = "high",
            ),
        ),
        specs = state.snapJoints,
        lowHalfId = state.snapLowHalfId,
        highHalfId = state.snapHighHalfId,
        lowPlacement = state.snapLowHalf?.placement,
        highPlacement = state.snapHighHalf?.placement,
        scale = 1f,
        socketRamp = false,
        repairNote = null,
        fullJoint = false,
        hookLengthMm = null,
        hookThicknessMm = null,
        hookLipMm = null,
    )

    /** One joint on the seam, at [point], as a tap would place it. */
    private fun spec(point: Vec3, halfId: String? = "low"): SnapJointSpec = SnapJointSpec(
        anchorPointMm = point,
        anchorHalfId = halfId,
        beamHalf = SnapJoint.JointHalf.LOW,
        tightness = SnapTightness.LOOSE,
        barbs = 1,
        facing = SnapFacing.SAME,
    )

    /** The same session with a joint placed, selected, as a tap would leave it. */
    private fun MainUiState.withJoint(point: Vec3, halfId: String? = "low"): MainUiState =
        copy(snapJoints = listOf(spec(point, halfId)), snapSelectedJoint = 0)

    /** The same session's selected joint moved to [point]. */
    private fun MainUiState.withAnchor(point: Vec3): MainUiState =
        copy(snapJoints = snapJoints.mapIndexed { index, one -> if (index == snapSelectedJoint) one.copy(anchorPointMm = point) else one })

    /** The plate as a split leaves it: two 40 mm boxes meeting at Z = 20. */
    private fun splitState(): MainUiState {
        val low = MeshFixtures.box(0f, 0f, 0f, 40f, 40f, 20f, name = "part lower")
        val high = MeshFixtures.box(0f, 0f, 20f, 40f, 40f, 40f, name = "part upper")
        return MainUiState(
            printer = printer,
            models = listOf(
                plateObject("low", low),
                plateObject("high", high),
            ),
            selectedModelId = "low",
        ).copy(
            snapLowHalfId = "low",
            snapHighHalfId = "high",
            snapAxis = ModelPlacement.Axis.Z,
            snapLowFaceMm = 20f,
            snapHighFaceMm = 20f,
        ).withJoint(Vec3(20f, 20f, 31f))
    }

    private fun jointFor(state: MainUiState): SnapFitJoint =
        (SnapJoint.build(
            axis = state.snapAxis,
            anchorMm = state.snapAnchorLocalMm!!,
            scale = 1f,
            lowHalf = state.snapLowFitHalf!!,
            highHalf = state.snapHighFitHalf!!,
            beamHalf = SnapJoint.JointHalf.LOW,
        ) as SnapJoint.Either.Placed).placement.joint

    /** The same object with its placed mesh - and so its bounds - moved. */
    private fun PlateObject.movedBy(dx: Float, dy: Float, dz: Float): PlateObject {
        val builder = MeshSolidBuilder(name)
        val vertices = mesh.interleavedVertices
        for (triangle in 0 until mesh.triangleCount) {
            val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE
            builder.addTriangle(
                vertices[base] + dx, vertices[base + 1] + dy, vertices[base + 2] + dz,
                vertices[base + 6] + dx, vertices[base + 7] + dy, vertices[base + 8] + dz,
                vertices[base + 12] + dx, vertices[base + 13] + dy, vertices[base + 14] + dz,
            )
        }
        return copy(mesh = builder.build())
    }

    /** A plate object holding [mesh], placed where the mesh already is. */
    private fun plateObject(id: String, mesh: StlMesh): PlateObject = PlateObject(
        id = id,
        name = mesh.displayName,
        sourceMesh = mesh,
        mesh = mesh,
        sourcePath = null,
        placement = ModelPlacement.centeredOnBed(mesh, bedWidthMm = 220.0, bedDepthMm = 220.0),
    )
}
