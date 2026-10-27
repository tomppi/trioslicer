package com.tomppi.enderslicer.ui

import android.content.Context

/**
 * The wide navigation rail's menu fold, as a UI preference.
 *
 * Mirrors [UiScale]: one small shared-preferences file, a value initialized before the first
 * frame, a save on every change and a sanitized default. The rail's menu block (the five
 * destinations) can fold away so the action rows below it get the height on a short fold
 * window; the user expects that fold to stay folded, so it is a preference, not remembered
 * composition state. Default is expanded - the rail looks exactly like it always did until the
 * user presses the arrow.
 */
internal object RailMenuPreference {
    const val PREFERENCES_NAME = "enderslicer-rail-menu"
    const val KEY_EXPANDED = "rail-menu-expanded"
    const val DEFAULT_EXPANDED = true

    @Volatile
    private var activeExpanded = DEFAULT_EXPANDED

    /** Reads the stored choice once; call before the rail is composed. */
    fun initialize(context: Context): Boolean {
        activeExpanded = context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_EXPANDED, DEFAULT_EXPANDED)
        return activeExpanded
    }

    fun current(): Boolean = activeExpanded

    /** Writes the choice and remembers it for the frames in this session. */
    fun save(context: Context, expanded: Boolean): Boolean {
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_EXPANDED, expanded)
            .apply()
        activeExpanded = expanded
        return expanded
    }
}
