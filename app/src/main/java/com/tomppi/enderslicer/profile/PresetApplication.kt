package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.OrcaSliceSettings
import com.tomppi.enderslicer.model.PrusaSliceSettings
import com.tomppi.enderslicer.model.SlicerSettings
import org.json.JSONObject

/**
 * Turns a stored preset into the settings a model should become, for one engine. The plan carries
 * the keys the preset actually supplies so the caller can report or roll back an application.
 */
internal object PresetApplication {
    data class Plan<S : Any>(
        val settings: S,
        val appliedKeys: LinkedHashSet<String>,
    )

    fun prepareCura(kind: PresetKind, current: SlicerSettings, valuesJson: String): Plan<SlicerSettings> =
        prepare(CuraPresetSchema, kind, current, valuesJson)

    fun preparePrusa(kind: PresetKind, current: PrusaSliceSettings, valuesJson: String): Plan<PrusaSliceSettings> =
        prepare(PrusaPresetSettings, kind, current, valuesJson)

    fun prepareOrca(kind: PresetKind, current: OrcaSliceSettings, valuesJson: String): Plan<OrcaSliceSettings> =
        prepare(OrcaPresetSettings, kind, current, valuesJson)

    private fun <S : Any> prepare(
        schema: PresetSchema<S>,
        kind: PresetKind,
        current: S,
        valuesJson: String,
    ): Plan<S> {
        val sanitized = PresetValueSanitizer.sanitize(schema.engine, kind, JSONObject(valuesJson))
        val presentKeys = schema.keys(kind).filterTo(linkedSetOf()) { key -> sanitized.has(key) }
        require(presentKeys.isNotEmpty()) { "The preset has no usable ${kind.label.lowercase()} values" }
        val appliedKeys = presentKeys.filterTo(linkedSetOf()) { key -> !sanitized.isNull(key) }
        val changed = schema.apply(kind, current, sanitized)
        schema.validateMerged(kind, changed)
        return Plan(changed, appliedKeys)
    }
}
