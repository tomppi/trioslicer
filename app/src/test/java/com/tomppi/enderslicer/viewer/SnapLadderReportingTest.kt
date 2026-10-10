package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.viewer.BedClipper.Half
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the ladder says when it cannot build what was asked for.
 *
 * Two failures are reported here that used to be silent. A seam thin enough that
 * the tooth is not even proud of the clearance around it (a plate under about
 * 3.3 mm) was still built: the joint closed, and held with a NEGATIVE
 * engagement - measured at -0.110 mm on a 0.36 mm half. And a rung the user
 * pins walks past the ladder's own decision, which is kept - the user is
 * responsible - but a key that cannot fit was built anyway and cut a through
 * hole (2.24 x 2.24 mm of it) in the mate.
 */
class SnapLadderReportingTest {
    @Test
    fun aSeamTooThinForAToothIsRefusedAndTheRefusalSaysWhichNumbers() {
        // 0.36 mm of material on one side of the seam: the beam it can carry is
        // 0.18 mm thick, so its tooth stands 0.09 mm proud against the 0.2 mm of
        // clearance around it. That is the auditor's -0.11 mm engagement.
        val plate = MeshFixtures.box(0f, 0f, 0f, 40f, 40f, 4f)
        val low = BedClipper.clipClosed(plate, ModelPlacement.Axis.Z, 0.36f, Half.LOW)
        val high = BedClipper.clipClosed(plate, ModelPlacement.Axis.Z, 0.36f, Half.HIGH)
        val lowHalf = SnapFitHalf.inPlace(low, 0.36f)
        val highHalf = SnapFitHalf.inPlace(high, 0.36f)
        val anchor = Vec3(20f, 20f, 0.36f)

        val joint = SnapFit.generate(
            Vec3(0f, 0f, 1f),
            anchor,
            1f,
            lowHalf,
            highHalf,
            SnapFitParameters(),
        )

        // The joint is still built - the user decides what he prints - but it
        // advertises NO click and the panel is told which numbers did it.
        assertNotNull("the joint is still placed", joint)
        assertTrue("and it holds nothing: " + joint!!.clickSummary, !joint.holds)
        assertTrue("which it says instead of a click", joint.clicks.isEmpty())
        assertTrue(
            "in the user's own terms: " + joint.clickSummary,
            joint.clickSummary.contains("not proud of its clearance"),
        )
        val clamp = joint.clamps.firstOrNull { it.label == "tooth engagement" }
        assertNotNull("with the clamp that says it: " + joint.clamps, clamp)
        assertTrue("naming the engagement: " + clamp!!.reason, clamp.reason.contains("engagement"))
        assertTrue("with the number it measured: " + clamp.reason, clamp.reason.contains("-0.1"))
        assertTrue("and what to do about it: " + clamp.reason, clamp.reason.contains("clearance"))
    }

    @Test
    fun aSeamThickEnoughForAToothStillGetsOne() {
        // The same plate with real material on both sides: the ladder still
        // builds, so the refusal above is about the tooth, not about thin parts.
        val plate = MeshFixtures.box(0f, 0f, 0f, 40f, 40f, 20f)
        val low = BedClipper.clipClosed(plate, ModelPlacement.Axis.Z, 10f, Half.LOW)
        val high = BedClipper.clipClosed(plate, ModelPlacement.Axis.Z, 10f, Half.HIGH)
        val result = SnapFit.generate(
            Vec3(0f, 0f, 1f),
            Vec3(20f, 20f, 10f),
            1f,
            SnapFitHalf.inPlace(low, 10f),
            SnapFitHalf.inPlace(high, 10f),
            SnapFitParameters(),
        )

        assertNotNull("a 10 mm half carries a joint", result)
        assertTrue(
            "whose tooth is proud of its clearance: " + result!!.dimensions.lipDepthMm,
            result.dimensions.lipDepthMm - result.dimensions.lipClearanceMm >= SnapFit.MIN_ENGAGEMENT_MM,
        )
    }

    @Test
    fun aPinnedFullJointThatCannotCarryItsKeyLeavesTheKeyOffAndSaysSo() {
        // The hollow seam: the cap is a ring, so the key's socket stands over the
        // void and the ladder drops to the cantilever on its own. Pinning FULL
        // keeps the pad and the cantilever - the user asked - but a key that
        // cannot fit is not built.
        val hollow = MeshFixtures.hollowBox(size = 40f, height = 20f, cavity = 30f)
        val high = MeshFixtures.box(0f, 0f, 20f, 40f, 40f, 40f)

        val joint = SnapFit.generate(
            Vec3(0f, 0f, 1f),
            Vec3(20f, 20f, 20f),
            1f,
            SnapFitHalf.inPlace(hollow, 20f),
            SnapFitHalf.inPlace(high, 20f),
            SnapFitParameters(),
            SnapFitRung.FULL,
        )

        assertNotNull("the full joint is still built when it is asked for", joint)
        assertEquals(SnapFitRung.FULL, joint!!.rung)
        assertTrue("with the whole-seam pad", joint.registrationSolid.triangleCount > 0)

        // No key stands past the beam: the union is the beam's own width.
        val across = localX(joint).maxOf { it }
        assertEquals(
            "the key that could not fit was not built",
            joint.dimensions.beamWidthMm * 0.5f,
            across,
            1e-3f,
        )
        assertNotNull("and the panel is told what was left out", joint.rungReason)
        assertTrue("in the user's terms: " + joint.rungReason, joint.rungReason!!.contains("key"))
        val clamp = joint.clamps.firstOrNull { it.label == "key" }
        assertNotNull("with the clamp that says it: " + joint.clamps, clamp)
        assertTrue("and that the key was left off: " + clamp!!.reason, clamp.reason.contains("left off"))
    }

    private fun localX(joint: SnapFitJoint): List<Float> {
        val vertices = joint.unionSolid.interleavedVertices
        return (0 until joint.unionSolid.triangleCount * 3).map { vertex ->
            val base = vertex * MeshSolidBuilder.FLOATS_PER_VERTEX
            joint.frame.local(Vec3(vertices[base], vertices[base + 1], vertices[base + 2])).x
        }
    }
}
