package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.ModelPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The joint really lands in the halves, whether or not the plate has moved them
 * since the split.
 *
 * Closedness is not the question: two disjoint closed solids are closed, and a
 * block welded to nothing passes every manifoldness check a walkthrough makes.
 * What the boolean engine's own intersection answers is whether the union shares
 * volume with the half it is rooted in and whether the pocket shares volume
 * with the half it is cut from - and the same answers must come out for the same
 * joint whether the halves touch, sit seven millimetres apart (what the plate's
 * packer does to them), or are at opposite ends of the bed.
 *
 * Runs against a host build of the same Manifold JNI shim the phone runs
 * (scripts/build-manifold-host.sh, whose output app/build.gradle.kts puts on the
 * test JVM's library path). On a machine without that build the test skips, the
 * way the host CuraEngine tests do.
 */
class SnapFitIntersectionTest {
    @Test
    fun theJointSharesVolumeWithItsOwnHalvesWhereverThePlatePutsThem() {
        assumeTrue(
            "the host Manifold build is not on java.library.path; run scripts/build-manifold-host.sh",
            MeshBoolean.ensureLoaded(),
        )
        // Closed boxes as the two halves: the engine takes them as they are,
        // and the property under test is the joint's placement, not the cut.
        val low = MeshFixtures.box(0f, 0f, 0f, 40f, 40f, 20f, name = "low half")
        val high = MeshFixtures.box(0f, 0f, 20f, 40f, 40f, 40f, name = "high half")
        val anchor = Vec3(20f, 20f, 20f)

        // The three arrangements the property is about: the halves as the split
        // left them, the packer's separation along the assembly axis, and both
        // halves pushed to opposite ends of the bed at once.
        val touching = arrange(low, high, 0f, 0f, 0f, 0f, 0f, 0f, anchor)
        val separated = arrange(low, high, 0f, 0f, 0f, 0f, 0f, 7f, anchor)
        val oppositeEnds = arrange(low, high, -90f, -90f, 0f, 90f, 90f, 7f, anchor)

        val touchingSeat = seat(touching.joint.unionSolid, touching.beamMesh)
        val touchingPocket = seat(touching.joint.subtractSolid, touching.socketMesh)
        assertTrue(
            "the union shares volume with the half it is rooted in: " + touchingSeat + " mm3",
            touchingSeat > 0.0,
        )
        assertTrue(
            "the pocket shares volume with the half it is cut from: " + touchingPocket + " mm3",
            touchingPocket > 0.0,
        )

        for ((name, arrangement) in listOf("7 mm apart" to separated, "opposite ends" to oppositeEnds)) {
            val seat = seat(arrangement.joint.unionSolid, arrangement.beamMesh)
            val pocket = seat(arrangement.joint.subtractSolid, arrangement.socketMesh)
            assertTrue("$name: the union shares volume with its half: $seat mm3", seat > 0.0)
            assertTrue("$name: the pocket shares volume with the mate: $pocket mm3", pocket > 0.0)
            // 1e-3 mm3: the engine's own float precision at bed-scale
            // coordinates, not slack for a different answer.
            assertEquals("$name: the same beam seat as touching", touchingSeat, seat, 1e-3)
            assertEquals("$name: the same pocket as touching", touchingPocket, pocket, 1e-3)
            assertEquals("$name: the same joint", touching.joint.dimensions, arrangement.joint.dimensions)
            println("PROOF arrangement=$name seat=$seat pocket=$pocket")
        }

        // And the booleans really move material, in the separated case too: the
        // beam adds volume to its half and the pocket removes it from the mate.
        val beamBefore = MeshVolume.of(separated.beamMesh)
        val mateBefore = MeshVolume.of(separated.socketMesh)
        val joined = MeshBoolean.union(separated.beamMesh, separated.joint.unionSolid)
        val pocketed = MeshBoolean.subtract(separated.socketMesh, separated.joint.subtractSolid)
        assertTrue("the union ran: " + joined, joined is MeshBoolean.Result.Success)
        assertTrue("the subtraction ran: " + pocketed, pocketed is MeshBoolean.Result.Success)
        val joinedVolume = (joined as MeshBoolean.Result.Success).volumeMm3
        val pocketedVolume = (pocketed as MeshBoolean.Result.Success).volumeMm3
        assertTrue(
            "the beam half gained material: " + beamBefore + " -> " + joinedVolume,
            joinedVolume > beamBefore,
        )
        assertTrue(
            "the mate lost material: " + mateBefore + " -> " + pocketedVolume,
            pocketedVolume < mateBefore,
        )
        println(
            "PROOF beam_half=" + beamBefore + " -> " + joinedVolume + " (+" + (joinedVolume - beamBefore) +
                "), mate=" + mateBefore + " -> " + pocketedVolume + " (-" + (mateBefore - pocketedVolume) + ")",
        )
    }

    /** One arrangement: the two halves placed, and the joint built for them. */
    private class Arrangement(val joint: SnapFitJoint, val beamMesh: StlMesh, val socketMesh: StlMesh)

    private fun arrange(
        low: StlMesh,
        high: StlMesh,
        lowX: Float,
        lowY: Float,
        lowZ: Float,
        highX: Float,
        highY: Float,
        highZ: Float,
        anchor: Vec3,
    ): Arrangement {
        val lowHalf = SnapFitHalf.placed(low, translated(low, lowX, lowY, lowZ), 20f)
        val highHalf = SnapFitHalf.placed(high, translated(high, highX, highY, highZ), 20f)
        val result = SnapJoint.build(
            axis = ModelPlacement.Axis.Z,
            anchorMm = anchor,
            scale = 1f,
            lowHalf = lowHalf,
            highHalf = highHalf,
            beamHalf = SnapJoint.JointHalf.LOW,
        )
        assertTrue("the joint has to exist: " + result, result is SnapJoint.Either.Placed)
        val placement = (result as SnapJoint.Either.Placed).placement
        return Arrangement(placement.joint, placement.beamMesh, placement.socketMesh)
    }

    /** The volume [solid] shares with [half], straight from the engine. */
    private fun seat(solid: StlMesh, half: StlMesh): Double {
        val shared = MeshBoolean.intersect(solid, half)
        assertTrue(
            "the engine refused the intersection: " + (shared as? MeshBoolean.Result.Failure)?.reason,
            shared is MeshBoolean.Result.Success,
        )
        return (shared as MeshBoolean.Result.Success).volumeMm3
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
}
