package com.tomppi.enderslicer.conical

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConicalSettingsTest {
    @Test
    fun validationClampsEverySafetyCriticalSetting() {
        val settings = ConicalSettings(
            enabled = true,
            coneAngleDegrees = 200.0,
            refinementIterations = 9,
            coneType = ConeType.INWARD,
            firstLayerHeightMm = 50.0,
            xShiftMm = 99999.0,
            yShiftMm = -99999.0,
            pauseAfterProbe = true,
        ).validated()

        assertEquals(60.0, settings.coneAngleDegrees, 0.0)
        assertEquals(3, settings.refinementIterations)
        assertEquals(ConeType.OUTWARD, settings.coneType)
        assertEquals(5.0, settings.firstLayerHeightMm, 0.0)
        assertEquals(2000.0, settings.xShiftMm, 0.0)
        assertEquals(-2000.0, settings.yShiftMm, 0.0)
        assertTrue(settings.enabled)
        assertTrue(settings.pauseAfterProbe)
    }

    @Test
    fun validDefaultsArePreservedAndExposeRadians() {
        val settings = ConicalSettings().validated()

        assertEquals(16.0, settings.coneAngleDegrees, 0.0)
        assertEquals(1, settings.refinementIterations)
        assertEquals(ConeType.OUTWARD, settings.coneType)
        assertEquals(0.2, settings.firstLayerHeightMm, 0.0)
        assertEquals(0.0, settings.xShiftMm, 0.0)
        assertEquals(0.0, settings.yShiftMm, 0.0)
        assertTrue(!settings.enabled)
        assertTrue(!settings.pauseAfterProbe)
        assertEquals(Math.toRadians(16.0), settings.coneAngleRadians, 1e-12)
    }

    @Test
    fun coneTypeSignIsOutwardPositiveInwardNegative() {
        assertEquals(1.0, ConeType.OUTWARD.sign, 0.0)
        assertEquals(-1.0, ConeType.INWARD.sign, 0.0)
    }
}
