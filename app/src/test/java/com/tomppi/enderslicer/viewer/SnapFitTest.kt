package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.viewer.BedClipper.Half
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The joint generator, asserted on its geometry rather than on its comments.
 *
 * Every claim the design rests on is measured here: the beam lies along the
 * assembly axis, the key is square and clearanced on all sides, the barb's
 * leading face is a 45-degree ramp while its back face stays square - the
 * difference between a joint that cams together and one that never assembles -
 * and the socket is the beam plus its own clearance, closed and positive.
 */
class SnapFitTest {
    private val parameters = SnapFitParameters()

    @Test
    fun theBeamLiesAlongTheAssemblyAxis() {
        val (low, high) = halves()
        val joint = joint(low, high)

        assertEquals("the frame's axis is the split plane's normal", 1f, joint.frame.axis.z, 1e-6f)
        assertEquals("and the anchor is dropped onto the mating plane", 0f, joint.frame.local(ANCHOR).z, 1e-4f)
        assertEquals("the plane is where the halves meet", 20f, joint.frame.planeOffsetMm, 1e-3f)

        val dimensions = joint.dimensions
        val beam = localVertices(joint.unionSolid, joint.frame)
            .filter { abs(it.x) <= dimensions.beamWidthMm * 0.5f + 1e-4f }
        val across = beam.maxOf { it.x } - beam.minOf { it.x }
        val through = beam.maxOf { it.y } - beam.minOf { it.y }
        val along = beam.maxOf { it.z } - beam.minOf { it.z }

        assertEquals("the beam is its own width across", dimensions.beamWidthMm, across, 1e-4f)
        assertEquals(
            "and its thickness plus the barb through",
            dimensions.beamThicknessMm + dimensions.lipDepthMm,
            through,
            1e-4f,
        )
        assertEquals(
            "it runs from its root to its tip",
            dimensions.beamRootMm + dimensions.beamLengthMm,
            along,
            1e-3f,
        )
        assertEquals("rooted below the mating plane", -dimensions.beamRootMm, beam.minOf { it.z }, 1e-3f)
        assertEquals("reaching past it", dimensions.beamLengthMm, beam.maxOf { it.z }, 1e-3f)
        assertTrue(
            "a beam, not a lump: " + along + " mm along the axis against " + across + " mm across",
            along > 3f * maxOf(across, through),
        )
        assertEquals("centred on the anchor", 0f, (beam.maxOf { it.x } + beam.minOf { it.x }) * 0.5f, 1e-4f)
        assertEquals("and in the model, its tip is above the plane", 28f, joint.unionSolid.bounds.maxZ, 1e-3f)
    }

    @Test
    fun theKeyIsSquareAndClearanced() {
        val (low, high) = halves()
        val joint = joint(low, high)
        val dimensions = joint.dimensions
        val socketEdge = dimensions.beamWidthMm * 0.5f + dimensions.lipClearanceMm + 1e-4f

        val key = localVertices(joint.unionSolid, joint.frame)
            .filter { it.x > dimensions.beamWidthMm * 0.5f + 1e-3f }
        val keyWidth = key.maxOf { it.x } - key.minOf { it.x }
        val keyDepth = key.maxOf { it.y } - key.minOf { it.y }
        assertEquals("the key is as wide as the parameter says", dimensions.keySizeMm, keyWidth, 1e-4f)
        assertEquals("and as deep", dimensions.keySizeMm, keyDepth, 1e-4f)
        assertEquals("a square", keyWidth, keyDepth, 1e-5f)
        assertEquals("standing out of the mating face", dimensions.keyHeightMm, key.maxOf { it.z }, 1e-3f)
        assertEquals("rooted as deep as the beam", -dimensions.beamRootMm, key.minOf { it.z }, 1e-3f)

        val socketKey = localVertices(joint.subtractSolid, joint.frame).filter { it.x > socketEdge }
        assertEquals(
            "its socket is the key plus clearance on every side",
            dimensions.keySizeMm + 2f * dimensions.keyClearanceMm,
            socketKey.maxOf { it.x } - socketKey.minOf { it.x },
            1e-4f,
        )
        assertEquals(
            "in both directions",
            dimensions.keySizeMm + 2f * dimensions.keyClearanceMm,
            socketKey.maxOf { it.y } - socketKey.minOf { it.y },
            1e-4f,
        )
        assertEquals("the key's near side", key.minOf { it.x }, dimensions.keyOffsetMm - dimensions.keySizeMm * 0.5f, 1e-4f)
        assertEquals(
            "the socket's near side, one clearance further out",
            key.minOf { it.x } - dimensions.keyClearanceMm,
            socketKey.minOf { it.x },
            1e-4f,
        )
    }

    @Test
    fun theBarbsLeadingFaceIsA45DegreeRampAndItsBackFaceIsSquare() {
        val (low, high) = halves()
        val joint = joint(low, high)
        val dimensions = joint.dimensions
        val half = dimensions.beamThicknessMm * 0.5f
        val diagonal = 0.70710678f

        val ramp = (0 until joint.unionSolid.triangleCount).filter { triangle ->
            val normal = localNormal(joint.unionSolid, triangle, joint.frame)
            abs(normal.x) < 1e-3f &&
                abs(normal.y - diagonal) < 5e-3f &&
                abs(normal.z - diagonal) < 5e-3f
        }
        assertTrue("the barb has a 45-degree lead-in", ramp.isNotEmpty())
        for (triangle in ramp) {
            val face = localTriangle(joint.unionSolid, triangle, joint.frame)
            assertTrue("the ramp is on the barb, out past the beam's flank", face.all { it.y >= half - 1e-4f })
            assertTrue(
                "and between the catch face and the tip",
                face.all { it.z >= dimensions.lipBackMm - 1e-3f && it.z <= dimensions.beamTipMm + 1e-3f },
            )
        }

        val catchFace = (0 until joint.unionSolid.triangleCount).filter { triangle ->
            val normal = localNormal(joint.unionSolid, triangle, joint.frame)
            normal.z < -0.999f && localTriangle(joint.unionSolid, triangle, joint.frame).all { it.z > dimensions.lipBackMm - 1e-3f }
        }
        assertTrue("the back face is square, facing back along the axis", catchFace.isNotEmpty())
        val catch = localTriangle(joint.unionSolid, catchFace.first(), joint.frame)
        assertEquals("it sits at the barb's back", dimensions.lipBackMm, catch.maxOf { it.z }, 1e-3f)
        assertEquals("and spans the barb's depth", half + dimensions.lipDepthMm, catch.maxOf { it.y }, 1e-3f)

        val squareLead = (0 until joint.unionSolid.triangleCount).filter { triangle ->
            val face = localTriangle(joint.unionSolid, triangle, joint.frame)
            val centre = (face[0] + face[1] + face[2]) * (1f / 3f)
            abs(centre.x) <= dimensions.beamWidthMm * 0.5f + 1e-3f &&
                centre.y > half + 1e-3f &&
                localNormal(joint.unionSolid, triangle, joint.frame).z > 0.99f
        }
        assertTrue("no square face meets the other part first: " + squareLead, squareLead.isEmpty())
    }

    @Test
    fun theSocketIsTheBeamPlusClearanceAndIsAClosedSolid() {
        val (low, high) = halves()
        val joint = joint(low, high)
        val dimensions = joint.dimensions

        assertTrue("the joint solid is closed", MeshFixtures.isClosed(joint.unionSolid))
        assertTrue("with positive volume", MeshFixtures.signedVolume(joint.unionSolid) > 0.0)
        assertTrue("the socket solid is closed", MeshFixtures.isClosed(joint.subtractSolid))
        assertTrue("with positive volume", MeshFixtures.signedVolume(joint.subtractSolid) > 0.0)

        // The pocket's cross-section, read at its mouth: a prism has vertices
        // only at its corners, and the mouth ring is one of them.
        val mouth = localVertices(joint.subtractSolid, joint.frame).filter {
            abs(it.x) <= dimensions.beamWidthMm * 0.5f + dimensions.lipClearanceMm + 1e-4f && it.z < 0f
        }
        assertEquals(
            "the pocket is the beam plus clearance across",
            dimensions.beamWidthMm + 2f * dimensions.lipClearanceMm,
            mouth.maxOf { it.x } - mouth.minOf { it.x },
            1e-4f,
        )
        assertEquals(
            "and through",
            dimensions.beamThicknessMm + 2f * dimensions.lipClearanceMm,
            mouth.maxOf { it.y } - mouth.minOf { it.y },
            1e-4f,
        )
        assertEquals(
            "the pocket opens below the mating face by the mating clearance",
            -dimensions.matingClearanceMm,
            localVertices(joint.subtractSolid, joint.frame).minOf { it.z },
            1e-4f,
        )
        val barbPocket = localVertices(joint.subtractSolid, joint.frame)
            .filter { it.z > dimensions.lipBackMm && it.y > 0f }
        assertEquals(
            "the barb's pocket is its depth plus clearance",
            dimensions.beamThicknessMm * 0.5f + dimensions.lipDepthMm + dimensions.lipClearanceMm,
            barbPocket.maxOf { it.y },
            1e-4f,
        )
        assertEquals(
            "and reaches past its tip",
            dimensions.beamTipMm + dimensions.lipClearanceMm,
            barbPocket.maxOf { it.z },
            1e-4f,
        )
    }

    @Test
    fun theScaleFactorChangesEveryDimensionProportionally() {
        val (low, high) = halves(width = 160f, depth = 80f, height = 80f, cut = 40f)
        val anchor = Vec3(80f, 40f, 40f)
        val one = joint(low, high, scale = 1f, anchor = anchor)
        val two = joint(low, high, scale = 2f, anchor = anchor)
        val first = one.dimensions
        val second = two.dimensions

        assertEquals(2f * first.keySizeMm, second.keySizeMm, 1e-4f)
        assertEquals(2f * first.keyHeightMm, second.keyHeightMm, 1e-4f)
        assertEquals(2f * first.keyOffsetMm, second.keyOffsetMm, 1e-4f)
        assertEquals(2f * first.beamLengthMm, second.beamLengthMm, 1e-4f)
        assertEquals(2f * first.beamWidthMm, second.beamWidthMm, 1e-4f)
        assertEquals(2f * first.beamThicknessMm, second.beamThicknessMm, 1e-4f)
        assertEquals(2f * first.beamRootMm, second.beamRootMm, 1e-4f)
        assertEquals(2f * first.lipDepthMm, second.lipDepthMm, 1e-4f)
        assertEquals(2f * first.lipRunMm, second.lipRunMm, 1e-4f)
        assertEquals("the key's clearance follows the scale", 2f * first.keyClearanceMm, second.keyClearanceMm, 1e-5f)
        assertEquals("so does the pocket's", 2f * first.lipClearanceMm, second.lipClearanceMm, 1e-5f)
        assertEquals("and the mating face's", 2f * first.matingClearanceMm, second.matingClearanceMm, 1e-5f)

        val beamOne = localVertices(one.unionSolid, one.frame)
            .filter { abs(it.x) <= first.beamWidthMm * 0.5f + 1e-4f }
        val beamTwo = localVertices(two.unionSolid, two.frame)
            .filter { abs(it.x) <= second.beamWidthMm * 0.5f + 1e-4f }
        assertEquals(
            "the geometry follows: twice the beam along the axis",
            2f * (beamOne.maxOf { it.z } - beamOne.minOf { it.z }),
            beamTwo.maxOf { it.z } - beamTwo.minOf { it.z },
            1e-3f,
        )
        assertEquals(
            "and twice as far across",
            2f * (beamOne.maxOf { it.x } - beamOne.minOf { it.x }),
            beamTwo.maxOf { it.x } - beamTwo.minOf { it.x },
            1e-3f,
        )
    }

    @Test
    fun theJointFollowsAnAxisThatIsNotZ() {
        val box = MeshFixtures.box(0f, 0f, 0f, 80f, 40f, 40f)
        val low = BedClipper.clipClosed(box, ModelPlacement.Axis.X, 40f, Half.LOW)
        val high = BedClipper.clipClosed(box, ModelPlacement.Axis.X, 40f, Half.HIGH)

        val joint = SnapFit.generate(Vec3(1f, 0f, 0f), Vec3(40f, 20f, 20f), 1f, low, high, parameters)

        assertNotNull("an X split takes a joint too", joint)
        val dimensions = joint!!.dimensions
        assertEquals("the axis is the X direction", 1f, joint.frame.axis.x, 1e-6f)
        assertEquals("the beam still runs along it", dimensions.beamRootMm + dimensions.beamLengthMm, joint.unionSolid.bounds.width, 1e-3f)
        assertEquals("from its root inside the low half", 40f - dimensions.beamRootMm, joint.unionSolid.bounds.minX, 1e-3f)
        assertEquals("to its tip inside the high half", 40f + dimensions.beamLengthMm, joint.unionSolid.bounds.maxX, 1e-3f)
        assertTrue("and the socket is closed in that frame too", MeshFixtures.isClosed(joint.subtractSolid))
    }

    @Test
    fun halvesInTheWrongOrderOrWithNoRoomProduceNothing() {
        val (low, high) = halves()

        assertNull(
            "halves the wrong way round straddle no plane",
            SnapFit.generate(Vec3(0f, 0f, 1f), ANCHOR, 1f, high, low, parameters),
        )
        assertNull(
            "a zero scale is not a joint",
            SnapFit.generate(Vec3(0f, 0f, 1f), ANCHOR, 0f, low, high, parameters),
        )
        assertNull(
            "a zero axis is not a direction",
            SnapFit.generate(Vec3(0f, 0f, 0f), ANCHOR, 1f, low, high, parameters),
        )
        val shallow = MeshFixtures.box(0f, 0f, 0f, 40f, 20f, 4f)
        val shallowLow = BedClipper.clipClosed(shallow, ModelPlacement.Axis.Z, 1f, Half.LOW)
        val shallowHigh = BedClipper.clipClosed(shallow, ModelPlacement.Axis.Z, 1f, Half.HIGH)
        assertNull(
            "no material under the beam's root",
            SnapFit.generate(Vec3(0f, 0f, 1f), Vec3(20f, 10f, 1f), 1f, shallowLow, shallowHigh, parameters),
        )

        val thin = MeshFixtures.box(0f, 0f, 0f, 40f, 20f, 1.2f)
        val thinLow = BedClipper.clipClosed(thin, ModelPlacement.Axis.Z, 0.6f, Half.LOW)
        val thinHigh = BedClipper.clipClosed(thin, ModelPlacement.Axis.Z, 0.6f, Half.HIGH)
        assertNull(
            "a mate with less material than the wall the beam must leave is refused",
            SnapFit.generate(Vec3(0f, 0f, 1f), Vec3(20f, 10f, 0.6f), 1f, thinLow, thinHigh, parameters),
        )
    }

    private fun halves(
        width: Float = 80f,
        depth: Float = 40f,
        height: Float = 40f,
        cut: Float = 20f,
    ): Pair<StlMesh, StlMesh> {
        val box = MeshFixtures.box(0f, 0f, 0f, width, depth, height)
        return BedClipper.clipClosed(box, ModelPlacement.Axis.Z, cut, Half.LOW) to
            BedClipper.clipClosed(box, ModelPlacement.Axis.Z, cut, Half.HIGH)
    }

    private fun joint(
        low: StlMesh,
        high: StlMesh,
        scale: Float = 1f,
        anchor: Vec3 = ANCHOR,
    ): SnapFitJoint {
        val result = SnapFit.generate(Vec3(0f, 0f, 1f), anchor, scale, low, high, parameters)
        assertNotNull("this joint has to exist", result)
        return result!!
    }

    private fun localVertices(mesh: StlMesh, frame: SnapFitFrame): List<Vec3> {
        val vertices = mesh.interleavedVertices
        return (0 until mesh.triangleCount * 3).map { vertex ->
            val base = vertex * MeshSolidBuilder.FLOATS_PER_VERTEX
            frame.local(Vec3(vertices[base], vertices[base + 1], vertices[base + 2]))
        }
    }

    private fun localTriangle(mesh: StlMesh, triangle: Int, frame: SnapFitFrame): List<Vec3> {
        val vertices = mesh.interleavedVertices
        val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE
        return (0 until 3).map { corner ->
            val at = base + corner * MeshSolidBuilder.FLOATS_PER_VERTEX
            frame.local(Vec3(vertices[at], vertices[at + 1], vertices[at + 2]))
        }
    }

    private fun localNormal(mesh: StlMesh, triangle: Int, frame: SnapFitFrame): Vec3 {
        val vertices = mesh.interleavedVertices
        val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE + 3
        val normal = Vec3(vertices[base], vertices[base + 1], vertices[base + 2])
        return Vec3(normal.dot(frame.side), normal.dot(frame.rise), normal.dot(frame.axis))
    }

    private companion object {
        val ANCHOR = Vec3(40f, 20f, 20f)
    }
}
