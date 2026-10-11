package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.viewer.BedClipper.Half
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clamps that only had their sentences tested, measured on the geometry
 * they clamped.
 *
 * Hook length and the tooth count were already pinned. The hook's thickness,
 * the room the beam needs to bend through, and the socket's own ramp were
 * reported by the panel and otherwise left to trust. Each is asserted here
 * both ways: the clamp says what it cut short, and the solid it built is the
 * size it named.
 */
class SnapClampGeometryTest {
    @Test
    fun aThicknessTheMaterialWillNotTakeIsClampedAndTheBeamIsTheClampedSize() {
        // A 4 mm part split 1 mm up: the lightest rung shrinks the beam to half
        // the thinner side, which is well under the parameter's 1.4 mm.
        val (low, high) = shallowHalves()
        val joint = joint(low, high, face = 1f, anchor = Vec3(20f, 10f, 1f))

        assertEquals("the ladder is on its lightest rung", SnapFitRung.MINIMAL, joint.rung)
        val clamp = joint.clamps.firstOrNull { it.label == "hook thickness" }
        assertNotNull("the panel is told: " + joint.clamps, clamp)
        assertEquals("with the thickness that was asked for", 1.4f, clamp!!.askedMm, 1e-3f)
        assertEquals("and the one that was built", joint.dimensions.beamThicknessMm, clamp.actualMm, 1e-3f)
        assertTrue("in the user's terms: " + clamp.reason, clamp.reason.contains("thickness clamped"))

        // The beam really is that thin: measured across the beam at its tip,
        // where the profile carries the underside and the beam's own top face.
        val dimensions = joint.dimensions
        val beam = localVertices(joint.unionSolid, joint.frame).filter {
            abs(it.x) <= dimensions.beamWidthMm * 0.5f + 1e-4f &&
                abs(it.z - dimensions.beamLengthMm) < 1e-3f
        }
        val thickness = beam.maxOf { it.y } - beam.minOf { it.y }
        assertEquals("the geometry follows the clamp", clamp.actualMm, thickness, 1e-3f)
        assertTrue("and the clamp is a real cut: " + clamp, clamp.actualMm < clamp.askedMm - 1e-3f)
    }

    @Test
    fun deflectionRoomIsClampedToTheMatesOwnMaterialAndTheFloorFollows() {
        // The hook bends away from its own lip. On this half the anchor is
        // 1 mm from that side of the mate's cross-section, so the mate has less
        // than the sink-plus-clearance the floor wants to be dropped through.
        val (low, high) = halves()
        val joint = joint(low, high, face = 20f, anchor = Vec3(1f, 20f, 20f))

        val dimensions = joint.dimensions
        val half = dimensions.beamThicknessMm * 0.5f
        val clamp = joint.clamps.firstOrNull { it.label == "deflection room" }
        assertNotNull("the panel is told: " + joint.clamps, clamp)
        assertTrue(
            "with what the hook needed: " + clamp!!.askedMm + " against " + clamp.actualMm,
            clamp.actualMm < clamp.askedMm - 1e-3f,
        )
        assertTrue(
            "in the user's terms: " + clamp.reason,
            clamp.reason.contains("the pocket floor was left with"),
        )

        // The floor is dropped by the clamped room and no more, measured in
        // the socket's own frame; the drop below the beam is short of what the
        // un-clamped room would have been.
        val socket = localVertices(joint.subtractSolid, joint.socketFrame).filter {
            abs(it.x) <= dimensions.beamWidthMm * 0.5f + dimensions.lipClearanceMm + 1e-4f
        }
        val floor = socket.minOf { it.y }
        assertEquals(
            "the floor is dropped by exactly the clamped room",
            -(half + clamp.actualMm),
            floor,
            1e-3f,
        )
        val beam = localVertices(joint.unionSolid, joint.frame).filter {
            abs(it.x) <= dimensions.beamWidthMm * 0.5f + 1e-4f
        }
        val drop = beam.minOf { it.y } - floor
        assertTrue(
            "and the clamp really shortens it: " + drop + " mm below the beam against " + dimensions.lipDepthMm + " asked",
            drop < dimensions.lipDepthMm - 1e-3f,
        )
    }

    @Test
    fun aSocketRampWithNoRoomBehindThePawlIsClampedAndTheMouthFollows() {
        // A 1.5 mm hook puts the pawl half a millimetre from the mating face,
        // so the land in front of it is shorter than the chamfer the lip wants.
        val (low, high) = halves()
        val joint = joint(
            low,
            high,
            face = 20f,
            anchor = Vec3(40f, 20f, 20f),
            parameters = SnapFitParameters(beamLengthOverrideMm = 1.5f, socketRamp = true),
        )

        val dimensions = joint.dimensions
        val clamp = joint.clamps.firstOrNull { it.label == "socket ramp" }
        assertNotNull("the panel is told: " + joint.clamps, clamp)
        assertEquals("with the depth the lip asked for", dimensions.lipDepthMm, clamp!!.askedMm, 1e-3f)
        assertTrue("and the depth the land allows", clamp.actualMm < clamp.askedMm - 1e-3f)
        assertTrue(
            "in the user's terms: " + clamp.reason,
            clamp.reason.contains("the socket's lead-in is"),
        )

        // The mouth really is opened by only the clamped depth, at the mating
        // face, on the side the hook stands - and not by the depth the lip
        // would have needed.
        val half = dimensions.beamThicknessMm * 0.5f
        val roof = half + dimensions.lipClearanceMm
        val socket = localVertices(joint.subtractSolid, joint.socketFrame)
        assertTrue(
            "the mouth's own corner is the clamped chamfer wide: " + socket,
            socket.any {
                it.y > 0f &&
                    abs(it.z + dimensions.matingClearanceMm) < 1e-3f &&
                    abs(it.y - (roof + clamp.actualMm)) < 1e-3f
            },
        )
        // The channel's own roof is the full lip's height, which is where a
        // chamfer that was not clamped would have ended. The funnel stops at
        // the clamped depth instead, so the two are different corners and the
        // clamp really did cut the wedge short.
        val mouth = socket.filter {
            it.y > 0f && abs(it.z + dimensions.matingClearanceMm) < 1e-3f
        }
        val funnelCorner = mouth.minOf { it.y }
        val channelRoof = mouth.maxOf { it.y }
        assertEquals("the funnel's inner corner is the clamped chamfer", roof + clamp.actualMm, funnelCorner, 1e-3f)
        assertTrue(
            "and stops short of the channel roof the full lip would have reached: " +
                funnelCorner + " against " + channelRoof,
            funnelCorner < channelRoof - 1e-3f,
        )
    }

    private fun halves(): Pair<StlMesh, StlMesh> {
        val box = MeshFixtures.box(0f, 0f, 0f, 80f, 40f, 40f)
        return BedClipper.clipClosed(box, ModelPlacement.Axis.Z, 20f, Half.LOW) to
            BedClipper.clipClosed(box, ModelPlacement.Axis.Z, 20f, Half.HIGH)
    }

    private fun shallowHalves(): Pair<StlMesh, StlMesh> {
        val box = MeshFixtures.box(0f, 0f, 0f, 40f, 20f, 4f)
        return BedClipper.clipClosed(box, ModelPlacement.Axis.Z, 1f, Half.LOW) to
            BedClipper.clipClosed(box, ModelPlacement.Axis.Z, 1f, Half.HIGH)
    }

    private fun joint(
        low: StlMesh,
        high: StlMesh,
        face: Float,
        anchor: Vec3,
        parameters: SnapFitParameters = SnapFitParameters(),
    ): SnapFitJoint {
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

    private fun localVertices(mesh: StlMesh, frame: SnapFitFrame): List<Vec3> {
        val vertices = mesh.interleavedVertices
        return (0 until mesh.triangleCount * 3).map { vertex ->
            val base = vertex * MeshSolidBuilder.FLOATS_PER_VERTEX
            frame.local(Vec3(vertices[base], vertices[base + 1], vertices[base + 2]))
        }
    }
}
