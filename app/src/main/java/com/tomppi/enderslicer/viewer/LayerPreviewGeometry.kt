package com.tomppi.enderslicer.viewer

import com.tomppi.enderslicer.engine.GcodeLayerPreview
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The layer view's geometry: every path of the layers in view, built once per window.
 *
 * The paths of every layer stay in the parser's compact storage; this holds the window
 * [LayerWindow] plans for the style in view - the selected layer and its context for the
 * current-layer style, and the whole stack from the bed to the scrub for the build-up style - so
 * the vertex buffers never hold the whole print. Nothing here samples: a path in the window is
 * drawn, and a layer a device cannot hold is refused by name with both numbers rather than
 * thinned out.
 *
 * A driver may refuse an allocation the RAM figures said was fine, so the window is asked for
 * first and the path budget halves until something fits. A build-up window only ever grows as
 * the scrub moves up, so moving inside it rebuilds just the selected layer's ribbons, and
 * growing it by a layer appends that layer instead of rebuilding the stack. It is deliberately
 * free of GL calls, so a unit test can read the built geometry.
 */
internal class LayerPreviewGeometry(
    private val value: GcodeLayerPreview,
    private val capacity: PreviewCapacity,
    style: LayerPreviewStyle = LayerPreviewStyle.CURRENT_LAYER,
) {
    private val costs = IntArray(value.layers.size) { value.layers[it].segmentCount }

    /** The style whose window this is: the build-up window is the whole stack to the scrub. */
    var style: LayerPreviewStyle = style
        private set

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

    /**
     * Paths the allocated buffers can take. A build-up window keeps room past its own stack so
     * the next layers the scrub reaches are appended; it may be larger than the window's own
     * segments, and the footer always names the window's own figures.
     */
    private var pathCapacity = 0

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

    /**
     * Switches the window's style. The current-layer window is a context band and the build-up
     * window is the whole stack, so a style change re-plans - and rebuilds - the window.
     */
    fun setStyle(next: LayerPreviewStyle): Boolean {
        if (style == next) return false
        style = next
        if (value.layers.isNotEmpty()) rebuild()
        return true
    }

    fun select(layerIndex: Int): Boolean {
        val bounded = if (value.layers.isEmpty()) 0 else layerIndex.coerceIn(value.layers.indices)
        val changed = selectedLayerIndex != bounded || window.isEmpty()
        selectedLayerIndex = bounded
        when {
            window.isEmpty() -> rebuild()
            style == LayerPreviewStyle.BUILD_UP -> selectBuildUp(bounded)
            // Moving inside the current-layer window only changes which layer is drawn in full:
            // its ribbons are rebuilt, the path buffers stay.
            bounded in window -> rebuildRibbons()
            else -> rebuild()
        }
        return changed
    }

    /**
     * Build-up holds layer zero to the selection. Moving down inside that range only changes
     * which layer is drawn in full; moving up extends the stack by the layers it reaches, which
     * the held buffers append when they have the room. Only a range the held window cannot
     * extend - a reduced window, or one whose buffers are full - rebuilds.
     */
    private fun selectBuildUp(bounded: Int) {
        when {
            bounded in window -> rebuildRibbons()
            bounded > window.last -> if (!growTo(bounded)) rebuild()
            else -> rebuild()
        }
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

    /**
     * Extends the held build-up window up to [bounded] without rebuilding the stack under it.
     *
     * The layers under the held window are already in the buffers; only the range from the held
     * top to the new top has to be written, into the room [pathCapacity] kept for exactly this.
     * A buffer that is full is reallocated and its paths copied across, which still beats
     * rebuilding every layer from the bed; when even that allocation is refused the caller
     * rebuilds, so the driver-refusal halving still applies.
     */
    private fun growTo(bounded: Int): Boolean {
        val planned = LayerWindow.planBuildUp(
            value.layers.size,
            { costs[it] },
            bounded,
            windowBudget(),
        )
        if (planned.first != window.first || planned.last <= window.last) return false
        val segments = LayerWindow.segments(planned) { costs[it] }
        if (segments <= 0) return false
        val held = window
        val oldVertices = pathVertexCount()
        if (segments > pathCapacity && !reallocate(segments, oldVertices)) return false
        pathEnds = pathEnds.copyOf(planned.count())
        writePaths(
            range = (held.last + 1)..planned.last,
            firstSlot = held.count(),
            firstVertex = oldVertices,
        )
        window = planned
        rebuildRibbons()
        return true
    }

    /** Grows the path buffers to hold [segments] paths, keeping the [vertices] already built. */
    private fun reallocate(segments: Int, vertices: Int): Boolean {
        val oldPositions = pathPositions
        val oldColors = pathColors
        // The roomy allocation is what keeps the next scrub steps appending; if the driver
        // refuses it, the window's own size is tried before the caller falls back to a rebuild.
        for (capacity in intArrayOf(bufferCapacity(segments, windowBudget()), segments)) {
            try {
                pathPositions = allocateFloatBuffer(
                    Math.multiplyExact(capacity, LayerWindow.PATH_POSITION_FLOATS),
                )
                pathColors = allocateFloatBuffer(
                    Math.multiplyExact(capacity, LayerWindow.PATH_COLOR_FLOATS),
                )
                copyFloats(oldPositions, pathPositions, vertices * 3)
                copyFloats(oldColors, pathColors, vertices * 4)
                pathCapacity = capacity
                return true
            } catch (refusedAllocation: OutOfMemoryError) {
                pathPositions = oldPositions
                pathColors = oldColors
            } catch (overflow: ArithmeticException) {
                pathPositions = oldPositions
                pathColors = oldColors
            }
        }
        return false
    }

    private fun copyFloats(from: FloatBuffer?, to: FloatBuffer?, count: Int) {
        if (from == null || to == null || count <= 0) return
        val source = from.duplicate()
        source.position(0)
        source.limit(min(count.toLong(), source.capacity().toLong()).toInt())
        to.position(0)
        to.put(source)
        to.position(0)
    }

    private fun rebuild() {
        releaseBuffers()
        status = ""
        if (value.layers.isEmpty()) return

        // The driver may refuse what the figures promised, so try the whole window, then half
        // the path budget, down to the selected layer alone. Nothing is remembered between
        // selections: a smaller window is a planning outcome (a selection near either end of
        // the print, or the layer-count caps), and treating it as a ceiling for the session is
        // what collapsed Build-up to "1 layer in the buffers" while 6.9 GB were free. A refusal
        // that really is the driver's costs one failed allocation to find again, which is cheap.
        val planned = fullWindow()
        var refused: Int? = null
        for (attempt in BandAllocator.attempts(windowBudget(), LayerWindow.MINIMUM_SEGMENTS, Int.MAX_VALUE)) {
            val range = planWindow(attempt)
            val segments = LayerWindow.segments(range) { costs[it] }
            val granted = try {
                allocate(range, segments)
                true
            } catch (refusedAllocation: OutOfMemoryError) {
                releaseBuffers()
                false
            } catch (overflow: ArithmeticException) {
                releaseBuffers()
                false
            }
            if (!granted) {
                refused = maxOf(refused ?: 0, segments)
                continue
            }
            window = range
            writePaths(range, firstSlot = 0, firstVertex = 0)
            buildRibbons()
            status = describe(range, segments, planned, refused)
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
        // The roomy allocation is what lets the build-up stack grow by a layer without a
        // rebuild; if the driver refuses it, the window's own size is tried first and only a
        // refusal of that halves the window.
        val roomy = bufferCapacity(segments, windowBudget())
        try {
            allocateBuffers(range, segments, roomy)
        } catch (refusedAllocation: OutOfMemoryError) {
            releaseBuffers()
            if (roomy == segments) throw refusedAllocation
            allocateBuffers(range, segments, segments)
        } catch (overflow: ArithmeticException) {
            releaseBuffers()
            if (roomy == segments) throw overflow
            allocateBuffers(range, segments, segments)
        }
    }

    private fun allocateBuffers(range: IntRange, segments: Int, bufferSegments: Int) {
        pathPositions = allocateFloatBuffer(
            Math.multiplyExact(bufferSegments, LayerWindow.PATH_POSITION_FLOATS),
        )
        pathColors = allocateFloatBuffer(
            Math.multiplyExact(bufferSegments, LayerWindow.PATH_COLOR_FLOATS),
        )
        pathEnds = IntArray(range.count())
        pathCapacity = bufferSegments
        allocateRibbons()
    }

    /**
     * Paths to allocate for a window of [segments]: the window itself, plus a quarter of it in
     * the build-up style so the next layers the scrub reaches append instead of rebuilding the
     * stack. Never more than the budget that let this window be planned.
     */
    private fun bufferCapacity(segments: Int, budget: Int): Int {
        if (style != LayerPreviewStyle.BUILD_UP) return segments
        val grown = (segments.toLong() * BUILD_UP_HEADROOM_NUMERATOR / BUILD_UP_HEADROOM_DENOMINATOR)
            .coerceAtLeast(segments.toLong())
            .coerceAtMost(maxOf(segments.toLong(), budget.toLong()))
            .coerceAtMost(MAX_PATH_SEGMENTS.toLong())
        return grown.toInt()
    }

    private fun releaseBuffers() {
        pathPositions = null
        pathColors = null
        pathEnds = IntArray(0)
        pathCapacity = 0
        ribbonPositions = null
        ribbonHalo = null
        ribbonColors = null
        ribbonVertexCount = 0
    }

    private fun allocateRibbons() {
        val ribbons = value.layers[selectedLayerIndex].segmentCount.coerceAtLeast(0)
        ribbonPositions = allocateFloatBuffer(Math.multiplyExact(ribbons, LayerWindow.RIBBON_POSITION_FLOATS))
        ribbonHalo = allocateFloatBuffer(Math.multiplyExact(ribbons, LayerWindow.RIBBON_POSITION_FLOATS))
        ribbonColors = allocateFloatBuffer(Math.multiplyExact(ribbons, LayerWindow.RIBBON_COLOR_FLOATS))
    }

    /**
     * Writes the paths of [range] into the buffers, starting at [firstSlot] of the window and
     * vertex [firstVertex]. A rebuild writes the whole window from slot zero; a grown build-up
     * window writes only the layers it added after the ones already there.
     */
    private fun writePaths(range: IntRange, firstSlot: Int, firstVertex: Int) {
        val positions = pathPositions ?: return
        val colors = pathColors ?: return
        positions.position(firstVertex * 3)
        colors.position(firstVertex * 4)
        var vertices = firstVertex
        var slot = firstSlot
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

    /** The selected layer's own ribbons, the only ones drawn in full; rebuilt per selection. */
    private fun rebuildRibbons() {
        ribbonPositions = null
        ribbonHalo = null
        ribbonColors = null
        ribbonVertexCount = 0
        if (window.isEmpty()) {
            status = ""
            return
        }
        allocateRibbons()
        buildRibbons()
        status = describe(window, LayerWindow.segments(window) { costs[it] }, fullWindow(), refused = null)
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

    /** The paths this style's window is planned against, from the device profile. */
    private fun windowBudget(): Int =
        if (style == LayerPreviewStyle.BUILD_UP) {
            LayerWindow.vertexPathBudget(capacity.segments)
        } else {
            capacity.segments
        }

    /** The window the style wants when the path budget is not the limit. */
    private fun fullWindow(): IntRange {
        if (value.layers.isEmpty()) return IntRange.EMPTY
        return if (style == LayerPreviewStyle.BUILD_UP) {
            LayerWindow.stackRange(value.layers.size, selectedLayerIndex)
        } else {
            LayerWindow.plan(value.layers.size, { costs[it] }, selectedLayerIndex, Int.MAX_VALUE)
        }
    }

    private fun planWindow(budget: Int): IntRange =
        if (style == LayerPreviewStyle.BUILD_UP) {
            LayerWindow.planBuildUp(value.layers.size, { costs[it] }, selectedLayerIndex, budget)
        } else {
            LayerWindow.plan(value.layers.size, { costs[it] }, selectedLayerIndex, budget)
        }

    private fun describe(range: IntRange, segments: Int, planned: IntRange, refused: Int?): String {
        val paths = if (range.isEmpty()) 0 else range.sumOf { value.layers[it].sourceSegmentCount }
        val layers = range.count()
        val text = StringBuilder()
        text.append(if (layers == 1) "1 layer" else "$layers layers")
        text.append(" in the buffers · ").append(segments).append(" runs for ").append(paths).append(" paths")
        // A reduced window says what it is missing and why, with both numbers.
        if (style == LayerPreviewStyle.BUILD_UP) {
            // The build-up stack starts at the bed. A window that does not is a reduced one:
            // it names the runs it holds against the runs of the whole stack to its top, so a
            // device that cannot take the stack is never a silently short one.
            if (!range.isEmpty() && range.first > 0) {
                val stack = LayerWindow.stackRange(value.layers.size, range.last)
                val stackSegments = LayerWindow.segments(stack) { costs[it] }
                text.append(" · the window holds ").append(segments).append(" of the ")
                    .append(stackSegments).append(" runs the whole stack to this layer holds (")
                    .append(range.count()).append(" of ").append(stack.count()).append(" layers)")
                if (refused != null) {
                    text.append(", after the device refused ").append(refused)
                }
            }
        } else if (range.count() < planned.count()) {
            val plannedSegments = LayerWindow.segments(planned) { costs[it] }
            text.append(" · the window holds ").append(segments).append(" of the ")
                .append(plannedSegments).append(" runs the ").append(planned.count())
                .append(" layers around it hold")
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
                        red = x + match; green = match; blue = chroma + match
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

        /** Build-up buffers keep a quarter more room than the window, for the growing stack. */
        private const val BUILD_UP_HEADROOM_NUMERATOR = 5L
        private const val BUILD_UP_HEADROOM_DENOMINATOR = 4L

        /** Paths whose colour buffer still fits an Int byte count; far above any device budget. */
        private const val MAX_PATH_SEGMENTS = 67_000_000
    }
}
