package com.tomppi.enderslicer.engine

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layer preview's cap is a memory guard, not a sampling policy.
 *
 * The reported bug was a single layer drawn with most of its paths missing: past 800,000
 * segments the parser kept one path in three spread across the whole print, so every layer came
 * out sparse. These tests build a file past that cap for each of the three dialects the app
 * slices with and measure the layer that was chosen: every path of it is in the stored runs, in
 * order, with no gap between them, and the runs that did merge only ever merge paths that
 * genuinely continue each other.
 */
class GcodeLayerPreviewFoldTest {

    @Test
    fun curaDenseLayerIsDrawnWholePastTheBudget() = assertDenseLayerDrawnWhole(GcodeDialect.CURA)

    @Test
    fun prusaDenseLayerIsDrawnWholePastTheBudget() = assertDenseLayerDrawnWhole(GcodeDialect.PRUSA)

    @Test
    fun orcaDenseLayerIsDrawnWholePastTheBudget() = assertDenseLayerDrawnWhole(GcodeDialect.ORCA)

    private fun assertDenseLayerDrawnWhole(dialect: GcodeDialect) {
        val dense = 4_000
        val bulkLayers = 190
        val bulkPerLayer = 4_300
        val file = write(dialect, dense, bulkLayers, bulkPerLayer)
        try {
            val preview = GcodeLayerPreviewParser.parse(file, dialect)

            assertTrue("the file has to be past the 800,000 budget", preview.folded)
            assertTrue(preview.totalSegmentCount > 800_000)
            assertEquals(
                "the source count is what the footer shows",
                dense + bulkLayers * bulkPerLayer,
                preview.totalSegmentCount,
            )

            // The dense layer is a connected zig-zag: every move turns 90 degrees from the one
            // before it, so no two of its paths may be folded together.
            val layer = preview.layers.first()
            assertEquals(dense, layer.sourceSegmentCount)
            assertEquals("no path of the dense layer may be folded away", dense, layer.segmentCount)
            val values = layer.segments
            var previousX = 0f
            var previousY = 0f
            var length = 0.0
            for (index in 0 until dense) {
                val offset = index * GcodeLayerPreview.VALUES_PER_SEGMENT
                val x1 = values[offset]
                val y1 = values[offset + 1]
                val x2 = values[offset + 2]
                val y2 = values[offset + 3]
                assertEquals("run " + index + " starts where the one before it ended", previousX, x1, 1e-3f)
                assertEquals("run " + index + " starts where the one before it ended", previousY, y1, 1e-3f)
                val dx = x2 - x1
                val dy = y2 - y1
                length += kotlin.math.sqrt(dx * dx + dy * dy).toDouble()
                previousX = x2
                previousY = y2
            }
            // Half the moves run along X, half along Y, each half a millimetre long.
            assertEquals(dense * 0.5, length, 1e-2)

            // The straight bulk does fold - and keeps its whole extent and length when it does.
            val bulk = preview.layers.last()
            assertEquals(bulkPerLayer, bulk.sourceSegmentCount)
            assertTrue("a straight wall folds", bulk.segmentCount < bulkPerLayer)
            val bulkLength = runLength(bulk)
            assertEquals(
                "folding may not lose distance",
                bulkPerLayer * 0.4,
                bulkLength,
                1e-2,
            )
            assertEquals("the first run starts at the wall's start", 0f, bulk.segments[0], 1e-3f)
            assertEquals(
                "the last run still reaches the wall's end",
                (bulkPerLayer * 0.4).toFloat(),
                bulk.segments[bulk.segments.size - 4],
                1e-3f,
            )
        } finally {
            file.delete()
        }
    }

    /** Total length of a layer's stored runs. */
    private fun runLength(layer: GcodeLayerPreview.Layer): Double {
        var total = 0.0
        val values = layer.segments
        var offset = 0
        while (offset + 5 < values.size) {
            val dx = values[offset + 2] - values[offset]
            val dy = values[offset + 3] - values[offset + 1]
            total += kotlin.math.sqrt(dx * dx + dy * dy).toDouble()
            offset += GcodeLayerPreview.VALUES_PER_SEGMENT
        }
        return total
    }

    /**
     * A file just past the budget: [dense] zig-zag paths in the first layer, then [bulkLayers]
     * layers of straight wall each [bulkPerLayer] paths long.
     */
    private fun write(
        dialect: GcodeDialect,
        dense: Int,
        bulkLayers: Int,
        bulkPerLayer: Int,
    ): File {
        val text = StringBuilder(1 shl 24)
        text.append(";FLAVOR:Marlin\nM83\nG90\n")
        var number = 0
        var z = 0.2
        fun openLayer() {
            val zText = String.format(Locale.US, "%.3f", z)
            when (dialect) {
                GcodeDialect.CURA -> text.append(";LAYER:").append(number).append('\n')
                GcodeDialect.PRUSA -> text.append(";LAYER_CHANGE\n;Z:").append(zText).append('\n')
                GcodeDialect.ORCA -> text.append("; CHANGE_LAYER\n; Z_HEIGHT:").append(zText)
                    .append("\n; LAYER_HEIGHT:0.2\n")
            }
            number++
        }
        openLayer()
        text.append(";TYPE:WALL-OUTER\n")
        var x = 0.0
        var y = 0.0
        text.append("G1 X0 Y0 Z0.200 F3000\n")
        for (index in 0 until dense) {
            if (index % 2 == 0) x += 0.5 else y += 0.5
            text.append("G1 X").append(coordinate(x)).append(" Y").append(coordinate(y))
                .append(" E0.05 F1800\n")
        }
        for (layer in 0 until bulkLayers) {
            z = 0.2 + (layer + 1) * 0.2
            openLayer()
            text.append(";TYPE:WALL-OUTER\n")
            text.append("G1 X0 Y0 Z").append(coordinate(z)).append(" F3000\n")
            var wallX = 0.0
            for (segment in 0 until bulkPerLayer) {
                wallX += 0.4
                text.append("G1 X").append(coordinate(wallX)).append(" E0.05 F1800\n")
            }
        }
        val file = kotlin.io.path.createTempFile("enderslicer-layer-fold", ".gcode").toFile()
        file.writeText(text.toString())
        return file
    }

    private fun coordinate(value: Double): String = String.format(Locale.US, "%.3f", value)
}
