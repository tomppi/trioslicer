package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetSettingsTest {
    @Test
    fun filamentPresetChangesOnlyMaterialBehavior() {
        val source = SlicerSettings(
            printerName = "Source printer",
            layerHeightMm = 0.12,
            infillDensityPercent = 35.0,
            nozzleTemperatureC = 245,
            bedTemperatureC = 85,
            materialFlowPercent = 97.5,
            fanSpeedPercent = 40.0,
            retractionDistanceMm = 0.8,
        )
        val current = SlicerSettings(
            printerName = "Current printer",
            layerHeightMm = 0.28,
            infillDensityPercent = 8.0,
            nozzleTemperatureC = 200,
            bedTemperatureC = 55,
            materialFlowPercent = 100.0,
            fanSpeedPercent = 100.0,
            retractionDistanceMm = 1.5,
        )

        val values = PresetSettings.capture(PresetKind.FILAMENT, source)
        val applied = PresetSettings.apply(PresetKind.FILAMENT, current, values)

        assertEquals("Current printer", applied.printerName)
        assertEquals(0.28, applied.layerHeightMm, 0.0001)
        assertEquals(8.0, applied.infillDensityPercent, 0.0001)
        assertEquals(245, applied.nozzleTemperatureC)
        assertEquals(85, applied.bedTemperatureC)
        assertEquals(97.5, applied.materialFlowPercent, 0.0001)
        assertEquals(40.0, applied.fanSpeedPercent, 0.0001)
        assertEquals(0.8, applied.retractionDistanceMm, 0.0001)
        assertTrue(PresetSettings.matches(PresetKind.FILAMENT, applied, values))
    }

    @Test
    fun printPresetPreservesPrinterAndFilament() {
        val source = SlicerSettings(
            machineWidthMm = 310.0,
            layerHeightMm = 0.12,
            wallLineCount = 4,
            infillDensityPercent = 28.0,
            supportsEnabled = false,
            nozzleTemperatureC = 255,
            materialFlowPercent = 91.0,
        )
        val current = SlicerSettings(
            machineWidthMm = 230.0,
            layerHeightMm = 0.28,
            wallLineCount = 2,
            infillDensityPercent = 10.0,
            supportsEnabled = true,
            nozzleTemperatureC = 205,
            materialFlowPercent = 103.0,
        )

        val applied = PresetSettings.apply(
            PresetKind.PRINT,
            current,
            PresetSettings.capture(PresetKind.PRINT, source),
        )

        assertEquals(230.0, applied.machineWidthMm, 0.0001)
        assertEquals(205, applied.nozzleTemperatureC)
        assertEquals(103.0, applied.materialFlowPercent, 0.0001)
        assertEquals(0.12, applied.layerHeightMm, 0.0001)
        assertEquals(4, applied.wallLineCount)
        assertEquals(28.0, applied.infillDensityPercent, 0.0001)
        assertFalse(applied.supportsEnabled)
    }

    @Test
    fun capturesCompleteStableCategorySnapshots() {
        val settings = SlicerSettings(
            layerHeightMm = 0.16,
            arcOverhangEnabled = true,
            nozzleTemperatureC = 222,
            coastingEnabled = true,
        )

        PresetKind.entries.forEach { kind ->
            val values = PresetSettings.capture(kind, settings)
            PresetSettings.validateComplete(kind, values)
            assertTrue(PresetSettings.matches(kind, settings, JSONObject(values.toString())))
        }
    }

    @Test
    fun legacyPartialPresetRemainsUsableAndPreservesNewerValues() {
        val legacy = JSONObject().put(SlicerSettings.Keys.NOZZLE_TEMPERATURE, 225)
        PresetSettings.validateUsable(PresetKind.FILAMENT, legacy)

        val current = SlicerSettings(nozzleTemperatureC = 200, bedTemperatureC = 70)
        val applied = PresetSettings.apply(PresetKind.FILAMENT, current, legacy)

        assertEquals(225, applied.nozzleTemperatureC)
        assertEquals(70, applied.bedTemperatureC)
        assertFalse(PresetSettings.matches(PresetKind.FILAMENT, applied, legacy))
    }

    @Test
    fun malformedPresetWithoutRecognizedValuesIsRejected() {
        val error = runCatching {
            PresetSettings.validateUsable(
                PresetKind.FILAMENT,
                JSONObject().put(SlicerSettings.Keys.NOZZLE_TEMPERATURE, "hot"),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    @Test
    fun applyingPresetMarksOnlyItsCategoryAsExplicitOverrides() {
        val current = SlicerSettings(overriddenSettingKeys = setOf(SlicerSettings.Keys.MACHINE_WIDTH))
        val values = PresetSettings.capture(PresetKind.FILAMENT, SlicerSettings())
        val applied = PresetSettings.apply(PresetKind.FILAMENT, current, values)

        assertTrue(SlicerSettings.Keys.MACHINE_WIDTH in applied.overriddenSettingKeys)
        assertTrue(PresetSettings.keys(PresetKind.FILAMENT).all { it in applied.overriddenSettingKeys })
        assertFalse(SlicerSettings.Keys.LAYER_HEIGHT in applied.overriddenSettingKeys)
    }
}
