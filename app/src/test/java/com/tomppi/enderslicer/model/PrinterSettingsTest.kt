package com.tomppi.enderslicer.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrinterSettingsTest {
    private val basePrinter = PrinterDefinition(
        name = "Base printer",
        widthMm = 100.0,
        depthMm = 100.0,
        heightMm = 100.0,
        buildPlateShape = "rectangular",
        originAtCenter = false,
        heatedBed = false,
        heatedBuildVolume = false,
        gcodeFlavor = "Marlin",
        extruders = 1,
        nozzleSizeMm = 0.4,
        filamentDiameterMm = 1.75,
        printheadXMinMm = -10.0,
        printheadYMinMm = -10.0,
        printheadXMaxMm = 10.0,
        printheadYMaxMm = 10.0,
        gantryHeightMm = 20.0,
    )

    @Test
    fun derivesEffectivePrinterFromSettings() {
        val effective = basePrinter.withSettings(
            SlicerSettings(
                printerName = "Custom CoreXY",
                machineWidthMm = 300.0,
                machineDepthMm = 310.0,
                machineHeightMm = 400.0,
                buildPlateShape = "elliptic",
                originAtCenter = true,
                heatedBed = true,
                heatedBuildVolume = true,
                gcodeFlavor = "RepRap",
                nozzleSizeMm = 0.6,
                filamentDiameterMm = 2.85,
                printheadXMinMm = -40.0,
                printheadYMinMm = -35.0,
                printheadXMaxMm = 45.0,
                printheadYMaxMm = 50.0,
                gantryHeightMm = 60.0,
            ),
        )

        assertEquals("Custom CoreXY", effective.name)
        assertEquals(300.0, effective.widthMm, 0.0)
        assertEquals(310.0, effective.depthMm, 0.0)
        assertEquals(400.0, effective.heightMm, 0.0)
        assertEquals("elliptic", effective.buildPlateShape)
        assertTrue(effective.originAtCenter)
        assertTrue(effective.heatedBed)
        assertTrue(effective.heatedBuildVolume)
        assertEquals("RepRap", effective.gcodeFlavor)
        assertEquals(0.6, effective.nozzleSizeMm, 0.0)
        assertEquals(2.85, effective.filamentDiameterMm, 0.0)
        assertEquals(-40.0, effective.printheadXMinMm, 0.0)
        assertEquals(50.0, effective.printheadYMaxMm, 0.0)
        assertEquals(60.0, effective.gantryHeightMm, 0.0)
        assertEquals(basePrinter.extruders, effective.extruders)
    }

    @Test
    fun customGcodeOnlyReplacesFallbackWhenEnabled() {
        val disabled = SlicerSettings(
            customStartGcodeEnabled = false,
            customStartGcode = "G28\nM117 custom",
            customEndGcodeEnabled = false,
            customEndGcode = "M84",
        )
        assertEquals("G28 ; fallback", disabled.resolveStartGcode("G28 ; fallback"))
        assertEquals("M104 S0 ; fallback", disabled.resolveEndGcode("M104 S0 ; fallback"))

        val enabled = disabled.copy(customStartGcodeEnabled = true, customEndGcodeEnabled = true)
        assertEquals("G28\nM117 custom", enabled.resolveStartGcode("G28 ; fallback"))
        assertEquals("M84", enabled.resolveEndGcode("M104 S0 ; fallback"))
    }
}
