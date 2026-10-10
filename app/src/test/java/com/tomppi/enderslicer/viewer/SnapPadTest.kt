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

        val contoured = SnapPad.contour(joint, hollow)
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

        val contoured = SnapPad.contour(joint, low)
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
        val paper = MeshFixtures.hollowBox(size = 40f, height = 20f, cavity = 39.7f)
        assertNull(
            "a paper-thin wall cannot carry a pad",
            SnapPad.contour(fullJoint(paper, high), paper),
        )
        val hull = MeshFixtures.hollowBox(size = 40f, height = 20f, cavity = 38f)
        assertNull(
            "nor can a hull-thin millimetre wall",
            SnapPad.contour(fullJoint(hull, high), hull),
        )

        // The same shape with enough wall is exactly what the contour is for:
        // the rim survives and the pad follows it.
        val walled = MeshFixtures.hollowBox(size = 40f, height = 20f, cavity = 32f)
        assertNotNull(
            "a 4 mm wall can carry a contoured pad",
            SnapPad.contour(fullJoint(walled, high), walled),
        )
    }

    /** The full rung, forced, on two in-place halves with their faces at Z = 20. */
    private fun fullJoint(low: StlMesh, high: StlMesh): SnapFitJoint {
        val joint = SnapFit.generate(
            Vec3(0f, 0f, 1f),
            Vec3(20f, 20f, 20f),
            1f,
            SnapFitHalf.inPlace(low, 20f),
            SnapFitHalf.inPlace(high, 20f),
            SnapFitParameters(),
            SnapFitRung.FULL,
        )
        assertNotNull("the full joint has to exist", joint)
        assertEquals(SnapFitRung.FULL, joint!!.rung)
        return joint
    }

    private fun sharedVolume(solid: StlMesh, half: StlMesh): Double {
        val shared = MeshBoolean.intersect(solid, half)
        assertTrue(
            "the engine refused the intersection: " + (shared as? MeshBoolean.Result.Failure)?.reason,
            shared is MeshBoolean.Result.Success,
        )
        return (shared as MeshBoolean.Result.Success).volumeMm3
    }
}
