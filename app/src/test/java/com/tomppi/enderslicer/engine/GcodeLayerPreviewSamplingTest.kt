package com.tomppi.enderslicer.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GcodeLayerPreviewSamplingTest {
    @Test
    fun feasibleCapRetainsEveryRareFeatureClass() {
        val file = featureGcode()

        val preview = GcodeLayerPreviewParser.parse(file, maxSegments = 4)
        val retained = retainedFeatures(preview)

        assertTrue(GcodeLayerPreview.Feature.ARC_OVERHANG in retained)
        assertTrue(GcodeLayerPreview.Feature.SUPPORT_INTERFACE in retained)
        assertTrue(GcodeLayerPreview.Feature.SUPPORT in retained)
        assertTrue(GcodeLayerPreview.Feature.ADHESION in retained)
    }

    @Test
    fun capOneUsesDocumentedRareFeaturePriority() {
        val preview = GcodeLayerPreviewParser.parse(featureGcode(), maxSegments = 1)

        assertEquals(
            setOf(GcodeLayerPreview.Feature.ARC_OVERHANG),
            retainedFeatures(preview),
        )
    }

    private fun featureGcode(): File = temporaryGcode(
        """
        ;FLAVOR:Marlin
        M83
        ;LAYER:0
        ;TYPE:ARC-OVERHANG
        G1 X1 Y0 Z0.2 E1 F1200
        ;TYPE:SUPPORT-INTERFACE
        G1 X2 Y0 E1 F1200
        ;TYPE:SUPPORT
        G1 X3 Y0 E1 F1200
        ;TYPE:SKIRT
        G1 X4 Y0 E1 F1200
        ;TYPE:WALL-OUTER
        G1 X5 Y0 E1 F1200
        G1 X6 Y0 E1 F1200
        G1 X7 Y0 E1 F1200
        G1 X8 Y0 E1 F1200
        """.trimIndent(),
    )

    private fun retainedFeatures(preview: GcodeLayerPreview): Set<GcodeLayerPreview.Feature> =
        preview.layers.flatMap { layer ->
            layer.segments.asList()
                .chunked(GcodeLayerPreview.VALUES_PER_SEGMENT)
                .map { values -> GcodeLayerPreview.Feature.fromCode(values[5].toInt()) }
        }.toSet()

    private fun temporaryGcode(content: String): File {
        val directory = kotlin.io.path.createTempDirectory("enderslicer-preview-sampling").toFile()
        return File(directory, "output.gcode").apply { writeText(content) }
    }
}
