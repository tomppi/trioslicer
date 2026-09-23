package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.engine.GcodeLayerPreview
import com.tomppi.enderslicer.engine.LayerEvent
import com.tomppi.enderslicer.engine.LayerEventType
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every input a slice depends on - settings, engine, printer, model, placement,
 * support paint, Smart Infill package - has to drop the published artifact, so
 * the export path can never offer G-code that no longer matches the plate.
 * [MainUiState.withoutPublishedSlice] is that single place.
 */
class MainUiStateTest {
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

    private fun slicedState() = MainUiState(
        printer = printer,
        sliceResultId = "artifact-1",
        gcodePath = "/tmp/slice-results/artifact-1/model.gcode",
        gcodeComplete = true,
        baseGcodePath = "/tmp/slice-results/artifact-1/model.base.gcode",
        sliceEngine = SlicerEngine.CURA,
        layerPreview = GcodeLayerPreview(
            layers = emptyList(),
            minX = 0f,
            minY = 0f,
            maxX = 1f,
            maxY = 1f,
            minSpeedMmPerSecond = 0f,
            maxSpeedMmPerSecond = 1f,
            minLayerHeightMm = 0.2f,
            maxLayerHeightMm = 0.2f,
            totalSegmentCount = 0,
            truncated = false,
        ),
        layerEvents = listOf(
            LayerEvent(
                id = "event-1",
                layerNumber = 3,
                zMm = 0.6f,
                type = LayerEventType.MESSAGE,
            ),
        ),
        estimatedPrintSeconds = 3_600,
        sliceLogPath = "/tmp/slice-results/artifact-1/slice.log",
        sliceDurationMilliseconds = 12_345,
        sliceProgressPercent = 100,
        warnings = listOf("Layer 3 is taller than the nozzle can print"),
        statusMessage = "Sliced 1.2 MB of validated G-code",
    )

    @Test
    fun theSlicedStateIsExportableToBeginWith() {
        assertTrue(slicedState().hasCurrentGcode())
    }

    @Test
    fun droppingThePublishedSliceLeavesNothingExportable() {
        val dropped = slicedState().withoutPublishedSlice("Support paint changed; slice again to export G-code")

        assertNull(dropped.sliceResultId)
        assertNull(dropped.gcodePath)
        assertNull(dropped.baseGcodePath)
        assertNull(dropped.layerPreview)
        assertNull(dropped.estimatedPrintSeconds)
        assertNull(dropped.sliceLogPath)
        assertNull(dropped.sliceDurationMilliseconds)
        assertEquals(emptyList<LayerEvent>(), dropped.layerEvents)
        assertNull(dropped.sliceEngine)
        assertFalse(dropped.gcodeComplete)
        assertFalse(dropped.hasCurrentGcode())
    }

    @Test
    fun droppingThePublishedSliceOnlyChangesTheReasonAndTheArtifacts() {
        val before = slicedState()
        val dropped = before.withoutPublishedSlice("Support paint changed; slice again to export G-code")

        assertEquals("Support paint changed; slice again to export G-code", dropped.statusMessage)
        assertEquals(before.warnings, dropped.warnings)
        assertFalse(dropped.isBusy)
        assertEquals(before.sliceProgressPercent, dropped.sliceProgressPercent)
    }

    @Test
    fun droppingThePublishedSliceKeepsTheCurrentMessageByDefault() {
        val dropped = slicedState().withoutPublishedSlice()

        assertEquals("Sliced 1.2 MB of validated G-code", dropped.statusMessage)
        assertFalse(dropped.hasCurrentGcode())
    }
}
