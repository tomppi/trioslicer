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
    private val viewModel = source("ui/MainViewModel.kt")
    private val store = source("data/WorkspaceStateStore.kt")

    /** The style is a control beside the cantilever, and it is what places and re-tunes. */
    @Test
    fun thePanelOffersBothStylesAndWiresTheControl() {
        assertTrue("a style row, from the one enum both styles come from", app.contains("SnapJointStyle.entries"))
        assertTrue("labelled as the choice it is", app.contains("\"Style:\""))
        assertTrue("with what each one does", app.contains("one tooth on the half's own wall"))
        assertTrue("and the cantilever's own description kept", app.contains("a beam across the seam with a barb on it"))
        assertTrue("the control reaches the view model", app.contains("onStyle = viewModel::setSnapStyle"))
        assertTrue(
            "which writes the selected joint's style and the next one's default",
            viewModel.contains("fun setSnapStyle(style: SnapJointStyle)") &&
                viewModel.contains("copy(style = style)") &&
                viewModel.contains("snapStyle = style"),
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
