package com.tomppi.enderslicer.ui

import android.content.Context
import androidx.compose.ui.unit.Density

/**
 * How large the app draws itself. Android's own Display size changes the whole
 * interface, and this is the same lever inside the app: the root multiplies the
 * Compose density, so text, controls, spacing and icons grow together instead of
 * drifting apart.
 *
 * The scale is a display preference, not part of a profile: it is stored on its
 * own, away from the engine settings that a configuration snapshot carries, so
 * restoring a setup on another phone does not import someone else's text size.
 */
object UiScale {
    const val MIN_PERCENT = 75
    const val MAX_PERCENT = 150
    const val DEFAULT_PERCENT = 100

    private const val PREFERENCES_NAME = "enderslicer-ui-scale"
    private const val KEY_PERCENT = "ui-scale-percent"

    @Volatile
    private var activePercent = DEFAULT_PERCENT

    fun initialize(context: Context): Int {
        val stored = context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_PERCENT, DEFAULT_PERCENT)
        activePercent = sanitize(stored)
        return activePercent
    }

    fun current(): Int = activePercent

    fun save(context: Context, percent: Int): Int {
        val sanitized = sanitize(percent)
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_PERCENT, sanitized)
            .apply()
        activePercent = sanitized
        return sanitized
    }

    fun sanitize(percent: Int): Int = percent.coerceIn(MIN_PERCENT, MAX_PERCENT)

    fun factor(percent: Int): Float = sanitize(percent) / 100f

    /**
     * The interface at [percent]. Only the DENSITY is multiplied, and the font
     * scale is left as the device set it.
     *
     * Compose resolves text as `sp * fontScale * density`, so multiplying both
     * applies the scale twice to every label while leaving controls at one
     * times: at 138% the title grew 1.62x instead of 1.38x, and text outran the
     * rows around it. Density alone scales dp and sp together, and it leaves the
     * user's own system font-size preference alone, which is not ours to change.
     */
    fun scaled(base: Density, percent: Int): Density =
        Density(base.density * factor(percent), base.fontScale)
}
