package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.ModelPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2

/**
 * The wall lip, measured.
 *
 * Every claim here is read off the meshes the builder produced, not off what it
 * meant to do: the engagement is the overlap of the two flat faces as the
 * triangles have them; the ramp is the angle between the ramp's own two ends; the
 * hold is what happens to the two halves when they are pulled apart - a clash in
 * the right place and none in the wrong one.
 *
 * The fixture is the joint's own description of a part: a 2 mm wall with a base
 * behind it for the wall to bend over, and a mate that is solid inboard of the
 * wall. The wall's half carries the tooth, the mate carries the ledge.
 */
class SnapLipTest {
    @Test
    fun theEngagementIsWhatTheSizeControlSets() {
        val joint = built(SnapFitParameters(lipEngagementOverrideMm = 1.1f, lipToothHeightOverrideMm = 2f))
        assertEquals("the ledge's bearing face is the engagement wide", 1.1f, joint.dimensions.lipEngagementMm, 1e-3f)

        // Measured: the tooth's hold face and the ledge's bearing face, in their
        // own halves' frames, and the rise band they share.
        val tooth = flatFace(joint, joint.frame, faceOfTooth(joint), towards = -1f)
        val ledge = flatFace(joint, joint.socketFrame, joint.dimensions.lipHoldAtMm, towards = 1f)
        assertEquals("the tooth's hold face is a flat wall", 0f, tooth.normal.z + 1f, 1e-3f)
        assertEquals("and so is the ledge's, facing it", 0f, ledge.normal.z - 1f, 1e-3f)
        val overlap = minOf(tooth.high, ledge.high) - maxOf(tooth.low, ledge.low)
        assertEquals("the two faces overlap by exactly the engagement", 1.1f, overlap, 1e-2f)
    }

    @Test
    fun theRampIsAtTheAngleTheParameterSets() {
        for (angle in listOf(45f, 30f, 60f)) {
            val joint = built(SnapFitParameters(rampAngleDeg = angle))
            assertEquals(angle, joint.dimensions.rampAngleDeg, 1e-3f)
            assertEquals(
                "the tooth's leading face is at " + angle + " degrees to the axis",
                angle,
                leadingAngle(joint, joint.frame, joint.dimensions.lipToothHeightMm, leading = true),
                0.5f,
            )
            assertEquals(
                "and the ledge's is at the same angle, facing it",
                angle,
                leadingAngle(
                    joint,
                    joint.socketFrame,
                    joint.dimensions.lipClearanceMm + joint.dimensions.lipEngagementMm,
                    leading = false,
                ),
                0.5f,
            )
        }
    }

    @Test
    fun aSquareLeadingFaceIsTheAngleNinety() {
        val joint = built(SnapFitParameters(rampAngleDeg = 90f))
        assertEquals(90f, leadingAngle(joint, joint.frame, joint.dimensions.lipToothHeightMm, true), 0.5f)
        assertEquals(
            90f,
            leadingAngle(
                joint,
                joint.socketFrame,
                joint.dimensions.lipClearanceMm + joint.dimensions.lipEngagementMm,
                false,
            ),
            0.5f,
        )
    }

    /**
     * The hold, measured on the applied pair: the two halves as the booleans
     * leave them. Pulled apart by the clearance the two flat faces meet; pulled
     * apart by any more than that and the ledge drives into the tooth - which is
     * what holding means.
     */
    @Test
    fun theFlatFacesReallyMeetWhenThePairIsPulledApart() {
        assumeTrue("the host Manifold build is not on java.lang.library.path", MeshBoolean.ensureLoaded())
        val joint = built()
        val lip = MeshBoolean.union(wallHalf(), joint.unionSolid)
        assertTrue("the tooth went on: " + lip, lip is MeshBoolean.Result.Success)
        val lipMesh = (lip as MeshBoolean.Result.Success).mesh
        val mate = MeshBoolean.union(mateHalf(), joint.registrationSolid)
        assertTrue("the ledge went on: " + mate, mate is MeshBoolean.Result.Success)
        val mateMesh = (mate as MeshBoolean.Result.Success).mesh

        // Assembled, the halves share no material at all: the tooth sits in the
        // recess and the ledge comes in beside it.
        assertTrue("the pair does not interpenetrate as built", clash(lipMesh, mateMesh) < 0.01)

        // Pulled apart by the clearance: the faces meet, and nothing clashes.
        val seated = movedBy(mateMesh, joint.dimensions.lipClearanceMm)
        assertTrue(
            "at the clearance the two flat faces bear on each other without a clash",
            clash(lipMesh, seated) < 0.01,
        )
        // And a hair further: now they clash. That clash is the hold.
        val pulled = movedBy(mateMesh, joint.dimensions.lipClearanceMm + 0.3f)
        assertTrue(
            "pulling further drives the ledge into the tooth: " + clash(lipMesh, pulled) + " mm3",
            clash(lipMesh, pulled) > 0.1,
        )
    }

    @Test
    fun theStrainIsTheWallsOwnNumbers() {
        val joint = built()
        val wall = joint.dimensions.wallThicknessMm
        val free = joint.dimensions.wallFreeLengthMm
        assertEquals("the wall is the 2 mm one the fixture is made of", 2f, wall, 0.15f)
        assertTrue("and it has a free length to bend over: " + free, free > 20f)
        val travel = joint.dimensions.lipToothHeightMm - joint.dimensions.lipClearanceMm
        assertEquals("the travel is the tooth's height less the clearance", travel, joint.dimensions.lipDeflectionMm, 1e-3f)
        assertEquals(
            "and the strain is the cantilever's own closed form on the wall",
            1.5f * wall * travel / (free * free) * 100f,
            joint.dimensions.lipStrainPercent,
            1e-3f,
        )
        assertTrue(
            "a wall this long is well inside the material's figure",
            joint.dimensions.lipStrainPercent < SnapLip.MATERIAL_STRAIN_PERCENT,
        )
    }

    /** A setting the material cannot take is refused, and the refusal names the figure. */
    @Test
    fun aStrainPastTheMaterialsFigureIsRefusedByName() {
        // The base comes right up behind the wall: a 2.5 mm free length.
        val outcome = buildOn(wall = wallHalf(deep = 3f), parameters = SnapFitParameters())
        assertTrue("refused: " + outcome, outcome is SnapLip.Outcome.Refused)
        val reason = (outcome as SnapLip.Outcome.Refused).reason
        assertTrue("the figure is named: " + reason, reason.contains("PLA's own figure is 2.0%"))
        assertTrue("and the wall it measured is in the sentence: " + reason, reason.contains("mm wall bent"))
        assertTrue("with the free length it measured", reason.contains("mm free length"))
    }

    /** A wall that joins other material inside its own thickness cannot bend. */
    @Test
    fun aWallShorterThanTheJointIsRefused() {
        val outcome = buildOn(wall = wallHalf(deep = 1.5f), parameters = SnapFitParameters())
        assertTrue("refused: " + outcome, outcome is SnapLip.Outcome.Refused)
        val reason = (outcome as SnapLip.Outcome.Refused).reason
        assertTrue("the short run is the reason: " + reason, reason.contains("only runs"))
        assertTrue("and it says the wall cannot bend", reason.contains("cannot bend"))
    }

    /**
     * A tooth at a corner: the one failure the user cannot see on the screen, so
     * it is refused with the distance that was measured.
     */
    @Test
    fun aToothAtACornerIsRefusedWithTheDistance() {
        // A return wall 1.5 mm along the seam from the tap.
        val cornered = solids(
            MeshFixtures.box(-20f, -2f, -30f, 20f, 0f, 0f, name = "wall"),
            MeshFixtures.box(1.5f, -2f, -30f, 3.5f, 18f, 0f, name = "return"),
        )
        val outcome = buildOn(wall = cornered, parameters = SnapFitParameters())
        assertTrue("refused: " + outcome, outcome is SnapLip.Outcome.Refused)
        val reason = (outcome as SnapLip.Outcome.Refused).reason
        assertTrue("the corner is the reason: " + reason, reason.contains("another wall joins"))
        assertTrue("with the distance measured: " + reason, reason.contains("mm along it"))
        assertTrue("and why it matters", reason.contains("nowhere to move"))
    }

    /** The mate has to be solid a little inboard of the wall: the ledge stands on it. */
    @Test
    fun aMateThatIsHollowInboardOfTheWallIsRefused() {
        // A wall cut in two: the mate carries the same 2 mm band and nothing inboard.
        val hollowMate = MeshFixtures.box(-20f, -2f, 0f, 20f, 0f, 30f, name = "mate-wall")
        val outcome = buildOn(mate = hollowMate, parameters = SnapFitParameters())
        assertTrue("refused: " + outcome, outcome is SnapLip.Outcome.Refused)
        val reason = (outcome as SnapLip.Outcome.Refused).reason
        assertTrue("the hollow mate is the reason: " + reason, reason.contains("hollow just inboard"))
        assertTrue("and the ledge is what needs it", reason.contains("ledge has nothing solid to stand on"))
    }

    /** A block is not a wall, and the refusal says how much solid it measured. */
    @Test
    fun solidMaterialIsNotAWall() {
        val solid = MeshFixtures.box(-20f, -20f, -30f, 20f, 0f, 0f, name = "solid")
        val outcome = buildOn(wall = solid, parameters = SnapFitParameters())
        assertTrue("refused: " + outcome, outcome is SnapLip.Outcome.Refused)
        val reason = (outcome as SnapLip.Outcome.Refused).reason
        assertTrue("the solid is named: " + reason, reason.contains("of solid, not a wall"))
        assertTrue("with what it measured", reason.contains("mm of solid"))
    }

    /** The two size controls come through as built dimensions, and are clamped by the tooth. */
    @Test
    fun theTwoSizesAreBuiltAsAskedAndClampedByTheTooth() {
        val asked = built(SnapFitParameters(lipEngagementOverrideMm = 0.8f, lipToothHeightOverrideMm = 2.4f))
        assertEquals(0.8f, asked.dimensions.lipEngagementMm, 1e-3f)
        assertEquals(2.4f, asked.dimensions.lipToothHeightMm, 1e-3f)

        // A tooth shorter than the hold plus its clearance holds less, and says so.
        val clamped = built(SnapFitParameters(lipEngagementOverrideMm = 0.9f, lipToothHeightOverrideMm = 0.8f))
        assertEquals(0.6f, clamped.dimensions.lipEngagementMm, 1e-3f)
        assertTrue(
            "the clamp is reported rather than silent: " + clamped.clamps.map { it.reason },
            clamped.clamps.any { it.label == "engagement" && it.reason.contains("clamped to it") },
        )
    }

    // ------------------------------------------------------------- the fixture

    /** A lip built on the standard pair, with [parameters], asserted to exist. */
    private fun built(parameters: SnapFitParameters = SnapFitParameters()): SnapFitJoint {
        val outcome = buildOn(wall = wallHalf(), mate = mateHalf(), parameters = parameters)
        assertTrue("the lip has to exist: " + outcome, outcome is SnapLip.Outcome.Built)
        return (outcome as SnapLip.Outcome.Built).joint
    }

    private fun buildOn(
        wall: StlMesh = wallHalf(),
        mate: StlMesh = mateHalf(),
        parameters: SnapFitParameters = SnapFitParameters(),
    ): SnapLip.Outcome = SnapLip.build(
        axis = Vec3(0f, 0f, 1f),
        anchorMm = Vec3(0f, -1f, 0f),
        scale = 1f,
        lipHalf = SnapFitHalf.inPlace(wall, 0f),
        mateHalf = SnapFitHalf.inPlace(mate, 0f),
        parameters = parameters,
    )

    /**
     * The lip half: a 2 mm wall whose inner face is at Y = 0, running from
     * X = -20 to 20 so the tap in the middle has wall either side of it, and
     * [deep] millimetres back from the seam to its own free end.
     */
    private fun wallHalf(deep: Float = 30f): StlMesh =
        MeshFixtures.box(-20f, -2f, -deep, 20f, 0f, 0f, name = "wall")

    /** The mate: solid from the wall's face inboard, which is what the ledge stands on. */
    private fun mateHalf(): StlMesh = MeshFixtures.box(-20f, 0f, 0f, 20f, 20f, 30f, name = "mate")

    /** The hold face of the lip's tooth, along the axis from the mating plane. */
    private fun faceOfTooth(joint: SnapFitJoint): Float =
        -joint.dimensions.lipToothHeightMm / kotlin.math.tan(
            joint.dimensions.rampAngleDeg.coerceAtMost(89.9f) * (Math.PI / 180.0).toFloat(),
        )

    /**
     * The flat face of [mesh] at local z = [at], as the rise band it covers and
     * the way it faces. Found by looking for the mesh's own vertices on that
     * plane: the prism's corners are where its faces are.
     */
    private fun flatFace(
        joint: SnapFitJoint,
        frame: SnapFitFrame,
        at: Float,
        towards: Float,
    ): Face {
        val mesh = if (frame === joint.frame) joint.unionSolid else joint.registrationSolid
        val rises = ArrayList<Float>()
        var found = 0
        for (triangle in 0 until mesh.triangleCount) {
            val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE
            var onPlane = true
            val local = ArrayList<Vec3>(3)
            for (corner in 0 until 3) {
                val vertex = base + corner * MeshSolidBuilder.FLOATS_PER_VERTEX
                val point = Vec3(
                    mesh.interleavedVertices[vertex],
                    mesh.interleavedVertices[vertex + 1],
                    mesh.interleavedVertices[vertex + 2],
                )
                val at2 = frame.local(point)
                local += at2
                if (abs(at2.z - at) > 0.02f) onPlane = false
            }
            if (!onPlane) continue
            // The face's own winding gives the way it looks: the prism is closed
            // and its normals point out.
            val a = local[1] - local[0]
            val b = local[2] - local[0]
            val normal = Vec3(
                a.y * b.z - a.z * b.y,
                a.z * b.x - a.x * b.z,
                a.x * b.y - a.y * b.x,
            )
            if (abs(normal.z) < 1e-6f || normal.z * towards < 0f) continue
            found++
            local.forEach { rises += it.y }
        }
        assertTrue("a flat face at z = " + at + " faces " + towards, found > 0)
        return Face(low = rises.min(), high = rises.max(), normal = Vec3(0f, 0f, towards))
    }

    /**
     * The angle between the axis and the leading face of a feature, in degrees.
     *
     * The face is found by what it is: the one that looks along the axis the way
     * the part is pushed in, whose own highest point is the feature's top. A
     * ramp leans back as it climbs; a square face stands straight up and takes
     * the room the ramp would, which is the angle the parameter asked for.
     */
    private fun leadingAngle(joint: SnapFitJoint, frame: SnapFitFrame, topRise: Float, leading: Boolean): Float {
        val mesh = if (frame === joint.frame) joint.unionSolid else joint.registrationSolid
        var best = Float.NaN
        for (triangle in 0 until mesh.triangleCount) {
            val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE
            val local = (0 until 3).map { corner ->
                val vertex = base + corner * MeshSolidBuilder.FLOATS_PER_VERTEX
                frame.local(
                    Vec3(
                        mesh.interleavedVertices[vertex],
                        mesh.interleavedVertices[vertex + 1],
                        mesh.interleavedVertices[vertex + 2],
                    ),
                )
            }
            val a = local[1] - local[0]
            val b = local[2] - local[0]
            val normal = Vec3(
                a.y * b.z - a.z * b.y,
                a.z * b.x - a.x * b.z,
                a.x * b.y - a.y * b.x,
            ).normalized() ?: continue
            val looks = if (leading) normal.z > 0.2f else normal.z < -0.2f
            if (!looks) continue
            val top = local.maxOf { it.y }
            if (abs(top - topRise) > 0.05f) continue
            val riseRun = local.maxOf { it.y } - local.minOf { it.y }
            val alongRun = local.maxOf { it.z } - local.minOf { it.z }
            val angle = if (alongRun < 1e-3f) 90f else atan2(riseRun, alongRun) * (180.0 / Math.PI).toFloat()
            best = if (best.isNaN()) angle else maxOf(best, angle)
        }
        assertTrue("a leading face whose top is " + topRise + " mm up the rise", !best.isNaN())
        return best
    }

    /** [mesh] moved along the assembly axis by [along]: positive pulls it apart. */
    private fun movedBy(mesh: StlMesh, along: Float): StlMesh {
        val builder = MeshSolidBuilder(mesh.displayName)
        for (triangle in 0 until mesh.triangleCount) {
            val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE
            val points = FloatArray(9)
            for (corner in 0 until 3) {
                val vertex = base + corner * MeshSolidBuilder.FLOATS_PER_VERTEX
                points[corner * 3] = mesh.interleavedVertices[vertex]
                points[corner * 3 + 1] = mesh.interleavedVertices[vertex + 1]
                points[corner * 3 + 2] = mesh.interleavedVertices[vertex + 2] + along
            }
            builder.addTriangle(
                points[0], points[1], points[2],
                points[3], points[4], points[5],
                points[6], points[7], points[8],
            )
        }
        return builder.build()
    }

    /**
     * How much of [first] the second solid takes away: the clash between two
     * parts, measured by subtraction rather than by intersection, because two
     * halves that touch are exactly the case an intersection cannot read back.
     */
    private fun clash(first: StlMesh, second: StlMesh): Double {
        val result = MeshBoolean.subtract(first, second)
        assertTrue(
            "the engine refused the subtraction: " + (result as? MeshBoolean.Result.Failure)?.reason,
            result is MeshBoolean.Result.Success,
        )
        return MeshVolume.of(first) - (result as MeshBoolean.Result.Success).volumeMm3
    }

    private fun solids(vararg meshes: StlMesh): StlMesh {
        val builder = MeshSolidBuilder(meshes.first().displayName)
        for (mesh in meshes) {
            for (triangle in 0 until mesh.triangleCount) {
                val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE
                val vertices = mesh.interleavedVertices
                builder.addTriangle(
                    vertices[base], vertices[base + 1], vertices[base + 2],
                    vertices[base + MeshSolidBuilder.FLOATS_PER_VERTEX],
                    vertices[base + MeshSolidBuilder.FLOATS_PER_VERTEX + 1],
                    vertices[base + MeshSolidBuilder.FLOATS_PER_VERTEX + 2],
                    vertices[base + 2 * MeshSolidBuilder.FLOATS_PER_VERTEX],
                    vertices[base + 2 * MeshSolidBuilder.FLOATS_PER_VERTEX + 1],
                    vertices[base + 2 * MeshSolidBuilder.FLOATS_PER_VERTEX + 2],
                )
            }
        }
        return builder.build()
    }

    private class Face(val low: Float, val high: Float, val normal: Vec3)
}
