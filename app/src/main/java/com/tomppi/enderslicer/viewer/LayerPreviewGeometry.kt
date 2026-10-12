package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.engine.GcodeLayerPreview
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The layer view's geometry: every path of the layers in view, built once per selection.
 *
 * The paths of every layer stay in the parser's compact storage; this holds only the window
 * [LayerWindow] plans around the selected layer, so the vertex buffers never hold the whole
 * print. Nothing here samples: a path in the window is drawn, and the layer a device cannot
 * hold is refused by name with both numbers rather than thinned out.
 *
 * A driver may refuse an allocation the RAM figures said was fine, so the window is asked for
 * first and the path budget halves until something fits, remembering what fitted for the
 * session. It is deliberately free of GL calls, so a unit test can read the built geometry.
 */
internal class LayerPreviewGeometry(
    private val value: GcodeLayerPreview,
    private val capacity: PreviewCapacity,
) {
    private val costs = IntArray(value.layers.size) { value.layers[it].segmentCount }

    /** The layers whose paths are in the buffers. */
    var window: IntRange = IntRange.EMPTY
        private set

    var selectedLayerIndex: Int = 0
        private set

    var pathPositions: FloatBuffer? = null
        private set
    var pathColors: FloatBuffer? = null
        private set

    /** Vertex end of each layer in the window, indexed from the window's first layer. */
    var pathEnds: IntArray = IntArray(0)
        private set

    var ribbonPositions: FloatBuffer? = null
        private set
    var ribbonHalo: FloatBuffer? = null
        private set
    var ribbonColors: FloatBuffer? = null
        private set
    var ribbonVertexCount: Int = 0
        private set

    /** What the preview is holding, for the footer; the refusal when nothing fitted. */
    var status: String = ""
        private set

    /** True when the buffers hold the selected layer's geometry. */
    val ready: Boolean get() = pathPositions != null && !window.isEmpty()

    fun select(layerIndex: Int): Boolean {
        val bounded = if (value.layers.isEmpty()) 0 else layerIndex.coerceIn(value.layers.indices)
        val changed = selectedLayerIndex != bounded || window.isEmpty()
        val insideWindow = !window.isEmpty() && bounded in window
        selectedLayerIndex = bounded
        when {
            window.isEmpty() -> rebuild()
            // Moving inside the window only changes which layer is drawn in full: its ribbons
            // are rebuilt, the path buffers stay.
            insideWindow -> {
                ribbonPositions = null
                ribbonHalo = null
                ribbonColors = null
                ribbonVertexCount = 0
                allocateRibbons()
                buildRibbons()
                status = describe(
                    range = window,
                    segments = LayerWindow.segments(window) { costs[it] },
                    context = fullContext(),
                    refused = null,
                )
            }
            else -> rebuild()
        }
        return changed
    }

    /** First vertex of the given layer's paths inside the window's buffers. */
    fun vertexStart(layerIndex: Int): Int {
        if (window.isEmpty() || layerIndex <= window.first) return 0
        val slot = layerIndex - window.first - 1
        return pathEnds.getOrElse(slot) { pathEnds.lastOrNull() ?: 0 }
    }

    /** Vertex end of the given layer's paths inside the window's buffers. */
    fun vertexEnd(layerIndex: Int): Int {
        if (window.isEmpty()) return 0
        val slot = layerIndex - window.first
        return pathEnds.getOrElse(slot) { pathEnds.lastOrNull() ?: 0 }
    }

    /** Total vertices the path buffers hold. */
    fun pathVertexCount(): Int = pathEnds.lastOrNull() ?: 0

    private fun rebuild() {
        pathPositions = null
        pathColors = null
        pathEnds = IntArray(0)
        ribbonPositions = null
        ribbonHalo = null
        ribbonColors = null
        ribbonVertexCount = 0
        status = ""
        if (value.layers.isEmpty()) return

        // The driver may refuse what the figures promised, so try the whole window, then half
        // the path budget, down to the selected layer alone. Nothing is remembered between
        // selections: a smaller window is a planning outcome (a selection near either end of
        // the print, or the layer-count caps), and treating it as a ceiling for the session is
        // what collapsed Build-up to "1 layer in the buffers" while 6.9 GB were free. A refusal
        // that really is the driver's costs one failed allocation to find again, which is cheap.
        val context = fullContext()
        var refused: Int? = null
        for (attempt in BandAllocator.attempts(capacity.segments, LayerWindow.MINIMUM_SEGMENTS, Int.MAX_VALUE)) {
            val range = LayerWindow.plan(value.layers.size, { costs[it] }, selectedLayerIndex, attempt)
            val segments = LayerWindow.segments(range) { costs[it] }
            val granted = try {
                allocate(range, segments)
                true
            } catch (refusedAllocation: OutOfMemoryError) {
                false
            } catch (overflow: ArithmeticException) {
                false
            }
            if (!granted) {
                refused = maxOf(refused ?: 0, segments)
                continue
            }
            window = range
            buildPaths(range)
            buildRibbons()
            status = describe(range, segments, context, refused)
            return
        }
        val layer = value.layers[selectedLayerIndex]
        status = LayerWindow.refusal(
            layerNumber = layer.number,
            paths = layer.sourceSegmentCount,
            bytes = LayerWindow.bytes(layer.segmentCount),
        )
    }

    private fun allocate(range: IntRange, segments: Int) {
        pathPositions = allocateFloatBuffer(Math.multiplyExact(segments, LayerWindow.PATH_POSITION_FLOATS))
        pathColors = allocateFloatBuffer(Math.multiplyExact(segments, LayerWindow.PATH_COLOR_FLOATS))
        pathEnds = IntArray(range.count())
        allocateRibbons()
    }

    private fun allocateRibbons() {
        val ribbons = value.layers[selectedLayerIndex].segmentCount.coerceAtLeast(0)
        ribbonPositions = allocateFloatBuffer(Math.multiplyExact(ribbons, LayerWindow.RIBBON_POSITION_FLOATS))
        ribbonHalo = allocateFloatBuffer(Math.multiplyExact(ribbons, LayerWindow.RIBBON_POSITION_FLOATS))
        ribbonColors = allocateFloatBuffer(Math.multiplyExact(ribbons, LayerWindow.RIBBON_COLOR_FLOATS))
    }

    private fun buildPaths(range: IntRange) {
        val positions = pathPositions ?: return
        val colors = pathColors ?: return
        var vertices = 0
        var slot = 0
        for (layerIndex in range) {
            val layer = value.layers[layerIndex]
            val values = layer.segments
            var offset = 0
            while (offset + 5 < values.size) {
                val x1 = values[offset]
                val y1 = values[offset + 1]
                val x2 = values[offset + 2]
                val y2 = values[offset + 3]
                val speed = values[offset + 4]
                val feature = GcodeLayerPreview.Feature.fromCode(values[offset + 5].toInt())
                positions.put(x1).put(y1).put(layer.z)
                positions.put(x2).put(y2).put(layer.z)
                putPreviewColor(
                    buffer = colors,
                    feature = feature,
                    speed = speed,
                    minimum = value.minSpeedMmPerSecond,
                    maximum = value.maxSpeedMmPerSecond,
                    copies = 2,
                )
                vertices += 2
                offset += GcodeLayerPreview.VALUES_PER_SEGMENT
            }
            pathEnds[slot++] = vertices
        }
        positions.position(0)
        colors.position(0)
    }

    private fun buildRibbons() {
        val positions = ribbonPositions ?: return
        val halo = ribbonHalo ?: return
        val colors = ribbonColors ?: return
        val layer = value.layers[selectedLayerIndex]
        val values = layer.segments
        var offset = 0
        var drawn = 0
        while (offset + 5 < values.size) {
            val x1 = values[offset]
            val y1 = values[offset + 1]
            val x2 = values[offset + 2]
            val y2 = values[offset + 3]
            val speed = values[offset + 4]
            val feature = GcodeLayerPreview.Feature.fromCode(values[offset + 5].toInt())
            putRibbon(positions, x1, y1, x2, y2, layer.z + 0.035f, CORE_RIBBON_WIDTH)
            putRibbon(halo, x1, y1, x2, y2, layer.z + 0.025f, HALO_RIBBON_WIDTH)
            putPreviewColor(
                buffer = colors,
                feature = feature,
                speed = speed,
                minimum = value.minSpeedMmPerSecond,
                maximum = value.maxSpeedMmPerSecond,
                copies = 6,
            )
            drawn++
            offset += GcodeLayerPreview.VALUES_PER_SEGMENT
        }
        positions.position(0)
        halo.position(0)
        colors.position(0)
        ribbonVertexCount = drawn * 6
    }

    /** The window the context caps alone would give, whatever the path budget says. */
    private fun fullContext(): IntRange =
        LayerWindow.plan(value.layers.size, { costs[it] }, selectedLayerIndex, Int.MAX_VALUE)

    private fun describe(range: IntRange, segments: Int, context: IntRange, refused: Int?): String {
        val paths = if (range.isEmpty()) 0 else range.sumOf { value.layers[it].sourceSegmentCount }
        val layers = range.count()
        val text = StringBuilder()
        text.append(if (layers == 1) "1 layer" else "$layers layers")
        text.append(" in the buffers · ").append(segments).append(" runs for ").append(paths).append(" paths")
        // A reduced window says what it is missing and why, with both numbers.
        if (range.count() < context.count()) {
            val contextSegments = LayerWindow.segments(context) { costs[it] }
            text.append(" · the window holds ").append(segments).append(" of the ")
                .append(contextSegments).append(" runs the ").append(context.count()).append(" layers around it hold")
            if (refused != null) {
                text.append(", after the device refused ").append(refused)
            }
        }
        if (value.folded) {
            text.append(" · folded across the print (").append(value.totalSegmentCount)
                .append(" paths, ").append(value.storedSegmentCount).append(" runs)")
        }
        text.append(" · ").append(capacity.note)
        return text.toString()
    }

    private fun allocateFloatBuffer(size: Int): FloatBuffer {
        require(size >= 0) { "Float buffer size cannot be negative" }
        return ByteBuffer.allocateDirect(Math.multiplyExact(size, Float.SIZE_BYTES))
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    private fun putRibbon(
        buffer: FloatBuffer,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        z: Float,
        width: Float,
    ) {
        val dx = x2 - x1
        val dy = y2 - y1
        val length = sqrt(dx * dx + dy * dy).coerceAtLeast(0.0001f)
        val nx = -dy / length * width * 0.5f
        val ny = dx / length * width * 0.5f
        val ax = x1 + nx
        val ay = y1 + ny
        val bx = x1 - nx
        val by = y1 - ny
        val cx = x2 - nx
        val cy = y2 - ny
        val dx2 = x2 + nx
        val dy2 = y2 + ny
        buffer.put(ax).put(ay).put(z)
        buffer.put(bx).put(by).put(z)
        buffer.put(cx).put(cy).put(z)
        buffer.put(ax).put(ay).put(z)
        buffer.put(cx).put(cy).put(z)
        buffer.put(dx2).put(dy2).put(z)
    }

    private fun putPreviewColor(
        buffer: FloatBuffer,
        feature: GcodeLayerPreview.Feature,
        speed: Float,
        minimum: Float,
        maximum: Float,
        copies: Int,
    ) {
        val red: Float
        val green: Float
        val blue: Float
        when (feature) {
            GcodeLayerPreview.Feature.SUPPORT -> {
                red = 0.15f; green = 0.95f; blue = 1f
            }
            GcodeLayerPreview.Feature.SUPPORT_INTERFACE -> {
                red = 1f; green = 0.25f; blue = 0.9f
            }
            GcodeLayerPreview.Feature.ADHESION -> {
                red = 1f; green = 0.62f; blue = 0.08f
            }
            GcodeLayerPreview.Feature.ARC_OVERHANG -> {
                red = 0.72f; green = 0.38f; blue = 1f
            }
            GcodeLayerPreview.Feature.WAVE_OVERHANG -> {
                red = 0.12f; green = 0.92f; blue = 0.82f
            }
            else -> {
                val range = kotlin.math.max(maximum - minimum, 0.001f)
                val normalized = ((speed - minimum) / range).coerceIn(0f, 1f)
                val hue = 240f * (1f - normalized)
                val chroma = 0.95f
                val hueSection = (hue / 60f) % 6f
                val x = chroma * (1f - abs(hueSection % 2f - 1f))
                val match = 1f - chroma
                when {
                    hueSection < 1f -> {
                        red = chroma + match; green = x + match; blue = match
                    }
                    hueSection < 2f -> {
                        red = x + match; green = chroma + match; blue = match
                    }
                    hueSection < 3f -> {
                        red = match; green = chroma + match; blue = x + match
                    }
                    hueSection < 4f -> {
                        red = match; green = x + match; blue = chroma + match
                    }
                    hueSection < 5f -> {
                        red = x + match; green = match; blue = chroma + match
                    }
                    else -> {
                        red = chroma + match; green = match; blue = x + match
                    }
                }
            }
        }
        repeat(copies) {
            buffer.put(red).put(green).put(blue).put(1f)
        }
    }

    companion object {
        const val CORE_RIBBON_WIDTH = 0.38f
        const val HALO_RIBBON_WIDTH = 0.96f
    }
}
