package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.model.ModelPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The boolean-first split, with the native engine stood in for.
 *
 * The rule the split was moved onto: a model the engine will take is cut by
 * subtracting a half-space box, so each half is a closed solid by construction,
 * and anything else - a model the engine refuses, a subtract that fails, or a
 * build without the library - falls back to exactly today's clipper cut, named
 * in [SolidSplitter.Split.path] so a caller can report it.
 *
 * The engine is injected because libmanifold_jni.so is only packaged for
 * arm64-v8a; the device test is where real Manifold produces real halves.
 */
class SolidSplitterTest {
    /** A stand-in for Manifold: it answers what the test tells it to. */
    private class FakeEngine(
        override var available: Boolean = true,
        var manifold: Boolean = true,
        var closed: Boolean = true,
        var result: (StlMesh, StlMesh) -> MeshBoolean.Result = { _, _ ->
            MeshBoolean.Result.Success(MeshFixtures.box(0f, 0f, 0f, 1f, 1f, 1f), SnapFitGate.NO_ERROR, closed, 1.0, 0.0)
        },
    ) : SolidSplitter.Engine {
        val models = ArrayList<StlMesh>()
        val boxes = ArrayList<StlMesh>()
        var subtracts = 0
        var manifoldChecks = 0

        override fun isManifold(mesh: StlMesh): Boolean {
            manifoldChecks++
            return manifold
        }

        override fun subtract(first: StlMesh, second: StlMesh): MeshBoolean.Result {
            subtracts++
            models.add(first)
            boxes.add(second)
            return result(first, second)
        }
    }

    private val cut = 10f

    private fun cube() = MeshFixtures.box(0f, 0f, 0f, 20f, 20f, 20f)

    private fun splitWith(engine: FakeEngine, mesh: StlMesh = cube()) =
        SolidSplitter.split(mesh, ModelPlacement.Axis.Z, cut, engine)

    @Test
    fun aClosedModelIsSplitBySubtractingTheHalfSpaceOnTheOtherSide() {
        val engine = FakeEngine()
        val cube = cube()

        val split = splitWith(engine, cube)

        assertEquals("the boolean is the path when the engine takes the model", SolidSplitter.Path.BOOLEAN, split.path)
        assertEquals("one subtract per half", 2, engine.subtracts)
        assertEquals("and the model was checked first", 1, engine.manifoldChecks)
        assertSame("the original is the first operand of both", cube, engine.models[0])
        assertSame("and stays the model", cube, engine.models[1])
        assertNotSame("the two boxes are each their own half-space", engine.boxes[0], engine.boxes[1])
        assertNotSame("and the halves are the engine's own results", split.low, split.high)
    }

    @Test
    fun theBoxIsTheHalfSpaceOnTheRemovedSideOfThePlane() {
        val engine = FakeEngine()
        val cube = cube()

        val split = splitWith(engine, cube)
        assertEquals(SolidSplitter.Path.BOOLEAN, split.path)

        // The low half keeps Z <= 10, so the material removed from it is
        // everything at or above the plane: a box standing on Z = 10.
        val above = engine.boxes[0]
        assertEquals("the cut face is the plane", 10f, above.bounds.minZ, 1e-6f)
        assertTrue("and it reaches past the top of the model", above.bounds.maxZ > 20f)
        assertTrue("it wraps the model on X", above.bounds.minX < 0f && above.bounds.maxX > 20f)
        assertTrue("and on Y", above.bounds.minY < 0f && above.bounds.maxY > 20f)
        assertEquals(
            "a closed box, not an inside-out one",
            (above.bounds.maxX - above.bounds.minX).toDouble() *
                (above.bounds.maxY - above.bounds.minY) *
                (above.bounds.maxZ - above.bounds.minZ),
            MeshFixtures.signedVolume(above),
            1e-3,
        )

        // The high half keeps Z >= 10: the box stands under the plane.
        val below = engine.boxes[1]
        assertEquals("the other box caps at the plane", 10f, below.bounds.maxZ, 1e-6f)
        assertTrue("and reaches under the model", below.bounds.minZ < 0f)
        assertEquals(
            "closed and outward too",
            (below.bounds.maxX - below.bounds.minX).toDouble() *
                (below.bounds.maxY - below.bounds.minY) *
                (below.bounds.maxZ - below.bounds.minZ),
            MeshFixtures.signedVolume(below),
            1e-3,
        )
    }

    @Test
    fun theBoxFollowsTheAxisAndTheHalf() {
        for (axis in ModelPlacement.Axis.values()) {
            val box = SolidSplitter.halfSpaceBox(cube().bounds, axis, 6f, removeAbove = false)
            val along = box.bounds.spanAlong(axis)
            assertEquals(axis.name + ": the cut face is on the plane", 6f, along.endInclusive, 1e-6f)
            assertTrue(axis.name + ": the box reaches past the far side", along.start < 0f)
        }
    }

    @Test
    fun aModelTheEngineWillNotTakeIsCutByTheClipper() {
        val engine = FakeEngine(manifold = false)
        val cube = cube()

        val split = splitWith(engine, cube)

        assertEquals("the fallback is named", SolidSplitter.Path.CLIPPER, split.path)
        assertEquals("and nothing was booleaned", 0, engine.subtracts)
        assertEquals(
            "the halves are today's capped cut",
            BedClipper.clipClosed(cube, ModelPlacement.Axis.Z, cut, BedClipper.Half.LOW).triangleCount,
            split.low.triangleCount,
        )
        assertEquals(
            BedClipper.clipClosed(cube, ModelPlacement.Axis.Z, cut, BedClipper.Half.HIGH).triangleCount,
            split.high.triangleCount,
        )
        assertEquals(
            "and they add up to the cube",
            8000.0,
            MeshFixtures.signedVolume(split.low) + MeshFixtures.signedVolume(split.high),
            1e-2,
        )
    }

    @Test
    fun aBuildWithoutTheEngineIsCutByTheClipper() {
        val engine = FakeEngine(available = false)

        val split = splitWith(engine)

        assertEquals(SolidSplitter.Path.CLIPPER, split.path)
        assertEquals("the engine is never asked", 0, engine.subtracts)
        assertEquals(0, engine.manifoldChecks)
    }

    @Test
    fun aRefusedOrOpenSubtractFallsBackToTheClipperForBothHalves() {
        val refused = FakeEngine(
            result = { _, _ -> MeshBoolean.Result.Failure("subtract refused the first mesh: NotManifold") },
        )
        val open = FakeEngine(closed = false)

        for (engine in listOf(refused, open)) {
            val split = splitWith(engine)
            assertEquals(
                "a boolean that did not produce two closed halves is not used",
                SolidSplitter.Path.CLIPPER,
                split.path,
            )
        }
    }

    @Test
    fun aPlaneOutsideTheModelKeepsTheClippersDegenerateAnswers() {
        val engine = FakeEngine()
        val cube = cube()

        val above = SolidSplitter.split(cube, ModelPlacement.Axis.Z, 30f, engine)

        assertSame("nothing to cut off below the plane", cube, above.low)
        assertEquals("and nothing at or above it", 0, above.high.triangleCount)
        assertEquals(SolidSplitter.Path.CLIPPER, above.path)
        assertEquals("the engine is not asked about a plane it cannot cut", 0, engine.subtracts)
    }
}
