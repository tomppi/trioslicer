package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.viewer.BedClipper.Half
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard and the spread where the geometry is really mirrored.
 *
 * A joint whose beam could not go into the half the user chose is built into the
 * other one, and that is a joint walked along the REVERSED assembly direction:
 * its own side axis points the other way. The footprint guard and the spread
 * both measured such a joint's rectangle in its own frame and then compared it
 * where the other joint stands, so a mirrored joint was read as its own
 * reflection - and the guard reported "no conflict" while the real rectangles
 * crossed by four millimetres. The room the spread keeps about an anchor had the
 * same fault, and it never carried the tooth's own reach either.
 */
class SnapFlipFrameTest {
    @Test
    fun aJointBuiltIntoTheOtherHalfIsGuardedInTheSeamsOwnFrame() {
        val (low, high) = halves()
        val lowHalf = SnapFitHalf.inPlace(low, 20f)
        val highHalf = SnapFitHalf.inPlace(high, 20f)
        val first = build(lowHalf, highHalf, Vec3(40f, 20f, 20f), SnapJoint.JointHalf.LOW)
        // 11 mm along the seam's own side axis, which for a +Z cut is -Y: the
        // second joint's key reaches back across the first one's, and the two
        // really cross. Its own frame reads that side the other way round.
        val second = build(lowHalf, highHalf, Vec3(40f, 9f, 20f), SnapJoint.JointHalf.HIGH)
        val wall = maxOf(first.dimensions.beamThicknessMm, second.dimensions.beamThicknessMm)

        val a = SnapLayout.footprintOf(first)
        val b = SnapLayout.footprintOf(second)
        val trueGapX = gapIn(a, b, side = true)
        val trueGapY = gapIn(a, b, side = false)
        assertTrue(
            "the fixture really does cross: " + trueGapX + " mm and " + trueGapY + " mm",
            trueGapX < 0f && trueGapY < 0f,
        )

        val conflicts = SnapLayout.conflicts(listOf(a, b), wall)
        assertTrue("the guard has to report it: " + conflicts, conflicts.isNotEmpty())
        assertTrue("and says they cross: " + conflicts, conflicts.single().contains("cross by"))

        // The reading the guard used to make: the second rectangle taken as it
        // stands in its own frame, only the anchors projected. It leaves 1.8 mm
        // between them - more than the wall - so it reported nothing at all.
        val delta = b.anchor - a.anchor
        val dx = delta.dot(a.side)
        val dy = delta.dot(a.rise)
        val naiveGapX = maxOf(a.rect.minX, b.rect.minX + dx) - minOf(a.rect.maxX, b.rect.maxX + dx)
        assertTrue(
            "the old reading missed it: " + naiveGapX + " mm of wall",
            naiveGapX >= wall,
        )
    }

    @Test
    fun theSpreadsRoomIsTheSeamsOwnSideAndTheToothsOwnReach() {
        val (low, high) = halves()
        val lowHalf = SnapFitHalf.inPlace(low, 20f)
        val highHalf = SnapFitHalf.inPlace(high, 20f)
        val anchor = Vec3(40f, 20f, 20f)
        val forward = build(lowHalf, highHalf, anchor, SnapJoint.JointHalf.LOW)
        val flipped = build(lowHalf, highHalf, anchor, SnapJoint.JointHalf.HIGH)
        val axes = SnapFit.frameAxes(Vec3(0f, 0f, 1f))!!
        val rim = SnapFit.seamRim(lowHalf, highHalf, axes.first, axes.second, axes.third, anchor)!!
        val room = SnapSpread.anchorRoomFor(forward)

        assertTrue(
            "the room carries the tooth's own reach, not half a parameter: " + room,
            room.maxY - room.minY >=
                forward.dimensions.beamThicknessMm + forward.dimensions.lipDepthMm - 1e-3f,
        )
        assertEquals(
            "the forward joint's room is where it stands in the seam's frame",
            room,
            SnapSpread.anchorRoomIn(rim, forward),
        )
        assertEquals(
            "and the mirrored one is mirrored with it",
            AnchorRoom(-room.maxX, -room.minX, room.minY, room.maxY),
            SnapSpread.anchorRoomIn(rim, flipped),
        )
        assertEquals(
            "the flipped joint really is the other way round",
            -1f,
            flipped.frame.side.dot(forward.frame.side),
            1e-4f,
        )
    }

    @Test
    fun anOppositeFacingPutsTheRoomOnTheOtherSideOfTheBeam() {
        val (low, high) = halves()
        val lowHalf = SnapFitHalf.inPlace(low, 20f)
        val highHalf = SnapFitHalf.inPlace(high, 20f)
        val same = build(lowHalf, highHalf, Vec3(40f, 20f, 20f), SnapJoint.JointHalf.LOW)
        val opposite = build(
            lowHalf,
            highHalf,
            Vec3(40f, 20f, 20f),
            SnapJoint.JointHalf.LOW,
            SnapFitParameters(facing = SnapFacing.OPPOSITE),
        )

        val half = same.dimensions.beamThicknessMm * 0.5f
        val reach = half + same.dimensions.lipDepthMm
        assertEquals("a same-facing joint's room stands up with its hook", reach, SnapSpread.anchorRoomFor(same).maxY, 1e-3f)
        assertEquals(-half, SnapSpread.anchorRoomFor(same).minY, 1e-3f)
        assertEquals("and the opposite one's hangs down with its hook", -reach, SnapSpread.anchorRoomFor(opposite).minY, 1e-3f)
        assertEquals(half, SnapSpread.anchorRoomFor(opposite).maxY, 1e-3f)
    }

    /** The clear distance between the two footprints on one of [a]'s own axes. */
    private fun gapIn(a: SnapLayout.Footprint, b: SnapLayout.Footprint, side: Boolean): Float {
        val first = rectIn(a, a)
        val second = rectIn(b, a)
        return if (side) {
            maxOf(first.minX, second.minX) - minOf(first.maxX, second.maxX)
        } else {
            maxOf(first.minY, second.minY) - minOf(first.maxY, second.maxY)
        }
    }

    /** [footprint]'s own rectangle, carried into [reference]'s two axes. */
    private fun rectIn(footprint: SnapLayout.Footprint, reference: SnapLayout.Footprint): SnapLayout.SeamRect {
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (column in 0..1) {
            for (row in 0..1) {
                val x = if (column == 0) footprint.rect.minX else footprint.rect.maxX
                val y = if (row == 0) footprint.rect.minY else footprint.rect.maxY
                val point = footprint.anchor + footprint.side * x + footprint.rise * y
                val delta = point - reference.anchor
                val u = delta.dot(reference.side)
                val v = delta.dot(reference.rise)
                minX = minOf(minX, u)
                maxX = maxOf(maxX, u)
                minY = minOf(minY, v)
                maxY = maxOf(maxY, v)
            }
        }
        return SnapLayout.SeamRect(minX, maxX, minY, maxY)
    }

    private fun build(
        lowHalf: SnapFitHalf,
        highHalf: SnapFitHalf,
        anchor: Vec3,
        beamHalf: SnapJoint.JointHalf,
        parameters: SnapFitParameters = SnapFitParameters(),
    ): SnapFitJoint {
        val result = SnapJoint.build(
            axis = ModelPlacement.Axis.Z,
            anchorMm = anchor,
            scale = 1f,
            lowHalf = lowHalf,
            highHalf = highHalf,
            beamHalf = beamHalf,
            parameters = parameters,
        )
        assertTrue("this joint has to exist: " + result, result is SnapJoint.Either.Placed)
        return (result as SnapJoint.Either.Placed).placement.joint
    }

    private fun halves(): Pair<StlMesh, StlMesh> {
        val box = MeshFixtures.box(0f, 0f, 0f, 80f, 40f, 40f)
        return BedClipper.clipClosed(box, ModelPlacement.Axis.Z, 20f, Half.LOW) to
            BedClipper.clipClosed(box, ModelPlacement.Axis.Z, 20f, Half.HIGH)
    }
}
