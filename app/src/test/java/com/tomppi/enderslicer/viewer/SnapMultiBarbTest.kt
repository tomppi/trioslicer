package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.viewer.BedClipper.Half
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The multi-barb beam, asserted on its geometry.
 *
 * One pawl on the mate and several teeth on the beam - not a matching series of
 * grooves, which is the conclusion docs/snap-fit-notes.md records. What is
 * measured here: the teeth sit one pitch apart (the lead-in's run plus a
 * lip-depth flat), each has a square catch at a different offset, the deepest
 * catch is where the mate's single step sits, every click has a name, the
 * socket gives the beam the room it needs to bend before it will go together at
 * all, the mouth chamfer really is what cams in a square-faced hook, and one
 * tooth is exactly the joint that was built before any of this existed.
 */
class SnapMultiBarbTest {
    @Test
    fun twoTeethSitOnePitchApartWithTwoSquareCatches() {
        val joint = joint(SnapFitParameters(barbCount = 2))
        val dimensions = joint.dimensions

        assertEquals("two teeth", 2, dimensions.barbCount)
        assertEquals("the pitch is the run plus a lip-depth flat", dimensions.lipRunMm + dimensions.lipDepthMm, dimensions.toothPitchMm, 1e-5f)
        assertEquals("with the catches a pitch apart", dimensions.toothPitchMm, dimensions.teethCatchMm[0] - dimensions.teethCatchMm[1], 1e-5f)
        assertEquals("and both on the beam", 2, dimensions.teethCatchMm.size)

        val catches = catchFaces(joint)
        assertEquals("the beam carries two square catches: " + catches, 2, catches.size)
        assertEquals(dimensions.teethCatchMm[0], catches[0], 1e-3f)
        assertEquals(dimensions.teethCatchMm[1], catches[1], 1e-3f)
        assertEquals(
            "and the pawl is one clearance in front of the deepest of them",
            dimensions.teethCatchMm.last() - dimensions.lipClearanceMm,
            dimensions.pawlMm,
            1e-5f,
        )
    }

    @Test
    fun twoTeethOfferTwoClicksAndTheDeepestIsTheSeatedOne() {
        val joint = joint(SnapFitParameters(barbCount = 2))

        assertEquals("two clicks", 2, joint.clicks.size)
        assertEquals("the tooth nearest the tip is caught first", 1, joint.clicks[0].order)
        assertEquals(
            "with the halves still apart by one pitch",
            joint.dimensions.toothPitchMm + joint.dimensions.lipClearanceMm,
            joint.clicks[0].gapMm,
            1e-4f,
        )
        assertEquals("and it says so in the user's terms", "first click - loose", joint.clicks[0].label)
        assertEquals("the deepest catch seats the halves", joint.dimensions.lipClearanceMm, joint.clicks[1].gapMm, 1e-4f)
        assertEquals(SnapFit.SEATED_CLICK, joint.clicks[1].label)
        assertEquals("the joint reports the seated click as its engagement", joint.clicks.last(), joint.seatedClick)
        assertTrue("and the summary names it: " + joint.clickSummary, joint.clickSummary.contains(SnapFit.SEATED_CLICK))
        assertTrue("with the other depths listed too: " + joint.clickSummary, joint.clickSummary.contains("clicks at"))
    }

    @Test
    fun threeTeethOfferThreeClicksAndAOneToothBeamOffersOne() {
        val three = joint(SnapFitParameters(barbCount = 3))
        assertEquals(3, three.clicks.size)
        assertEquals(listOf("first click - loose", "second click", SnapFit.SEATED_CLICK), three.clicks.map { it.label })
        assertTrue("the clicks are a pitch apart", abs(three.clicks[1].gapMm - three.clicks[0].gapMm) > 0f)
        assertEquals(
            "each a pitch further in",
            three.dimensions.toothPitchMm,
            three.clicks[0].gapMm - three.clicks[1].gapMm,
            1e-4f,
        )
        assertEquals("the deepest is the seated one", SnapFit.SEATED_CLICK, three.clickSummary.substringBefore(" ("))
        assertEquals("three catch faces in the beam", 3, catchFaces(three).size)

        val one = joint(SnapFitParameters())
        assertEquals("one tooth is one click", 1, one.clicks.size)
        assertEquals("and it is the seated one", SnapFit.SEATED_CLICK, one.clicks.single().label)
    }

    @Test
    fun aBeamTooShortForThreeTeethClampsTheCountAndSaysWhy() {
        // 4 mm of mate leaves about 2.5 mm of hook: one tooth and a bit of a
        // second. The clamp is reported, never silent.
        val pair = halves(height = 8f, cut = 4f)
        val joint = joint(SnapFitParameters(barbCount = 3), pair, face = 4f, anchor = Vec3(40f, 20f, 4f))

        assertTrue("no more than the beam's length takes: " + joint.dimensions.barbCount, joint.dimensions.barbCount < 3)
        val clamp = joint.clamps.firstOrNull { it.label == "barbs" }
        assertNotNull("and the panel is told: " + joint.clamps, clamp)
        assertEquals("with what was asked for", 3f, clamp!!.askedMm, 1e-3f)
        assertTrue("in the user's terms: " + clamp.reason, clamp.reason.contains("pitch"))
    }

    @Test
    fun oneToothIsExactlyTheBeamThatWasBuiltBeforeThisExisted() {
        // The fingerprint of the beam the committed code produced for this
        // fixture, captured before the multi-barb work started. One tooth is the
        // default, so this has to stay byte for byte what it was.
        val joint = joint(SnapFitParameters())

        assertEquals(1, joint.dimensions.barbCount)
        assertEquals(32, joint.unionSolid.triangleCount)
        assertEquals(121.07107594298208, MeshFixtures.signedVolume(joint.unionSolid), 1e-9)
        assertEquals(6836155364772969285L, rawHash(joint.unionSolid))
        assertEquals(-1751914096227418809L, sortedHash(joint.unionSolid))
        assertEquals("the socket's step is still one clearance in front of the catch", joint.dimensions.lipBackMm - joint.dimensions.lipClearanceMm, joint.dimensions.pawlMm, 1e-5f)
        assertEquals(1, catchFaces(joint).size)
    }

    @Test
    fun theSocketGivesTheBeamRoomToBendOrTheJointCannotClose() {
        val joint = joint(SnapFitParameters())

        val sink = joint.dimensions.lipDepthMm - joint.dimensions.lipClearanceMm
        assertTrue(
            "the pocket's floor is dropped by the sink plus a clearance: " + joint.dimensions.deflectionRoomMm,
            joint.dimensions.deflectionRoomMm >= sink + joint.dimensions.lipClearanceMm - 1e-4f,
        )
        // The floor really is cut there, in the socket's own frame. Only the
        // beam's own slot counts: the key's socket is deeper still and is a
        // different feature.
        val pocket = localVertices(joint.subtractSolid, joint.socketFrame).filter {
            abs(it.x) <= joint.dimensions.beamWidthMm * 0.5f + joint.dimensions.lipClearanceMm + 1e-4f
        }
        val floor = pocket.minOf { it.y }
        assertEquals(
            "and the socket is cut that deep",
            -(joint.dimensions.beamThicknessMm * 0.5f + joint.dimensions.deflectionRoomMm),
            floor,
            1e-3f,
        )
        assertTrue(
            "the readout takes every tooth's lip as the worst case: " + joint.dimensions.deflectionMm,
            joint.dimensions.barbCount * joint.dimensions.lipDepthMm == joint.dimensions.deflectionMm,
        )
    }

    @Test
    fun theMouthChamferOpensThePocketAtFortyFiveDegreesAndKeepsThePawl() {
        val plain = joint(SnapFitParameters())
        val ramped = joint(SnapFitParameters(socketRamp = true))

        assertEquals("no chamfer by default", 0f, plain.dimensions.mouthChamferMm, 0f)
        assertTrue("and one when it is asked for", ramped.dimensions.mouthChamferMm > 0f)
        assertEquals(
            "at 45 degrees, the printable limit for an overhanging roof",
            45f,
            ramped.dimensions.mouthChamferAngleDeg,
            1e-4f,
        )
        assertEquals("as deep as the tooth is proud", ramped.dimensions.lipDepthMm, ramped.dimensions.mouthChamferMm, 1e-4f)
        assertEquals(
            "and it cannot eat the pawl: the pawl stays where the deepest catch is",
            ramped.dimensions.teethCatchMm.last() - ramped.dimensions.lipClearanceMm,
            ramped.dimensions.pawlMm,
            1e-5f,
        )
        // The mouth is wider than the slot behind it: that funnel IS the ramp.
        // The socket's section is read as its own corners: the mouth ring at
        // the mating clearance, the chamfer's inner end, and the pawl.
        val socket = localVertices(ramped.subtractSolid, ramped.socketFrame).filter {
            abs(it.x) <= ramped.dimensions.beamWidthMm * 0.5f + ramped.dimensions.lipClearanceMm + 1e-4f
        }
        val half = ramped.dimensions.beamThicknessMm * 0.5f
        val roof = half + ramped.dimensions.lipClearanceMm
        val wide = roof + ramped.dimensions.lipDepthMm
        val run = ramped.dimensions.mouthChamferMm
        assertTrue(
            "the mouth is opened by the chamfer at the mating face",
            socket.any { abs(it.z + ramped.dimensions.matingClearanceMm) < 1e-3f && abs(it.y - (roof + run)) < 1e-3f },
        )
        assertTrue(
            "and closes back onto the slot at the chamfer's own run",
            socket.any { abs(it.z - run) < 1e-3f && abs(it.y - roof) < 1e-3f },
        )
        assertTrue(
            "which is where the pawl's step begins",
            socket.any { abs(it.z - ramped.dimensions.pawlMm) < 1e-3f && abs(it.y - roof) < 1e-3f },
        )
        assertTrue(
            "with the wide slot beyond it",
            socket.any { abs(it.z - ramped.dimensions.pawlMm) < 1e-3f && abs(it.y - wide) < 1e-3f },
        )
    }

    @Test
    fun aSquareFacedHookHasNoRampAndIsCammedByTheSocketInstead() {
        val square = joint(SnapFitParameters(rampAngleDeg = 90f, socketRamp = true))
        val dimensions = square.dimensions
        val half = dimensions.beamThicknessMm * 0.5f

        assertEquals("a square lead-in is as long along the axis as a 45-degree ramp", dimensions.lipDepthMm, dimensions.lipRunMm, 1e-4f)
        val leading = leadingFaces(square)
        assertEquals("the tooth meets the mate with one square face per tooth: " + leading, 1, leading.size)
        assertEquals("at the tooth's own lead-in", dimensions.teethCatchMm[0] + dimensions.lipRunMm, leading.single(), 1e-3f)

        val ramped = joint(SnapFitParameters(socketRamp = true))
        assertTrue("a ramped hook has no square face of its own: " + leadingFaces(ramped), leadingFaces(ramped).isEmpty())
        assertTrue("so the socket's chamfer is what cams it in", ramped.dimensions.mouthChamferMm > 0f)
        assertEquals(
            "and the tooth is still the same height",
            half + dimensions.lipDepthMm,
            localVertices(square.unionSolid, square.frame)
                .filter { abs(it.x) <= dimensions.beamWidthMm * 0.5f + 1e-3f }
                .maxOf { it.y },
            1e-4f,
        )
    }

    @Test
    fun oppositeFacingPutsTheHookOnTheOtherSideOfTheBeamAndThePocketWithIt() {
        val same = joint(SnapFitParameters())
        val opposite = joint(SnapFitParameters(facing = SnapFacing.OPPOSITE))

        val half = same.dimensions.beamThicknessMm * 0.5f
        val sameBeam = localVertices(same.unionSolid, same.frame).filter { abs(it.x) <= same.dimensions.beamWidthMm * 0.5f + 1e-4f }
        val oppositeBeam = localVertices(opposite.unionSolid, opposite.frame).filter { abs(it.x) <= opposite.dimensions.beamWidthMm * 0.5f + 1e-4f }
        assertEquals("the default hook stands on the +rise side", half + same.dimensions.lipDepthMm, sameBeam.maxOf { it.y }, 1e-4f)
        assertEquals("the opposite one on the -rise side", -(half + opposite.dimensions.lipDepthMm), oppositeBeam.minOf { it.y }, 1e-4f)
        assertEquals("and leaves the other side flat", half, oppositeBeam.maxOf { it.y }, 1e-4f)

        // The catch still faces back along the same assembly direction: a mixed
        // pair locks the slide both ways rather than the pull-apart differently.
        assertEquals(1f, same.frame.axis.z, 1e-6f)
        assertEquals(1f, opposite.frame.axis.z, 1e-6f)
        assertEquals("one catch face each", 1, catchFaces(same).size)
        assertEquals("and the mirrored one too", 1, catchFaces(opposite).size)
        assertEquals("at the same depth", catchFaces(same).single(), catchFaces(opposite).single(), 1e-4f)

        // The pocket follows the hook, or the two would not mate at all: the
        // wide slot on the hook's side, the narrow roof one clearance off the
        // beam, and the deflection room dropped to the OTHER side with it.
        val oppositeSocket = localVertices(opposite.subtractSolid, opposite.socketFrame).filter {
            abs(it.x) <= opposite.dimensions.beamWidthMm * 0.5f + opposite.dimensions.lipClearanceMm + 1e-4f
        }
        assertEquals(
            "the pocket's wide side is on the hook's side",
            -(half + opposite.dimensions.lipDepthMm + opposite.dimensions.lipClearanceMm),
            oppositeSocket.minOf { it.y },
            1e-3f,
        )
        assertTrue(
            "and its narrow roof one clearance off the beam",
            oppositeSocket.any { abs(it.y + half + opposite.dimensions.lipClearanceMm) < 1e-3f },
        )
        assertEquals(
            "and the room the beam bends through on the other side again",
            half + opposite.dimensions.deflectionRoomMm,
            oppositeSocket.maxOf { it.y },
            1e-3f,
        )
    }

    @Test
    fun theTightStepIsClearanceOnlyAndExactlyTheStep() {
        val loose = joint(SnapFitParameters())
        val tight = joint(SnapFitParameters().tightenedBy(SnapTightness.TIGHT.stepMm))

        assertEquals(
            "exactly the step less around the beam and its teeth",
            loose.dimensions.lipClearanceMm - SnapTightness.TIGHT.stepMm,
            tight.dimensions.lipClearanceMm,
            1e-6f,
        )
        assertEquals(
            "and around the key",
            loose.dimensions.keyClearanceMm - SnapTightness.TIGHT.stepMm,
            tight.dimensions.keyClearanceMm,
            1e-6f,
        )
        assertEquals("the beam is the same length", loose.dimensions.beamLengthMm, tight.dimensions.beamLengthMm, 0f)
        assertEquals("and the same thickness", loose.dimensions.beamThicknessMm, tight.dimensions.beamThicknessMm, 0f)
        assertEquals("and the same lip", loose.dimensions.lipDepthMm, tight.dimensions.lipDepthMm, 0f)
        assertEquals("and the same ramp angle", loose.dimensions.rampAngleDeg, tight.dimensions.rampAngleDeg, 0f)
        assertEquals("and the same teeth", loose.dimensions.barbCount, tight.dimensions.barbCount)
        assertEquals("and the same facing: a step is clearance, never orientation", loose.dimensions.facing, tight.dimensions.facing)
        assertEquals("and the same catch depths", loose.dimensions.teethCatchMm, tight.dimensions.teethCatchMm)
        assertEquals(
            "the mating face keeps its own clearance: that is the face relief, not the fit",
            loose.dimensions.matingClearanceMm,
            tight.dimensions.matingClearanceMm,
            0f,
        )
        assertEquals("0.06 mm, about what a 0.4 mm nozzle resolves", 0.06f, SnapTightness.TIGHT.stepMm, 1e-6f)
    }

    @Test
    fun threeTeethNeedABeamLongEnoughToCarryThem() {
        val joint = joint(SnapFitParameters(barbCount = 3))
        val deepest = joint.dimensions.teethCatchMm.last()
        assertTrue("the deepest catch is inside the pocket: " + deepest, deepest > 0f)
        assertTrue(
            "and the beam's tip is past the shallowest: " + joint.dimensions.beamTipMm,
            joint.dimensions.beamTipMm > joint.dimensions.teethCatchMm.first(),
        )
        assertNull("nothing was clamped on this seam", joint.clamps.firstOrNull { it.label == "barbs" })
        assertEquals("three teeth on the beam", 3, catchFaces(joint).size)
    }

    /** A joint on an 80 x 40 x 40 box split in half, in its own coordinates. */
    private fun joint(
        parameters: SnapFitParameters,
        halves: Pair<StlMesh, StlMesh>? = null,
        face: Float = 20f,
        anchor: Vec3 = Vec3(40f, 20f, 20f),
    ): SnapFitJoint {
        val (low, high) = halves ?: halves()
        val result = SnapFit.generate(
            Vec3(0f, 0f, 1f),
            anchor,
            1f,
            SnapFitHalf.inPlace(low, face),
            SnapFitHalf.inPlace(high, face),
            parameters,
        )
        assertNotNull("this joint has to exist", result)
        return result!!
    }

    private fun halves(height: Float = 40f, cut: Float = 20f): Pair<StlMesh, StlMesh> {
        val box = MeshFixtures.box(0f, 0f, 0f, 80f, 40f, height)
        return BedClipper.clipClosed(box, ModelPlacement.Axis.Z, cut, Half.LOW) to
            BedClipper.clipClosed(box, ModelPlacement.Axis.Z, cut, Half.HIGH)
    }

    /**
     * The z of every square face on the beam that faces back towards the mating
     * plane - the catches, tip-most first. A face whose normal is -z and which
     * stands above the beam's own surface can only be a catch.
     */
    private fun catchFaces(joint: SnapFitJoint): List<Float> {
        val half = joint.dimensions.beamThicknessMm * 0.5f
        val width = joint.dimensions.beamWidthMm * 0.5f + 1e-3f
        val zs = ArrayList<Float>()
        for (triangle in 0 until joint.unionSolid.triangleCount) {
            val face = localTriangle(joint.unionSolid, triangle, joint.frame)
            val normal = localNormal(joint.unionSolid, triangle, joint.frame)
            val centre = (face[0] + face[1] + face[2]) * (1f / 3f)
            if (normal.z < -0.99f && abs(centre.y) > half + 1e-3f && abs(centre.x) <= width) zs += centre.z
        }
        return distinct(zs)
    }

    /** The z of every square face on the beam that faces along the assembly direction. */
    private fun leadingFaces(joint: SnapFitJoint): List<Float> {
        val half = joint.dimensions.beamThicknessMm * 0.5f
        val width = joint.dimensions.beamWidthMm * 0.5f + 1e-3f
        val zs = ArrayList<Float>()
        for (triangle in 0 until joint.unionSolid.triangleCount) {
            val face = localTriangle(joint.unionSolid, triangle, joint.frame)
            val normal = localNormal(joint.unionSolid, triangle, joint.frame)
            val centre = (face[0] + face[1] + face[2]) * (1f / 3f)
            if (normal.z > 0.99f && abs(centre.y) > half + 1e-3f && abs(centre.x) <= width) zs += centre.z
        }
        return distinct(zs)
    }

    /**
     * One entry per face: a quad is two triangles, and a prism's face is two
     * triangles wherever it was built. Faces within a micron of each other are
     * the same face.
     */
    private fun distinct(zs: List<Float>): List<Float> {
        val sorted = zs.sortedDescending()
        val faces = ArrayList<Float>()
        for (z in sorted) {
            if (faces.isEmpty() || abs(faces.last() - z) > 1e-3f) faces += z
        }
        return faces
    }

    private fun localVertices(mesh: StlMesh, frame: SnapFitFrame): List<Vec3> =
        (0 until mesh.triangleCount * 3).map { vertex ->
            val base = vertex * MeshSolidBuilder.FLOATS_PER_VERTEX
            frame.local(Vec3(mesh.interleavedVertices[base], mesh.interleavedVertices[base + 1], mesh.interleavedVertices[base + 2]))
        }

    private fun localTriangle(mesh: StlMesh, triangle: Int, frame: SnapFitFrame): List<Vec3> {
        val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE
        return (0 until 3).map { corner ->
            val at = base + corner * MeshSolidBuilder.FLOATS_PER_VERTEX
            frame.local(Vec3(mesh.interleavedVertices[at], mesh.interleavedVertices[at + 1], mesh.interleavedVertices[at + 2]))
        }
    }

    private fun localNormal(mesh: StlMesh, triangle: Int, frame: SnapFitFrame): Vec3 {
        val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE + 3
        val normal = Vec3(mesh.interleavedVertices[base], mesh.interleavedVertices[base + 1], mesh.interleavedVertices[base + 2])
        return Vec3(normal.dot(frame.side), normal.dot(frame.rise), normal.dot(frame.axis))
    }

    /** Every float of [mesh], in order, hashed the way the baseline was captured. */
    private fun rawHash(mesh: StlMesh): Long {
        val vertices = mesh.interleavedVertices
        var hash = 17L
        for (index in 0 until vertices.size) {
            hash = hash * 31 + (vertices[index] * 1000f).roundToInt().toLong()
        }
        return hash
    }

    /** The same, with the vertex positions sorted first: order-independent. */
    private fun sortedHash(mesh: StlMesh): Long {
        val vertices = mesh.interleavedVertices
        val positions = ArrayList<String>(vertices.size / 3)
        var index = 0
        while (index < vertices.size) {
            positions.add(vertices[index].toString() + "," + vertices[index + 1] + "," + vertices[index + 2])
            index += 3
        }
        positions.sort()
        var hash = 5L
        for (position in positions) hash = hash * 31 + position.hashCode()
        return hash
    }
}
