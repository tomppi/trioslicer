package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.model.SlicerEngine
import com.tomppi.enderslicer.profile.CuraPresetSchema
import com.tomppi.enderslicer.profile.OrcaPresetSettings
import com.tomppi.enderslicer.profile.PresetApplication
import com.tomppi.enderslicer.profile.PresetKind
import com.tomppi.enderslicer.profile.PresetSettings
import com.tomppi.enderslicer.profile.PresetValues
import com.tomppi.enderslicer.profile.PrusaPresetSettings
import org.json.JSONObject

/** The values a preset of [engine] would capture from the live settings right now. */
internal fun MainUiState.capturePreset(engine: SlicerEngine, kind: PresetKind): JSONObject = when (engine) {
    SlicerEngine.CURA -> CuraPresetSchema.capture(kind, settings)
    SlicerEngine.PRUSA -> PrusaPresetSettings.capture(kind, prusaSettings)
    SlicerEngine.ORCA -> OrcaPresetSettings.capture(kind, orcaSettings)
}

/** Whether a saved preset still matches the live settings of its own engine. */
internal fun matchesPresetValues(
    engine: SlicerEngine,
    kind: PresetKind,
    current: JSONObject,
    saved: JSONObject,
): Boolean = when (engine) {
    SlicerEngine.CURA -> PresetSettings.matchesValues(kind, current, saved)
    SlicerEngine.PRUSA -> PresetValues.matches(
        PrusaPresetSettings.keys(kind),
        current,
        saved,
        PrusaPresetSettings.nullableKeys(kind),
    )

    SlicerEngine.ORCA -> PresetValues.matches(OrcaPresetSettings.keys(kind), current, saved)
}

/**
 * Applies a saved preset to the settings of the engine that owns it.
 *
 * A preset may only be applied while its engine is the active one: applying a PrusaSlicer preset
 * while OrcaSlicer is slicing would rewrite a model the current slice never reads, which is the
 * confusion engine-scoped presets exist to prevent.
 */
internal fun MainViewModel.applyPreset(
    engine: SlicerEngine,
    kind: PresetKind,
    valuesJson: String,
): Boolean {
    if (uiState.value.isBusy) return false
    require(engine == currentEngine) { "Switch to ${engine.label} to apply its presets" }

    return when (engine) {
        SlicerEngine.CURA -> {
            val before = uiState.value.settings
            val plan = PresetApplication.prepareCura(kind, before, valuesJson)

            // MainViewModel.updateSettings owns persistence and stale-output
            // invalidation, but records one explicit override key per call. Register the
            // complete key set while values are still unchanged, then switch all values
            // in one final update. A process interruption can therefore leave the old
            // values marked as modified, never a partially applied preset.
            plan.appliedKeys.forEach { key ->
                updateSettings(key) { current -> current }
            }

            val markerKey = plan.appliedKeys.first()
            updateSettings(markerKey) { current ->
                check(current.copy(overriddenSettingKeys = before.overriddenSettingKeys) == before) {
                    "Settings changed while the preset was being applied"
                }
                plan.settings
            }

            check(uiState.value.settings == plan.settings) {
                "The preset could not be applied while another operation was active"
            }
            true
        }

        SlicerEngine.PRUSA -> {
            val before = uiState.value.prusaSettings
            val plan = PresetApplication.preparePrusa(kind, before, valuesJson)
            updatePrusaSettings(kind.name) { current ->
                check(current == before) { "Settings changed while the preset was being applied" }
                plan.settings
            }
            check(uiState.value.prusaSettings == plan.settings) {
                "The preset could not be applied while another operation was active"
            }
            true
        }

        SlicerEngine.ORCA -> {
            val before = uiState.value.orcaSettings
            val plan = PresetApplication.prepareOrca(kind, before, valuesJson)
            updateOrcaSettings(kind.name) { current ->
                check(current == before) { "Settings changed while the preset was being applied" }
                plan.settings
            }
            check(uiState.value.orcaSettings == plan.settings) {
                "The preset could not be applied while another operation was active"
            }
            true
        }
    }
}
