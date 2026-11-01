package com.tomppi.enderslicer.ui

import android.content.Context

/**
 * Which workspace owns the plate: the slicer's own model/plate tools, or the embedded
 * ScanRuler engine's 3D viewport.
 *
 * The selection is also the agent signal: the in-app agent works with the workspace the human
 * is looking at, exactly as the top-bar menus choose the engine. It is a UI preference, not
 * remembered composition state - leaving the Plate tab disposes the subtree, and the user
 * expects the last chosen workspace back.
 */
enum class PlateView(val label: String) {
    PLATE("Plate"),
    SCAN_RULER("ScanRuler"),
}

internal object PlateViewPreference {
    const val PREFERENCES_NAME = "enderslicer-plate-view"
    const val KEY_VIEW = "plate-view"
    val DEFAULT: PlateView = PlateView.PLATE

    @Volatile
    private var activeView: PlateView = DEFAULT

    /** Reads the stored workspace once at startup. */
    fun initialize(context: Context): PlateView {
        val raw = context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_VIEW, null)
        activeView = parse(raw)
        return activeView
    }

    fun current(): PlateView = activeView

    fun save(context: Context, view: PlateView): PlateView {
        context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_VIEW, view.name)
            .apply()
        activeView = view
        return view
    }

    /** Any unknown or stale stored value falls back to the plate rather than a blank workspace. */
    internal fun parse(raw: String?): PlateView =
        PlateView.entries.firstOrNull { it.name == raw } ?: DEFAULT
}
