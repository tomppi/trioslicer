package com.tomppi.enderslicer.smartinfill

import com.tomppi.enderslicer.model.SlicerSettings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartInfillSettingsTest {
    @Test
    fun filaSimPrintAssumptionsBecomeAuthoritativeSliceSettings() {
        val packageValue = SmartInfillPackage(
            id = "filasim-test",
            directory = File("."),
            sourceName = "beam.stl",
            sourceSha256 = "0".repeat(64),
            baseDensityPercent = 12.5,
            pattern = "gyroid",
            mode = "graded",
            perimeters = 4,
            lineWidthMm = 0.45,
            topBottomLayers = 6,
            layerHeightMm = 0.24,
            upstreamCommit = SmartInfillActivity.FILASIM_COMMIT,
            modifiers = emptyList(),
        )

        val effective = packageValue.applyTo(
            SlicerSettings(
                adaptiveLayerHeightEnabled = true,
                layerHeightMm = 0.16,
                lineWidthMm = 0.4,
                wallLineCount = 2,
                topLayers = 5,
                bottomLayers = 5,
                infillDensityPercent = 20.0,
                infillPattern = "cubic",
            ),
        )

        assertFalse(effective.adaptiveLayerHeightEnabled)
        assertEquals(0.24, effective.layerHeightMm, 0.0)
        assertEquals(0.45, effective.lineWidthMm, 0.0)
        assertEquals(4, effective.wallLineCount)
        assertEquals(1.8, effective.wallThicknessMm, 1e-9)
        assertEquals(6, effective.topLayers)
        assertEquals(6, effective.bottomLayers)
        assertEquals(1.44, effective.topBottomThicknessMm, 1e-9)
        assertEquals(12.5, effective.infillDensityPercent, 0.0)
        assertEquals("gyroid", effective.infillPattern)
        assertTrue(SlicerSettings.Keys.INFILL_DENSITY in effective.overriddenSettingKeys)
        assertTrue(SlicerSettings.Keys.WALL_LINE_COUNT in effective.overriddenSettingKeys)
    }

    @Test
    fun unsupportedPatternIsRejectedInsteadOfSilentlyChangingTheAnalysis() {
        val packageValue = SmartInfillPackage(
            id = "filasim-test",
            directory = File("."),
            sourceName = "beam.stl",
            sourceSha256 = "0".repeat(64),
            baseDensityPercent = 10.0,
            pattern = "unknown-pattern",
            mode = "graded",
            perimeters = 2,
            lineWidthMm = 0.4,
            topBottomLayers = 4,
            layerHeightMm = 0.2,
            upstreamCommit = SmartInfillActivity.FILASIM_COMMIT,
            modifiers = emptyList(),
        )

        val error = runCatching { packageValue.applyTo(SlicerSettings()) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("unsupported Cura infill pattern"))
    }
}
