package com.tomppi.enderslicer.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The physical bead width drives both the nozzle-path geometry and the
 * inspector readout (they share resolveBeadWidthMm), so these cases
 * pin the flow math to the sliced settings.
 */
class NozzlePathBeadWidthTest {

    private val lineWidth = 0.44f
    private val layerHeight = 0.20f
    // Ender/tinkergnome filament cross-section at 1.75 mm.
    private val filamentArea = (Math.PI * 0.875f * 0.875f).toFloat()

    private fun widthAt(length: Float, deltaE: Float, layer: Float = layerHeight): Float =
        resolveBeadWidthMm(length, deltaE, layer, layerHeight, lineWidth, filamentArea)

    @Test
    fun nominalFlowProducesSettingsWidth() {
        // 10 mm extrusion at 0.44 x 0.20: deltaE = 0.88 mm^3 / 2.405 mm^2.
        val width = widthAt(10f, 0.88f / filamentArea)
        assertEquals(0.44f, width, 0.005f)
    }

    @Test
    fun widthFollowsFlowNotSettings() {
        // Half the length with the same deltaE doubles the cross-section.
        val width = widthAt(5f, 0.88f / filamentArea)
        assertEquals(0.88f, width, 0.005f)
    }

    @Test
    fun parsedLayerHeightRescalesWidth() {
        // Same volume at 0.28 mm layers gives a narrower bead.
        val width = widthAt(10f, 0.88f / filamentArea, layer = 0.28f)
        assertEquals(0.314286f, width, 0.005f)
    }

    @Test
    fun microSegmentCollapsesToZero() {
        assertEquals(0f, widthAt(0.02f, 10f), 0.0001f)
    }

    @Test
    fun widthClampedToQuarterLineWidth() {
        val width = widthAt(10f, 0.01f)
        assertEquals(lineWidth * 0.4f, width, 0.0001f)
    }

    @Test
    fun widthClampedToFourTimesLineWidth() {
        val width = widthAt(10f, 100f)
        assertEquals(lineWidth * 4f, width, 0.0001f)
    }

    @Test
    fun invalidLayerHeightFallsBackToSliceHeight() {
        assertEquals(0.44f, widthAt(10f, 0.88f / filamentArea, layer = -1f), 0.005f)
    }
}
