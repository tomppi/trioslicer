package com.tomppi.enderslicer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.engine.GcodeLayerPreview
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The slice's status in the top bar: the one line the Plate can never fold away,
 * so it has to carry every state of the slice on its own.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class SliceStatusTest {

    @get:Rule
    val compose = createComposeRule()

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

    private fun state(
        isBusy: Boolean = false,
        percent: Int? = null,
        sliced: Boolean = false,
        layers: Int? = null,
        seconds: Int? = null,
        warnings: List<String> = emptyList(),
    ) = MainUiState(
        printer = printer,
        settings = SlicerSettings(),
        isBusy = isBusy,
        sliceProgressPercent = percent,
        sliceResultId = if (sliced) "slice-1" else null,
        gcodePath = if (sliced) "/tmp/s-hook.gcode" else null,
        gcodeComplete = sliced,
        estimatedPrintSeconds = seconds,
        layerPreview = layers?.let { count ->
            GcodeLayerPreview(
                layers = List(count) { index ->
                    GcodeLayerPreview.Layer(
                        number = index,
                        z = 0.2f * index,
                        height = 0.2f,
                        segments = FloatArray(0),
                        supportSegmentCount = 0,
                        supportInterfaceSegmentCount = 0,
                    )
                },
                minX = 0f, minY = 0f, maxX = 10f, maxY = 10f,
                minSpeedMmPerSecond = 0f, maxSpeedMmPerSecond = 0f,
                minLayerHeightMm = 0.2f, maxLayerHeightMm = 0.2f,
                totalSegmentCount = 0, truncated = false,
            )
        },
        warnings = warnings,
    )

    @Test
    fun anUnslicedPlateSaysSo() {
        assertEquals("Not sliced", sliceStatusLabel(state(), detailed = true))
        assertEquals("Not sliced", sliceStatusLabel(state(), detailed = false))
    }

    /** While the engine works, that is the status - whatever the last slice left. */
    @Test
    fun slicingOutranksTheLastResult() {
        val busy = state(isBusy = true, sliced = true, layers = 8, seconds = 754)
        assertEquals("Slicing…", sliceStatusLabel(busy, detailed = true))
        assertEquals("Slicing…", sliceStatusLabel(busy, detailed = false))
    }

    /** PrusaSlicer and OrcaSlicer report how far along they are; Cura does not. */
    @Test
    fun aReportedProgressIsPartOfTheStatus() {
        assertEquals("Slicing… 42%", sliceStatusLabel(state(isBusy = true, percent = 42), detailed = true))
        assertEquals("Slicing…", sliceStatusLabel(state(isBusy = true), detailed = true))
    }

    @Test
    fun aFinishedSliceCarriesItsNumbersOnAWideWindow() {
        assertEquals(
            "Sliced · 8 layers · 12m",
            sliceStatusLabel(state(sliced = true, layers = 8, seconds = 754), detailed = true),
        )
        // A phone's bar is narrower: the state, without the numbers.
        assertEquals(
            "Sliced",
            sliceStatusLabel(state(sliced = true, layers = 8, seconds = 754), detailed = false),
        )
    }

    @Test
    fun aFinishedSliceWithoutAPreviewStillSaysWhenItPrints() {
        assertEquals(
            "Sliced · 12m",
            sliceStatusLabel(state(sliced = true, seconds = 754), detailed = true),
        )
        assertEquals(
            "Sliced",
            sliceStatusLabel(state(sliced = true), detailed = true),
        )
    }

    @Test
    fun theBarShowsTheStateAndTheWarnings() {
        compose.setContent {
            SliceStatus(
                state = state(sliced = true, layers = 8, seconds = 754, warnings = listOf("a", "b")),
                detailed = true,
            )
        }

        compose.onNodeWithText("Sliced · 8 layers · 12m").assertIsDisplayed()
        compose.onNodeWithText("2 warnings").assertIsDisplayed()
    }

    @Test
    fun theBarSpinsWhileTheEngineWorks() {
        compose.setContent {
            SliceStatus(state = state(isBusy = true), detailed = true)
        }

        compose.onNodeWithText("Slicing…").assertIsDisplayed()
    }

    @Test
    fun theBarShowsHowFarAlongAReportedSliceIs() {
        compose.setContent {
            SliceStatus(state = state(isBusy = true, percent = 42), detailed = true)
        }

        compose.onNodeWithText("Slicing… 42%").assertIsDisplayed()
    }
}
