package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerEngine
import com.tomppi.enderslicer.model.SlicerSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveOverhangPresetTest {
    @Test
    fun printPresetRoundTripsEveryWaveSetting() {
        val source = SlicerSettings(
            waveOverhangEnabled = true,
            waveOverhangPattern = "zigzag",
            waveOverhangLineSpacingMm = 0.42,
            waveOverhangFlowMm3PerMm = 0.19,
            waveOverhangSpeedMmPerSecond = 4.5,
            waveOverhangFanSpeedPercent = 98.0,
            waveOverhangPerimeterOverlapMm = 0.12,
            waveOverhangMinimumWidthMm = 0.84,
            waveOverhangMaxIterations = 525,
            waveOverhangReverseOddLayers = false,
        )
        val values = PresetSettings.capture(PresetKind.PRINT, source)
        val restored = PresetSettings.apply(PresetKind.PRINT, SlicerSettings(), values)

        assertTrue(restored.waveOverhangEnabled)
        assertEquals("zigzag", restored.waveOverhangPattern)
        assertEquals(0.42, restored.waveOverhangLineSpacingMm, 0.0001)
        assertEquals(0.19, restored.waveOverhangFlowMm3PerMm, 0.0001)
        assertEquals(4.5, restored.waveOverhangSpeedMmPerSecond, 0.0001)
        assertEquals(98.0, restored.waveOverhangFanSpeedPercent, 0.0001)
        assertEquals(0.12, restored.waveOverhangPerimeterOverlapMm, 0.0001)
        assertEquals(0.84, restored.waveOverhangMinimumWidthMm, 0.0001)
        assertEquals(525, restored.waveOverhangMaxIterations)
        assertFalse(restored.waveOverhangReverseOddLayers)
    }

    @Test
    fun sanitizerRejectsInvalidPatternAndArcWaveConflict() {
        val invalidPattern = JSONObject()
            .put(SlicerSettings.Keys.WAVE_OVERHANG_PATTERN, "random")
        val conflicting = JSONObject()
            .put(SlicerSettings.Keys.ARC_OVERHANG_ENABLED, true)
            .put(SlicerSettings.Keys.WAVE_OVERHANG_ENABLED, true)

        assertTrue(
            runCatching {
                PresetValueSanitizer.sanitize(SlicerEngine.CURA, PresetKind.PRINT, invalidPattern)
            }.isFailure,
        )
        assertTrue(
            runCatching {
                PresetValueSanitizer.sanitize(SlicerEngine.CURA, PresetKind.PRINT, conflicting)
            }.isFailure,
        )
    }
}
