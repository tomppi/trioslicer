package com.tomppi.enderslicer.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The brick phone's action bar, folded and unfolded.
 *
 * Collapsed it is a handle and nothing else - a small arrow, the way the sidebar's
 * own handle is - so the plate keeps the strip for the model; the expand tap buys
 * back the button and the two rows below it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class ActionBarTest {

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

    private var toggles = 0

    private fun show(expanded: Boolean, handleOnly: Boolean = false) {
        compose.setContent {
            ActionBar(
                state = MainUiState(printer = printer, settings = SlicerSettings()),
                nonPlanarEnabled = false,
                conicalEnabled = false,
                sliceBlockedReason = null,
                onSlice = {},
                onExportGcode = {},
                onTools = {},
                expanded = expanded,
                onToggle = { toggles++ },
                handleOnly = handleOnly,
            )
        }
    }

    @Test
    fun collapsedItIsOnlyItsArrow() {
        show(expanded = false)

        compose.onNodeWithContentDescription("Expand actions").assertIsDisplayed()
        compose.onNodeWithText("Slice").assertDoesNotExist()
        compose.onNodeWithText("Export G-code").assertDoesNotExist()
        compose.onNodeWithText("Model tools · move, rotate, scale, paint").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Collapse actions").assertCountEquals(0)
    }

    @Test
    fun expandedItCarriesTheRowsAndTheArrow() {
        show(expanded = true)

        compose.onNodeWithText("Slice").assertIsDisplayed()
        compose.onNodeWithText("Export G-code").assertIsDisplayed()
        compose.onNodeWithContentDescription("Collapse actions").assertIsDisplayed()
    }

    /** While the sidebar panel is up, the bar is its own arrow and nothing else. */
    @Test
    fun thePanelLeavesTheBarItsArrowAlone() {
        show(expanded = true, handleOnly = true)

        compose.onNodeWithContentDescription("Bring the actions back").assertIsDisplayed()
        compose.onNodeWithText("Slice").assertDoesNotExist()
        compose.onNodeWithText("Model tools · move, rotate, scale, paint").assertDoesNotExist()
    }

    @Test
    fun theArrowIsTheExpandTap() {
        show(expanded = false)

        compose.onNodeWithContentDescription("Expand actions").performClick()
        assertEquals(1, toggles)
    }
}
