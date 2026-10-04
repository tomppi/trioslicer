package com.tomppi.enderslicer.ui

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Test

class UiScaleTest {
    @Test
    fun sanitizeClampsToTheSupportedRange() {
        assertEquals(UiScale.MIN_PERCENT, UiScale.sanitize(10))
        assertEquals(UiScale.MAX_PERCENT, UiScale.sanitize(10_000))
        assertEquals(125, UiScale.sanitize(125))
    }

    @Test
    fun scaledMultipliesDensityAndLeavesTheFontScaleAlone() {
        val base = Density(density = 2.0f, fontScale = 1.5f)
        val scaled = UiScale.scaled(base, 150)

        // Compose resolves text as sp * fontScale * density, so scaling the font
        // scale as well would apply the percentage twice to every label while
        // the controls only got it once.
        assertEquals(3.0f, scaled.density, 1e-4f)
        assertEquals(1.5f, scaled.fontScale, 1e-4f)
    }

    @Test
    fun defaultScaleLeavesTheDensityAlone() {
        val base = Density(density = 2.75f, fontScale = 1.0f)
        val scaled = UiScale.scaled(base, UiScale.DEFAULT_PERCENT)

        assertEquals(base.density, scaled.density, 1e-4f)
        assertEquals(base.fontScale, scaled.fontScale, 1e-4f)
    }
}
