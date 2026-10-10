package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.ModelPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which half carries the beam, and what happens when the one the user chose
 * cannot hold one.
 *
 * The generator always builds the beam into the half it is handed as the beam
 * half, along the direction it is handed, so asking for the other half is
 * [SnapJoint]'s job: it walks the axis the other way, turns each half's own
 * mating face round with it, and hands the pair over swapped. These tests pin
 * that down - the beam lands in the half that was asked for, the pocket in the
 * other, and a half with no room is reported as a flip or as a refusal that
 * names it.
 */
class SnapJointTest {
    @Test
    fun theChosenLowHalfCarriesTheBeamAndTheOtherGetsThePocket() {
        val (low, high) = halves()

        val result = SnapJoint.build(
            axis = ModelPlacement.Axis.Z,
            anchorMm = Vec3(20f, 20f, 20f),
            scale = 1f,
            lowHalf = low,
            highHalf = high,
            beamHalf = SnapJoint.JointHalf.LOW,
        )

        val placed = result as SnapJoint.Either.Placed
        assertTrue(placed.placement.chosenHalfCarriesBeam)
        assertEquals("a solid seam carries the full joint", SnapFitRung.FULL, placed.placement.joint.rung)
        assertSame("the low half takes the beam", low.mesh, placed.placement.beamMesh)
        assertSame("and the high half the pocket", high.mesh, placed.placement.socketMesh)
        assertEquals("the beam runs up the positive axis", 1f, placed.placement.joint.frame.axis.z, 1e-6f)
    }

    @Test
    fun theChosenHighHalfCarriesTheBeamToo() {
        val (low, high) = halves()

        val result = SnapJoint.build(
            axis = ModelPlacement.Axis.Z,
            anchorMm = Vec3(20f, 20f, 20f),
            scale = 1f,
            lowHalf = low,
            highHalf = high,
            beamHalf = SnapJoint.JointHalf.HIGH,
        )

        val placed = result as SnapJoint.Either.Placed
        assertTrue(placed.placement.chosenHalfCarriesBeam)
        assertSame("the chosen half takes the beam", high.mesh, placed.placement.beamMesh)
        assertSame("and the other the pocket", low.mesh, placed.placement.socketMesh)
        assertEquals(
            "the joint is walked the other way, so the barb still cams in",
            -1f,
            placed.placement.joint.frame.axis.z,
            1e-6f,
        )
        assertNotNull("and it is still geometry", placed.placement.joint.unionSolid)
    }

    @Test
    fun aHalfWithNoRoomIsReportedAsAFlipRatherThanSilentlyBuilt() {
        // A tall low half under a 2 mm lid: a beam asking to sit in the lid has
        // 2 mm of material to cross, and the default parameters need 8 mm of
        // reach plus a wall, so the chosen half genuinely cannot take it.
        val lowMesh = MeshFixtures.box(0f, 0f, 0f, 40f, 40f, 40f)
        val highMesh = MeshFixtures.box(0f, 0f, 40f, 40f, 40f, 42f)
        val low = SnapFitHalf.inPlace(lowMesh, 40f)
        val high = SnapFitHalf.inPlace(highMesh, 40f)

        val result = SnapJoint.build(
            axis = ModelPlacement.Axis.Z,
            anchorMm = Vec3(20f, 20f, 40f),
            scale = 1f,
            lowHalf = low,
            highHalf = high,
            beamHalf = SnapJoint.JointHalf.LOW,
        )

        val flipped = result as SnapJoint.Either.Flipped
        assertFalse("nothing was passed off as what was asked for", flipped.placement.chosenHalfCarriesBeam)
        assertSame("the beam went into the half with room", highMesh, flipped.placement.beamMesh)
        assertSame("and the pocket into the lid that could not take it", lowMesh, flipped.placement.socketMesh)
        assertEquals(
            "which is the joint walked the other way",
            -1f,
            flipped.placement.joint.frame.axis.z,
            1e-6f,
        )
        assertTrue(
            "and the sentence names the half that could not take it: " + flipped.why.summary,
            flipped.why.summary.startsWith("The lower half"),
        )
    }

    @Test
    fun halvesThatDoNotMeetAreRefusedWithTheAxisNamed() {
        // Two parts that overlap on the axis cannot make a joint on it: there is
        // no seam, whatever the user picked. Each half carries the face a split
        // would have given it - the first ends at X = 10, the second starts at
        // 5 - and those two faces do not agree on one plane.
        val first = MeshFixtures.box(0f, 0f, 0f, 10f, 10f, 10f)
        val second = MeshFixtures.box(5f, 0f, 0f, 15f, 10f, 10f)

        val result = SnapJoint.build(
            axis = ModelPlacement.Axis.X,
            anchorMm = Vec3(15f, 5f, 5f),
            scale = 1f,
            lowHalf = SnapFitHalf.inPlace(first, 10f),
            highHalf = SnapFitHalf.inPlace(second, 5f),
            beamHalf = SnapJoint.JointHalf.LOW,
        )

        val failed = result as SnapJoint.Either.Failed
        assertTrue(
            "the refusal names the axis and what it measured: " + failed.failure.summary,
            failed.failure.summary.contains("lie side by side along X, not across it"),
        )
    }

    @Test
    fun eachHalfCarriesItsOwnSideOfTheSplitsPlane() {
        val (low, high) = halves()

        assertEquals("the low half's face is the plane the split ran", 20f, low.faceMm, 1e-3f)
        assertEquals("and so is the high half's", 20f, high.faceMm, 1e-3f)
        assertEquals(
            "the low half's material ends there",
            20f,
            SnapFit.spanAlongAxis(low, ModelPlacement.Axis.Z)!!.endInclusive,
            1e-3f,
        )
        assertEquals(
            "and the high half's begins there",
            20f,
            SnapFit.spanAlongAxis(high, ModelPlacement.Axis.Z)!!.start,
            1e-3f,
        )
        assertTrue("which is a pair that meets", SnapFit.halvesMeet(low, high))
    }

    @Test
    fun theAxisDirectionIsThePlateAxis() {
        assertEquals(Vec3(1f, 0f, 0f), SnapFit.axisDirection(ModelPlacement.Axis.X))
        assertEquals(Vec3(0f, 1f, 0f), SnapFit.axisDirection(ModelPlacement.Axis.Y))
        assertEquals(Vec3(0f, 0f, 1f), SnapFit.axisDirection(ModelPlacement.Axis.Z))
    }

    /** Two halves of one 40 mm cube, cut at Z = 20, each carrying the plane. */
    private fun halves(): Pair<SnapFitHalf, SnapFitHalf> {
        val cube = MeshFixtures.box(0f, 0f, 0f, 40f, 40f, 40f)
        val low = BedClipper.clipClosed(cube, ModelPlacement.Axis.Z, 20f, BedClipper.Half.LOW)
        val high = BedClipper.clipClosed(cube, ModelPlacement.Axis.Z, 20f, BedClipper.Half.HIGH)
        return SnapFitHalf.inPlace(low, 20f) to SnapFitHalf.inPlace(high, 20f)
    }
}
