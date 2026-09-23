package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerEngine
import com.tomppi.enderslicer.model.SlicerSettings
import org.json.JSONObject
import kotlin.math.abs

/**
 * The per-engine shape of a named preset: which settings a print profile and a filament profile
 * carry, how they are captured from and applied to that engine's settings model, and whether the
 * live settings still match a saved preset.
 *
 * The Cura implementation lives in [PresetSettings] and reflects over
 * [com.tomppi.enderslicer.model.SlicerSettings]; PrusaSlicer and OrcaSlicer reuse their own JSON
 * codecs ([PrusaPresetSettings], [OrcaPresetSettings]).
 */
internal interface PresetSchema<S : Any> {
    val engine: SlicerEngine

    fun keys(kind: PresetKind): Set<String>

    fun capture(kind: PresetKind, settings: S): JSONObject

    fun apply(kind: PresetKind, current: S, values: JSONObject): S

    fun matches(kind: PresetKind, settings: S, values: JSONObject): Boolean

    fun validateMerged(kind: PresetKind, settings: S)

    /**
     * Keys that may legitimately hold JSON null. PrusaSlicer's extrusion widths are nullable:
     * null means "automatic", so it is a value a preset has to be able to store and restore.
     */
    fun nullableKeys(kind: PresetKind): Set<String> = emptySet()
}

/** Cura's schema: the original reflection-based preset layer over [SlicerSettings]. */
internal object CuraPresetSchema : PresetSchema<SlicerSettings> {
    override val engine: SlicerEngine = SlicerEngine.CURA

    override fun keys(kind: PresetKind): Set<String> = PresetSettings.keys(kind)

    override fun capture(kind: PresetKind, settings: SlicerSettings): JSONObject =
        PresetSettings.capture(kind, settings)

    override fun apply(kind: PresetKind, current: SlicerSettings, values: JSONObject): SlicerSettings =
        PresetSettings.apply(kind, current, values)

    override fun matches(kind: PresetKind, settings: SlicerSettings, values: JSONObject): Boolean =
        PresetSettings.matches(kind, settings, values)

    override fun validateMerged(kind: PresetKind, settings: SlicerSettings) =
        PresetValueSanitizer.validateMerged(kind, settings)
}

/** Value comparison shared by the JSON-backed schemas; mirrors PresetSettings.matchesValues. */
internal object PresetValues {
    fun matches(
        keys: Set<String>,
        current: JSONObject,
        saved: JSONObject,
        nullable: Set<String> = emptySet(),
    ): Boolean = keys.all { key ->
        val currentNull = !current.has(key) || current.isNull(key)
        val savedNull = !saved.has(key) || saved.isNull(key)
        when {
            currentNull && savedNull -> key in nullable
            currentNull != savedNull -> false
            else -> equivalent(current.opt(key), saved.opt(key))
        }
    }

    fun equivalent(current: Any?, saved: Any?): Boolean = when {
        current is Number && saved is Number ->
            abs(current.toDouble() - saved.toDouble()) <= EQUIVALENCE_TOLERANCE

        else -> current == saved
    }

    private const val EQUIVALENCE_TOLERANCE = 0.000_001
}
