package com.tomppi.enderslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GcodeLayerPreviewParserTest {
    @Test
    fun parsesSpeedColoredModelSupportAndInterfaceSegments() {
        val file = kotlin.io.path.createTempFile("enderslicer-layer-preview", ".gcode")
            .toFile()
            .apply {
                writeText(
                    """
                    G90
                    M82
                    G92 E0
                    G1 X0 Y0 Z0.2 F6000
                    G1 X20 Y20 E1 F1200
                    ;LAYER:0
                    ;TYPE:SKIRT
                    G1 X10 Y10 Z0.2 F3000
                    G1 X20 Y10 E2 F1800
                    ;TYPE:SUPPORT
                    G1 X20 Y20 E3 F2400
                    ;TYPE:SUPPORT-INTERFACE
                    G1 X10 Y20 E4 F600
                    ;TYPE:WALL-OUTER
                    G1 X10 Y10 E5 F1200
                    ;LAYER:1
                    ;TYPE:FILL
                    G1 X12 Y12 Z0.4 E6 F3600
                    G1 X18 Y18 E7
                    ;TIME_ELAPSED:50.0
                    """.trimIndent(),
                )
            }

        val preview = GcodeLayerPreviewParser.parse(file)

        assertEquals(2, preview.layers.size)
        assertEquals(6, preview.totalSegmentCount)
        assertEquals(10f, preview.minX, 0f)
        assertEquals(10f, preview.minY, 0f)
        assertEquals(20f, preview.maxX, 0f)
        assertEquals(20f, preview.maxY, 0f)
        assertEquals(10f, preview.minSpeedMmPerSecond, 0f)
        assertEquals(60f, preview.maxSpeedMmPerSecond, 0f)
        assertEquals(0.2f, preview.minLayerHeightMm, 0.0001f)
        assertEquals(0.2f, preview.maxLayerHeightMm, 0.0001f)
        assertEquals(0.2f, preview.layers[0].height, 0.0001f)
        assertEquals(0.2f, preview.layers[1].height, 0.0001f)
        assertEquals(1, preview.layers[0].supportSegmentCount)
        assertEquals(1, preview.layers[0].supportInterfaceSegmentCount)
        assertEquals(4, preview.layers[0].segmentCount)
        assertEquals(2, preview.layers[1].segmentCount)
        assertFalse(preview.truncated)

        val packed = preview.layers[0].segments
        assertEquals(
            GcodeLayerPreview.Feature.SUPPORT.code,
            packed[GcodeLayerPreview.VALUES_PER_SEGMENT + 5].toInt(),
        )
        assertEquals(
            GcodeLayerPreview.Feature.SUPPORT_INTERFACE.code,
            packed[GcodeLayerPreview.VALUES_PER_SEGMENT * 2 + 5].toInt(),
        )
        assertTrue(preview.layers.any { it.z == 0.4f })
    }

    @Test
    fun parsesArcOverhangMarkerAsDedicatedPreviewFeature() {
        val file = kotlin.io.path.createTempFile("enderslicer-arc-preview", ".gcode")
            .toFile()
            .apply {
                writeText(
                    """
                    G90
                    M82
                    G92 E0
                    ;LAYER:0
                    G1 X0 Y0 Z0.2 F3000
                    ;TYPE:ARC-OVERHANG
                    G1 X10 Y0 E1 F300
                    """.trimIndent(),
                )
            }

        val preview = GcodeLayerPreviewParser.parse(file)
        val featureCode = preview.layers.single().segments[5].toInt()

        assertEquals(GcodeLayerPreview.Feature.ARC_OVERHANG.code, featureCode)
    }

    @Test
    fun cappedPreviewSamplesAllLayersInsteadOfDroppingTheTopOfThePrint() {
        val file = kotlin.io.path.createTempFile("enderslicer-capped-layer-preview", ".gcode")
            .toFile()
            .apply {
                writeText(
                    buildString {
                        appendLine("G90")
                        appendLine("M82")
                        appendLine("G92 E0")
                        var extrusion = 0
                        repeat(3) { layer ->
                            appendLine(";LAYER:$layer")
                            appendLine(";TYPE:WALL-OUTER")
                            appendLine("G1 Z${(layer + 1) * 0.2} F3000")
                            repeat(4) { segment ->
                                extrusion++
                                val x = layer * 10 + segment + 1
                                appendLine("G1 X$x Y${layer + 1} E$extrusion F1200")
                            }
                        }
                    },
                )
            }

        val preview = GcodeLayerPreviewParser.parse(file, maxSegments = 5)

        assertTrue(preview.truncated)
        assertEquals(5, preview.totalSegmentCount)
        assertEquals(listOf(0, 1, 2), preview.layers.map { it.number })
        assertEquals(0.6f, preview.layers.last().z, 0.0001f)
        assertTrue(preview.layers.all { it.segmentCount > 0 })
    }
}
