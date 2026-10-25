package com.tomppi.enderslicer.viewer

import kotlin.math.abs

/**
 * The speed ramp the Layers tab paints model paths with: slow is blue, fast is red, and the
 * middle of the ramp runs through cyan and green.
 *
 * The HSV section for 180 to 240 degrees - cyan towards blue - was written with its neighbour's
 * formula, so the hue a 200 mm/s path carries came out **magenta**, which is the colour the
 * support interface uses. On a real Cura slice every wall and infill line is 200 mm/s, so the
 * whole model looked as if it were labelled "support interface" while the counts beside it said
 * otherwise (the reported 586-layer slice: 728,314 model paths against 1,460 interface paths).
 */
internal object PreviewColors {
    /** Where a slow path starts on the hue wheel; the ramp stops at 0 degrees (red). */
    const val SLOW_HUE_DEGREES = 240f

    /** Saturating HSV value both ends share, so the colours match the feature palette's weight. */
    private const val CHROMA = 0.95f

    /** Red, green and blue for [speed] within a [minimum]..[maximum] ramp. */
    fun speed(speed: Float, minimum: Float, maximum: Float): FloatArray {
        val range = kotlin.math.max(maximum - minimum, 0.001f)
        val normalized = ((speed - minimum) / range).coerceIn(0f, 1f)
        val hue = SLOW_HUE_DEGREES * (1f - normalized)
        val section = (hue / 60f) % 6f
        val x = CHROMA * (1f - abs(section % 2f - 1f))
        val match = 1f - CHROMA
        return when {
            section < 1f -> floatArrayOf(CHROMA + match, x + match, match)
            section < 2f -> floatArrayOf(x + match, CHROMA + match, match)
            section < 3f -> floatArrayOf(match, CHROMA + match, x + match)
            section < 4f -> floatArrayOf(match, x + match, CHROMA + match)
            section < 5f -> floatArrayOf(x + match, match, CHROMA + match)
            else -> floatArrayOf(CHROMA + match, match, x + match)
        }
    }
}
