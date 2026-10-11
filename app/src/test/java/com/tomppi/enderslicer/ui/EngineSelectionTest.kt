package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.engine.EnginePreparation
import org.junit.Assert.assertEquals
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

    @Test
    fun anIdTheAppNoLongerOffersIsDroppedRatherThanInvented() {
        assertEquals(
            setOf(EnginePreparation.Engine.CURA),
            decodeEngineSelection("cura,nonesuch"),
        )
    }
}
