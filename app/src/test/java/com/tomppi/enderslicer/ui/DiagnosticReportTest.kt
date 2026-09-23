package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerEngine
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diagnostic export is what the engines' failure messages point at, so it has
 * to carry the setup and the engine's own words without a second round trip.
 */
class DiagnosticReportTest {
    private val printer = PrinterDefinition(
        name = "Modified Ender 3 V2",
        widthMm = 220.0,
        depthMm = 220.0,
        heightMm = 250.0,
        buildPlateShape = "rectangular",
        originAtCenter = false,
        heatedBed = true,
        heatedBuildVolume = false,
        gcodeFlavor = "Marlin",
        extruders = 1,
        nozzleSizeMm = 0.4,
        filamentDiameterMm = 1.75,
        printheadXMinMm = -26.0,
        printheadYMinMm = -32.0,
        printheadXMaxMm = 32.0,
        printheadYMaxMm = 34.0,
        gantryHeightMm = 25.0,
    )

    @Test
    fun theReportCarriesTheSetupTheStatusAndTheLog() {
        val state = MainUiState(
            printer = printer,
            statusMessage = "OrcaSlicer failed with exit code 1. Export the error log for full details.",
            sliceEngine = SlicerEngine.ORCA,
            profileName = "Bambu PETG",
            profileSource = "Orca profile",
            sliceLogPath = "/data/logs/orca-1.log",
            sliceDurationMilliseconds = 1234L,
            estimatedPrintSeconds = 3661,
            warnings = listOf("Layer 3: thin wall"),
            extraOrcaSettings = mapOf("sparse_infill_density" to "15%"),
        )
        val report = diagnosticReport(
            state = state,
            engine = SlicerEngine.ORCA,
            appVersion = "1.3.0",
            device = "SM-F946B (samsung), Android 16 (API 36)",
            now = "2026-09-22T22:00:00Z",
            logText = "orca: starting\norca: done\n",
        )

        assertTrue(report.contains("OrcaSlicer failed with exit code 1"))
        assertTrue(report.contains("last slice ran on OrcaSlicer"))
        assertTrue(report.contains("Bambu PETG"))
        assertTrue(report.contains("infill 10.0% cubic"))
        assertTrue(report.contains("Overrides: 0 Cura, 0 PrusaSlicer, 1 OrcaSlicer"))
        assertTrue(report.contains("estimated 61 min"))
        assertTrue(report.contains("Layer 3: thin wall"))
        assertTrue(report.contains("orca: done"))
        assertTrue(report.contains("App: 1.3.0"))
    }

    @Test
    fun aReportWithoutALogSaysSo() {
        val report = diagnosticReport(
            state = MainUiState(printer = printer),
            engine = SlicerEngine.CURA,
            appVersion = "1.3.0",
            device = "device",
            now = "now",
            logText = null,
        )

        assertTrue(report.contains("The engine's log is no longer on disk."))
        assertTrue(report.contains("no exportable G-code"))
    }
}
