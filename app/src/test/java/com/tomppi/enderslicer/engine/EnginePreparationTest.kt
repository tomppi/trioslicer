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
            setOf(Engine.CURA, Engine.KLIPPER, Engine.CAD),
            chosen,
        )
        // The two big ones a Cura user did not ask for stay unticked.
        assertTrue(Engine.ORCA !in chosen)
        assertTrue(Engine.BLENDER !in chosen)
    }

    @Test
    fun eachSlicerTicksItselfAndNoOther() {
        assertEquals(Engine.PRUSA, (EnginePreparation.defaultSelection("prusa") - Engine.KLIPPER - Engine.CAD).single())
        assertEquals(Engine.ORCA, (EnginePreparation.defaultSelection("orca") - Engine.KLIPPER - Engine.CAD).single())
    }

    @Test
    fun anUnknownOrAbsentSlicerStillLeavesTheHostAndCad() {
        assertEquals(
            setOf(Engine.KLIPPER, Engine.CAD),
            EnginePreparation.defaultSelection(null),
        )
        assertEquals(
            setOf(Engine.KLIPPER, Engine.CAD),
            EnginePreparation.defaultSelection("nonesuch"),
        )
    }

    @Test
    fun cadPullsInThePythonItBorrowsAndNothingElseDoes() {
        assertTrue(Engine.KLIPPER in EnginePreparation.withDependencies(setOf(Engine.CAD)))
        assertEquals(setOf(Engine.PRUSA), EnginePreparation.withDependencies(setOf(Engine.PRUSA)))
        assertTrue(EnginePreparation.withDependencies(emptySet()).isEmpty())
    }

    @Test
    fun theSpaceNumberCountsThePythonCadBorrows() {
        assertEquals(
            Engine.CAD.needsBytes + Engine.KLIPPER.needsBytes,
            EnginePreparation.neededBytes(setOf(Engine.CAD)),
        )
        // CuraEngine ships as a native library; there is no tree to write for it.
        assertEquals(0L, EnginePreparation.neededBytes(setOf(Engine.CURA)))
    }

    @Test
    fun everyEngineWithAPayloadCostsSomething() {
        for (engine in Engine.entries.filter { it != Engine.CURA }) {
            assertTrue(engine.id, engine.needsBytes > 0L)
        }
    }
}
