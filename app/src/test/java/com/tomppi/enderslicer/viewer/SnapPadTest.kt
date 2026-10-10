package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.ModelPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The whole-seam pad, contoured to the material it stands on.
 *
 * What these measure is the thing a closedness check cannot: the pad has to
 * lie inside the half's own material, it has to stay out of a hollow middle,
 * and a wall too thin for a rim has to be refused so the ladder can drop a
 * rung rather than place a pad that is not there.
 *
 * Runs against the host build of the same Manifold JNI shim the phone runs
 * (scripts/build-manifold-host.sh); on a machine without it the test skips.
 */
class SnapPadTest {
    @Test
    fun thePadFollowsTheMaterialOfAHollowSeam() {
        assumeTrue(
            "the host Manifold build is not on java.library.path; run scripts/build-manifold-host.sh",
            MeshBoolean.ensureLoaded(),
        )
        val hollow = MeshFixtures.hollowBox(size = 40f, height = 20f, cavity = 30f)
        val high = MeshFixtures.box(0f, 0f, 20f, 40f, 40f, 40f)
        val joint = fullJoint(hollow, high)

        val contoured = SnapPad.contour(joint, SnapFitHalf.inPlace(hollow, 20f), SnapFitHalf.inPlace(high, 20f))
        assertNotNull("a hollow seam still gets a contoured pad", contoured)

        val plain = MeshVolume.of(joint.registrationSolid)
        val pad = MeshVolume.of(contoured!!.registrationSolid)
        assertTrue("the pad is not a wall across the void: " + pad + " of " + plain, pad < 0.5 * plain)
        assertTrue("and a rim survives, rather than the pad collapsing: " + pad, pad > 0.05 * plain)

        val shared = sharedVolume(contoured.registrationSolid, hollow)
        assertTrue(
            "the pad lies in the material: " + shared + " of " + pad + " mm3 (the rest is the tongue standing proud)",
            shared > 0.6 * pad,
        )
        println(
            "PROOF hollow pad=" + pad + " of face-sized " + plain + " in_material=" + shared +
                " recess=" + MeshVolume.of(contoured.registrationRecess),
        )
    }

    @Test
    fun aSolidSeamKeepsTheFaceSizedPad() {
        assumeTrue(
            "the host Manifold build is not on java.library.path; run scripts/build-manifold-host.sh",
            MeshBoolean.ensureLoaded(),
        )
        val low = MeshFixtures.box(0f, 0f, 0f, 40f, 40f, 20f, name = "low half")
        val high = MeshFixtures.box(0f, 0f, 20f, 40f, 40f, 40f, name = "high half")
        val joint = fullJoint(low, high)

        val contoured = SnapPad.contour(joint, SnapFitHalf.inPlace(low, 20f), SnapFitHalf.inPlace(high, 20f))
        assertNotNull("a solid seam keeps its pad", contoured)

        val plain = MeshVolume.of(joint.registrationSolid)
        val pad = MeshVolume.of(contoured!!.registrationSolid)
        assertEquals("the contoured pad is the pad it always was", plain, pad, 1e-3 * plain)
        val plainBounds = joint.registrationSolid.bounds
        val padBounds = contoured.registrationSolid.bounds
        listOf(
            "minX" to (plainBounds.minX to padBounds.minX),
            "maxX" to (plainBounds.maxX to padBounds.maxX),
            "minY" to (plainBounds.minY to padBounds.minY),
            "maxY" to (plainBounds.maxY to padBounds.maxY),
            "minZ" to (plainBounds.minZ to padBounds.minZ),
            "maxZ" to (plainBounds.maxZ to padBounds.maxZ),
        ).forEach { (name, pair) ->
            assertEquals("and its box is the same box at " + name, pair.first, pair.second, 1e-3f)
        }
        assertTrue(
            "the pad still lies inside the half",
            sharedVolume(contoured.registrationSolid, low) > 0.6 * pad,
        )
    }

    @Test
    fun aWallTooThinForARimIsRefusedWhereAThickerOneIsNot() {
        assumeTrue(
            "the host Manifold build is not on java.library.path; run scripts/build-manifold-host.sh",
            MeshBoolean.ensureLoaded(),
        )
        val high = MeshFixtures.box(0f, 0f, 20f, 40f, 40f, 40f)

        // A paper-thin wall: every inset that could leave a rim takes the
        // whole wall, so there is nothing to contour and the rung must give
        // way. The engine's own thin-shell limit lands in the same place - a
        // Benchy hull is about a millimetre, and a pad there is refused rather
        // than emitted as a sliver - which is what puts the reason in the
        // panel and drops the joint to the lesser hook.
        val highHalf = SnapFitHalf.inPlace(high, 20f)
        val paper = MeshFixtures.hollowBox(size = 40f, height = 20f, cavity = 39.7f)
        assertNull(
            "a paper-thin wall cannot carry a pad",
            SnapPad.contour(fullJoint(paper, high), SnapFitHalf.inPlace(paper, 20f), highHalf),
        )
        val hull = MeshFixtures.hollowBox(size = 40f, height = 20f, cavity = 38f)
        assertNull(
            "nor can a hull-thin millimetre wall",
            SnapPad.contour(fullJoint(hull, high), SnapFitHalf.inPlace(hull, 20f), highHalf),
        )

        // The same shape with enough wall is exactly what the contour is for:
        // the rim survives and the pad follows it.
        val walled = MeshFixtures.hollowBox(size = 40f, height = 20f, cavity = 32f)
        assertNotNull(
            "a 4 mm wall can carry a contoured pad",
            SnapPad.contour(fullJoint(walled, high), SnapFitHalf.inPlace(walled, 20f), highHalf),
        )
    }

    /**
     * The assertion that would have caught a one-sided joint: what the pad adds
     * to the beam half and what the recess takes out of the mate have to be the
     * same step, and each has to be big enough to see. A recess built in the
     * wrong frame cuts nothing and fails on the first number.
     */
    @Test
    fun thePadAndTheRecessAreComplementaryOnEverySeam() {
        assumeTrue(
            "the host Manifold build is not on java.library.path; run scripts/build-manifold-host.sh",
            MeshBoolean.ensureLoaded(),
        )
        val small = MeshFixtures.box(0f, 0f, 0f, 10f, 10f, 5f, name = "small lower")
        val smallUpper = MeshFixtures.box(0f, 0f, 5f, 10f, 10f, 10f, name = "small upper")
        val large = MeshFixtures.box(0f, 0f, 0f, 100f, 100f, 50f, name = "large lower")
        val largeUpper = MeshFixtures.box(0f, 0f, 50f, 100f, 100f, 100f, name = "large upper")
        val hollow = MeshFixtures.hollowBox(size = 40f, height = 20f, cavity = 30f)
        val hollowUpper = MeshFixtures.box(0f, 0f, 20f, 40f, 40f, 40f, name = "hollow upper")

        val cases = listOf(
            Case("10 mm cube", small, smallUpper, 5f, Vec3(5f, 5f, 5f)),
            Case("100 mm cube", large, largeUpper, 50f, Vec3(50f, 50f, 50f)),
            Case("hollow seam", hollow, hollowUpper, 20f, Vec3(20f, 20f, 20f)),
        )
        for (case in cases) {
            val joint = fullJoint(case.low, case.high, case.face, case.anchor)
            val contoured = SnapPad.contour(
                joint,
                SnapFitHalf.inPlace(case.low, case.face),
                SnapFitHalf.inPlace(case.high, case.face),
            )
            assertNotNull(case.name + ": the contoured pair is built", contoured)
            val pad = contoured!!.registrationSolid
            val recess = contoured.registrationRecess
            assertTrue(
                case.name + ": the recess lands on the mate, not in the gap",
                overlaps(recess.bounds, case.high.bounds),
            )
            val proud = MeshVolume.of(pad) - sharedVolume(pad, case.low)
            val removed = sharedVolume(recess, case.high)
            assertTrue(case.name + ": the pad adds a step: " + proud + " mm3", proud >= MIN_STEP_MM3)
            assertTrue(case.name + ": the recess cuts a step: " + removed + " mm3", removed >= MIN_STEP_MM3)
            assertTrue(
                case.name + ": and they match within the clearance: pad " + proud + ", recess " + removed,
                removed >= proud * 0.8 && removed <= proud * 1.6 + 1.0,
            )
            println("PROOF " + case.name + " pad_added=" + proud + " recess_removed=" + removed)
        }
    }

    private class Case(
        val name: String,
        val low: StlMesh,
        val high: StlMesh,
        val face: Float,
        val anchor: Vec3,
    )

    /** True when two boxes share any volume at all. */
    private fun overlaps(first: MeshBounds, second: MeshBounds): Boolean =
        minOf(first.maxX, second.maxX) > maxOf(first.minX, second.minX) &&
            minOf(first.maxY, second.maxY) > maxOf(first.minY, second.minY) &&
            minOf(first.maxZ, second.maxZ) > maxOf(first.minZ, second.minZ)

    /** The full rung, forced, on two in-place halves with their faces at Z = [face]. */
    private fun fullJoint(
        low: StlMesh,
        high: StlMesh,
        face: Float = 20f,
        anchor: Vec3 = Vec3(20f, 20f, 20f),
    ): SnapFitJoint {
        val joint = SnapFit.generate(
            Vec3(0f, 0f, 1f),
            anchor,
            1f,
            SnapFitHalf.inPlace(low, face),
            SnapFitHalf.inPlace(high, face),
            SnapFitParameters(),
            SnapFitRung.FULL,
        )
        assertNotNull("the full joint has to exist", joint)
        assertEquals(SnapFitRung.FULL, joint!!.rung)
        return joint
    }

    /** A step smaller than this is not a step. */
    private val MIN_STEP_MM3 = 1.0

    private fun sharedVolume(solid: StlMesh, half: StlMesh): Double {
        val shared = MeshBoolean.intersect(solid, half)
        assertTrue(
            "the engine refused the intersection: " + (shared as? MeshBoolean.Result.Failure)?.reason,
            shared is MeshBoolean.Result.Success,
        )
        return (shared as MeshBoolean.Result.Success).volumeMm3
    }
}
