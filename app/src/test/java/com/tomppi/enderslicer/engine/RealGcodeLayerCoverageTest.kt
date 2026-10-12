package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.viewer.PreviewMemoryPolicy
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * What the layer preview's cap does to a real print, measured.
 *
 * The old parser kept one path in three spread across the whole print once a file passed
 * 800,000 segments, so every layer of the reported 2.4-million-segment PrusaSlicer print came
 * out with most of its paths missing. The new parser folds instead: the numbers below are the
 * source paths of each layer, the paths the old sampler would have kept of them, and the runs
 * the fold stores - which cover every path and every millimetre of the layer.
 *
 * Run with -DrealPrusaGcode=<file> / -DrealGcode=<file> (or the REAL_PRUSA_GCODE / REAL_GCODE
 * environment variables) to measure a real file; without one the test is skipped.
 */
class RealGcodeLayerCoverageTest {

    private val realCura = File(System.getProperty("realGcode") ?: System.getenv("REAL_GCODE").orEmpty())
    private val realPrusa = File(System.getProperty("realPrusaGcode") ?: System.getenv("REAL_PRUSA_GCODE").orEmpty())

    @Test
    fun theReportedPrusaPrintKeepsEveryPathOfTheLayerInView() {
        assumeTrue("real prusa gcode not available", realPrusa.isFile)
        report(realPrusa, GcodeDialect.PRUSA, "PrusaSlicer")
    }

    @Test
    fun theReportedCuraPrintKeepsEveryPathOfTheLayerInView() {
        assumeTrue("real cura gcode not available", realCura.isFile)
        report(realCura, GcodeDialect.CURA, "Cura")
    }

    private fun report(file: File, dialect: GcodeDialect, label: String) {
        // The whole print, unfolded: every path of every layer.
        val whole = GcodeLayerPreviewParser.parse(file, dialect, Int.MAX_VALUE)
        // The same file at the conservative budget the low-memory profile keeps.
        val capped = GcodeLayerPreviewParser.parse(
            file,
            dialect,
            PreviewMemoryPolicy.LOW_MEMORY_SEGMENTS,
        )
        val source = whole.totalSegmentCount
        println(label + ": " + whole.layers.size + " layers, " + source + " source paths")
        println("  capped parse: folded=" + capped.folded + ", stored runs " + capped.storedSegmentCount)
        println(
            "  the old sampler would have drawn " +
                whole.layers.sumOf { oldSamplerWouldKeep(it, whole) } + " of " + source + " paths",
        )

        var globalStart = 0
        var shortestRatio = 1.0
        var worstLayer = 0
        whole.layers.forEachIndexed { index, sourceLayer ->
            val layer = capped.layers.getOrNull(index)
            if (layer != null && sourceLayer.segmentCount > 0) {
                val sourceLength = length(sourceLayer)
                val storedLength = length(layer)
                val ratio = if (sourceLength > 0.0) storedLength / sourceLength else 1.0
                if (ratio < shortestRatio) {
                    shortestRatio = ratio
                    worstLayer = layer.number
                }
            }
            globalStart += sourceLayer.segmentCount
        }
        println(
            "  shortest stored/source length ratio " +
                String.format(Locale.US, "%.4f", shortestRatio) + " (layer " + worstLayer + ")",
        )

        // The layer the report was about: the densest one, where the loss showed most.
        val densest = capped.layers.maxByOrNull { it.sourceSegmentCount } ?: error("no layers")
        val densestIndex = capped.layers.indexOf(densest)
        val densestStart = whole.layers.take(densestIndex).sumOf { it.segmentCount }
        println(
            "  densest layer " + (densestIndex + 1) + "/" + capped.layers.size +
                " (file layer " + densest.number + "): " + densest.sourceSegmentCount + " source paths, " +
                oldSamplerWouldKeep(densest, capped, densestStart) + " kept by the old sampler, " +
                densest.segmentCount + " runs stored now",
        )
        if (source > PreviewMemoryPolicy.LOW_MEMORY_SEGMENTS) {
            writeShots(
                sourceLayer = whole.layers[densestIndex],
                storedLayer = densest,
                startIndex = densestStart,
                limit = PreviewMemoryPolicy.LOW_MEMORY_SEGMENTS,
                sourcePaths = source,
                file = file,
            )
        }

        capped.layers.forEachIndexed { index, layer ->
            val sourceLayer = whole.layers.getOrNull(index) ?: return@forEachIndexed
            assertTrue(
                "layer " + layer.number + " lost length: the fold may only merge",
                length(layer) >= length(sourceLayer) * 0.98 - 1.0,
            )
            assertTrue(
                "layer " + layer.number + " stored more runs than paths",
                layer.segmentCount <= layer.sourceSegmentCount,
            )
        }
        if (source <= PreviewMemoryPolicy.LOW_MEMORY_SEGMENTS) {
            // A file under the budget is stored path for path: nothing merges, nothing drops.
            assertTrue("under the budget nothing folds", !capped.folded)
            assertEquals(source, capped.storedSegmentCount)
            assertTrue(capped.layers.all { it.segmentCount == it.sourceSegmentCount })
        }
    }

    /**
     * Two pictures of the densest layer: the paths the old sampler would have drawn of it, and
     * the runs the fold stores now. Same layer, same scale, so the missing third is visible.
     */
    private fun writeShots(
        sourceLayer: GcodeLayerPreview.Layer,
        storedLayer: GcodeLayerPreview.Layer,
        startIndex: Int,
        limit: Int,
        sourcePaths: Int,
        file: File,
    ) {
        val directory = File(
            System.getProperty("layerShots") ?: System.getenv("LAYER_SHOTS") ?: "build/layer-coverage",
        ).apply { mkdirs() }
        val base = file.nameWithoutExtension.take(24)
        val before = File(directory, base + "-layer" + sourceLayer.number + "-before.png")
        val after = File(directory, base + "-layer" + sourceLayer.number + "-after.png")
        drawLayer(sourceLayer.segments, before) { index ->
            oldSamplerWouldKeep(index + 1, sourcePaths, startIndex, limit) >
                oldSamplerWouldKeep(index, sourcePaths, startIndex, limit)
        }
        drawLayer(storedLayer.segments, after) { true }
        println("  wrote " + before.absolutePath + " and " + after.absolutePath)
    }

    private fun drawLayer(segments: FloatArray, file: File, keep: (Int) -> Boolean) {
        System.setProperty("java.awt.headless", "true")
        val size = 1_000
        val image = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = java.awt.Color(14, 16, 20)
        graphics.fillRect(0, 0, size, size)
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var offset = 0
        while (offset + 5 < segments.size) {
            minX = minOf(minX, segments[offset], segments[offset + 2])
            maxX = maxOf(maxX, segments[offset], segments[offset + 2])
            minY = minOf(minY, segments[offset + 1], segments[offset + 3])
            maxY = maxOf(maxY, segments[offset + 1], segments[offset + 3])
            offset += GcodeLayerPreview.VALUES_PER_SEGMENT
        }
        val span = maxOf(maxX - minX, maxY - minY, 1f)
        val margin = 40f
        val scale = (size - margin * 2f) / span
        graphics.color = java.awt.Color(120, 205, 255)
        graphics.stroke = java.awt.BasicStroke(1.2f)
        var index = 0
        offset = 0
        while (offset + 5 < segments.size) {
            if (keep(index)) {
                val x1 = margin + (segments[offset] - minX) * scale
                val y1 = margin + (segments[offset + 1] - minY) * scale
                val x2 = margin + (segments[offset + 2] - minX) * scale
                val y2 = margin + (segments[offset + 3] - minY) * scale
                graphics.drawLine(x1.toInt(), y1.toInt(), x2.toInt(), y2.toInt())
            }
            index++
            offset += GcodeLayerPreview.VALUES_PER_SEGMENT
        }
        graphics.dispose()
        javax.imageio.ImageIO.write(image, "png", file)
    }

    private fun length(layer: GcodeLayerPreview.Layer): Double {
        var total = 0.0
        val values = layer.segments
        var offset = 0
        while (offset + 5 < values.size) {
            val dx = values[offset + 2] - values[offset]
            val dy = values[offset + 3] - values[offset + 1]
            total += sqrt(dx * dx + dy * dy).toDouble()
            offset += GcodeLayerPreview.VALUES_PER_SEGMENT
        }
        return total
    }

    /**
     * How many paths the old cap kept of a layer: it stepped the whole print with
     * shouldRetainSegment, which advances the kept count by
     * (index + 1) * limit / count - index * limit / count per source path. The index is the
     * path's position in the print, so a layer's own offset matters.
     */
    private fun oldSamplerWouldKeep(
        layer: GcodeLayerPreview.Layer,
        whole: GcodeLayerPreview,
        startIndex: Int = whole.layers.takeWhile { it !== layer }.sumOf { it.segmentCount },
    ): Int = oldSamplerWouldKeep(layer.sourceSegmentCount, whole.totalSegmentCount, startIndex)

    private fun oldSamplerWouldKeep(
        layerPaths: Int,
        sourcePaths: Int,
        startIndex: Int,
        limit: Int = PreviewMemoryPolicy.LOW_MEMORY_SEGMENTS,
    ): Int {
        if (sourcePaths <= limit) return layerPaths
        var kept = 0
        for (step in 0 until layerPaths) {
            val index = startIndex.toLong() + step
            val before = index * limit / sourcePaths
            val after = (index + 1L) * limit / sourcePaths
            if (after > before) kept++
        }
        return kept
    }
}
