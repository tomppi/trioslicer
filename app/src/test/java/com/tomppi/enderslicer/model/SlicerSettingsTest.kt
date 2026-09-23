package com.tomppi.enderslicer.model

import org.junit.Assert.assertEquals
import org.junit.Test

class SlicerSettingsTest {
    @Test
    fun recomputesDefaultDerivedValues() {
        val settings = SlicerSettings().withRecomputedDerived()
        assertEquals(0.8, settings.wallThicknessMm, 1e-9)
        assertEquals(4, settings.topLayers)
        assertEquals(4, settings.bottomLayers)
        assertEquals(4, settings.initialBottomLayers)
    }

    @Test
    fun wallLineCountChangesWallThickness() {
        val settings = SlicerSettings(wallLineCount = 3).withRecomputedDerived()
        assertEquals(1.2, settings.wallThicknessMm, 1e-9)
    }

    @Test
    fun topBottomThicknessChangesLayerCounts() {
        val settings = SlicerSettings(topBottomThicknessMm = 1.0, layerHeightMm = 0.2).withRecomputedDerived()
        assertEquals(5, settings.topLayers)
        assertEquals(5, settings.bottomLayers)
        assertEquals(5, settings.initialBottomLayers)
    }

    @Test
    fun printSpeedDerivesDependentSpeeds() {
        val settings = SlicerSettings(printSpeedMmPerSecond = 200.0).withRecomputedDerived()
        assertEquals(100.0, settings.wallSpeedMmPerSecond, 1e-9)
        assertEquals(100.0, settings.outerWallSpeedMmPerSecond, 1e-9)
        assertEquals(100.0, settings.innerWallSpeedMmPerSecond, 1e-9)
        assertEquals(200.0, settings.infillSpeedMmPerSecond, 1e-9)
        assertEquals(100.0, settings.topBottomSpeedMmPerSecond, 1e-9)
        assertEquals(100.0, settings.supportSpeedMmPerSecond, 1e-9)
        assertEquals(100.0, settings.supportInterfaceSpeedMmPerSecond, 1e-9)
    }

    @Test
    fun manualOverrideWinsOverDerivation() {
        val settings = SlicerSettings(
            wallSpeedMmPerSecond = 80.0,
            overriddenSettingKeys = setOf(SlicerSettings.Keys.WALL_SPEED),
        ).withRecomputedDerived()
        assertEquals(80.0, settings.wallSpeedMmPerSecond, 1e-9)
        assertEquals(80.0, settings.outerWallSpeedMmPerSecond, 1e-9)
        assertEquals(80.0, settings.innerWallSpeedMmPerSecond, 1e-9)
    }

    @Test
    fun initialNozzleTemperatureFollowsNozzleTemperature() {
        val settings = SlicerSettings(nozzleTemperatureC = 215).withRecomputedDerived()
        assertEquals(215, settings.initialNozzleTemperatureC)
    }
}
