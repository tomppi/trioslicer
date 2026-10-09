package com.tomppi.enderslicer.data

import android.content.Context

/**
 * How a plate holding several objects is laid out and printed.
 *
 * Deliberately not part of [com.tomppi.enderslicer.model.SlicerSettings]: those feed the workspace
 * fingerprint, and toggling auto-arrange must not invalidate a saved workspace that is otherwise
 * unchanged.
 */
data class PlatePreferences(
    /** What a newly imported or newly built object gets: a place found for it, or wherever it lands. */
    val placement: Placement = Placement.AUTO,
    /** Gap kept between objects and to the bed edge, in millimetres. */
    val spacingMm: Double = DEFAULT_SPACING_MM,
    /** Print one object at a time, so the head clears what is already printed. */
    val sequential: Boolean = false,
    /** Emit per-object markers, so a print can be cancelled object by object. */
    val objectLabels: Boolean = true,
) {
    enum class Placement { AUTO, MANUAL }

    /** The values the packer can act on: a gap that is missing or out of range becomes a usable one. */
    fun sanitized(): PlatePreferences = copy(
        spacingMm = spacingMm.takeIf(Double::isFinite)
            ?.coerceIn(MIN_SPACING_MM, MAX_SPACING_MM)
            ?: DEFAULT_SPACING_MM,
    )

    companion object {
        const val DEFAULT_SPACING_MM = 6.0
        const val MIN_SPACING_MM = 0.0
        const val MAX_SPACING_MM = 50.0
    }
}

/** Persisted plate preferences, independent of profiles and settings. */
class PlatePreferencesStore(context: Context) {
    private val shared =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): PlatePreferences {
        val defaults = PlatePreferences()
        val placement = shared.getString(KEY_PLACEMENT, null)
            ?.let { name -> runCatching { PlatePreferences.Placement.valueOf(name) }.getOrNull() }
            ?: defaults.placement
        return PlatePreferences(
            placement = placement,
            spacingMm = number(KEY_SPACING_MM, defaults.spacingMm),
            sequential = shared.getBoolean(KEY_SEQUENTIAL, defaults.sequential),
            objectLabels = shared.getBoolean(KEY_OBJECT_LABELS, defaults.objectLabels),
        ).sanitized()
    }

    fun save(preferences: PlatePreferences) {
        val safe = preferences.sanitized()
        shared.edit()
            .putString(KEY_PLACEMENT, safe.placement.name)
            .putString(KEY_SPACING_MM, safe.spacingMm.toString())
            .putBoolean(KEY_SEQUENTIAL, safe.sequential)
            .putBoolean(KEY_OBJECT_LABELS, safe.objectLabels)
            .apply()
    }

    /** Kept as text, so a value this build cannot read falls back instead of throwing on load. */
    private fun number(key: String, fallback: Double): Double =
        shared.getString(key, null)?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: fallback

    private companion object {
        const val PREFERENCES = "plate-preferences"
        const val KEY_PLACEMENT = "placement"
        const val KEY_SPACING_MM = "spacing-mm"
        const val KEY_SEQUENTIAL = "sequential"
        const val KEY_OBJECT_LABELS = "object-labels"
    }
}
