package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.engine.EnginePreparation
import com.tomppi.enderslicer.engine.EnginePreparation.Engine
import com.tomppi.enderslicer.engine.EnginePreparation.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ticked engines survive a rotation as a string, because a Set is not a Bundle value.
 *
 * A selection that came back empty after a rotation would silently un-tick the engines the
 * user chose and prepare nothing, which is the kind of quiet loss that looks like the
 * button not working.
 */
class EngineSelectionTest {

    @Test
    fun aSelectionSurvivesTheStringItIsSavedAs() {
        val selected = setOf(
            EnginePreparation.Engine.CAD,
            EnginePreparation.Engine.KLIPPER,
            EnginePreparation.Engine.ORCA,
        )
        assertEquals(selected, decodeEngineSelection(encodeEngineSelection(selected)))
    }

    @Test
    fun anEmptySelectionRoundTripsToEmpty() {
        assertTrue(decodeEngineSelection(encodeEngineSelection(emptySet())).isEmpty())
    }

    /**
     * The action-row rule. The bug it guards: six engines finished, and the screen still
     * offered only "Prepare selected engines" and "Skip for now" - a definite state with no
     * way to accept it.
     */
    private fun rows(vararg ready: Engine): List<EnginePreparation.Row> =
        Engine.entries.map { engine ->
            EnginePreparation.Row(
                engine = engine,
                phase = if (engine in ready) Phase.READY else Phase.PENDING,
            )
        }

    @Test
    fun theScreenIsFinishedOnlyWhenEveryEngineItWouldPrepareIsReady() {
        assertTrue(engineSetupFinished(rows(*Engine.entries.toTypedArray()), setOf(Engine.CURA)))
        // The ticked engine is the one that has to be ready; another engine still waiting
        // does not hold a finished selection back.
        assertTrue(engineSetupFinished(rows(Engine.CURA), setOf(Engine.CURA)))
        // And a ticked engine that is not ready leaves work to do.
        assertFalse(engineSetupFinished(rows(Engine.CURA), setOf(Engine.PRUSA)))
    }

    @Test
    fun aTickedCadWithAnUnpreparedKlipperIsNotFinished() {
        // CAD borrows the Klipper payload, so "CAD is ready" alone is not the whole run.
        val partly = rows(Engine.CAD)
        assertFalse(engineSetupFinished(partly, setOf(Engine.CAD)))
        assertTrue(engineSetupFinished(rows(Engine.CAD, Engine.KLIPPER), setOf(Engine.CAD)))
    }

    @Test
    fun nothingSelectedIsNeverFinished() {
        // There would be nothing to accept, so the offer to accept it would be a lie.
        assertFalse(engineSetupFinished(rows(*Engine.entries.toTypedArray()), emptySet()))
    }

    @Test
    fun anIdTheAppNoLongerOffersIsDroppedRatherThanInvented() {
        assertEquals(
            setOf(EnginePreparation.Engine.CURA),
            decodeEngineSelection("cura,nonesuch"),
        )
    }
}
