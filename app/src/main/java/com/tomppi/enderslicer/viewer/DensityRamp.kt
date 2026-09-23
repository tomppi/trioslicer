package com.tomppi.enderslicer.viewer

/**
 * The viewer's density colormap: blue → cyan → yellow → red, the pinned
 * upstream viewer's own ramp (web/src/viewer/colormaps.ts). One function, so a
 * region's tint on the model and the swatch beside its number in the panel can
 * never disagree — a credibility matter for an FEA result view.
 *
 * Upstream scales the density by 0.8 before the ramp, because the sparse pattern
 * stops being reliable above it; the same scale is applied here.
 */
object DensityRamp {
    const val DENSITY_SCALE = 0.8

    /** RGB in 0..1 for a relative infill density (0..1). */
    fun color(densityFraction: Double): FloatArray = ramp(densityFraction / DENSITY_SCALE)

    /** RGB in 0..1 for a ramp parameter already in 0..1. */
    fun ramp(t: Double): FloatArray {
        val clamped = t.coerceIn(0.0, 1.0)
        return when {
            clamped < 0.33 -> floatArrayOf(0.15f, (0.3 + 1.8 * clamped).toFloat(), 0.9f)
            clamped < 0.66 -> floatArrayOf(
                (0.15 + 2.4 * (clamped - 0.33)).toFloat(),
                0.9f,
                (0.9 - 2.4 * (clamped - 0.33)).toFloat(),
            )

            else -> floatArrayOf(0.95f, (0.9 - 2.4 * (clamped - 0.66)).toFloat(), 0.1f)
        }
    }
}
