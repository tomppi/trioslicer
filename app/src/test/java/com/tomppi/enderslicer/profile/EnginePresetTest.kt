package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.OrcaSliceSettings
import com.tomppi.enderslicer.model.PrusaSliceSettings
import com.tomppi.enderslicer.model.SlicerEngine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Engine-scoped presets: each engine captures and applies only its own vocabulary, its value
 * tables classify every key it can store, and the library keeps names, limits and active markers
 * per engine so two engines cannot collide.
 */
class EnginePresetTest {
    @Test
    fun prusaPrintPresetCarriesOnlyPrintSettings() {
        val base = PrusaSliceSettings(extraKeys = mapOf("first_layer_temperature" to "215"))
        val saved = PrusaSliceSettings(
            layerHeightMm = 0.14,
            perimeters = 5,
            fillPattern = "gyroid",
            supportMaterial = true,
            printSpeedMmPerSecond = 42.0,
            nozzleTemperatureC = 245,
        )
        val captured = PrusaPresetSettings.capture(PresetKind.PRINT, saved)

        assertFalse(captured.has(PrusaSliceSettings.Keys.NOZZLE_TEMPERATURE))
        assertFalse(captured.has(PrusaSliceSettings.Keys.EXTRUSION_MULTIPLIER))

        val plan = PresetApplication.preparePrusa(PresetKind.PRINT, base, captured.toString())

        assertEquals(0.14, plan.settings.layerHeightMm, EPSILON)
        assertEquals(5, plan.settings.perimeters)
        assertEquals("gyroid", plan.settings.fillPattern)
        assertTrue(plan.settings.supportMaterial)
        assertEquals(42.0, plan.settings.printSpeedMmPerSecond, EPSILON)
        assertEquals(base.nozzleTemperatureC, plan.settings.nozzleTemperatureC)
        assertEquals(base.extraKeys, plan.settings.extraKeys)
        // An "automatic" extrusion width is carried by the preset but is not an override.
        assertEquals(
            PrusaPresetSettings.keys(PresetKind.PRINT) - PrusaPresetSettings.nullableKeys(PresetKind.PRINT),
            plan.appliedKeys,
        )
    }

    @Test
    fun prusaAutomaticExtrusionWidthSurvivesTheRoundTrip() {
        val automatic = PrusaSliceSettings(firstLayerExtrusionWidthMm = null)
        val manual = PrusaSliceSettings(firstLayerExtrusionWidthMm = 0.45)
        val captured = PrusaPresetSettings.capture(PresetKind.PRINT, automatic)

        assertTrue(captured.has(PrusaSliceSettings.Keys.FIRST_LAYER_EXTRUSION_WIDTH))
        assertTrue(captured.isNull(PrusaSliceSettings.Keys.FIRST_LAYER_EXTRUSION_WIDTH))

        val asAutomatic = PresetApplication.preparePrusa(PresetKind.PRINT, manual, captured.toString())
        assertNull(asAutomatic.settings.firstLayerExtrusionWidthMm)

        val asManual = PresetApplication.preparePrusa(
            PresetKind.PRINT,
            asAutomatic.settings,
            PrusaPresetSettings.capture(PresetKind.PRINT, manual).toString(),
        )
        assertEquals(0.45, asManual.settings.firstLayerExtrusionWidthMm ?: 0.0, EPSILON)
    }

    @Test
    fun prusaPresetIsModifiedWhenOnlyANullWidthChanged() {
        val automatic = PrusaSliceSettings(firstLayerExtrusionWidthMm = null)
        val captured = PrusaPresetSettings.capture(PresetKind.PRINT, automatic)

        assertTrue(PrusaPresetSettings.matches(PresetKind.PRINT, automatic, captured))
        assertFalse(
            PrusaPresetSettings.matches(
                PresetKind.PRINT,
                automatic.copy(firstLayerExtrusionWidthMm = 0.4),
                captured,
            ),
        )
    }

    @Test
    fun orcaFilamentPresetLeavesVendorSelectionAndCatalogAlone() {
        val base = OrcaSliceSettings(
            printerPreset = "Voron 0.4 nozzle",
            processPreset = "0.20mm Standard",
            filamentPreset = "Generic PLA",
            extraKeys = mapOf("filament_notes" to "keep me"),
        )
        val saved = OrcaSliceSettings(fanMaxSpeedPercent = 55, layerHeightMm = 0.3)
        val captured = OrcaPresetSettings.capture(PresetKind.FILAMENT, saved)

        assertFalse(captured.has(OrcaSliceSettings.Keys.LAYER_HEIGHT))

        val plan = PresetApplication.prepareOrca(PresetKind.FILAMENT, base, captured.toString())

        assertEquals(55, plan.settings.fanMaxSpeedPercent)
        assertEquals("Voron 0.4 nozzle", plan.settings.printerPreset)
        assertEquals("0.20mm Standard", plan.settings.processPreset)
        assertEquals("Generic PLA", plan.settings.filamentPreset)
        assertEquals(mapOf("filament_notes" to "keep me"), plan.settings.extraKeys)
    }

    @Test
    fun everyStorableKeyHasExactlyOneValueRule() {
        PresetKind.entries.forEach { kind ->
            val prusa = PrusaPresetSettings.rules(kind)
            assertEquals(
                PrusaPresetSettings.keys(kind),
                prusa.booleanKeys + prusa.integerKeys + prusa.stringKeys + prusa.numericRanges.keys,
            )
            val orca = OrcaPresetSettings.rules(kind)
            assertEquals(
                OrcaPresetSettings.keys(kind),
                orca.booleanKeys + orca.integerKeys + orca.stringKeys + orca.numericRanges.keys,
            )
        }
    }

    @Test
    fun engineSanitizerRejectsOutOfRangeForeignAndUnknownValues() {
        val tooHot = JSONObject().put(PrusaSliceSettings.Keys.NOZZLE_TEMPERATURE, 900)
        val error = runCatching {
            PresetValueSanitizer.sanitize(SlicerEngine.PRUSA, PresetKind.FILAMENT, tooHot)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("outside"))

        val foreignOnly = JSONObject().put(PrusaSliceSettings.Keys.NOZZLE_TEMPERATURE, 240)
        assertTrue(
            runCatching {
                PresetValueSanitizer.sanitize(SlicerEngine.PRUSA, PresetKind.PRINT, foreignOnly)
            }.isFailure,
        )

        val unknownPattern = JSONObject().put(OrcaSliceSettings.Keys.SPARSE_INFILL_PATTERN, "spiral")
        assertTrue(
            runCatching {
                PresetValueSanitizer.sanitize(SlicerEngine.ORCA, PresetKind.PRINT, unknownPattern)
            }.isFailure,
        )
    }

    @Test
    fun libraryKeepsNamesAndActiveMarkersPerEngine() {
        val curaPreset = preset("cura-1", SlicerEngine.CURA, PresetKind.PRINT, "Fine")
        val prusaPreset = preset("prusa-1", SlicerEngine.PRUSA, PresetKind.PRINT, "Fine")
        val library = PresetLibrary(presets = listOf(curaPreset, prusaPreset))
            .withActive(SlicerEngine.CURA, PresetKind.PRINT, "cura-1")
            .withActive(SlicerEngine.PRUSA, PresetKind.PRINT, "prusa-1")

        assertEquals(listOf("cura-1"), library.presets(SlicerEngine.CURA, PresetKind.PRINT).map(UserPreset::id))
        assertEquals(listOf("prusa-1"), library.presets(SlicerEngine.PRUSA, PresetKind.PRINT).map(UserPreset::id))
        assertEquals("cura-1", library.activeId(SlicerEngine.CURA, PresetKind.PRINT))
        assertEquals("prusa-1", library.activeId(SlicerEngine.PRUSA, PresetKind.PRINT))
        assertNull(library.activeId(SlicerEngine.ORCA, PresetKind.PRINT))
        assertNull(library.withoutActive("cura-1").activeId(SlicerEngine.CURA, PresetKind.PRINT))

        // The same name on another engine is not a duplicate.
        val normalized = UserPresetLibraryNormalizer.normalizePresets(listOf(curaPreset, prusaPreset), 100)
        assertEquals(2, normalized.size)
    }

    @Test
    fun presetLimitAppliesPerEngineAndKind() {
        val normalized = UserPresetLibraryNormalizer.normalizePresets(
            listOf(
                preset("p-old", SlicerEngine.PRUSA, PresetKind.PRINT, "Old", updatedAt = 1),
                preset("p-new", SlicerEngine.PRUSA, PresetKind.PRINT, "New", updatedAt = 5),
                preset("c-1", SlicerEngine.CURA, PresetKind.PRINT, "Fine", updatedAt = 2),
            ),
            maxPerKind = 1,
        )

        assertEquals(setOf("p-new", "c-1"), normalized.mapTo(hashSetOf(), UserPreset::id))
    }

    private fun preset(
        id: String,
        engine: SlicerEngine,
        kind: PresetKind,
        name: String,
        updatedAt: Long = 1L,
    ): UserPreset = UserPreset(
        id = id,
        engine = engine,
        kind = kind,
        name = name,
        valuesJson = "{}",
        createdAtEpochMillis = 0,
        updatedAtEpochMillis = updatedAt,
    )

    private companion object {
        const val EPSILON = 0.000_001
    }
}
