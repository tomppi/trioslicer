package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.ModelPlacement
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Whether the library's own piece fits the place it has been put in.
 *
 * The failure this pins is the one no closedness, seat or pair check can see: a
 * 10.2 mm hook on a 10 mm face unions a closed solid, grafts a closed catch and
 * still closes the pair - with half the hook standing in thin air. Every number
 * here is measured, and every one of them comes from the same fit the build
 * itself asks before it places a triangle.
 */
class SnapLibraryFitTest {
    /**
     * A shape with room builds, and its own footprint - measured in the mesh,
     * not read back from the fit - stands inside the room at the anchor.
     */
    @Test
    fun aHookThatFitsBuildsWithItsFootprintInsideTheRoom() {
        assumeTrue("the host Manifold build is not on java.library.path", MeshBoolean.ensureLoaded())
        val shape = SnapLibraryShape.CANTILEVER01
        val pair = pairOf(shape)
        val (low, high) = halves(40f, 40f)
        val anchor = Vec3(20f, 20f, SEAM_Z)

        val fit = requireNotNull(SnapLibrary.fitAt(pair, UP, anchor, 1f, low, hookOnLowHalf = true))
        assertTrue("a 20 mm shape fits a 40 mm face: " + fit.refusal(), fit.fits)
        assertEquals("the run either side of the anchor is the whole edge", 40f, fit.alongMm, 0.1f)
        assertEquals("and the face is the whole depth across it", 40f, fit.acrossMm, 0.1f)
        assertNull("with room to spare, nothing is said", fit.tightWarning())

        val joint = placedOf(pair, low, high, anchor, 1f)
        val bounds = joint.unionSolid.bounds
        assertEquals("the hook is its own size at 1:1", 20f, bounds.width, 0.05f)
        assertEquals(20f, bounds.depth, 0.05f)
        assertTrue(
            "and its footprint stands inside the face: " + bounds,
            bounds.minX >= -0.01f && bounds.maxX <= 40.01f && bounds.minY >= -0.01f && bounds.maxY <= 40.01f,
        )
        assertEquals(
            "which is what the fit said it would be",
            fit.needsAlongMm,
            maxOf(bounds.width, bounds.depth),
            0.01f,
        )
    }

    /**
     * A hook too big for its place is refused by name, with BOTH sets of numbers
     * and the scale that would fit - and nothing is added to either half.
     */
    @Test
    fun aHookTooBigForItsPlaceIsRefusedWithBothNumbersAndNoMaterial() {
        assumeTrue("the host Manifold build is not on java.library.path", MeshBoolean.ensureLoaded())
        val pair = pairOf(SnapLibraryShape.CANTILEVER01)
        val (low, high) = halves(10f, 10f)
        val before = MeshVolume.of(low.mesh) + MeshVolume.of(high.mesh)

        val result = SnapJoint.build(
            axis = ModelPlacement.Axis.Z,
            // On the seam's own rim, which is where a library pair belongs.
            anchorMm = Vec3(10f, 5f, SEAM_Z),
            scale = 0.51f,
            lowHalf = low,
            highHalf = high,
            beamHalf = SnapJoint.JointHalf.LOW,
            style = SnapJointStyle.LIBRARY_HOOK,
            library = pair,
        )

        val failure = (result as? SnapJoint.Either.Failed)
            ?: error("the shape was built where it cannot fit: " + result)
        val reason = failure.failure.reason
        assertTrue("the hook's own numbers are in it: " + reason, reason.contains("10.2 mm along the seam"))
        assertTrue("both of them: " + reason, reason.contains("10.2 mm across it"))
        assertTrue("and the face's: " + reason, reason.contains("gives 10.0 mm along and 10.0 mm across"))
        assertTrue("with the lever named: " + reason, reason.contains("scale it to 0.50\u00d7 at most"))
        assertTrue("and the shape by name: " + reason, reason.contains("cantilever01"))

        assertEquals(
            "nothing was added to either half: both volumes are what they were",
            before,
            MeshVolume.of(low.mesh) + MeshVolume.of(high.mesh),
            1e-6,
        )
    }

    /**
     * The scale the refusal suggests really does fit, in the fit and in the
     * mesh: the lever the sentence names is one that works.
     */
    @Test
    fun theScaleTheRefusalSuggestsDoesFit() {
        assumeTrue("the host Manifold build is not on java.library.path", MeshBoolean.ensureLoaded())
        val pair = pairOf(SnapLibraryShape.CANTILEVER01)
        val (low, high) = halves(10f, 10f)
        val anchor = Vec3(10f, 5f, SEAM_Z)

        val refused = requireNotNull(SnapLibrary.fitAt(pair, UP, anchor, 0.51f, low, hookOnLowHalf = true))
        assertTrue("the shape is too big at 0.51x", !refused.fits)
        // What the panel prints, and what the user can actually set: floored to
        // the slider's own hundredth, never rounded up past what fits.
        val suggested = refused.maxScaleLabel
        assertTrue("and the suggestion is smaller than the scale that failed", suggested < 0.51f)
        assertTrue("and it is under the printed one, never over", suggested <= refused.maxScale + 1e-4f)

        val atSuggestion = requireNotNull(SnapLibrary.fitAt(pair, UP, anchor, suggested, low, hookOnLowHalf = true))
        assertTrue("the suggested scale fits: " + atSuggestion.refusal(), atSuggestion.fits)
        assertTrue(
            "and it is said to be tight, with almost nothing to spare",
            atSuggestion.tight && atSuggestion.tightWarning() != null,
        )

        val joint = placedOf(pair, low, high, anchor, suggested)
        val bounds = joint.unionSolid.bounds
        assertTrue(
            "the built hook is no bigger than the face it stands on: " + bounds,
            bounds.width <= 10.01f && bounds.depth <= 10.01f,
        )
    }

    /**
     * The same shape at the same scale, on the same seam: one place has the room
     * for it and another does not, and the refusal is about the place it is in.
     *
     * The half is an L in section, so its face is 40 mm deep where the long arm
     * is and 20 mm where the short one is: a 25 mm hook fits the first place and
     * not the second, on the same seam and at the same scale.
     */
    @Test
    fun aShapeThatFitsInOnePlaceAndNotAnotherSaysSoPerPlace() {
        assumeTrue("the host Manifold build is not on java.library.path", MeshBoolean.ensureLoaded())
        val pair = pairOf(SnapLibraryShape.CANTILEVER01)
        // Both halves are the same L, so neither can take the hook on the short
        // arm: the answer is about the place, not about which half is chosen.
        val low = SnapFitHalf.inPlace(lShapedHalf(0f, SEAM_Z, "low"), SEAM_Z)
        val high = SnapFitHalf.inPlace(lShapedHalf(SEAM_Z, SEAM_Z * 2f, "high"), SEAM_Z)

        fun place(anchor: Vec3) = SnapJoint.build(
            axis = ModelPlacement.Axis.Z,
            anchorMm = anchor,
            scale = 1.25f,
            lowHalf = low,
            highHalf = high,
            beamHalf = SnapJoint.JointHalf.LOW,
            style = SnapJointStyle.LIBRARY_HOOK,
            library = pair,
        )

        val onTheLongArm = place(Vec3(10f, 0f, SEAM_Z))
        assertTrue(
            "the 40 mm face carries the 25 mm hook: " + onTheLongArm,
            onTheLongArm is SnapJoint.Either.Placed,
        )

        // Near the arm's own outer edge, so the run the place gives is that
        // edge's whole length and the depth is the arm's own 20 mm.
        val onTheShortArm = place(Vec3(3f, 30f, SEAM_Z))
        val failure = (onTheShortArm as? SnapJoint.Either.Failed)
            ?: error("a 25 mm hook fitted a 20 mm face: " + onTheShortArm)
        val reason = failure.failure.reason
        assertTrue("the shape's own numbers are in it: " + reason, reason.contains("25.0 mm along the seam"))
        assertTrue("and the place's, per dimension: " + reason, reason.contains("gives 40.0 mm along and 20.0 mm across"))
        assertTrue(
            "with the depth named as the lever, not the run: " + reason,
            reason.contains("place it where the face is deeper"),
        )
    }

    /** An L in section between [z0] and [z1]: a 40 x 20 arm and a 20 x 20 one. */
    private fun lShapedHalf(z0: Float, z1: Float, name: String): StlMesh {
        val long = MeshFixtures.box(0f, 0f, z0, 40f, 20f, z1, name + " long arm")
        val short = MeshFixtures.box(0f, 20f, z0, 20f, 40f, z1, name + " short arm")
        val union = MeshBoolean.union(long, short)
        return (union as? MeshBoolean.Result.Success)?.mesh
            ?: error("the L could not be built: " + (union as MeshBoolean.Result.Failure).reason)
    }

    /** The hook placed on the two halves, through the pipeline's own entry. */
    private fun placedOf(
        pair: SnapLibraryPair,
        low: SnapFitHalf,
        high: SnapFitHalf,
        anchor: Vec3,
        scale: Float,
    ): SnapFitJoint {
        val result = SnapJoint.build(
            axis = ModelPlacement.Axis.Z,
            anchorMm = anchor,
            scale = scale,
            lowHalf = low,
            highHalf = high,
            beamHalf = SnapJoint.JointHalf.LOW,
            style = SnapJointStyle.LIBRARY_HOOK,
            library = pair,
        )
        return (result as? SnapJoint.Either.Placed)
            ?.placement
            ?.joint
            ?: error("the shape was refused where the fit said it fits: " + result)
    }

    /** Two solid halves [size] across, meeting at [SEAM_Z]. */
    private fun halves(size: Float, height: Float): Pair<SnapFitHalf, SnapFitHalf> = Pair(
        SnapFitHalf.inPlace(MeshFixtures.box(0f, 0f, 0f, size, size, SEAM_Z, "low"), SEAM_Z),
        SnapFitHalf.inPlace(MeshFixtures.box(0f, 0f, SEAM_Z, size, size, SEAM_Z + height, "high"), SEAM_Z),
    )

    private fun pairOf(shape: SnapLibraryShape): SnapLibraryPair = SnapLibrary.load(shape, ::read)

    private fun read(file: String): StlMesh {
        val directory = listOf(
            File("src/main/assets/" + SnapLibrary.DIRECTORY),
            File("app/src/main/assets/" + SnapLibrary.DIRECTORY),
        ).firstOrNull(File::isDirectory) ?: error("the library assets are not where they were")
        return StlParser.parse(File(directory, file), file)
    }

    private companion object {
        /** Where the two test halves meet. */
        const val SEAM_Z = 20f

        /** The split's own assembly direction. */
        val UP = Vec3(0f, 0f, 1f)
    }
}
