package com.tomppi.enderslicer.data

import com.tomppi.enderslicer.model.PrusaSliceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrusaSliceSettingsJsonTest {

    @Test
    fun roundTripPreservesEveryField() {
        val settings = PrusaSliceSettings(
            layerHeightMm = 0.12,
            firstLayerHeightMm = 0.25,
            perimeters = 3,
            topSolidLayers = 6,
            bottomSolidLayers = 5,
            thinWalls = true,
            externalPerimetersFirst = true,
            fillDensityPercent = 22.5,
            fillPattern = "gyroid",
            skirtLoops = 2,
            skirtDistanceMm = 3.0,
            brimWidthMm = 4.0,
            supportMaterial = true,
            supportThresholdAngleDegrees = 48.0,
            supportPattern = "snug",
            supportInterface = false,
            supportInterfaceLayers = 1,
            printSpeedMmPerSecond = 75.0,
            externalPerimeterSpeedMmPerSecond = 30.0,
            infillSpeedMmPerSecond = 65.0,
            firstLayerSpeedMmPerSecond = 25.0,
            travelSpeedMmPerSecond = 180.0,
            nozzleTemperatureC = 205,
            firstLayerTemperatureC = 210,
            bedTemperatureC = 55,
            firstLayerBedTemperatureC = 55,
            fanSpeedPercent = 80,
            retractionLengthMm = 1.2,
            retractionSpeedMmPerSecond = 40.0,
            retractionMinTravelMm = 1.0,
            retractLiftMm = 0.2,
            useFirmwareRetraction = true,
            extrusionMultiplierPercent = 95.0,
        )

        val restored = PrusaSliceSettingsJson.deserialize(PrusaSliceSettingsJson.serialize(settings))
        assertNotNull(restored)
        assertEquals(settings, restored)
    }

    @Test
    fun extraKeysRoundTrip() {
        val settings = PrusaSliceSettings().copy(extraKeys = mapOf("seam_position" to "nearest", "top_solid_infill_flow_ratio" to "0.9"))
        val restored = PrusaSliceSettingsJson.deserialize(PrusaSliceSettingsJson.serialize(settings))
        assertNotNull(restored)
        assertEquals(settings.extraKeys, restored!!.extraKeys)
    }

    @Test
    fun unknownKeysFallBackToDefaults() {
        val restored = PrusaSliceSettingsJson.deserialize("""{"perimeters": 5}""")
        assertNotNull(restored)
        assertEquals(5, restored!!.perimeters)
        assertEquals(0.20, restored.layerHeightMm, 1e-9)
        assertEquals("grid", restored.fillPattern)
        assertTrue(restored.retractionLengthMm == 0.8)
    }
}
