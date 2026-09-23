package com.tomppi.enderslicer.profile

import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterSettingsMapperTest {
    @Test
    fun importsMachineValuesIntoEditablePrinterSettings() {
        val mapped = CuraSettingsMapper.apply(
            SlicerSettings(),
            mapOf(
                "machine_name" to "Imported CoreXY",
                "machine_width" to "350",
                "machine_depth" to "360",
                "machine_height" to "420",
                "machine_shape" to "elliptic",
                "machine_center_is_zero" to "true",
                "machine_heated_bed" to "true",
                "machine_heated_build_volume" to "true",
                "machine_gcode_flavor" to "RepRap",
                "machine_nozzle_size" to "0.8",
                "material_diameter" to "2.85",
                "gantry_height" to "70",
            ),
        )

        assertEquals("Imported CoreXY", mapped.printerName)
        assertEquals(350.0, mapped.machineWidthMm, 0.0)
        assertEquals(360.0, mapped.machineDepthMm, 0.0)
        assertEquals(420.0, mapped.machineHeightMm, 0.0)
        assertEquals("elliptic", mapped.buildPlateShape)
        assertTrue(mapped.originAtCenter)
        assertTrue(mapped.heatedBed)
        assertTrue(mapped.heatedBuildVolume)
        assertEquals("RepRap", mapped.gcodeFlavor)
        assertEquals(0.8, mapped.nozzleSizeMm, 0.0)
        assertEquals(2.85, mapped.filamentDiameterMm, 0.0)
        assertEquals(70.0, mapped.gantryHeightMm, 0.0)
        assertFalse(mapped.customStartGcodeEnabled)
        assertTrue(mapped.overriddenSettingKeys.isEmpty())
    }
}
