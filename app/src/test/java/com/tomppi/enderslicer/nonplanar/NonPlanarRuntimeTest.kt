package com.tomppi.enderslicer.nonplanar

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NonPlanarRuntimeTest {
    @After
    fun resetRuntime() {
        NonPlanarRuntime.activate(NonPlanarSettings())
    }

    @Test
    fun generationAdvancesOnlyWhenActivatedSettingsChange() {
        NonPlanarRuntime.activate(NonPlanarSettings(enabled = true))
        val first = requireNotNull(NonPlanarRuntime.snapshot())

        NonPlanarRuntime.activate(NonPlanarSettings(enabled = true))
        assertEquals(first.generation, requireNotNull(NonPlanarRuntime.snapshot()).generation)

        NonPlanarRuntime.activate(NonPlanarSettings(enabled = true, maximumSlopeDegrees = 35.0))
        val second = requireNotNull(NonPlanarRuntime.snapshot())
        assertEquals(first.generation + 1L, second.generation)
    }

    @Test
    fun disabledSettingsAreNotExposedAsASnapshot() {
        NonPlanarRuntime.activate(NonPlanarSettings(enabled = false))
        assertNull(NonPlanarRuntime.snapshot())
    }

    @Test
    fun reEnablingAfterDisableAdvancesTheGeneration() {
        NonPlanarRuntime.activate(NonPlanarSettings(enabled = true))
        val firstEnabled = requireNotNull(NonPlanarRuntime.snapshot()).generation

        NonPlanarRuntime.activate(NonPlanarSettings(enabled = false))
        assertNull(NonPlanarRuntime.snapshot())
        NonPlanarRuntime.activate(NonPlanarSettings(enabled = true))
        assertTrue(requireNotNull(NonPlanarRuntime.snapshot()).generation > firstEnabled)
    }
}
