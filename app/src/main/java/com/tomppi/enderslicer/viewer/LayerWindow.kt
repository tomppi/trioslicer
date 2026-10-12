package com.tomppi.enderslicer.viewer

import kotlin.math.max
import java.util.Locale

/**
 * The layers the layer view holds geometry for at once: the selected layer, the ones under it
 * that the build-up style draws as context, and a couple above so stepping up does not rebuild.
 *
 * A layer view only ever shows one layer, so there is no reason to hold the whole print: the
 * paths of every layer stay in the Java heap (the parser folds them to fit the device's share),
 * and the vertex buffers hold this window. The window never drops the selected layer - that is
 * what the refusal names when even one layer's geometry will not allocate.
 */
internal object LayerWindow {
    /** Layers under the selection the build-up style shows as context. */
    const val CONTEXT_BELOW = 8

    /** Layers above the selection, so stepping up does not rebuild the window. */
    const val CONTEXT_ABOVE = 2

    /** The floor a refused allocation halves to: the selected layer on its own. */
    const val MINIMUM_SEGMENTS = 1

    /** Two coloured line vertices per path, in the buffers. */
    const val PATH_POSITION_FLOATS = 6
    const val PATH_COLOR_FLOATS = 8

    /** Core and halo positions plus six RGBA colours per ribbon path. */
    const val RIBBON_POSITION_FLOATS = 18
    const val RIBBON_COLOR_FLOATS = 24

    /**
     * The window around [anchor], grown outwards one layer at a time from [segmentsOf] while the
     * context fits [segmentBudget]. The selected layer is always in the window, whatever it
     * costs: a layer too large to hold is named, never sampled.
     */
    fun plan(
        layerCount: Int,
        segmentsOf: (Int) -> Int,
        anchor: Int,
        segmentBudget: Int,
        contextBelow: Int = CONTEXT_BELOW,
        contextAbove: Int = CONTEXT_ABOVE,
    ): IntRange {
        if (layerCount <= 0) return IntRange.EMPTY
        val selected = anchor.coerceIn(0, layerCount - 1)
        var first = selected
        var last = selected
        var used = segmentsOf(selected).coerceAtLeast(0)
        var step = 1
        while (step <= max(contextBelow, contextAbove)) {
            if (step <= contextBelow && first > 0) {
                val cost = segmentsOf(first - 1).coerceAtLeast(0)
                if (used + cost > segmentBudget) break
                first--
                used += cost
            }
            if (step <= contextAbove && last < layerCount - 1) {
                val cost = segmentsOf(last + 1).coerceAtLeast(0)
                if (used + cost > segmentBudget) break
                last++
                used += cost
            }
            step++
        }
        return first..last
    }

    /** The stored paths a window holds, which is what its buffers have to take. */
    fun segments(range: IntRange, segmentsOf: (Int) -> Int): Int {
        if (range.isEmpty()) return 0
        var total = 0
        for (index in range) {
            total += segmentsOf(index).coerceAtLeast(0)
            if (total < 0) return Int.MAX_VALUE
        }
        return total
    }

    /** The lines a window's paths cost in the vertex buffers. */
    fun bytes(segments: Int): Long = segments.toLong() * (PATH_POSITION_FLOATS + PATH_COLOR_FLOATS) * 4L

    /** What the layer view says when even one layer's geometry will not allocate. */
    fun refusal(layerNumber: Int, paths: Int, bytes: Long): String =
        "The device refused the layer preview's buffers: layer " + layerNumber + " holds " + paths +
            " paths (" + megabytes(bytes) + ") and even that would not allocate"

    fun megabytes(bytes: Long): String =
        String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))
}
