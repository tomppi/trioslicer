package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.engine.EnginePreparation.Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first-init selection rules: what is ticked in advance, and what a tick really costs.
 *
 * The cost matters because the screen shows it before anything starts, and the estimate
 * has to be the tree the run will actually write - a CAD tick that quietly needs the
 * Klipper Python too would otherwise understate itself by 27 MB.
 */
class EnginePreparationTest {

    @Test
    fun theTickedEnginesAreTheActiveSlicerTheHostAndCad() {
        val chosen = EnginePreparation.defaultSelection("cura")

        assertEquals(
            setOf(Engine.CURA, Engine.KLIPPER, Engine.CAD, Engine.MESH2STEP),
            chosen,
        )
        // The two big ones a Cura user did not ask for stay unticked.
        assertTrue(Engine.ORCA !in chosen)
        assertTrue(Engine.BLENDER !in chosen)
    }

    @Test
    fun eachSlicerTicksItselfAndNoOther() {
        assertEquals(
            Engine.PRUSA,
            (EnginePreparation.defaultSelection("prusa") - Engine.KLIPPER - Engine.CAD - Engine.MESH2STEP).single(),
        )
        assertEquals(
            Engine.ORCA,
            (EnginePreparation.defaultSelection("orca") - Engine.KLIPPER - Engine.CAD - Engine.MESH2STEP).single(),
        )
    }

    @Test
    fun anUnknownOrAbsentSlicerStillLeavesTheHostAndCad() {
        assertEquals(
            setOf(Engine.KLIPPER, Engine.CAD, Engine.MESH2STEP),
            EnginePreparation.defaultSelection(null),
        )
        assertEquals(
            setOf(Engine.KLIPPER, Engine.CAD, Engine.MESH2STEP),
            EnginePreparation.defaultSelection("nonesuch"),
        )
    }

    @Test
    fun cadPullsInThePythonItBorrowsAndNothingElseDoes() {
        assertTrue(Engine.KLIPPER in EnginePreparation.withDependencies(setOf(Engine.CAD)))
        assertEquals(setOf(Engine.PRUSA), EnginePreparation.withDependencies(setOf(Engine.PRUSA)))
        assertTrue(EnginePreparation.withDependencies(emptySet()).isEmpty())

        // The converter borrows both: its scripts are worthless before the Python and CAD tree.
        assertEquals(
            setOf(Engine.MESH2STEP, Engine.CAD, Engine.KLIPPER),
            EnginePreparation.withDependencies(setOf(Engine.MESH2STEP)),
        )
        assertTrue(Engine.MESH2STEP !in EnginePreparation.withDependencies(setOf(Engine.CAD)))
    }

    @Test
    fun theSpaceNumberCountsThePythonCadBorrows() {
        assertEquals(
            Engine.CAD.needsBytes + Engine.KLIPPER.needsBytes,
            EnginePreparation.neededBytes(setOf(Engine.CAD)),
        )
        assertEquals(
            Engine.MESH2STEP.needsBytes + Engine.CAD.needsBytes + Engine.KLIPPER.needsBytes,
            EnginePreparation.neededBytes(setOf(Engine.MESH2STEP)),
        )
        // CuraEngine ships as a native library; there is no tree to write for it.
        assertEquals(0L, EnginePreparation.neededBytes(setOf(Engine.CURA)))
    }

    @Test
    fun theDependencyOrderPutsCadBeforeTheMeshToStepConverter() {
        // The enum order is the prepare order: Python before CAD, CAD before the converter.
        assertTrue(Engine.KLIPPER.ordinal < Engine.CAD.ordinal)
        assertTrue(Engine.CAD.ordinal < Engine.MESH2STEP.ordinal)
    }

    @Test
    fun theMeshToStepRowNamesItsDependenciesAndItsSmallPayload() {
        assertEquals("mesh2step", Engine.MESH2STEP.id)
        assertEquals("Mesh to STEP", Engine.MESH2STEP.label)
        assertTrue(Engine.MESH2STEP.detail.contains("Klipper Python"))
        assertTrue(Engine.MESH2STEP.detail.contains("CAD payload"))
        // 676 KB staged; the estimate rounds up to one MB like the other rows.
        assertEquals(1024L * 1024L, Engine.MESH2STEP.needsBytes)
    }

    @Test
    fun everyEngineWithAPayloadCostsSomething() {
        for (engine in Engine.entries.filter { it != Engine.CURA }) {
            assertTrue(engine.id, engine.needsBytes > 0L)
        }
    }
}
