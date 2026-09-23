package com.tomppi.enderslicer.nonplanar

import android.content.Context
import com.tomppi.enderslicer.engine.SliceArtifactPublisher
import com.tomppi.enderslicer.engine.invalidatePublishedSliceArtifacts
import java.io.File
import kotlin.math.abs
import kotlin.math.atan

/**
 * User-facing controls for the conformal non-planar pipeline (Ahlers'
 * method): the STL stays as displayed, and after CuraEngine slices it the top
 * toolpaths are projected straight down onto the printable surface regions,
 * so the nozzle follows the true 3D surface instead of a stack of flat
 * layers. All hot-end measurements feed the post-slice collision sweep.
 */
data class NonPlanarSettings(
    val enabled: Boolean = false,
    // The surface itself may not climb steeper than this from horizontal;
    // steeper facets are excluded from the conformal regions. The thesis's
    // 5-40 degree sweep found planar prints smoother above ~20 degrees.
    val maximumSlopeDegrees: Double = 20.0,
    // Clearance to the nearest obstacle, measured from horizontal like every
    // other angle (90 = straight up). 45 degrees is identical in both frames.
    val nozzleClearanceAngleDegrees: Double = 45.0,
    val nozzleClearanceHeightMm: Double = 50.0,
    // Taper angle of the nozzle's own cone, measured from horizontal (the
    // build plate): 90 degrees would be a vertical wall, 75 degrees is a
    // thin cone.
    val nozzleAngleDegrees: Double = 60.0,
    val nozzleProtrusionMm: Double = 5.0,
    val heatingBlockWidthMm: Double = 20.0,
    val heatingBlockDepthMm: Double = 16.0,
    val heatingBlockOffsetXmm: Double = 0.0,
    val heatingBlockOffsetYmm: Double = 0.0,
    // Maximum total Z span of a conformal surface region: taller regions are
    // left planar instead of asking the hot-end to travel too far.
    val maximumLiftMm: Double = 5.0,
    val maximumZSpeedMmPerSecond: Double = 5.0,
    val pauseAfterProbe: Boolean = false,
    // How many top planar layers are replaced by conformal shells riding
    // conformalShellLayers * layerHeight below the surface.
    val conformalShellLayers: Int = 3,
) {
    fun validated(): NonPlanarSettings = copy(
        maximumSlopeDegrees = maximumSlopeDegrees.coerceIn(MIN_SLOPE_DEGREES, MAX_SLOPE_DEGREES),
        nozzleClearanceAngleDegrees = nozzleClearanceAngleDegrees.coerceIn(
            MIN_CLEARANCE_ANGLE_DEGREES,
            MAX_CLEARANCE_ANGLE_DEGREES,
        ),
        nozzleClearanceHeightMm = nozzleClearanceHeightMm.coerceIn(
            MIN_CLEARANCE_HEIGHT_MM,
            MAX_CLEARANCE_HEIGHT_MM,
        ),
        nozzleAngleDegrees = nozzleAngleDegrees.coerceIn(MIN_NOZZLE_ANGLE_DEGREES, MAX_NOZZLE_ANGLE_DEGREES),
        nozzleProtrusionMm = nozzleProtrusionMm.coerceIn(MIN_NOZZLE_PROTRUSION_MM, MAX_NOZZLE_PROTRUSION_MM),
        heatingBlockWidthMm = heatingBlockWidthMm.coerceIn(MIN_BLOCK_SIZE_MM, MAX_BLOCK_SIZE_MM),
        heatingBlockDepthMm = heatingBlockDepthMm.coerceIn(MIN_BLOCK_SIZE_MM, MAX_BLOCK_SIZE_MM),
        heatingBlockOffsetXmm = heatingBlockOffsetXmm.coerceIn(-MAX_BLOCK_OFFSET_MM, MAX_BLOCK_OFFSET_MM),
        heatingBlockOffsetYmm = heatingBlockOffsetYmm.coerceIn(-MAX_BLOCK_OFFSET_MM, MAX_BLOCK_OFFSET_MM),
        maximumLiftMm = maximumLiftMm.coerceIn(MIN_MAXIMUM_LIFT_MM, MAX_MAXIMUM_LIFT_MM),
        maximumZSpeedMmPerSecond = maximumZSpeedMmPerSecond.coerceIn(
            MIN_Z_SPEED_MM_PER_SECOND,
            MAX_Z_SPEED_MM_PER_SECOND,
        ),
        conformalShellLayers = conformalShellLayers.coerceIn(MIN_CONFORMAL_SHELL_LAYERS, MAX_CONFORMAL_SHELL_LAYERS),
    )

    // Both the surface slope and the clearance angle are measured from
    // horizontal, so the path may never climb steeper than the obstacle cone.
    // The nozzle cone itself constrains the climb as well: a surface rising
    // faster than the cone wall (nozzleAngleDegrees from horizontal) would
    // always be inside the cone's widening shadow.
    val effectiveSlopeLimitDegrees: Double
        get() = minOf(
            maximumSlopeDegrees,
            nozzleClearanceAngleDegrees - CLEARANCE_MARGIN_DEGREES,
            nozzleAngleDegrees - CLEARANCE_MARGIN_DEGREES,
        ).coerceAtLeast(MIN_SLOPE_DEGREES)

    /**
     * The steepest slope that can clear the heating-block bottom edge when
     * the nozzle climbs straight toward the block's offset side: a surface
     * rising at this angle reaches the protrusion height exactly where the
     * block half-width (plus the nozzle-axis offset) ends. Slopes above this
     * still print, but the collision sweep will likely flag them. Null when
     * the geometry cannot define a bound.
     */
    val blockJunctionSlopeLimitDegrees: Double?
        get() {
            if (nozzleProtrusionMm <= 0.0) return null
            val span = minOf(
                heatingBlockWidthMm / 2.0 + abs(heatingBlockOffsetXmm),
                heatingBlockDepthMm / 2.0 + abs(heatingBlockOffsetYmm),
            )
            if (span <= 0.0) return null
            return Math.toDegrees(atan(nozzleProtrusionMm / span))
        }

    /** Tip to holding object: the nozzle cone plus the block cone height. */
    val holderHeightMm: Double
        get() = nozzleProtrusionMm + nozzleClearanceHeightMm

    companion object {
        const val MIN_SLOPE_DEGREES = 5.0
        const val MAX_SLOPE_DEGREES = 55.0
        const val MIN_CLEARANCE_ANGLE_DEGREES = 15.0
        const val MAX_CLEARANCE_ANGLE_DEGREES = 89.0
        const val MIN_CLEARANCE_HEIGHT_MM = 5.0
        const val MAX_CLEARANCE_HEIGHT_MM = 150.0
        const val MIN_MAXIMUM_LIFT_MM = 0.2
        const val MAX_MAXIMUM_LIFT_MM = 25.0
        const val MIN_NOZZLE_ANGLE_DEGREES = 5.0
        const val MAX_NOZZLE_ANGLE_DEGREES = 89.0
        const val MIN_NOZZLE_PROTRUSION_MM = 0.5
        const val MAX_NOZZLE_PROTRUSION_MM = 30.0
        const val MIN_BLOCK_SIZE_MM = 2.0
        const val MAX_BLOCK_SIZE_MM = 80.0
        const val MAX_BLOCK_OFFSET_MM = 40.0
        const val MIN_Z_SPEED_MM_PER_SECOND = 0.5
        const val MAX_Z_SPEED_MM_PER_SECOND = 20.0
        const val MIN_CONFORMAL_SHELL_LAYERS = 1
        const val MAX_CONFORMAL_SHELL_LAYERS = 8
        private const val CLEARANCE_MARGIN_DEGREES = 5.0
    }
}

data class NonPlanarSnapshot(
    val settings: NonPlanarSettings,
    val generation: Long,
)

/** Process-wide immutable snapshot used to keep one slice internally consistent. */
object NonPlanarRuntime {
    const val MACHINE_END_SENTINEL = ";ENDERSLICER_MACHINE_END_BEGIN"

    private val lock = Any()

    @Volatile
    private var snapshot = NonPlanarSnapshot(NonPlanarSettings(), 0L)

    fun activate(settings: NonPlanarSettings) {
        val safe = settings.validated()
        synchronized(lock) {
            if (snapshot.settings != safe) snapshot = NonPlanarSnapshot(safe, snapshot.generation + 1L)
        }
    }

    fun snapshot(): NonPlanarSnapshot? = snapshot.takeIf { it.settings.enabled }

    fun current(): NonPlanarSettings = snapshot.settings

    /** Marks the exact boundary where Cura begins executing the machine end script. */
    fun markMachineEndGcode(gcode: String): String {
        if (snapshot() == null) return gcode
        if (gcode.lineSequence().any { it.trim() == MACHINE_END_SENTINEL }) return gcode
        return if (gcode.isBlank()) MACHINE_END_SENTINEL else MACHINE_END_SENTINEL + "\n" + gcode
    }
}

class NonPlanarSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): NonPlanarSettings = NonPlanarSettings(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        maximumSlopeDegrees = number(KEY_MAX_SLOPE, 20.0),
        nozzleClearanceAngleDegrees = number(KEY_CLEARANCE_ANGLE, 45.0),
        nozzleClearanceHeightMm = number(KEY_CLEARANCE_HEIGHT, 50.0),
        maximumLiftMm = number(KEY_MAXIMUM_LIFT, 5.0),
        nozzleAngleDegrees = number(KEY_NOZZLE_ANGLE, 60.0),
        nozzleProtrusionMm = number(KEY_NOZZLE_PROTRUSION, 5.0),
        heatingBlockWidthMm = number(KEY_BLOCK_WIDTH, 20.0),
        heatingBlockDepthMm = number(KEY_BLOCK_DEPTH, 16.0),
        heatingBlockOffsetXmm = number(KEY_BLOCK_OFFSET_X, 0.0),
        heatingBlockOffsetYmm = number(KEY_BLOCK_OFFSET_Y, 0.0),
        maximumZSpeedMmPerSecond = number(KEY_MAX_Z_SPEED, 5.0),
        pauseAfterProbe = preferences.getBoolean(KEY_PAUSE_AFTER_PROBE, false),
        conformalShellLayers = preferences.getInt(KEY_CONFORMAL_SHELLS, 3),
    ).validated().also(NonPlanarRuntime::activate)

    /**
     * Persists the settings and reports whether that invalidated published slices.
     *
     * The removal itself is [invalidatePublishedSlices], a separate suspending
     * step, so that whoever saves decides where that work runs.
     */
    fun save(settings: NonPlanarSettings): Boolean {
        val safe = settings.validated()
        val changed = NonPlanarRuntime.current() != safe
        preferences.edit()
            .putBoolean(KEY_ENABLED, safe.enabled)
            .putString(KEY_MAX_SLOPE, safe.maximumSlopeDegrees.toString())
            .putString(KEY_CLEARANCE_ANGLE, safe.nozzleClearanceAngleDegrees.toString())
            .putString(KEY_CLEARANCE_HEIGHT, safe.nozzleClearanceHeightMm.toString())
            .putString(KEY_MAXIMUM_LIFT, safe.maximumLiftMm.toString())
            .putString(KEY_NOZZLE_ANGLE, safe.nozzleAngleDegrees.toString())
            .putString(KEY_NOZZLE_PROTRUSION, safe.nozzleProtrusionMm.toString())
            .putString(KEY_BLOCK_WIDTH, safe.heatingBlockWidthMm.toString())
            .putString(KEY_BLOCK_DEPTH, safe.heatingBlockDepthMm.toString())
            .putString(KEY_BLOCK_OFFSET_X, safe.heatingBlockOffsetXmm.toString())
            .putString(KEY_BLOCK_OFFSET_Y, safe.heatingBlockOffsetYmm.toString())
            .putString(KEY_MAX_Z_SPEED, safe.maximumZSpeedMmPerSecond.toString())
            .putBoolean(KEY_PAUSE_AFTER_PROBE, safe.pauseAfterProbe)
            .putInt(KEY_CONFORMAL_SHELLS, safe.conformalShellLayers)
            .commit()
        NonPlanarRuntime.activate(safe)
        return changed
    }

    /**
     * Removes every published slice, so G-code built with the previous non-planar
     * settings cannot be exported.
     *
     * Suspending and non-throwing on purpose. It runs off the main thread because
     * the tree can hold hundreds of megabytes, and it answers with a message
     * rather than raising one: the settings are already saved by the time this
     * runs - the Save button has closed its sheet - so a crash here would read as
     * a failed save.
     *
     * The removal goes through the publisher's own [SliceArtifactPublisher.release]:
     * that takes the lock the publisher holds while it renames a finished slice
     * into place, so a slice being published right now is never torn down, and one
     * another part of the app is reading through a lease is marked for deletion
     * instead of pulled from under it.
     */
    /**
     * Drops the last run's G-code: this setting changes what would be sliced, so
     * the artifact on disk no longer describes the current configuration.
     */
    suspend fun invalidatePublishedSlices(): String? = invalidatePublishedSliceArtifacts(
        File(appContext.filesDir, SliceArtifactPublisher.RESULTS_DIRECTORY_NAME),
    )

    private fun number(key: String, fallback: Double): Double =
        preferences.getString(key, null)?.toDoubleOrNull()?.takeIf(Double::isFinite) ?: fallback

    companion object {
        const val BACKEND_NAME = "Conformal surface non-planar backend (Ahlers method)"
        const val BACKEND_VERSION = 2

        private const val PREFERENCES = "enderslicer-non-planar-v2"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_MAX_SLOPE = "maximum-slope-degrees"
        private const val KEY_CLEARANCE_ANGLE = "clearance-angle-degrees"
        private const val KEY_CLEARANCE_HEIGHT = "clearance-height-mm"
        private const val KEY_MAXIMUM_LIFT = "maximum-lift-mm"
        private const val KEY_NOZZLE_ANGLE = "nozzle-angle-degrees"
        private const val KEY_NOZZLE_PROTRUSION = "nozzle-protrusion-mm"
        private const val KEY_BLOCK_WIDTH = "heating-block-width-mm"
        private const val KEY_BLOCK_DEPTH = "heating-block-depth-mm"
        private const val KEY_BLOCK_OFFSET_X = "heating-block-offset-x-mm"
        private const val KEY_BLOCK_OFFSET_Y = "heating-block-offset-y-mm"
        private const val KEY_MAX_Z_SPEED = "maximum-z-speed-mm-s"
        private const val KEY_PAUSE_AFTER_PROBE = "pause-after-probe"
        private const val KEY_CONFORMAL_SHELLS = "conformal-shell-layers"
    }
}
