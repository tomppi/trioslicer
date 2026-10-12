package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.viewer.SnapLibrary
import com.tomppi.enderslicer.viewer.SnapLibraryShape
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library hook's wiring: the shape picker, the tap that places one, and the
 * line that refuses to invent a strain figure.
 *
 * None of this is visible in the state a unit test can build - a Compose row,
 * an assets read and a branch inside the boolean pipeline are calls, not values
 * - so each claim is pinned against the source that owns it, the way the snap
 * fit's other wiring is. A later edit that drops the picker, hides Add behind
 * the wall lip's guard, or prints a strain number for a shape this app did not
 * design, fails here instead of on the phone.
 */
class SnapLibraryWiringContractTest {
    private val app = source("ui/EnderSlicerApp.kt")
    private val state = source("ui/MainUiState.kt")
    private val viewModel = source("ui/MainViewModel.kt")
    private val store = source("data/WorkspaceStateStore.kt")
    private val library = source("viewer/SnapLibrary.kt")
    private val joint = source("viewer/SnapJoint.kt")

    /**
     * The picker: the eight names, each with the README's own line about it, in
     * the Setup column, wired to the view model that puts it on the joint.
     */
    @Test
    fun thePanelOffersTheEightShapesAndWiresThePicker() {
        assertTrue(
            "a shape row, from the one enum the eight shapes come from",
            app.contains("SnapLibraryShape.entries") && app.contains("\"Shape:\""),
        )
        assertTrue(
            "shown for the library style only",
            app.contains("if (style == SnapJointStyle.LIBRARY_HOOK) {") &&
                app.contains("SnapLibraryPicker(libraryShape, actions.onLibraryShape)"),
        )
        assertTrue(
            "each entry carries the README's own line about the shape",
            app.contains("shape.summary"),
        )
        assertTrue(
            "the control reaches the view model",
            app.contains("onLibraryShape = viewModel::setSnapLibraryShape") &&
                viewModel.contains("fun setSnapLibraryShape(shape: SnapLibraryShape)"),
        )
        assertTrue(
            "which writes the selected library joint and the next one's default",
            viewModel.contains("it[target] = it[target].copy(libraryShape = shape)") &&
                viewModel.contains("snapLibraryShape = shape"),
        )
        assertTrue(
            "and travels with the joint, so a tapped joint carries its own shape",
            viewModel.contains("libraryShape = state.snapLibraryShape") &&
                store.contains("put(\"libraryShape\", joint.libraryShape.name)") &&
                state.contains("libraryShape = joint.libraryShape"),
        )
        assertTrue(
            "a joint recorded before the library existed decodes as the shape the picker starts on",
            store.contains("SnapLibraryShape.valueOf(json.optString(\"libraryShape\"))") &&
                store.contains("getOrDefault(SnapLibraryShape.CANTILEVER01)"),
        )
    }

    /** The eight names and their lines are the README's, not a second copy that can drift. */
    @Test
    fun theEightShapesAndTheirLinesAreTheReadmes() {
        val readme = File("src/main/assets/${SnapLibrary.DIRECTORY}/README.md")
            .takeIf(File::isFile)
            ?: File("app/src/main/assets/${SnapLibrary.DIRECTORY}/README.md")
        assertTrue("the library's own README is in the repo", readme.isFile)
        val rows = readme.readLines().filter { it.startsWith("| ") && !it.startsWith("| shape") }
            .map { line -> line.split("|").map(String::trim).filter(String::isNotEmpty) }
            .filter { it.size >= 3 }
        assertEquals("the README's table names the shapes", 8, rows.size)
        for (row in rows) {
            val shape = SnapLibraryShape.entries.firstOrNull { it.label == row[0] }
            assertTrue("the picker has the README's shape " + row[0], shape != null)
            assertEquals(
                "the picker's line about " + row[0] + " is the README's",
                row[2],
                shape!!.summary,
            )
        }
        assertEquals(
            "and the enum adds nothing the README does not name",
            8,
            SnapLibraryShape.entries.size,
        )
    }

    /**
     * Placing by tap: the Add control is offered for the library style - it is
     * not the wall lip's, which places its own ring - and a tap adds a joint
     * whose style and shape are the panel's, which the pipeline then builds.
     */
    @Test
    fun aTappedLibraryJointIsAddedAndBuilt() {
        assertTrue(
            "Add is inside the same guard as the cantilever's, which the lip is outside of",
            app.contains("if (state.snapStyle != SnapJointStyle.WALL_LIP) {") &&
                app.contains("\"Add joint\""),
        )
        assertTrue(
            "the library is not given the cantilever's rim solver, which would place its joints for it",
            app.contains("if (style != SnapJointStyle.LIBRARY_HOOK) {") &&
                app.contains("\"Auto spread\""),
        )
        assertTrue(
            "a tap while armed adds a joint of the panel's style and shape",
            viewModel.contains("state.snapAddingJoint -> {") &&
                viewModel.contains("style = state.snapStyle") &&
                viewModel.contains("libraryShape = state.snapLibraryShape"),
        )
        assertTrue(
            "the style is one the build can be told",
            source("viewer/SnapLip.kt").contains("LIBRARY_HOOK(\"Library hook\")") &&
                viewModel.contains("style = spec.style"),
        )
        assertTrue(
            "the build is handed the loaded pair, so the builder itself stays arithmetic",
            joint.contains("library: SnapLibraryPair? = null") &&
                joint.contains("SnapLibrary.build(") &&
                viewModel.contains("library = libraries[spec.libraryShape]"),
        )
        assertTrue(
            "the shape is read out of the app's own assets, once per shape per process",
            viewModel.contains("SnapLibrary.load(shape, ::libraryMesh)") &&
                viewModel.contains("app.assets.open(SnapLibrary.DIRECTORY + \"/\" + file)") &&
                library.contains("private val cache = HashMap<SnapLibraryShape, SnapLibraryPair>()"),
        )
        assertTrue(
            "and the joints already placed keep their own shapes, so a seam can mix them",
            viewModel.contains("specs.filter { it.style == SnapJointStyle.LIBRARY_HOOK }") &&
                viewModel.contains(".map { it.libraryShape }.toSet()"),
        )
    }

    /**
     * The fit is decided IN the build, with the numbers the build acts on.
     *
     * A shape that does not fit the place it is in has to be refused with both
     * sets of numbers and the lever that fixes it - and the place's room and the
     * piece's footprint must be read by the same function the build asks before
     * it places a triangle, or the panel's number is decoration. The room itself
     * comes off the same cap outline every other feature of the seam is read
     * from, and the piece measured is the hook, which is the one that has to sit
     * on the part; the catch is grafted local and takes no room of its own.
     */
    @Test
    fun theFitIsDecidedInsideTheBuildWithTheSameNumbers() {
        assertTrue(
            "the build asks the fit before it places a triangle",
            library.contains("val fit = fitAt(pair, axisDirection, anchorMm, scale, hookHalf, hookOnLowHalf)") &&
                library.contains("if (fit != null && !fit.fits) return Outcome.Refused(fit.refusal())"),
        )
        assertTrue(
            "and it is the build's own outcome that refuses, so the row, the ghost, " +
                "the preview and Apply all answer with the one sentence",
            joint.contains("is SnapLibrary.Outcome.Refused -> Attempt.Refused(placed.reason)"),
        )
        assertTrue(
            "the room is read off the seam's own cap outline, in the halves' own coordinates",
            library.contains("SnapSeam.room(hookHalf, axisDirection, anchorMm)"),
        )
        assertTrue(
            "the footprint measured is the hook piece's own",
            library.contains("pair.hookSizeMm"),
        )
        assertTrue(
            "a fit with almost nothing to spare is said out loud, on the joint's own warnings",
            library.contains("fit?.tightWarning()") &&
                app.contains("for (warning in one.joint.warnings)"),
        )
        assertTrue(
            "and the refusal names the lever: the largest scale that fits, and the kind of place that would",
            library.contains("scale it to %.2f") && library.contains("at most, or %s") &&
                library.contains("place it on a longer run") &&
                library.contains("place it where the face is deeper"),
        )
    }

    /**
     * The catch is grafted local, and the engine is what says the clip produced
     * a closed solid rather than the code assuming it.
     */
    @Test
    fun theCatchIsClippedToTheBoxAroundTheHookAndTheResultIsVerifiedClosed() {
        assertTrue(
            "the box is the hook's own reach - its material on the catch's side of the mating plane - grown",
            library.contains("private fun reachBounds(hook: StlMesh, planeZ: Float): Box3?") &&
                library.contains("reach.minX - GRAFT_MARGIN_MM") &&
                library.contains("const val GRAFT_MARGIN_MM = 3f"),
        )
        assertTrue(
            "and never past the catch's own envelope",
            library.contains("max(catch.bounds.minX, reach.minX - GRAFT_MARGIN_MM)") &&
                library.contains("min(catch.bounds.maxX, reach.maxX + GRAFT_MARGIN_MM)"),
        )
        assertTrue(
            "the clip is an intersection of closed solids",
            library.contains("MeshBoolean.intersect(catch, boxMesh)"),
        )
        assertTrue(
            "the box with the catch taken out is what frees the mate's own material for the hooks",
            library.contains("MeshBoolean.subtract(boxMesh, catch)") &&
                viewModel.contains("engine.subtract(mateMesh, catchVoid)") &&
                viewModel.contains("engine.union(freed.mesh, joint.registrationSolid)"),
        )
        assertTrue(
            "the two applied halves are checked for closing on each other, which a round pair " +
                "on a solid seam cannot do",
            viewModel.contains("private fun clashVolume(mesh: StlMesh, other: StlMesh, engine: SnapFitGate.Engine)") &&
                viewModel.contains("val catchInHookPlace = remap(") &&
                viewModel.contains("val hookInMatePlace = remap(") &&
                viewModel.contains("joint.registrationSolid, mateFit, hookFit, SnapLibrary.CATCH_NAME") &&
                viewModel.contains("would leave the two halves overlapping by") &&
                // And a check the engine could not make is its own refusal, never
                // a silent pass: NaN is not greater than the threshold.
                viewModel.contains("could not be checked for the two halves closing on each other"),
        )
        assertTrue(
            "the round pair is measured to need that room, in the meshes",
            testSource("viewer/SnapLibraryTest.kt").contains("the two halves would overlap by") &&
                testSource("viewer/SnapLibraryTest.kt").contains("clash.volumeMm3 > 0.5"),
        )
        assertTrue(
            "and the engine's own manifold verdict is what says the result is closed",
            library.contains("MeshBoolean.manifoldStatus(mesh) != SnapFitGate.NO_ERROR") &&
                library.contains("is not a closed"),
        )
        assertTrue(
            "a shape that cannot be prepared or clipped is named rather than placed",
            library.contains("throw IllegalStateException(result.reason)") &&
                library.contains("the engine could not "),
        )
    }

    /**
     * The honest line. A library shape's spring is not this app's design, so no
     * strain is computed for it and the panel says so instead of printing a
     * number - and the library's own clearance is the one number that IS
     * reported, measured from the files when the shape is loaded.
     */
    @Test
    fun theStrainLineSaysItIsNotComputedAndTheGapIsMeasured() {
        assertTrue(
            "the line exists, and it says the figure would be invented",
            library.contains("const val STRAIN_NOTE") &&
                library.contains("Strain is not computed for a library shape") &&
                library.contains("would be invented"),
        )
        assertTrue(
            "the panel shows it for a library joint",
            app.contains("SnapLibrary.STRAIN_NOTE"),
        )
        assertTrue(
            "and no strain number is computed or printed for one",
            library.contains("rootStrainPercent = 0f") &&
                !body(app, "if (style == SnapJointStyle.LIBRARY_HOOK) {", "if (style == SnapJointStyle.WALL_LIP) {")
                    .contains("Strain %.1f"),
        )
        assertTrue(
            "the clearance is measured from the two pieces, not assumed",
            library.contains("gapOneToOneMm = smallestGapMm(hook, catch)") &&
                library.contains("internal fun smallestGapMm(first: StlMesh, second: StlMesh, contactMm: Float = CONTACT_MM): Float"),
        )
        assertTrue(
            "its contacts are left out of the measurement, because the pair is assembled seated",
            library.contains("private const val CONTACT_MM = 0.0001f") &&
                library.contains("it in contactMm..<best") &&
                library.contains("distance >= contactMm && distance < best"),
        )
        assertTrue(
            "it is reported at the current scale and warns under what FDM resolves",
            library.contains("const val MIN_PRINTABLE_GAP_MM = 0.15f") &&
                library.contains("fun gapSummary(dimensions: SnapFitDimensions)") &&
                library.contains("fun gapWarning(dimensions: SnapFitDimensions): String?") &&
                app.contains("SnapLibrary.gapWarning(dimensions)") &&
                app.contains("SnapLibrary.gapSummary(dimensions)"),
        )
        assertTrue(
            "and it is the same scale both pieces follow",
            library.contains("libraryGapMm = pair.gapOneToOneMm * scale") &&
                library.contains("librarySizeMm = size * scale"),
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

    /** The same for the test sources, which live under src/test rather than src/main. */
    private fun testSource(relative: String): String {
        val candidates = listOf(
            File("src/test/java/com/tomppi/enderslicer/$relative"),
            File("app/src/test/java/com/tomppi/enderslicer/$relative"),
        )
        val file = candidates.firstOrNull(File::isFile)
            ?: error("Unable to locate test source for $relative from " + File(".").absolutePath)
        return file.readText()
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
