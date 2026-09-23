package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.model.OrcaSliceSettings
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.PrusaSliceSettings
import com.tomppi.enderslicer.model.SlicerEngine
import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Plate screen's summary cards follow the active engine: an Orca slice must not show the
 * Cura numbers, which is what it did while the cards only asked "is this Prusa?".
 */
class PlateSessionSummaryTest {

    private val printer = PrinterDefinition(
        name = "Modified Ender 3 V2",
        widthMm = 230.0, depthMm = 230.0, heightMm = 250.0,
        buildPlateShape = "rectangular", originAtCenter = false,
        heatedBed = true, heatedBuildVolume = false,
        gcodeFlavor = "Marlin", extruders = 1,
        nozzleSizeMm = 0.4, filamentDiameterMm = 1.75,
        printheadXMinMm = -26.0, printheadYMinMm = -32.0,
        printheadXMaxMm = 32.0, printheadYMaxMm = 34.0,
        gantryHeightMm = 25.0,
    )

    private val state = MainUiState(
        printer = printer,
        settings = SlicerSettings(
            layerHeightMm = 0.20,
            infillDensityPercent = 10.0,
            infillPattern = "cubic",
            supportsEnabled = true,
            adhesionType = "brim",
        ),
        prusaSettings = PrusaSliceSettings(
            layerHeightMm = 0.15,
            fillDensityPercent = 30.0,
            fillPattern = "gyroid",
            supportMaterial = false,
            supportPattern = "rectilinear",
            skirtLoops = 2,
            brimWidthMm = 0.0,
        ),
        orcaSettings = OrcaSliceSettings(
            layerHeightMm = 0.28,
            sparseInfillDensityPercent = 45.0,
            sparseInfillPattern = "honeycomb",
            supportEnabled = true,
            supportBasePattern = "snug",
            skirtLoops = 3,
            brimWidthMm = 5.0,
        ),
    )

    @Test
    fun `an Orca slice shows the Orca settings`() {
        val summary = plateSessionSummary(SlicerEngine.ORCA, state)
        assertEquals(0.28, summary.layerHeightMm, 1e-9)
        assertEquals(45.0, summary.infillPercent, 1e-9)
        assertEquals("Honeycomb", summary.infillPattern)
        assertTrue(summary.supportsEnabled)
        assertEquals("Snug", summary.supportsDetail)
        assertEquals("Brim 5.0 mm", summary.adhesion)
    }

    @Test
    fun `a Prusa slice shows the Prusa settings`() {
        val summary = plateSessionSummary(SlicerEngine.PRUSA, state)
        assertEquals(0.15, summary.layerHeightMm, 1e-9)
        assertEquals("Gyroid", summary.infillPattern)
        assertFalse(summary.supportsEnabled)
        assertEquals("Skirt 2x", summary.adhesion)
    }

    @Test
    fun `a Cura slice shows the app settings`() {
        val summary = plateSessionSummary(SlicerEngine.CURA, state)
        assertEquals(0.20, summary.layerHeightMm, 1e-9)
        assertEquals("Cubic", summary.infillPattern)
        assertTrue(summary.supportsEnabled)
        assertEquals("everywhere", summary.supportsDetail)
        assertEquals("brim", summary.adhesion)
    }

    @Test
    fun `adhesion names the brim only when there is one`() {
        assertEquals("Skirt 1x", adhesionLabel(0.0, 1))
        assertEquals("Brim 2.5 mm", adhesionLabel(2.5, 1))
    }
}
