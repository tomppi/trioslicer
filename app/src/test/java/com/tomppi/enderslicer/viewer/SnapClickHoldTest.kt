package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.viewer.BedClipper.Half
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The clicks the panel advertises, and whether they really hold.
 *
 * ONE PAWL and several teeth on the beam is the ratchet docs/snap-fit-notes.md
 * settled on. What it needs is a pocket whose narrow part is only as long as the
 * pawl: at any click but the seated one there is a tooth BEHIND the caught one,
 * and if the channel it stands in is one clearance off the beam the only way to
 * keep that tooth under the roof is to bend the beam down by the whole sink. A
 * cantilever bends further the further out it is, so the tooth the pawl is
 * holding - which is further out than the one forcing the bend - drops below the
 * pawl by at least as much, and the click holds with ZERO engagement. The pocket
 * was narrow from its mouth to the pawl, so every advertised click but the last
 * held nothing.
 *
 * Measured here the way the parent asked: at each click, with the beam at its
 * own natural height (which is all that click requires - the channel behind the
 * pawl is wide enough for a tooth to stand in), the beam shares no material with
 * the mate, and the caught tooth's catch face still stands above the pawl's own
 * face.
 */
class SnapClickHoldTest {
    @Test
    fun everyAdvertisedClickHoldsWithoutBendingTheBeam() {
        assumeTrue("the host Manifold build is not on java.library.path", MeshBoolean.ensureLoaded())
        val (low, high) = halves()
        val joint = jointAt(low, high, Vec3(40f, 20f, 20f), SnapFitParameters(barbCount = 3))
        assertEquals("three teeth, three clicks", 3, joint.clicks.size)

        val hollowed = MeshBoolean.subtract(high, joint.subtractSolid)
        assertTrue("the mate's pocket was cut: " + hollowed, hollowed is MeshBoolean.Result.Success)
        val material = (hollowed as MeshBoolean.Result.Success).mesh
        val before = MeshVolume.of(material)

        for ((index, click) in joint.clicks.withIndex()) {
            // The beam at its own height, withdrawn to this click. Nothing is
            // bent: the click is only real if the beam can sit there as it is.
            // A twentieth of a millimetre PAST the catch meeting the pawl: at
            // the contact itself the tooth's catch face and the pawl's own face
            // are coplanar, and the boolean reads its own float noise there.
            // Pulling the beam back instead would push the tooth's crown into
            // the land's face, which is the contact, not a jam.
            val swept = withdrawn(joint, click.gapMm - 0.05f)
            val carved = MeshBoolean.subtract(material, swept)
            assertTrue("the sweep ran: " + carved, carved is MeshBoolean.Result.Success)
            val clash = before - (carved as MeshBoolean.Result.Success).volumeMm3
            assertTrue(
                "click " + (index + 1) + " (" + click.label + ", " + click.gapMm +
                    " mm apart) needs no bending: the beam cuts " + clash + " mm3 out of the mate",
                clash < 0.02,
            )

            val engagement = engagement(joint, click.gapMm, index)
            assertTrue(
                "click " + (index + 1) + " (" + click.label + ", " + click.gapMm +
                    " mm apart) holds: the caught tooth stands " + engagement + " mm above the pawl",
                engagement > 0.05f,
            )
        }
    }

    @Test
    fun theChannelBehindThePawlIsWideEnoughForAToothToStandIn() {
        // The shape that makes the clicks hold, read off the socket itself: the
        // pocket's roof is the wide one everywhere except the land the pawl's
        // face is cut into, and that land is short.
        val (low, high) = halves()
        val joint = jointAt(low, high, Vec3(40f, 20f, 20f), SnapFitParameters(barbCount = 3))
        val dimensions = joint.dimensions
        val half = dimensions.beamThicknessMm * 0.5f
        val narrowRoof = half + dimensions.lipClearanceMm
        val wideRoof = narrowRoof + dimensions.lipDepthMm
        val width = dimensions.beamWidthMm * 0.5f + dimensions.lipClearanceMm + 1e-3f

        assertTrue("the land is shorter than the sink", dimensions.pawlLandMm < dimensions.lipDepthMm)
        assertTrue("and the deepest catch still sits in front of it", dimensions.teethCatchMm.last() > dimensions.pawlMm)

        val relief = socketVertices(joint, width).filter {
            it.z < dimensions.pawlMm - dimensions.pawlLandMm - 1e-3f &&
                it.z >= -dimensions.matingClearanceMm - 1e-3f
        }
        assertTrue("there is a channel behind the pawl", relief.isNotEmpty())
        assertEquals(
            "and it is deep enough for a tooth: " + relief.maxOf { it.y },
            wideRoof,
            relief.maxOf { it.y },
            1e-3f,
        )
        val landStart = dimensions.pawlMm - dimensions.pawlLandMm
        assertTrue(
            "the land starts where the relief ends, one clearance off the beam",
            socketVertices(joint, width).any { abs(it.z - landStart) < 1e-3f && abs(it.y - narrowRoof) < 1e-3f },
        )
        assertTrue(
            "and the wide slot starts at the pawl itself",
            socketVertices(joint, width).any {
                abs(it.z - dimensions.pawlMm) < 1e-3f && abs(it.y - wideRoof) < 1e-3f
            },
        )
    }

    /** Every vertex of the socket's own slot, in the socket's frame. */
    private fun socketVertices(joint: SnapFitJoint, width: Float): List<Vec3> {
        val vertices = joint.subtractSolid.interleavedVertices
        return (0 until joint.subtractSolid.triangleCount * 3).mapNotNull { vertex ->
            val base = vertex * MeshSolidBuilder.FLOATS_PER_VERTEX
            val local = joint.socketFrame.local(Vec3(vertices[base], vertices[base + 1], vertices[base + 2]))
            local.takeIf { abs(it.x) <= width }
        }
    }

    /**
     * How much of the caught tooth's catch face stands above the pawl's own
     * face at this click: the material that would press on the pawl if the
     * halves were pulled apart.
     */
    private fun engagement(joint: SnapFitJoint, gapMm: Float, index: Int): Float {
        val dimensions = joint.dimensions
        val sign = dimensions.facing.sign
        val half = dimensions.beamThicknessMm * 0.5f
        val narrowRoof = half + dimensions.lipClearanceMm
        val wideRoof = narrowRoof + dimensions.lipDepthMm
        // At a click the caught tooth's catch face rests ON the pawl's own
        // face: the click's gap is the faces' separation, and the pawl sits one
        // clearance behind the deepest catch.
        val catchAt = dimensions.pawlMm
        val vertices = joint.unionSolid.interleavedVertices
        var top = Float.NEGATIVE_INFINITY
        for (vertex in 0 until joint.unionSolid.triangleCount * 3) {
            val base = vertex * MeshSolidBuilder.FLOATS_PER_VERTEX
            val local = joint.socketFrame.local(Vec3(vertices[base], vertices[base + 1], vertices[base + 2]))
            if (abs(local.x) > dimensions.beamWidthMm * 0.5f + 1e-3f) continue
            val z = local.z - gapMm
            if (z < catchAt - 1e-3f || z > catchAt + dimensions.lipRunMm + 1e-3f) continue
            top = maxOf(top, sign * local.y)
        }
        assertTrue("the caught tooth is on the beam at click " + (index + 1), top.isFinite())
        return minOf(top, wideRoof) - maxOf(half, narrowRoof)
    }

    /** The joint as the parts close by [step]: back along the axis, at its own height. */
    private fun withdrawn(joint: SnapFitJoint, step: Float): StlMesh {
        val along = joint.frame.axis * (-step)
        val builder = MeshSolidBuilder(joint.unionSolid.displayName)
        val vertices = joint.unionSolid.interleavedVertices
        val points = FloatArray(9)
        for (triangle in 0 until joint.unionSolid.triangleCount) {
            val base = triangle * MeshSolidBuilder.FLOATS_PER_TRIANGLE
            for (corner in 0 until 3) {
                val at = base + corner * MeshSolidBuilder.FLOATS_PER_VERTEX
                val moved = Vec3(vertices[at], vertices[at + 1], vertices[at + 2]) + along
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
}
