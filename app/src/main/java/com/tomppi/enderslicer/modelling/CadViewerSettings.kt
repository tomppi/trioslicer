package com.tomppi.enderslicer.modelling

import android.content.Context
import com.tomppi.enderslicer.data.AppStateStore

/**
 * The CAD viewport's own settings.
 *
 * Deliberately not part of [com.tomppi.enderslicer.model.SlicerSettings]: these describe how this
 * device draws the engine's viewport, not how a part is printed, so they belong to the app rather
 * than to a print profile. Stored in the app's own preferences file for the same reason - a
 * second file is one more thing that can be cleared without clearing the rest.
 *
 * The render sizes are caps on the longest side, in pixels. 0 means no cap.
 */
data class CadViewerSettings(
    /** How large a frame the engine renders while a drag is in flight. */
    val dragQuality: Int = DEFAULT_DRAG,
    /** How large a frame it renders once the camera stops. 0 is the full display size. */
    val idleQuality: Int = DEFAULT_IDLE,
    /** Shaded surfaces, or wireframe. */
    val shaded: Boolean = true,
    /** The overlay that says what the engine is doing. */
    val showStatus: Boolean = true,
) {
    companion object {
        const val DEFAULT_DRAG = 640
        const val DEFAULT_IDLE = 0
        val DRAG_CHOICES = listOf(320, 480, 640, 800, 1080)
        val IDLE_CHOICES = listOf(0, 720, 1080, 1440)

        /** "640 px", or "Full" for no cap. */
        fun label(pixels: Int): String = if (pixels <= 0) "Full" else "$pixels px"
    }
}

/** Loads and stores [CadViewerSettings]. */
internal object CadViewerPreference {
    private const val KEY_DRAG = "cad-viewer-drag-quality"
    private const val KEY_IDLE = "cad-viewer-idle-quality"
    private const val KEY_SHADED = "cad-viewer-shaded"
    private const val KEY_STATUS = "cad-viewer-show-status"

    fun load(context: Context): CadViewerSettings {
        val prefs = preferences(context)
        return CadViewerSettings(
            dragQuality = prefs.getInt(KEY_DRAG, CadViewerSettings.DEFAULT_DRAG),
            idleQuality = prefs.getInt(KEY_IDLE, CadViewerSettings.DEFAULT_IDLE),
            shaded = prefs.getBoolean(KEY_SHADED, true),
            showStatus = prefs.getBoolean(KEY_STATUS, true),
        )
    }

    /** Stores the settings. Returns false when the write did not reach disk. */
    fun save(context: Context, settings: CadViewerSettings): Boolean =
        preferences(context).edit()
            .putInt(KEY_DRAG, settings.dragQuality)
            .putInt(KEY_IDLE, settings.idleQuality)
            .putBoolean(KEY_SHADED, settings.shaded)
            .putBoolean(KEY_STATUS, settings.showStatus)
            .commit()

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(AppStateStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
}
