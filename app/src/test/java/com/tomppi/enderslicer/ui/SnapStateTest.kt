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
        val preview = SnapPreview(
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
            scale = 1f,
            socketRamp = false,
            repairNote = null,
            fullJoint = false,
            hookLengthMm = null,
            hookThicknessMm = null,
            hookLipMm = null,
            lowFaceMm = 20f,
            highFaceMm = 20f,
        )

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
            "Tap the model to place a joint on the seam.",
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
        assertEquals("and the tool asks for a tap again", "Tap the model to place a joint on the seam.", empty.snapBlockedReason)
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
