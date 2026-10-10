package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.viewer.BedClipper.Half
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The joints as the boolean engine sees them: several on one seam, each in its
 * own half, and a beam that really can be pushed into its pocket.
 *
 * Closedness is not the question - a floating block is closed, and so is a
 * pocket that was never cut. What is asked here is whether the solids share
 * volume with the halves they belong to, whether two joints on one seam both
 * survive the successive booleans, and whether the beam's own sweep into the
 * socket is clear at every step of the way in - which is the only honest test
 * of "this assembles", and the one that catches a missing deflection gap.
 *
 * Runs against a host build of the same Manifold JNI shim the phone runs
 * (scripts/build-manifold-host.sh). Where that build is absent the tests skip,
 * the way the host CuraEngine tests do.
 */
class SnapAssemblyTest {
    @Test
    fun twoJointsEachShareVolumeWithTheirOwnHalves() {
        assumeTrue("the host Manifold build is not on java.library.path", MeshBoolean.ensureLoaded())
        val (low, high) = halves()

        val first = jointAt(low, high, Vec3(25f, 20f, 20f))
        val second = jointAt(low, high, Vec3(55f, 20f, 20f))

        // Each one is rooted in the half it was built for and cuts the other.
        for ((name, joint) in listOf("first" to first, "second" to second)) {
            val seat = shared(joint.unionSolid, low)
            val pocket = shared(joint.subtractSolid, high)
            assertTrue("$name joint: the beam is rooted in its own half: $seat mm3", seat > 0.0)
            assertTrue("$name joint: the pocket cuts the mate: $pocket mm3", pocket > 0.0)
        }
        // And the guard has nothing to say about a pair that far apart.
        val wall = maxOf(first.dimensions.beamThicknessMm, second.dimensions.beamThicknessMm)
        assertTrue(
            "the spacing guard passes a pair this far apart: " +
                SnapLayout.conflicts(listOf(SnapLayout.footprintOf(first), SnapLayout.footprintOf(second)), wall),
            SnapLayout.conflicts(listOf(SnapLayout.footprintOf(first), SnapLayout.footprintOf(second)), wall).isEmpty(),
        )

        // The successive booleans: each joint adds its own beam and cuts its own
        // pocket, so both are in the halves at the end.
        var beamHalf = low
        var mate = high
        for (joint in listOf(first, second)) {
            val joined = MeshBoolean.union(beamHalf, joint.unionSolid)
            assertTrue("the union ran: " + joined, joined is MeshBoolean.Result.Success)
            val pocketed = MeshBoolean.subtract(mate, joint.subtractSolid)
            assertTrue("the subtraction ran: " + pocketed, pocketed is MeshBoolean.Result.Success)
            val joinedMesh = (joined as MeshBoolean.Result.Success).mesh
            val pocketedMesh = (pocketed as MeshBoolean.Result.Success).mesh
            assertTrue(
                "the joint added material to the half: " + MeshVolume.of(beamHalf) + " -> " + MeshVolume.of(joinedMesh),
                MeshVolume.of(joinedMesh) > MeshVolume.of(beamHalf),
            )
            assertTrue(
                "and took material out of the mate: " + MeshVolume.of(mate) + " -> " + MeshVolume.of(pocketedMesh),
                MeshVolume.of(pocketedMesh) < MeshVolume.of(mate),
            )
            beamHalf = joinedMesh
            mate = pocketedMesh
        }
        // Both joints are still there at the end: each one's own beam shares
        // volume with the half the joints before it left behind.
        for ((name, joint) in listOf("first" to first, "second" to second)) {
            val stillThere = shared(joint.unionSolid, beamHalf)
            assertTrue("$name beam survives the other joint's boolean: $stillThere mm3", stillThere > 0.5)
        }
    }

    @Test
    fun aChamferedSocketWithASquareFacedHookStillAssembles() {
        assumeTrue("the host Manifold build is not on java.library.path", MeshBoolean.ensureLoaded())
        val (low, high) = halves()
        // The ramp lives in the hole: the hook has no lead-in of its own, and
        // the socket's 45-degree mouth is what cams it in.
        val joint = jointAt(low, high, Vec3(40f, 20f, 20f), SnapFitParameters(rampAngleDeg = 90f, socketRamp = true))

        assertTrue("the mouth is chamfered", joint.dimensions.mouthChamferMm > 0f)
        assertTrue("and the hook has no ramp", squareLeadInIsTheOnlyWay(joint))
        assertSweepsInClear(joint, high)
    }

    @Test
    fun aRampedHookAssemblesToo() {
        assumeTrue("the host Manifold build is not on java.library.path", MeshBoolean.ensureLoaded())
        val (low, high) = halves()
        val joint = jointAt(low, high, Vec3(40f, 20f, 20f))
        assertSweepsInClear(joint, high)
    }

    @Test
    fun aMixedFacingPairAssemblesInOneDirectionAndCatchesBothWays() {
        assumeTrue("the host Manifold build is not on java.library.path", MeshBoolean.ensureLoaded())
        val (low, high) = halves()
        val same = jointAt(low, high, Vec3(35f, 20f, 20f), SnapFitParameters(facing = SnapFacing.SAME))
        val opposite = jointAt(low, high, Vec3(45f, 20f, 20f), SnapFitParameters(facing = SnapFacing.OPPOSITE))

        val half = same.dimensions.beamThicknessMm * 0.5f
        val sameBeam = beamVertices(same)
        val oppositeBeam = beamVertices(opposite)
        assertTrue("one hook stands up", sameBeam.maxOf { it.y } > half + 1e-3f)
        assertTrue("and the other hangs down", oppositeBeam.minOf { it.y } < -half - 1e-3f)
        assertEquals(
            "both are walked along the same assembly direction",
            same.frame.axis.z,
            opposite.frame.axis.z,
            1e-6f,
        )
        // Both really do go together, in that one direction.
        assertSweepsInClear(same, high)
        assertSweepsInClear(opposite, high)
    }

    @Test
    fun aTwoToothBeamAssemblesAndItsSecondClickIsReal() {
        assumeTrue("the host Manifold build is not on java.library.path", MeshBoolean.ensureLoaded())
        val (low, high) = halves()
        val joint = jointAt(low, high, Vec3(40f, 20f, 20f), SnapFitParameters(barbCount = 2))

        assertEquals("two teeth", 2, joint.dimensions.barbCount)
        assertSweepsInClear(joint, high)
        // The pawl is the deepest catch's step, and the shallow tooth's catch
        // passes it: the two engagement depths are a pitch apart.
        assertTrue(
            "the shallow click is a pitch in front of the seated one: " + joint.clicks,
            joint.clicks.first().gapMm - joint.clicks.last().gapMm > joint.dimensions.toothPitchMm - 1e-4f,
        )
    }

    /**
     * Walks the beam into its socket the way the parts go together: at every
     * step of the way in, with the beam sunk by what the pawl demands of it, the
     * beam and the socket share no material. This is the check a missing
     * deflection gap fails, and the reason the pocket's floor is dropped.
     */
    private fun assertSweepsInClear(joint: SnapFitJoint, mate: StlMesh) {
        // What the beam must not touch is the mate's MATERIAL, which is the mate
        // with its own pocket cut out of it - the pocket's solid is the void the
        // beam is meant to fill, so intersecting with that would measure the
        // joint working rather than the joint jamming.
        val hollowed = MeshBoolean.subtract(mate, joint.subtractSolid)
        assertTrue("the mate's pocket was cut: " + hollowed, hollowed is MeshBoolean.Result.Success)
        val material = (hollowed as MeshBoolean.Result.Success).mesh

        // The sink the pawl demands, plus a hair: sinking by exactly the tooth's
        // own height less the clearance it already has leaves the tooth's crown
        // touching the pocket's roof to the float, and a real beam is pressed a
        // fraction past that point or it never goes in at all.
        val sink = joint.dimensions.lipDepthMm - joint.dimensions.lipClearanceMm + 0.05f
        val steps = listOf(0f, 0.5f, 1f, 2f, 3f, 4f, 5f, 6f, 7f, joint.dimensions.beamLengthMm)
        // The clash is measured as what the beam TAKES AWAY from the material:
        // an intersection that comes out empty is the answer this wants, and an
        // empty solid is not something the engine can read back as geometry.
        val before = MeshVolume.of(material)
        for (step in steps) {
            val swept = sweptIn(joint, step, sink)
            val carved = MeshBoolean.subtract(material, swept)
            assertTrue("the sweep ran: " + carved, carved is MeshBoolean.Result.Success)
            val clash = before - (carved as MeshBoolean.Result.Success).volumeMm3
            assertTrue(
                "the beam is " + step + " mm in and cuts " + clash + " mm3 out of the material it passes",
                clash < 0.02,
            )
        }
    }

    /**
     * The joint as the parts close by [step]: everything moves back along the
     * assembly axis, and the BEAM bends out of the way by [sink] while it is
     * under the pawl. The key stands at the beam's root and does not bend with
     * it - moving the key would be measuring a joint nobody can print.
     */
    private fun sweptIn(joint: SnapFitJoint, step: Float, sink: Float): StlMesh {
        val along = joint.frame.axis * (-step)
        val bend = joint.frame.rise * (-sink * joint.dimensions.facing.sign)
        val beamWidth = joint.dimensions.beamWidthMm * 0.5f + 1e-3f
        val builder = MeshSolidBuilder(joint.unionSolid.displayName)
        val vertices = joint.unionSolid.interleavedVertices
        val points = FloatArray(9)
        for (triangle in 0 until joint.unionSolid.triangleCount) {
            val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE
            for (corner in 0 until 3) {
                val at = base + corner * MeshSolidBuilder.FLOATS_PER_VERTEX
                val point = Vec3(vertices[at], vertices[at + 1], vertices[at + 2])
                val local = joint.frame.local(point)
                val shift = if (kotlin.math.abs(local.x) <= beamWidth) along + bend else along
                val moved = point + shift
                points[corner * 3] = moved.x
                points[corner * 3 + 1] = moved.y
                points[corner * 3 + 2] = moved.z
            }
            builder.addTriangle(
                points[0], points[1], points[2],
                points[3], points[4], points[5],
                points[6], points[7], points[8],
            )
        }
        return builder.build()
    }


    /** True when every tooth's leading face is square: a ramp would not be. */
    private fun squareLeadInIsTheOnlyWay(joint: SnapFitJoint): Boolean {
        val half = joint.dimensions.beamThicknessMm * 0.5f
        val beamWidth = joint.dimensions.beamWidthMm * 0.5f + 1e-3f
        var square = 0
        var ramps = 0
        val vertices = joint.unionSolid.interleavedVertices
        for (triangle in 0 until joint.unionSolid.triangleCount) {
            val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE
            val normalAt = base + 3
            val normal = Vec3(vertices[normalAt], vertices[normalAt + 1], vertices[normalAt + 2])
            val local = Vec3(normal.dot(joint.frame.side), normal.dot(joint.frame.rise), normal.dot(joint.frame.axis))
            val centre = (0 until 3).map { corner ->
                val at = base + corner * MeshSolidBuilder.FLOATS_PER_VERTEX
                joint.frame.local(Vec3(vertices[at], vertices[at + 1], vertices[at + 2]))
            }.reduce { a, b -> a + b } * (1f / 3f)
            // A square lead-in is a wall across the axis standing above the
            // beam's own surface; a ramp is a face leaning back with it.
            when {
                local.z > 0.99f && kotlin.math.abs(centre.y) > half && kotlin.math.abs(centre.x) <= beamWidth -> square++
                local.z > 0.3f && local.y * joint.dimensions.facing.sign > 0.3f -> ramps++
            }
        }
        return square > 0 && ramps == 0
    }

    private fun beamVertices(joint: SnapFitJoint): List<Vec3> {
        val width = joint.dimensions.beamWidthMm * 0.5f + 1e-4f
        val vertices = joint.unionSolid.interleavedVertices
        return (0 until joint.unionSolid.triangleCount * 3).mapNotNull { vertex ->
            val base = vertex * MeshSolidBuilder.FLOATS_PER_VERTEX
            val local = joint.frame.local(Vec3(vertices[base], vertices[base + 1], vertices[base + 2]))
            local.takeIf { kotlin.math.abs(it.x) <= width }
        }
    }

    private fun jointAt(
        low: StlMesh,
        high: StlMesh,
        anchor: Vec3,
        parameters: SnapFitParameters = SnapFitParameters(),
    ): SnapFitJoint {
        val result = SnapJoint.build(
            axis = ModelPlacement.Axis.Z,
            anchorMm = anchor,
            scale = 1f,
            lowHalf = SnapFitHalf.inPlace(low, anchor.z),
            highHalf = SnapFitHalf.inPlace(high, anchor.z),
            beamHalf = SnapJoint.JointHalf.LOW,
            parameters = parameters,
        )
        assertTrue("the joint has to exist: " + result, result is SnapJoint.Either.Placed)
        return (result as SnapJoint.Either.Placed).placement.joint
    }

    private fun halves(): Pair<StlMesh, StlMesh> {
        val box = MeshFixtures.box(0f, 0f, 0f, 80f, 40f, 40f)
        return BedClipper.clipClosed(box, ModelPlacement.Axis.Z, 20f, Half.LOW) to
            BedClipper.clipClosed(box, ModelPlacement.Axis.Z, 20f, Half.HIGH)
    }

    /** The volume two solids share, straight from the engine. 0 when they miss. */
    private fun shared(first: StlMesh, second: StlMesh): Double {
        val result = MeshBoolean.intersect(first, second)
        assertTrue(
            "the engine refused the intersection: " + (result as? MeshBoolean.Result.Failure)?.reason,
            result is MeshBoolean.Result.Success,
        )
        return (result as MeshBoolean.Result.Success).volumeMm3
    }

    /** [mesh] shifted, keeping its winding and its triangle count. */
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
}
