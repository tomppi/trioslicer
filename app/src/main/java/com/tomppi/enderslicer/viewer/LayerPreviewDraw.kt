package com.tomppi.enderslicer.viewer

import kotlin.math.max

/**
 * The path vertices the layer view's renderer draws, kept apart from the GL calls so a unit test
 * can count the layers a draw covers against the window that was planned.
 *
 * The current-layer style draws the selected layer, in full. The build-up style first draws the
 * stack the window holds under the selection - every layer from the bed up, faintly - and then
 * the selected layer in full over it, so the model reads as the whole build to the scrub.
 */
internal object LayerPreviewDraw {
    /** A contiguous vertex range of the path buffers, and the layers it stands for. */
    data class Paths(val firstVertex: Int, val vertexCount: Int, val layers: IntRange) {
        /** Layers the range covers, start to end. */
        val layerCount: Int get() = if (layers.isEmpty()) 0 else layers.last - layers.first + 1

        val isEmpty: Boolean get() = vertexCount <= 0 || layers.isEmpty()
    }

    /** The stack under the selection: every layer the window holds below it, from the bed up. */
    fun buildUp(geometry: LayerPreviewGeometry, style: LayerPreviewStyle): Paths {
        if (style != LayerPreviewStyle.BUILD_UP) return Paths(0, 0, IntRange.EMPTY)
        val window = geometry.window
        if (window.isEmpty()) return Paths(0, 0, IntRange.EMPTY)
        val first = window.first
        val last = minOf(geometry.selectedLayerIndex - 1, window.last)
        if (last < first) return Paths(0, 0, IntRange.EMPTY)
        val firstVertex = geometry.vertexStart(first)
        val vertexCount = max(geometry.vertexEnd(last) - firstVertex, 0)
        return Paths(firstVertex, vertexCount, first..last)
    }

    /** The selected layer's own paths, drawn in full over the stack. */
    fun selected(geometry: LayerPreviewGeometry): Paths {
        val window = geometry.window
        if (window.isEmpty()) return Paths(0, 0, IntRange.EMPTY)
        val selected = geometry.selectedLayerIndex.coerceIn(window.first, window.last)
        val firstVertex = geometry.vertexStart(selected)
        val vertexCount = max(geometry.vertexEnd(selected) - firstVertex, 0)
        return Paths(firstVertex, vertexCount, selected..selected)
    }
}
