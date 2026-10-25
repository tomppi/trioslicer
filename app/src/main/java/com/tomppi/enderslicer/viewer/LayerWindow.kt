package com.tomppi.enderslicer.viewer

import java.util.Locale
import kotlin.math.max

/**
 * The layers the layer view holds geometry for at once, planned from the selected layer and the
 * style the view is in.
 *
 * The current-layer style only ever shows one layer, so it holds that layer and the few around
 * it: eight under and two above, so stepping through the print does not rebuild every time. The
 * build-up style draws the whole model from the bed up to the scrub, so its window is layer zero
 * to the selected layer - the whole stack. Both are windows, never samples: every layer in the
 * range is drawn.
 *
 * Both windows are planned against the device profile's [PreviewCapacity.segments], converted
 * for the build-up style to what its vertex buffers cost a path ([vertexPathBudget]). A budget
 * that cannot hold the whole stack leaves the selected layer in the window, holds the layers
 * under it that do fit, and names the reduction with both numbers; nothing here samples.
 */
internal object LayerWindow {
    /** Layers under the selection the current-layer style keeps as context. */
    const val CONTEXT_BELOW = 8

    /** Layers above the selection, so stepping up does not rebuild the current-layer window. */
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
     * The current-layer window around [anchor], grown outwards one layer at a time from
     * [segmentsOf] while the context fits [segmentBudget]. The selected layer is always in the
     * window, whatever it costs: a layer too large to hold is named, never sampled.
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
                if (used.toLong() + cost > segmentBudget) break
                first--
                used += cost
            }
            if (step <= contextAbove && last < layerCount - 1) {
                val cost = segmentsOf(last + 1).coerceAtLeast(0)
                if (used.toLong() + cost > segmentBudget) break
                last++
                used += cost
            }
            step++
        }
        return first..last
    }

    /**
     * The build-up window for [anchor]: layer zero to the selected layer - the whole model under
     * the scrub - while it fits [segmentBudget]. A budget too small for the whole stack holds the
     * layers directly under the selection that do fit, so the top of the build stays continuous
     * with the scrub; the selected layer is never dropped. It is planned from the bed upward so
     * growing the selection upward only ever extends the same stack.
     */
    fun planBuildUp(
        layerCount: Int,
        segmentsOf: (Int) -> Int,
        anchor: Int,
        segmentBudget: Int,
    ): IntRange {
        if (layerCount <= 0) return IntRange.EMPTY
        val selected = anchor.coerceIn(0, layerCount - 1)
        var whole = 0L
        for (index in 0..selected) {
            whole += segmentsOf(index).coerceAtLeast(0)
            if (whole > segmentBudget) break
        }
        if (whole <= segmentBudget) return 0..selected
        var first = selected
        var used = segmentsOf(selected).coerceAtLeast(0).toLong()
        while (first > 0) {
            val cost = segmentsOf(first - 1).coerceAtLeast(0).toLong()
            if (used + cost > segmentBudget) break
            first--
            used += cost
        }
        return first..selected
    }

    /** The whole build-up stack to [anchor]: layer zero to the selected layer. */
    fun stackRange(layerCount: Int, anchor: Int): IntRange {
        if (layerCount <= 0) return IntRange.EMPTY
        return 0..anchor.coerceIn(0, layerCount - 1)
    }

    /**
     * The paths a window's vertex buffers may hold for this device profile.
     *
     * [PreviewCapacity.segments] is the profile's heap share counted in stored paths at
     * [PreviewMemoryPolicy.LAYER_SEGMENT_BYTES] each; the window's positions and colours cost
     * [PATH_POSITION_FLOATS] + [PATH_COLOR_FLOATS] floats a path, so that same share holds this
     * many of them. The build-up stack is planned against this figure, not against a layer count.
     */
    fun vertexPathBudget(capacitySegments: Int): Int {
        val heapBytes = capacitySegments.coerceAtLeast(0).toLong() *
            PreviewMemoryPolicy.LAYER_SEGMENT_BYTES
        val pathBytes = (PATH_POSITION_FLOATS + PATH_COLOR_FLOATS).toLong() * 4L
        return (heapBytes / pathBytes)
            .coerceIn(MINIMUM_SEGMENTS.toLong(), Int.MAX_VALUE.toLong())
            .toInt()
    }

    /** The stored paths a window holds, which is what its buffers have to take. */
    fun segments(range: IntRange, segmentsOf: (Int) -> Int): Int {
        if (range.isEmpty()) return 0
        var total = 0L
        for (index in range) {
            total += segmentsOf(index).coerceAtLeast(0)
            if (total > Int.MAX_VALUE) return Int.MAX_VALUE
        }
        return total.toInt()
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
