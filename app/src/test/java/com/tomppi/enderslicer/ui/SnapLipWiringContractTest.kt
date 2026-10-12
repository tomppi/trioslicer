package com.tomppi.enderslicer.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wall lip's wiring: the style control, the two sizes, and where the lip's
 * booleans run.
 *
 * None of this is visible in the state a unit test can build - a Compose row and
 * a branch inside the boolean pipeline are calls, not values - so each claim is
 * pinned against the source that owns it, the way the snap fit's other wiring is.
 * A later edit that drops the style from the record, or routes the lip's ledge
 * through the cantilever's union, fails here instead of on the phone.
 */
class SnapLipWiringContractTest {
    private val app = source("ui/EnderSlicerApp.kt")
    private val state = source("ui/MainUiState.kt")
    private val viewModel = source("ui/MainViewModel.kt")
    private val store = source("data/WorkspaceStateStore.kt")

    /** The style is a control beside the cantilever, and it is what places and re-tunes. */
    @Test
    fun thePanelOffersBothStylesAndWiresTheControl() {
        assertTrue("a style row, from the one enum both styles come from", app.contains("SnapJointStyle.entries"))
        assertTrue("labelled as the choice it is", app.contains("\"Style:\""))
        assertTrue(
            "with what each one does",
            app.contains("one tooth ring on the lip half, one ledge ring on the mate"),
        )
        assertTrue("and the cantilever's own description kept", app.contains("a beam across the seam with a barb on it"))
        assertTrue("the control reaches the view model", app.contains("onStyle = viewModel::setSnapStyle"))
        assertTrue(
            "which puts the ring on the seam instead of a tapped joint",
            viewModel.contains("fun setSnapStyle(style: SnapJointStyle)") &&
                viewModel.contains("listOf(ringJointSpec(state))") &&
                viewModel.contains("snapStyle = style"),
        )
    }

    /**
     * The two controls the ring is sized by: the wall it leaves where the part is
     * solid, and how far its recess floor goes past the step. Both are the lip's
     * own, both reach the build, and both come back with the record.
     */
    @Test
    fun theWallAndDepthControlsBelongToTheLipAndReachTheBuild() {
        val hook = body(app, "private fun SnapHookGroup(", "private fun SnapFitsGroup(")
        assertTrue("the wall's own row", hook.contains("label = \"Wall\""))
        assertTrue("and the depth's", hook.contains("label = \"Depth\""))
        assertTrue(
            "each with its own range",
            hook.contains("LIP_WALL_RANGE") && hook.contains("LIP_DEPTH_RANGE"),
        )
        assertTrue(
            "shown for a wall lip only, beside Engage and Tooth",
            hook.contains("if (style == SnapJointStyle.WALL_LIP) {") && hook.contains("label = \"Engage\""),
        )
        assertTrue(
            "they reach the view model",
            app.contains("onLipWall = viewModel::setSnapLipWall") &&
                app.contains("onLipDepth = viewModel::setSnapLipDepth"),
        )
        assertTrue(
            "which write the selected joint and the next one's default",
            viewModel.contains("lipWallMm = millimetres") &&
                viewModel.contains("snapLipWallMm = millimetres") &&
                viewModel.contains("lipRecessExtraMm = millimetres") &&
                viewModel.contains("snapLipDepthMm = millimetres"),
        )
        assertTrue(
            "the build is told both, and takes the panel's own when a joint carries none",
            state.contains("lipWallOverrideMm = spec.lipWallMm ?: snapLipWallMm") &&
                state.contains("lipRecessExtraOverrideMm = spec.lipRecessExtraMm ?: snapLipDepthMm"),
        )
        assertTrue(
            "a size that moved is built again, so the readout is the joint and not the one before it",
            viewModel.contains("private fun snapSizeTargetIndex") &&
                viewModel.contains("previewSnapJoint()\n    }"),
        )
        assertTrue(
            "and the placement's own parameters carry all four, or the sliders are decoration",
            viewModel.contains("lipWallOverrideMm = spec.lipWallMm ?: state.snapLipWallMm") &&
                viewModel.contains("lipRecessExtraOverrideMm = spec.lipRecessExtraMm ?: state.snapLipDepthMm") &&
                viewModel.contains("lipEngagementOverrideMm = spec.lipEngagementMm ?: state.snapLipEngagementMm"),
        )
        assertTrue(
            "the record keeps both, and a cantilever writes neither",
            viewModel.contains("lipWallMm = spec.lipWallMm") &&
                store.contains("put(\"lipWallMm\"") &&
                store.contains("put(\"lipRecessExtraMm\"") &&
                store.contains("put(\"style\", joint.style.name)"),
        )
        assertTrue(
            "the wall the readout names is the one the closed form used",
            hook.contains("lip.wallThicknessMm") && hook.contains("lip.lipWallAskedMm") &&
                hook.contains("lip.lipRecessMm"),
        )
        val lip = source("viewer/SnapLip.kt")
        assertTrue(
            "and the build sizes the wall and the floor from them",
            lip.contains("val wallAsked = parameters.lipWallOverrideMm ?: parameters.lipWallMm * scale") &&
                lip.contains("val recessDepth = stepAndClearance + extra"),
        )
    }

    /**
     * The lip is one feature and it is the whole seam: the panel says so and does
     * not offer a tap, a move or a spread, which are the cantilever's.
     */
    @Test
    fun thePanelSaysTheLipIsTheWholeSeamAndOffersNoTaps() {
        assertTrue(
            "the sheet names the seam rather than a count",
            app.contains("\"Snap fit · the whole seam\"") && app.contains("the whole seam"),
        )
        assertTrue(
            "the row is the whole seam and not joint one of several",
            app.contains("SnapJointStyle.WALL_LIP.label + \" · the whole seam\""),
        )
        assertTrue(
            "the status line says what happens",
            app.contains("The wall lip is the whole seam: one tooth ring on the lip half and one ") &&
                app.contains("ledge ring on the mate, following the seam's own edge."),
        )
        assertTrue(
            "and the tap controls are behind the lip's own guard",
            app.contains("if (state.snapStyle != SnapJointStyle.WALL_LIP) {") &&
                app.contains("\"Add joint\"") &&
                app.contains("\"Auto spread\""),
        )
        assertTrue(
            "the step the ring needed is read out with its reason",
            app.contains("one.joint.lipStepSummary") &&
                !app.contains("pad %.2f mm: a %.2f mm tooth"),
        )
        assertTrue(
            "and what the material could not give is shown rather than swallowed",
            app.contains("for (warning in one.joint.warnings)"),
        )
        assertTrue(
            "the readout is beside the row in one column and under the list in the wide panel, " +
                "where the list's own height cap cannot clip its last line",
            app.contains("if (!compact) SnapLipReadout(one)") &&
                app.contains("SnapJointStyle.WALL_LIP) SnapLipReadout(one)"),
        )
    }

    /**
     * The ring follows the seam's own outline, and the two halves' step is sized
     * from the ring: not the cantilever's pad depth, and not a tooth at a tap.
     */
    @Test
    fun theRingFollowsTheSeamAndSizesItsOwnStep() {
        val lip = source("viewer/SnapLip.kt")
        assertTrue(
            "the contour is the seam's outline",
            lip.contains("SnapFit.capOutlines(cap)") && lip.contains("insetPolygon(contour, wall)"),
        )
        assertTrue(
            "and it is swept as one closed hook, mitred at every corner",
            lip.contains("private fun ringMesh(") && lip.contains("offsetVertex(previous, here, next, radius)"),
        )
        assertTrue(
            "the step is sized from the ring: the tooth, its ramp and the clearances",
            lip.contains("val stepDepth = toothHeight + run + 2f * clearance") &&
                lip.contains("val stepAndClearance = stepDepth + clearance"),
        )
        assertTrue(
            "and the depth control is the extra cut past that step",
            lip.contains("val recessDepth = stepAndClearance + extra") &&
                lip.contains("val extraAsked = parameters.lipRecessExtraOverrideMm"),
        )
        assertTrue(
            "the ring is read round the whole seam, not at one sample",
            lip.contains("private fun measureRing(") && lip.contains("samples.groupBy { it.edgeIndex }"),
        )
        assertTrue(
            "a corner is a warning with its place, not a refusal",
            lip.contains("another wall joins the seam ") && lip.contains("val warnings = ArrayList<String>()"),
        )
        assertTrue(
            "the lip is dispatched to the ring, with no tap in the build",
            source("viewer/SnapJoint.kt").contains("SnapLip.buildRing("),
        )
    }

    /** The two sizes are the lip's own, they are shown only for it, and they say what they do. */
    @Test
    fun theTwoSizeControlsBelongToTheLipAndSayWhatTheyDo() {
        assertTrue("the engagement control", app.contains("\"Engage\""))
        assertTrue("and the tooth's height", app.contains("\"Tooth\""))
        assertTrue("with their own ranges", app.contains("LIP_ENGAGEMENT_RANGE") && app.contains("LIP_TOOTH_RANGE"))
        val dimensions = body(app, "private fun SnapHookGroup(", "private fun SnapFitsGroup(")
        assertTrue(
            "shown for a wall lip only",
            dimensions.contains("if (style == SnapJointStyle.WALL_LIP) {") &&
                dimensions.contains("return"),
        )
        assertTrue(
            "the engagement is the hold, and the tooth's height is the tooth",
            dimensions.contains("The engagement is the hold: how far the tooth and the ledge overlap") &&
                dimensions.contains("The tooth's height: how far it stands off the wall's face"),
        )
        assertTrue(
            "and the print's one hanging face is named with what compensates for it",
            dimensions.contains("prints deeper than drawn") &&
                dimensions.contains("the engagement is the "),
        )
        assertTrue("the strain is the wall's, at the travel it moves", dimensions.contains("Wall strain"))
        assertTrue(
            "the material's own figure is in the sentence",
            dimensions.contains("SnapLip.MATERIAL_STRAIN_PERCENT"),
        )
    }

    /**
     * The rung ladder belongs to the cantilever: a lip joint is not a rung, and a
     * control that fitted it to the seam would be a lie.
     */
    @Test
    fun theRungLadderIsTheCantilevers() {
        val setup = body(app, "private fun SnapSetupGroup(", "private fun SnapJointRow(")
        val ladder = setup.indexOf("\"Joint:\"")
        val gate = setup.indexOf("if (style == SnapJointStyle.CANTILEVER)")
        assertTrue("the ladder is in the panel", ladder > 0)
        assertTrue("and behind the cantilever's own gate", gate in 0 until ladder)
        assertFalse("never shown for the lip", setup.contains("if (style == SnapJointStyle.WALL_LIP)"))
    }

    /** The lip's parameters are threaded through the build and the record. */
    @Test
    fun theStyleAndTheSizesTravelIntoTheBuildAndTheRecord() {
        assertTrue(
            "the build is told which joint to make",
            viewModel.contains("style = spec.style"),
        )
        assertTrue(
            "and the two sizes with it",
            viewModel.contains("lipEngagementOverrideMm = spec.lipEngagementMm") &&
                viewModel.contains("lipToothHeightOverrideMm = spec.lipToothHeightMm"),
        )
        val record = body(viewModel, "private fun recordOfPair(", "fun setSnapActive(")
        assertTrue(
            "the pair's record keeps all three, so a printed lip can be re-tuned",
            record.contains("style = spec.style") &&
                record.contains("lipEngagementMm = spec.lipEngagementMm") &&
                record.contains("lipToothHeightMm = spec.lipToothHeightMm"),
        )
        assertTrue(
            "and the descriptor writes them for a lip and reads them leniently",
            store.contains("put(\"style\", joint.style.name)") &&
                store.contains("SnapJointStyle.valueOf(json.optString(\"style\"))"),
        )
        assertTrue(
            "an old record's joint is the cantilever it was",
            store.contains("getOrDefault(SnapJointStyle.CANTILEVER)"),
        )
    }

    /**
     * The lip's booleans are its own: the recess comes out of the half the tooth
     * goes into, the tooth goes into that same half, and the ledge is unioned into
     * the MATE - which the cantilever's one-union-per-half pipeline cannot say.
     */
    @Test
    fun theLipsBooleansRunInTheirOwnBranch() {
        val placement = body(viewModel, "private fun placeEveryJoint(", "private fun contouredOrLesser(")
        val branch = placement.indexOf("if (joint.style == SnapJointStyle.WALL_LIP)")
        assertTrue("the lip has its own branch", branch > 0)
        assertTrue(
            "the recess is cut from the tooth's half first",
            placement.contains("engine.subtract(contoured.beamMesh, joint.subtractSolid)"),
        )
        assertTrue(
            "then the tooth is unioned into it",
            placement.contains("engine.union(hollowed.mesh, joint.unionSolid)"),
        )
        assertTrue(
            "and the ledge is unioned into the mate, which is the other half",
            placement.contains("engine.union(contoured.socketMesh, joint.registrationSolid)"),
        )
        assertTrue(
            "a lip whose tooth or ledge could not be made is that joint's own refusal",
            placement.contains("has no wall where it was placed for its tooth to root in") &&
                placement.contains("the wall lip's ledge would stand in the open"),
        )
    }

    /** The text between [from] and the next [to] in [source]. */
    private fun body(source: String, from: String, to: String): String {
        val start = source.indexOf(from)
        assertTrue("the source no longer holds " + from, start >= 0)
        val end = source.indexOf(to, start + from.length)
        assertTrue("the source no longer holds " + to + " after " + from, end > start)
        return source.substring(start, end)
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
