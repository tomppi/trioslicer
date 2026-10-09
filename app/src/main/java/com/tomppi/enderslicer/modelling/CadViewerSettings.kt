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
 * The render sizes are caps on the longest side, in pixels. 0 means no cap. Everything from
 * [tessellation] down is handed to the engine, which is where the picture is actually made; each
 * one was measured on the device by rendering with it changed and comparing pixels, because
 * OCCT's rendering path will accept a call and quietly ignore it.
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
    /** How finely curved surfaces are meshed: coarse, standard, fine, very fine. */
    val tessellation: String = "standard",
    /** dark, mid or light. */
    val background: String = "dark",
    /** A grid on the bed, drawn in millimetres. */
    val grid: Boolean = false,
    val gridStepMm: Float = 10f,
    /** The axis cross in the corner. */
    val axes: Boolean = true,
    /** perspective or orthographic. */
    val projection: String = "perspective",
    /** The wireframe drawn over a shaded surface, which is what makes a step visible. */
    val edges: Boolean = true,
    /**
     * Anti-aliasing, done by drawing the frame twice as wide and averaging it down.
     *
     * MSAA is what OCCT offers and this driver ignores it - the frame is byte-identical with it
     * at 0 and at 4 - so the engine supersamples instead. It costs four times the pixels, which
     * is why it is a setting rather than a constant.
     */
    val antialiasing: Boolean = true,
) {
    companion object {
        const val DEFAULT_DRAG = 640
        const val DEFAULT_IDLE = 0
        val DRAG_CHOICES = listOf(320, 480, 640, 800, 1080)
        val IDLE_CHOICES = listOf(0, 720, 1080, 1440)
        val TESSELLATIONS = listOf("coarse", "standard", "fine", "very fine")
        val BACKGROUNDS = listOf("dark", "mid", "light")
        val PROJECTIONS = listOf("perspective", "orthographic")
        val GRID_STEPS = listOf(1f, 5f, 10f, 25f)

        /** "640 px", or "Full" for no cap. */
        fun label(pixels: Int): String = if (pixels <= 0) "Full" else "$pixels px"

        /** "Very fine" from "very fine": these are shown as words, not as identifiers. */
        fun word(value: String): String = value.replaceFirstChar { it.uppercase() }

        fun gridLabel(step: Float): String =
            if (step < 1f) "%.1f mm".format(step) else "${step.toInt()} mm"
    }
}

/** Loads and stores [CadViewerSettings]. */
internal object CadViewerPreference {
    private const val KEY_DRAG = "cad-viewer-drag-quality"
    private const val KEY_IDLE = "cad-viewer-idle-quality"
    private const val KEY_SHADED = "cad-viewer-shaded"
    private const val KEY_STATUS = "cad-viewer-show-status"
    private const val KEY_TESSELLATION = "cad-viewer-tessellation"
    private const val KEY_BACKGROUND = "cad-viewer-background"
    private const val KEY_GRID = "cad-viewer-grid"
    private const val KEY_GRID_STEP = "cad-viewer-grid-step"
    private const val KEY_AXES = "cad-viewer-axes"
    private const val KEY_PROJECTION = "cad-viewer-projection"
    private const val KEY_EDGES = "cad-viewer-edges"
    private const val KEY_ANTIALIASING = "cad-viewer-antialiasing"

    fun load(context: Context): CadViewerSettings {
        val prefs = preferences(context)
        val defaults = CadViewerSettings()
        return CadViewerSettings(
            dragQuality = prefs.getInt(KEY_DRAG, defaults.dragQuality),
            idleQuality = prefs.getInt(KEY_IDLE, defaults.idleQuality),
            shaded = prefs.getBoolean(KEY_SHADED, defaults.shaded),
            showStatus = prefs.getBoolean(KEY_STATUS, defaults.showStatus),
            tessellation = prefs.getString(KEY_TESSELLATION, defaults.tessellation)
                ?: defaults.tessellation,
            background = prefs.getString(KEY_BACKGROUND, defaults.background)
                ?: defaults.background,
            grid = prefs.getBoolean(KEY_GRID, defaults.grid),
            gridStepMm = prefs.getFloat(KEY_GRID_STEP, defaults.gridStepMm),
            axes = prefs.getBoolean(KEY_AXES, defaults.axes),
            projection = prefs.getString(KEY_PROJECTION, defaults.projection)
                ?: defaults.projection,
            edges = prefs.getBoolean(KEY_EDGES, defaults.edges),
            antialiasing = prefs.getBoolean(KEY_ANTIALIASING, defaults.antialiasing),
        )
    }

    /** Stores the settings. Returns false when the write did not reach disk. */
    fun save(context: Context, settings: CadViewerSettings): Boolean =
        preferences(context).edit()
            .putInt(KEY_DRAG, settings.dragQuality)
            .putInt(KEY_IDLE, settings.idleQuality)
            .putBoolean(KEY_SHADED, settings.shaded)
            .putBoolean(KEY_STATUS, settings.showStatus)
            .putString(KEY_TESSELLATION, settings.tessellation)
            .putString(KEY_BACKGROUND, settings.background)
            .putBoolean(KEY_GRID, settings.grid)
            .putFloat(KEY_GRID_STEP, settings.gridStepMm)
            .putBoolean(KEY_AXES, settings.axes)
            .putString(KEY_PROJECTION, settings.projection)
            .putBoolean(KEY_EDGES, settings.edges)
            .putBoolean(KEY_ANTIALIASING, settings.antialiasing)
            .commit()

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(AppStateStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
}
