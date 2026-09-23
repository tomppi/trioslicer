package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerEngine
import com.tomppi.enderslicer.model.SlicerSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetStabilityTest {
    @Test
    fun completePresetRegistersAllKeysBeforeSwitchingValues() {
        val before = SlicerSettings(
            layerHeightMm = 0.28,
            wallLineCount = 2,
            infillDensityPercent = 8.0,
            overriddenSettingKeys = setOf(SlicerSettings.Keys.MACHINE_WIDTH),
        )
        val saved = SlicerSettings(
            layerHeightMm = 0.12,
            wallLineCount = 4,
            infillDensityPercent = 35.0,
        )
        val plan = PresetApplication.prepareCura(
            PresetKind.PRINT,
            before,
            PresetSettings.capture(PresetKind.PRINT, saved).toString(),
        )

        var actual = before
        plan.appliedKeys.forEach { key ->
            actual = actual.copy(overriddenSettingKeys = actual.overriddenSettingKeys + key)
        }

        assertEquals(
            before,
            actual.copy(overriddenSettingKeys = before.overriddenSettingKeys),
        )
        assertTrue(PresetSettings.keys(PresetKind.PRINT).all { it in actual.overriddenSettingKeys })

        val markerKey = plan.appliedKeys.first()
        actual = plan.settings.copy(overriddenSettingKeys = actual.overriddenSettingKeys + markerKey)

        assertEquals(plan.settings, actual)
        assertEquals(0.12, actual.layerHeightMm, 0.0001)
        assertEquals(4, actual.wallLineCount)
        assertEquals(35.0, actual.infillDensityPercent, 0.0001)
        assertTrue(SlicerSettings.Keys.MACHINE_WIDTH in actual.overriddenSettingKeys)
    }

    @Test
    fun partialLegacyPresetAppliesOnlyCarriedKeys() {
        val before = SlicerSettings(nozzleTemperatureC = 200, bedTemperatureC = 70)
        val plan = PresetApplication.prepareCura(
            PresetKind.FILAMENT,
            before,
            JSONObject().put(SlicerSettings.Keys.NOZZLE_TEMPERATURE, 225).toString(),
        )

        assertEquals(setOf(SlicerSettings.Keys.NOZZLE_TEMPERATURE), plan.appliedKeys)
        assertEquals(225, plan.settings.nozzleTemperatureC)
        assertEquals(70, plan.settings.bedTemperatureC)
        assertTrue(SlicerSettings.Keys.NOZZLE_TEMPERATURE in plan.settings.overriddenSettingKeys)
        assertFalse(SlicerSettings.Keys.BED_TEMPERATURE in plan.settings.overriddenSettingKeys)
    }

    @Test
    fun fractionalIntegerPresetValueIsRejectedInsteadOfTruncated() {
        val error = runCatching {
            PresetApplication.prepareCura(
                PresetKind.PRINT,
                SlicerSettings(),
                JSONObject().put(SlicerSettings.Keys.WALL_LINE_COUNT, 2.5).toString(),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("whole number"))
    }

    @Test
    fun unsafeTemperatureIsRejectedBeforeLiveSettingsChange() {
        val error = runCatching {
            PresetApplication.prepareCura(
                PresetKind.FILAMENT,
                SlicerSettings(),
                JSONObject().put(SlicerSettings.Keys.NOZZLE_TEMPERATURE, 900).toString(),
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("outside"))
    }

    @Test
    fun invalidCrossFieldPresetIsRejected() {
        val values = JSONObject()
            .put(SlicerSettings.Keys.COASTING_ENABLED, true)
            .put(SlicerSettings.Keys.COASTING_VOLUME, 2.0)
            .put(SlicerSettings.Keys.COASTING_MINIMUM_VOLUME, 1.0)
        val error = runCatching {
            PresetApplication.prepareCura(PresetKind.FILAMENT, SlicerSettings(), values.toString())
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("Minimum volume"))
    }

    @Test
    fun duplicateIdsAndNamesKeepTheNewestUsableRecord() {
        val oldById = preset("same-id", PresetKind.PRINT, "Old", updatedAt = 1)
        val newById = preset("same-id", PresetKind.PRINT, "New", updatedAt = 5)
        val oldByName = preset("old-name", PresetKind.FILAMENT, "PLA", updatedAt = 2)
        val newByName = preset("new-name", PresetKind.FILAMENT, "pla", updatedAt = 6)

        val normalized = UserPresetLibraryNormalizer.normalizePresets(
            listOf(oldById, newById, oldByName, newByName),
            maxPerKind = 100,
        )

        assertEquals(setOf("same-id", "new-name"), normalized.mapTo(hashSetOf(), UserPreset::id))
        assertEquals("New", normalized.first { it.id == "same-id" }.name)
    }

    @Test
    fun missingActiveIdsAreClearedAfterNormalization() {
        val existing = preset("print-1", PresetKind.PRINT, "Fine", updatedAt = 1)
        val normalized = UserPresetLibraryNormalizer.normalize(
            PresetLibrary(
                presets = listOf(existing),
                activePrintPresetIds = mapOf(SlicerEngine.CURA to "missing"),
                activeFilamentPresetIds = mapOf(SlicerEngine.CURA to "also-missing"),
            ),
            maxPerKind = 100,
        )

        assertNull(normalized.activeId(SlicerEngine.CURA, PresetKind.PRINT))
        assertNull(normalized.activeId(SlicerEngine.CURA, PresetKind.FILAMENT))
    }

    private fun preset(
        id: String,
        kind: PresetKind,
        name: String,
        updatedAt: Long,
    ): UserPreset {
        val values = when (kind) {
            PresetKind.PRINT -> JSONObject().put(SlicerSettings.Keys.LAYER_HEIGHT, 0.2)
            PresetKind.FILAMENT -> JSONObject().put(SlicerSettings.Keys.NOZZLE_TEMPERATURE, 210)
        }
        return UserPreset(
            id = id,
            engine = SlicerEngine.CURA,
            kind = kind,
            name = name,
            valuesJson = values.toString(),
            createdAtEpochMillis = 0,
            updatedAtEpochMillis = updatedAt,
        )
    }
}
