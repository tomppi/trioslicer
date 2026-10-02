package com.tomppi.enderslicer.data

import android.content.Context

/**
 * The KAMP switch: whether a Klipper slice should ask for an adaptive mesh.
 *
 * Deliberately not a [com.tomppi.enderslicer.model.SlicerSettings] property. That class is at
 * the JVM's limit for the `copy` kotlinc generates - 161 properties, 88 of them double-width,
 * which is 255 parameter slots exactly - and one more property makes the class fail to load
 * with "Too many arguments in method signature", taking every screen that reads a setting with
 * it. Growing it needs settings grouped into smaller objects, which is a change to make on
 * purpose rather than in passing.
 *
 * It is also the right shape for this switch: it belongs to the machine and the host, not to a
 * print profile, so it is stored on its own rather than copied around with the settings.
 */
internal object KampPreference {
    private const val KEY_ADAPTIVE_MESH = "kamp-adaptive-mesh-enabled"

    /** Whether the app should give a Klipper slice a mesh to measure. Off until asked for. */
    fun isEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ADAPTIVE_MESH, false)

    /** Stores the switch. Returns false when the write did not reach disk. */
    fun setEnabled(context: Context, enabled: Boolean): Boolean =
        preferences(context).edit().putBoolean(KEY_ADAPTIVE_MESH, enabled).commit()

    // The app's own preferences file, not a second one: a switch is part of this app's state,
    // and a store of its own is one more thing that can be cleared without clearing the rest.
    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(AppStateStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
}
