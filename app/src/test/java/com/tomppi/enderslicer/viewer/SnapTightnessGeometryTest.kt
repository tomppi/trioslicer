package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.ModelPlacement
import com.tomppi.enderslicer.viewer.BedClipper.Half
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Loose/Tight step, measured in the sockets it cuts.
 *
 * The clearance between the hook and the pocket this app talks about is the
 * clearance around the hook; it never appears on the hook itself, and the panel
 * says so - 0.20 mm loose, 0.14 mm tight. What the step has to change is the
 * SOCKET the mate is cut with, and it does: the slot narrows by the step, the
 * pawl moves with it and the pocket's floor follows. This test is the proof the
 * step is in the geometry rather than only in the label.
 */
class SnapTightnessGeometryTest {
    @Test
    fun theTightStepCutsASocketSixHundredthsSmaller() {
        val loose = joint(SnapFitParameters())
        val tight = joint(SnapFitParameters().tightenedBy(SnapTightness.TIGHT.stepMm))

        // The hook is deliberately the same part: the fit is the room around it.
        assertEquals("the hook is the same piece in both steps", hash(loose.unionSolid), hash(tight.unionSolid))
        assertNotEquals("the socket is not", hash(loose.subtractSolid), hash(tight.subtractSolid))

        // Measured inside the socket itself: how far its wall stands off the
        // beam, and off the key.
        assertEquals(
            "the loose socket is the beam plus 0.20 mm a side",
            0.20f,
            slotClearance(loose),
            1e-3f,
        )
        assertEquals(
            "the tight socket is the beam plus 0.14 mm a side",
            0.14f,
            slotClearance(tight),
            1e-3f,
        )
        assertEquals(
            "and the step really is the difference between them",
            SnapTightness.TIGHT.stepMm,
            slotClearance(loose) - slotClearance(tight),
            1e-3f,
        )
        assertEquals(
            "the key's socket is stepped with it",
            SnapTightness.TIGHT.stepMm,
            keyClearance(loose) - keyClearance(tight),
            1e-3f,
        )
    }

    /** How far the pocket's own wall stands off the beam, read off the socket. */
    private fun slotClearance(joint: SnapFitJoint): Float {
        val dimensions = joint.dimensions
        val roof = dimensions.beamThicknessMm * 0.5f + dimensions.lipClearanceMm
        val vertices = joint.subtractSolid.interleavedVertices
        var widest = 0f
        for (vertex in 0 until joint.subtractSolid.triangleCount * 3) {
            val base = vertex * MeshSolidBuilder.FLOATS_PER_VERTEX
            val local = joint.socketFrame.local(Vec3(vertices[base], vertices[base + 1], vertices[base + 2]))
            // The key's socket is a different feature on the far side of the
            // key gap; only the beam's own slot counts here.
            if (abs(local.y) > roof + 1e-3f) continue
            widest = maxOf(widest, abs(local.x))
        }
        return widest - dimensions.beamWidthMm * 0.5f
    }

    /** The same, for the key's own socket. */
    private fun keyClearance(joint: SnapFitJoint): Float {
        val dimensions = joint.dimensions
        val vertices = joint.subtractSolid.interleavedVertices
        var widest = 0f
        for (vertex in 0 until joint.subtractSolid.triangleCount * 3) {
            val base = vertex * MeshSolidBuilder.FLOATS_PER_VERTEX
            val local = joint.socketFrame.local(Vec3(vertices[base], vertices[base + 1], vertices[base + 2]))
            if (local.x < dimensions.keyOffsetMm - dimensions.keySizeMm * 0.5f) continue
            widest = maxOf(widest, abs(local.x - dimensions.keyOffsetMm))
        }
        return widest - dimensions.keySizeMm * 0.5f
    }

    private fun hash(mesh: StlMesh): Long {
        val vertices = mesh.interleavedVertices
        var hash = 17L
        for (index in 0 until vertices.size) hash = hash * 31 + (vertices[index] * 1000f).toInt()
        return hash
    }

    private fun joint(parameters: SnapFitParameters): SnapFitJoint {
        val box = MeshFixtures.box(0f, 0f, 0f, 80f, 40f, 40f)
        val low = BedClipper.clipClosed(box, ModelPlacement.Axis.Z, 20f, Half.LOW)
        val high = BedClipper.clipClosed(box, ModelPlacement.Axis.Z, 20f, Half.HIGH)
        val result = SnapFit.generate(
            Vec3(0f, 0f, 1f),
            Vec3(40f, 20f, 20f),
            1f,
            SnapFitHalf.inPlace(low, 20f),
            SnapFitHalf.inPlace(high, 20f),
            parameters,
        )
        assertTrue("this joint has to exist", result != null)
        return result!!
    }
}
