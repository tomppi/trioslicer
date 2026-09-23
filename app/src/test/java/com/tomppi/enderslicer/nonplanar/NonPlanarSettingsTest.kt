package com.tomppi.enderslicer.nonplanar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NonPlanarSettingsTest {
    @Test
    fun validationClampsEverySafetyCriticalSetting() {
        val settings = NonPlanarSettings(
            enabled = true,
            maximumSlopeDegrees = 80.0,
            nozzleClearanceAngleDegrees = 20.0,
            nozzleClearanceHeightMm = 1.0,
            maximumZSpeedMmPerSecond = 100.0,
            conformalShellLayers = 99,
            pauseAfterProbe = true,
        ).validated()

        assertEquals(55.0, settings.maximumSlopeDegrees, 0.0)
        assertEquals(20.0, settings.nozzleClearanceAngleDegrees, 0.0)
        assertEquals(5.0, settings.nozzleClearanceHeightMm, 0.0)
        assertEquals(20.0, settings.maximumZSpeedMmPerSecond, 0.0)
        assertEquals(8, settings.conformalShellLayers)
        assertEquals(15.0, settings.effectiveSlopeLimitDegrees, 0.0)
        assertTrue(settings.enabled)
        assertTrue(settings.pauseAfterProbe)
    }

    @Test
    fun conformalShellCountStaysWithinItsPracticalRange() {
        assertEquals(1, NonPlanarSettings(conformalShellLayers = 0).validated().conformalShellLayers)
        assertEquals(8, NonPlanarSettings(conformalShellLayers = 50).validated().conformalShellLayers)
    }
}
