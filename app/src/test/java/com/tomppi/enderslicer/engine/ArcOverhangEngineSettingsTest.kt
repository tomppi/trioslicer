package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcOverhangEngineSettingsTest {
    @Test
    fun defaultsAreConservativeAndDisabled() {
        val settings = SlicerSettings()
        val values = ArcOverhangEngineSettings.values(settings)

        assertFalse(values.getValue(ArcOverhangEngineSettings.ENABLED).toBoolean())
        assertEquals("5.0", values.getValue(ArcOverhangEngineSettings.SPEED))
        assertEquals("105.0", values.getValue(ArcOverhangEngineSettings.FLOW))
        assertEquals("100.0", values.getValue(ArcOverhangEngineSettings.FAN_SPEED))
    }

    @Test
    fun enabledSettingsAreForwardedToNativeCuraEngineKeys() {
        val settings = SlicerSettings(
            arcOverhangEnabled = true,
            arcOverhangSpeedMmPerSecond = 4.5,
            arcOverhangMaxRadiusMm = 42.0,
        )
        val values = ArcOverhangEngineSettings.values(settings)

        assertTrue(values.getValue(ArcOverhangEngineSettings.ENABLED).toBoolean())
        assertEquals("4.5", values.getValue(ArcOverhangEngineSettings.SPEED))
        assertEquals("42.0", values.getValue(ArcOverhangEngineSettings.MAX_RADIUS))
    }

    @Test
    fun invalidRadiusRangeIsRejectedBeforeSlicing() {
        assertThrows(IllegalArgumentException::class.java) {
            ArcOverhangEngineSettings.values(
                SlicerSettings(
                    arcOverhangMinRadiusMm = 8.0,
                    arcOverhangMaxRadiusMm = 2.0,
                ),
            )
        }
    }
}
