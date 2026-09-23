package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveOverhangEngineSettingsTest {
    @Test
    fun defaultsAreOptInAndUseNozzleSquaredFlow() {
        val settings = SlicerSettings()
        val values = WaveOverhangEngineSettings.values(settings)

        assertEquals("false", values[WaveOverhangEngineSettings.ENABLED])
        assertEquals("smart", values[WaveOverhangEngineSettings.PATTERN])
        assertEquals("0.16", values[WaveOverhangEngineSettings.FLOW_MM3_PER_MM])
        assertEquals("true", values[WaveOverhangEngineSettings.REVERSE_ODD_LAYERS])
        assertEquals(10, values.size)
    }

    @Test
    fun completeConfigurationIsTransportedWithoutUnitConversion() {
        val values = WaveOverhangEngineSettings.values(
            SlicerSettings(
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
            ),
        )

        assertEquals("true", values[WaveOverhangEngineSettings.ENABLED])
        assertEquals("zigzag", values[WaveOverhangEngineSettings.PATTERN])
        assertEquals("0.42", values[WaveOverhangEngineSettings.LINE_SPACING])
        assertEquals("0.19", values[WaveOverhangEngineSettings.FLOW_MM3_PER_MM])
        assertEquals("4.5", values[WaveOverhangEngineSettings.SPEED])
        assertEquals("98.0", values[WaveOverhangEngineSettings.FAN_SPEED])
        assertEquals("0.12", values[WaveOverhangEngineSettings.PERIMETER_OVERLAP])
        assertEquals("0.84", values[WaveOverhangEngineSettings.MINIMUM_WIDTH])
        assertEquals("525", values[WaveOverhangEngineSettings.MAX_ITERATIONS])
        assertEquals("false", values[WaveOverhangEngineSettings.REVERSE_ODD_LAYERS])
    }

    @Test
    fun arcAndWaveCannotBeEnabledTogether() {
        val error = runCatching {
            WaveOverhangEngineSettings.values(
                SlicerSettings(arcOverhangEnabled = true, waveOverhangEnabled = true),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("enable only one"))
    }

    @Test
    fun invalidPatternFailsClosed() {
        val error = runCatching {
            WaveOverhangEngineSettings.values(SlicerSettings(waveOverhangPattern = "random"))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun outOfRangeAbsoluteFlowFailsClosed() {
        val error = runCatching {
            WaveOverhangEngineSettings.values(SlicerSettings(waveOverhangFlowMm3PerMm = 1.51))
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("flow"))
    }
}
