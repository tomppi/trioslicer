package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watertight pre-flight, with the native engine stood in for.
 *
 * The rule the feature was agreed on: a half that is not closed is repaired
 * once, and if it is still open it is refused by name - never booleaned in the
 * hope that the engine copes. The engine itself is injected because
 * libmanifold_jni.so is only packaged for arm64-v8a, so the decision has to be
 * testable on the JVM.
 */
class SnapFitGateTest {
    /** A stand-in for Manifold: it knows closed from open, and nothing else. */
    private class FakeEngine : SnapFitGate.Engine {
        var repairs = 0
        var unions = 0
        var subtracts = 0

        override fun manifoldStatus(mesh: StlMesh): String? =
            if (MeshFixtures.isClosed(mesh)) SnapFitGate.NO_ERROR else "NotManifold"

        override fun repair(mesh: StlMesh): MeshRepair.Result {
            repairs++
            return MeshRepair.repair(mesh)
        }

        override fun union(first: StlMesh, second: StlMesh): MeshBoolean.Result {
            unions++
            return MeshBoolean.Result.Failure("not used here")
        }

        override fun subtract(first: StlMesh, second: StlMesh): MeshBoolean.Result {
            subtracts++
            return MeshBoolean.Result.Failure("not used here")
        }
    }

    @Test
    fun aClosedHalfGoesStraightThrough() {
        val engine = FakeEngine()
        val closed = MeshFixtures.box(0f, 0f, 0f, 10f, 10f, 10f)

        val result = SnapFitGate.prepare(closed, engine, "part lower") as SnapFitGate.Result.Prepared

        assertEquals("nothing was repaired", 0, engine.repairs)
        assertNull("so there is nothing to report", result.ready.note)
        assertTrue("and the mesh is the one that came in", result.ready.mesh === closed)
    }

    @Test
    fun aHoledHalfIsRepairedAndTheRepairIsReported() {
        val engine = FakeEngine()
        val open = MeshFixtures.without(MeshFixtures.box(0f, 0f, 0f, 10f, 10f, 10f)) { it < 2 }

        val result = SnapFitGate.prepare(open, engine, "part upper") as SnapFitGate.Result.Prepared

        assertEquals(1, engine.repairs)
        assertTrue("the repaired mesh is closed", MeshFixtures.isClosed(result.ready.mesh))
        assertEquals("and the report says what was done", "closed 1 hole (4 edges)", result.ready.note)
    }

    @Test
    fun aHalfThatCannotBeClosedIsRefusedByName() {
        val engine = FakeEngine()
        // An open surface, not a solid with a hole: the repair refuses it whole.
        val sheet = MeshFixtures.fromSoup(
            "sheet",
            MeshFixtures.triangleOf(0f, 0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f),
        )

        val result = SnapFitGate.prepare(sheet, engine, "part lower") as SnapFitGate.Result.Refused

        assertTrue(
            "the model is named: " + result.reason,
            result.reason.startsWith("part lower is not watertight and could not be closed:"),
        )
        assertTrue(
            "and the reason is the repair's own: " + result.reason,
            result.reason.contains("open surface"),
        )
        assertEquals("one repair attempt, not a loop", 1, engine.repairs)
    }
}
