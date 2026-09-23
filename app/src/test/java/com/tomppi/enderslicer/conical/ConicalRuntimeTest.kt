package com.tomppi.enderslicer.conical

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConicalRuntimeTest {
    @After
    fun resetRuntime() {
        ConicalRuntime.activate(ConicalSettings())
    }

    @Test
    fun generationAdvancesOnlyWhenActivatedSettingsChange() {
        ConicalRuntime.activate(ConicalSettings(enabled = true))
        val first = requireNotNull(ConicalRuntime.snapshot())

        ConicalRuntime.activate(ConicalSettings(enabled = true))
        assertEquals(first.generation, requireNotNull(ConicalRuntime.snapshot()).generation)

        ConicalRuntime.activate(ConicalSettings(enabled = true, coneAngleDegrees = 20.0))
        val second = requireNotNull(ConicalRuntime.snapshot())
        assertEquals(first.generation + 1L, second.generation)
    }

    @Test
    fun disabledSettingsAreNotExposedAsASnapshot() {
        ConicalRuntime.activate(ConicalSettings(enabled = false))
        assertNull(ConicalRuntime.snapshot())
    }

    @Test
    fun reEnablingAfterDisableAdvancesTheGeneration() {
        ConicalRuntime.activate(ConicalSettings(enabled = true))
        val firstEnabled = requireNotNull(ConicalRuntime.snapshot()).generation

        ConicalRuntime.activate(ConicalSettings(enabled = false))
        assertNull(ConicalRuntime.snapshot())
        ConicalRuntime.activate(ConicalSettings(enabled = true))
        assertTrue(requireNotNull(ConicalRuntime.snapshot()).generation > firstEnabled)
    }

    @Test
    fun machineEndSentinelIsOnlyPreparedWhenEnabled() {
        ConicalRuntime.activate(ConicalSettings(enabled = false))
        assertEquals("M0", ConicalRuntime.markMachineEndGcode("M0"))

        ConicalRuntime.activate(ConicalSettings(enabled = true))
        val marked = ConicalRuntime.markMachineEndGcode("M0")
        assertTrue(marked.startsWith("${ConicalRuntime.MACHINE_END_SENTINEL}\n"))
        assertTrue(marked.endsWith("M0"))

        // Idempotent: never insert a second sentinel.
        assertEquals(marked, ConicalRuntime.markMachineEndGcode(marked))
    }
}
