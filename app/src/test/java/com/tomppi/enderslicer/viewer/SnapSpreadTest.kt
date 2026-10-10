package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.viewer.BedClipper.Half
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the automatic spread puts its joints, and what it refuses.
 *
 * The rim is the material the two halves share at their mating faces - the
 * contoured cross-section the pad work already computes, read the other way
 * round - and the joints go along it, evenly and no closer than the joint's own
 * footprint plus a wall. On a thin band, which is what a hull's cross-section
 * looks like, the middle of the bounding box is air and only the rim is worth
 * walking, which is the whole reason this exists.
 */
class SnapSpreadTest {
    @Test
    fun theRimIsTheMaterialBothHalvesShareAtTheSeam() {
        val (low, high) = halves(80f, 40f, 40f, 20f)
        val axes = SnapFit.frameAxes(Vec3(0f, 0f, 1f))!!
        val (lowFit, highFit) = (low to high).fittings(20f)
        val rim = SnapFit.seamRim(lowFit, highFit, axes.first, axes.second, axes.third, Vec3(40f, 20f, 20f))

        assertNotNull("a solid seam has a rim", rim)
        // The frame's own axes: for a Z cut, the in-plane side is the model's Y
        // and the rise is its X, so a 80 x 40 seam reads 40 x 80 here.
        assertEquals("as wide as the seam is along the frame's side", 40f, rim!!.widthMm, 1f)
        assertEquals("and as tall as it is along the rise", 80f, rim.heightMm, 1f)
        assertTrue("with plenty of rim to walk: " + rim.rimPoints.size, rim.rimPoints.size > 20)
        // Every rim point is on material: the middle of a solid face is not rim.
        val middle = rim.rimPoints.count { hypot((it.x - 0.0).toDouble(), (it.y - 0.0).toDouble()) < 5.0 }
        assertEquals("the rim is an outline, not a fill: ", 0, middle)
    }

    @Test
    fun aHollowSeamHasARimWhereTheWallsAreAndAirInTheMiddle() {
        // A 40 mm cross-section with a 30 mm cavity sunk into it: the rim is the
        // ring of wall, and the middle of the bounding box is the void.
        val hollow = MeshFixtures.hollowBox(size = 40f, height = 20f, cavity = 30f, name = "hollow half")
        val solid = MeshFixtures.box(0f, 0f, 20f, 40f, 40f, 40f, name = "mate")
        val axes = SnapFit.frameAxes(Vec3(0f, 0f, 1f))!!
        val rim = SnapFit.seamRim(
            SnapFitHalf.inPlace(hollow, 20f),
            SnapFitHalf.inPlace(solid, 20f),
            axes.first,
            axes.second,
            axes.third,
            Vec3(20f, 20f, 20f),
        )

        assertNotNull("the hollow half still has a rim where its wall is", rim)
        val middle = rim!!.rimPoints.count { hypot((it.x - 0.0).toDouble(), (it.y - 0.0).toDouble()) < 6.0 }
        assertEquals("and none of it is over the void", 0, middle)
    }

    @Test
    fun theSpreadKeepsTheMinimumSpacingOnASolidSeam() {
        val (low, high) = halves(80f, 40f, 40f, 20f)
        val probe = probeJoint(low, high, Vec3(40f, 20f, 20f))
        val axes = SnapFit.frameAxes(Vec3(0f, 0f, 1f))!!
        val (lowFit, highFit) = (low to high).fittings(20f)
        val rim = SnapFit.seamRim(lowFit, highFit, axes.first, axes.second, axes.third, Vec3(40f, 20f, 20f))!!

        val plan = SnapSpread.plan(rim, probe, emptyList(), SnapSpread.MAX_JOINTS)

        val proposed = plan as SnapSpread.Plan.Proposed
        assertTrue("2 to 4 joints, as the option promises: " + proposed.anchors.size, proposed.anchors.size in 2..4)
        val spacing = SnapSpread.spacingFor(probe)
        assertTrue("the spacing is the footprint plus a wall: " + spacing, spacing > probe.dimensions.beamWidthMm)
        // What the spread proposes is what the layout guard lets the user join:
        // the sampling runs on the guard's own rule, because two anchors a joint
        // apart can still have their pockets cross when they sit diagonally, and
        // the device found exactly that - a spread the user could not join.
        val joints = proposed.anchors.map { jointAt(lowFit, highFit, it) }
        val wall = joints.maxOf { it.dimensions.beamThicknessMm }
        val conflicts = SnapLayout.conflicts(joints.map(SnapLayout::footprintOf), wall)
        assertTrue("a spread that can be joined: " + conflicts, conflicts.isEmpty())
        // Every proposed joint stands on the rim rather than in the middle of
        // the face, and stands there INSIDE the outline: the app proposes
        // places where the joint itself fits, not places on the edge where its
        // key would hang over it.
        val grid = rim.grid!!
        val inset = SnapSpread.anchorInsetFor(probe)
        val room = SnapSpread.anchorRoomFor(probe)
        for (anchor in proposed.anchors) {
            val plane = rim.plane(anchor)
            val nearest = rim.rimPoints.minOf { hypot((it.x - plane.x).toDouble(), (it.y - plane.y).toDouble()) }
            assertTrue(
                "a proposed joint is at the rim, not in the middle of the face: " + nearest + " mm from it",
                nearest <= maxOf(room.maxX, -room.minX) + 2.0f,
            )
            val distance = grid.distanceToEdge(grid.columnOf(plane.x), grid.rowOf(plane.y), 16)
            assertTrue(
                "and inset from the outline by at least half the key: " + distance + " mm, asked " + inset,
                distance >= inset - 1e-3f,
            )
            val covered = coverage(
                grid,
                plane.x + room.minX,
                plane.x + room.maxX,
                plane.y + room.minY,
                plane.y + room.maxY,
            )
            assertEquals("with the whole joint on material: " + covered, 1f, covered, 1e-3f)
        }
    }

    @Test
    fun theSpreadKeepsClearOfTheJointsAlreadyPlaced() {
        val (low, high) = halves(80f, 40f, 40f, 20f)
        val probe = probeJoint(low, high, Vec3(40f, 20f, 20f))
        val axes = SnapFit.frameAxes(Vec3(0f, 0f, 1f))!!
        val (lowFit, highFit) = (low to high).fittings(20f)
        val rim = SnapFit.seamRim(lowFit, highFit, axes.first, axes.second, axes.third, Vec3(40f, 20f, 20f))!!

        val placed = listOf(rim.plane(Vec3(20f, 20f, 20f)), rim.plane(Vec3(60f, 20f, 20f)))
        val plan = SnapSpread.plan(rim, probe, placed, SnapSpread.MAX_JOINTS)

        val proposed = plan as SnapSpread.Plan.Proposed
        // The joints already on the seam are part of the packing, not just of
        // the distance: the guard's rule is what decides whether they can be
        // joined, here as it does on the plate.
        val joints = proposed.anchors.map { jointAt(lowFit, highFit, it) } +
            placed.map { jointAt(lowFit, highFit, rim.model(it)) }
        val wall = joints.maxOf { it.dimensions.beamThicknessMm }
        val conflicts = SnapLayout.conflicts(joints.map(SnapLayout::footprintOf), wall)
        assertTrue("a spread that keeps clear of what is already placed: " + conflicts, conflicts.isEmpty())
    }

    @Test
    fun theSpreadStepsAroundAJointAlreadyOnTheMiddleOfTheSeam() {
        // The device's other find: a hand-placed joint near the middle of a
        // 20 mm seam, and the spread's own first joint landing on top of it -
        // the two then crossed by 0.98 mm and Join refused the whole set. The
        // seed is packed against what is already there, like every other point.
        val (low, high) = halves(20f, 20f, 15f, 7.5f)
        val probe = probeJoint(low, high, Vec3(10f, 10f, 7.5f))
        val axes = SnapFit.frameAxes(Vec3(0f, 0f, 1f))!!
        val (lowFit, highFit) = (low to high).fittings(7.5f)
        val rim = SnapFit.seamRim(lowFit, highFit, axes.first, axes.second, axes.third, Vec3(10f, 10f, 7.5f))!!
        // The middle of the face is where the seed's own ideal point sits.
        val placed = listOf(rim.plane(Vec3(10f, 10f, 7.5f)))

        val plan = SnapSpread.plan(rim, probe, placed, SnapSpread.MAX_JOINTS)

        val proposed = plan as SnapSpread.Plan.Proposed
        assertTrue(
            "the spread still finds room beside it, and not on it: " + proposed.anchors.size,
            proposed.anchors.size in 2..4,
        )
        val joints = proposed.anchors.map { jointAt(lowFit, highFit, it) } +
            placed.map { jointAt(lowFit, highFit, rim.model(it)) }
        val wall = joints.maxOf { it.dimensions.beamThicknessMm }
        val conflicts = SnapLayout.conflicts(joints.map(SnapLayout::footprintOf), wall)
        assertTrue("the guard lets the whole set be joined: " + conflicts, conflicts.isEmpty())
    }

    @Test
    fun theSpreadRefusesRatherThanCallingOneJointASpread() {
        // A rim with no room left on it: every point of it is taken. One joint
        // is a placement, not a spread, and the app says so with the number
        // instead of quietly proposing the one.
        val (low, high) = halves(80f, 40f, 40f, 20f)
        val probe = probeJoint(low, high, Vec3(40f, 20f, 20f))
        val axes = SnapFit.frameAxes(Vec3(0f, 0f, 1f))!!
        val (lowFit, highFit) = (low to high).fittings(20f)
        val rim = SnapFit.seamRim(lowFit, highFit, axes.first, axes.second, axes.third, Vec3(40f, 20f, 20f))!!

        val plan = SnapSpread.plan(rim, probe, rim.rimPoints, SnapSpread.MAX_JOINTS)

        val refused = plan as SnapSpread.Plan.Refused
        assertTrue("in the user's terms: " + refused.reason, refused.reason.contains("no room for another"))
        assertTrue("and it names the spacing: " + refused.reason, refused.reason.contains("spacing"))
        assertTrue("and says what to do: " + refused.reason, refused.reason.contains("smaller"))
    }

    @Test
    fun theSpacingDoesNotGrowWithWhereThePlatePutTheHalves() {
        // The bug the device found: the pocket is placed through the mate's own
        // transform, so measuring it in the beam half's frame carried the
        // packer's separation between the halves into the joint's own size - a
        // 20 mm cube's seam came out needing 56 mm between joints, and the
        // automatic spread refused to place a second one.
        val (low, high) = halves(20f, 20f, 20f, 10f)
        val touching = SnapFitHalf.inPlace(low, 10f) to SnapFitHalf.inPlace(high, 10f)
        val apart = SnapFitHalf.placed(low, translated(low, 0f, 0f, 0f), 10f) to
            SnapFitHalf.placed(high, translated(high, 5f, 0f, 28f), 10f)

        val near = probeJointOn(touching.first, touching.second, Vec3(10f, 10f, 10f))
        val far = probeJointOn(apart.first, apart.second, Vec3(10f, 10f, 10f))

        assertEquals(
            "the spacing is a property of the joint, not of the plate's layout",
            SnapSpread.spacingFor(near),
            SnapSpread.spacingFor(far),
            1e-3f,
        )
        assertEquals(
            "and the layout guard reads the same footprint either way",
            SnapLayout.footprintOf(near).rect,
            SnapLayout.footprintOf(far).rect,
        )
        assertTrue(
            "a 20 mm cube's seam takes two joints: " + SnapSpread.spacingFor(near),
            SnapSpread.spacingFor(near) < 20f,
        )
    }

    @Test
    fun jointsTooCloseTogetherAreReportedRatherThanOneCuttingTheOtherAway() {
        val (low, high) = halves(80f, 40f, 40f, 20f)
        val first = probeJoint(low, high, Vec3(30f, 20f, 20f))
        val second = probeJoint(low, high, Vec3(32f, 20f, 20f))
        val wall = maxOf(first.dimensions.beamThicknessMm, second.dimensions.beamThicknessMm)

        val conflicts = SnapLayout.conflicts(
            listOf(SnapLayout.footprintOf(first), SnapLayout.footprintOf(second)),
            wall,
        )

        assertEquals("the pair is reported, not dropped: " + conflicts, 1, conflicts.size)
        assertTrue("with both joints named: " + conflicts.single(), conflicts.single().contains("joints 1 and 2"))
        assertTrue("and what to do: " + conflicts.single(), conflicts.single().contains("wall") || conflicts.single().contains("overlap"))
    }

    @Test
    fun jointsFarEnoughApartAreNotConflicts() {
        val (low, high) = halves(80f, 40f, 40f, 20f)
        val first = probeJoint(low, high, Vec3(25f, 20f, 20f))
        val second = probeJoint(low, high, Vec3(55f, 20f, 20f))

        val conflicts = SnapLayout.conflicts(
            listOf(SnapLayout.footprintOf(first), SnapLayout.footprintOf(second)),
            maxOf(first.dimensions.beamThicknessMm, second.dimensions.beamThicknessMm),
        )

        assertTrue("nothing to say about a pair that fits: " + conflicts, conflicts.isEmpty())
    }

    /** One joint on this pair, at [anchor], built exactly as the preview builds it. */
    private fun probeJoint(low: StlMesh, high: StlMesh, anchor: Vec3): SnapFitJoint =
        probeJointOn(SnapFitHalf.inPlace(low, anchor.z), SnapFitHalf.inPlace(high, anchor.z), anchor)

    /** One joint of the spread, at [anchor], exactly as the preview would build it. */
    private fun jointAt(lowFit: SnapFitHalf, highFit: SnapFitHalf, anchor: Vec3): SnapFitJoint {
        val result = SnapJoint.build(
            axis = ModelPlacement.Axis.Z,
            anchorMm = anchor,
            scale = 1f,
            lowHalf = lowFit,
            highHalf = highFit,
            beamHalf = SnapJoint.JointHalf.LOW,
        )
        return when (result) {
            is SnapJoint.Either.Placed -> result.placement.joint
            is SnapJoint.Either.Flipped -> result.placement.joint
            is SnapJoint.Either.Failed -> throw AssertionError("a proposal has to build: " + result.failure.summary)
        }
    }

    /** One joint on a pair that is already placed, exactly as the preview builds it. */
    private fun probeJointOn(low: SnapFitHalf, high: SnapFitHalf, anchor: Vec3): SnapFitJoint {
        val result = SnapJoint.build(
            axis = ModelPlacement.Axis.Z,
            anchorMm = anchor,
            scale = 1f,
            lowHalf = low,
            highHalf = high,
            beamHalf = SnapJoint.JointHalf.LOW,
        )
        assertTrue("the probe joint has to exist: " + result, result is SnapJoint.Either.Placed)
        return (result as SnapJoint.Either.Placed).placement.joint
    }

    /** [mesh] shifted on the plate, as the packer shifts a half. */
    private fun translated(mesh: StlMesh, dx: Float, dy: Float, dz: Float): StlMesh {
        if (dx == 0f && dy == 0f && dz == 0f) return mesh
        val builder = MeshSolidBuilder(mesh.displayName)
        val vertices = mesh.interleavedVertices
        for (triangle in 0 until mesh.triangleCount) {
            val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE
            builder.addTriangle(
                vertices[base] + dx, vertices[base + 1] + dy, vertices[base + 2] + dz,
                vertices[base + 6] + dx, vertices[base + 7] + dy, vertices[base + 8] + dz,
                vertices[base + 12] + dx, vertices[base + 13] + dy, vertices[base + 14] + dz,
            )
        }
        return builder.build()
    }

    /** The pair as the joint builder wants them: in place, with the plane they share. */
    private fun Pair<StlMesh, StlMesh>.fittings(face: Float): Pair<SnapFitHalf, SnapFitHalf> =
        SnapFitHalf.inPlace(first, face) to SnapFitHalf.inPlace(second, face)

    private fun halves(width: Float, depth: Float, height: Float, cut: Float): Pair<StlMesh, StlMesh> {
        val box = MeshFixtures.box(0f, 0f, 0f, width, depth, height)
        return BedClipper.clipClosed(box, ModelPlacement.Axis.Z, cut, Half.LOW) to
            BedClipper.clipClosed(box, ModelPlacement.Axis.Z, cut, Half.HIGH)
    }

    @Test
    fun theAutomaticAnchorsAreInsetSoTheKeyStaysOnASmallFace() {
        // The device's own complaint: a 20 mm face took its automatic joints
        // exactly on the outline, and the joint's key - which sits BESIDE the
        // beam, a whole key away from the anchor - then hung over the edge of
        // the part. The anchor is inset by half the key's own width now, and
        // this is what that has to buy: the key on the material, on a face
        // small enough that a key's width is a real fraction of it.
        val (low, high) = halves(20f, 20f, 15f, 7.5f)
        val probe = probeJoint(low, high, Vec3(10f, 10f, 7.5f))
        val axes = SnapFit.frameAxes(Vec3(0f, 0f, 1f))!!
        val (lowFit, highFit) = (low to high).fittings(7.5f)
        val rim = SnapFit.seamRim(lowFit, highFit, axes.first, axes.second, axes.third, Vec3(10f, 10f, 7.5f))!!
        val grid = rim.grid!!
        val inset = SnapSpread.anchorInsetFor(probe)

        val plan = SnapSpread.plan(rim, probe, emptyList(), SnapSpread.MAX_JOINTS)
        val proposed = plan as SnapSpread.Plan.Proposed
        assertTrue("a 20 mm face still takes a spread: " + proposed.anchors.size, proposed.anchors.size >= 2)
        for (anchor in proposed.anchors) {
            val plane = rim.plane(anchor)
            // Inside the outline by the inset itself. The outline cells cannot
            // say this: they are a millimetre apart, and one cell of slack is
            // all the difference between on the rim and a key over the edge.
            val distance = grid.distanceToEdge(grid.columnOf(plane.x), grid.rowOf(plane.y), 8)
            assertTrue(
                "the anchor is inset from the material's edge: " + distance + " mm, asked " + inset,
                distance >= inset - 1e-3f,
            )
            // The key's own footprint about that anchor, as SnapFit covers the
            // cap with it: it has to be on the material the halves share.
            val dimensions = probe.dimensions
            val covered = coverage(
                grid,
                plane.x + dimensions.keyOffsetMm - dimensions.keySizeMm * 0.5f,
                plane.x + dimensions.keyOffsetMm + dimensions.keySizeMm * 0.5f,
                plane.y - dimensions.keySizeMm * 0.5f,
                plane.y + dimensions.keySizeMm * 0.5f,
            )
            assertEquals("the key sits on the face, not over its edge: " + covered, 1f, covered, 1e-3f)
        }
    }

    @Test
    fun aRimWithNoMaterialToMeasureIsLeftExactlyAsItWas() {
        // A hand-built rim carries no grid, and the inset is read off the
        // material rather than guessed: nothing to measure means nothing moves.
        val (low, high) = halves(80f, 40f, 40f, 20f)
        val probe = probeJoint(low, high, Vec3(40f, 20f, 20f))
        val axes = SnapFit.frameAxes(Vec3(0f, 0f, 1f))!!
        val (lowFit, highFit) = (low to high).fittings(20f)
        val measured = SnapFit.seamRim(lowFit, highFit, axes.first, axes.second, axes.third, Vec3(40f, 20f, 20f))!!
        val handBuilt = SeamRim(
            measured.originMm,
            measured.side,
            measured.rise,
            measured.widthMm,
            measured.heightMm,
            measured.rimPoints,
        )

        assertEquals(measured.rimPoints, SnapSpread.anchorsFor(handBuilt, probe))
        assertTrue(
            "a rim with material to measure against is inset from it",
            SnapSpread.anchorsFor(measured, probe).size != measured.rimPoints.size,
        )
    }

    /** How much of one rectangle in the seam's plane is material both halves share. */
    private fun coverage(grid: RimGrid, minX: Float, maxX: Float, minY: Float, maxY: Float): Float {
        val samples = 8
        var inside = 0
        for (row in 0 until samples) {
            for (column in 0 until samples) {
                val x = minX + (maxX - minX) * (column + 0.5f) / samples
                val y = minY + (maxY - minY) * (row + 0.5f) / samples
                if (grid.isShared(grid.columnOf(x), grid.rowOf(y))) inside++
            }
        }
        return inside.toFloat() / (samples * samples)
    }

    private fun distance(first: Vec3, second: Vec3): Float =
        hypot((first.x - second.x).toDouble(), (first.y - second.y).toDouble()).toFloat()
}
