package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.model.OrcaSliceSettings
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.PrusaSliceSettings
import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * All three engines' settings screens, at the wide layout: two columns of the same
 * menu, each with its own scroll and its own cards - the Cura sheet's split is not
 * a Cura-only shape.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w1000dp-h800dp")
class SettingsSheetsTest {

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

    private val settings = mutableStateOf(SlicerSettings())

    private fun inColumn(tag: String, text: String) =
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)))

    private fun inColumnStartingWith(tag: String, text: String) =
        compose.onNode(hasText(text, substring = true) and hasAnyAncestor(hasTestTag(tag)))

    @Test
    fun theCuraSheetSplitsIntoTwoColumns() {
        compose.setContent {
            CategorizedSettingsSheet(
                state = MainUiState(printer = printer, settings = settings.value),
                onSettings = { _, _ -> },
                onResetOverrides = {},
                expandedLayout = true,
                modifier = Modifier.fillMaxSize(),
            )
        }

        compose.onNodeWithTag(SETTINGS_LEFT_PANE_TAG).assertExists()
        compose.onNodeWithTag(SETTINGS_RIGHT_PANE_TAG).assertExists()
        // The profile block is the left column's, once, and the sections are split
        // between the two: the sixth category is the first of the right column.
        // The catalogue is not loaded in a test, so the printer row names the id it
        // falls back to.
        inColumnStartingWith(SETTINGS_LEFT_PANE_TAG, "Printer: ").assertIsDisplayed()
        inColumnStartingWith(SETTINGS_RIGHT_PANE_TAG, "Printer: ").assertDoesNotExist()
        inColumn(SETTINGS_LEFT_PANE_TAG, "Quality").assertIsDisplayed()
        inColumn(SETTINGS_RIGHT_PANE_TAG, "Quality").assertDoesNotExist()
        inColumn(SETTINGS_RIGHT_PANE_TAG, "Cooling").assertIsDisplayed()
    }

    @Test
    fun thePrusaSheetSplitsIntoTwoColumns() {
        compose.setContent {
            PrusaSettingsSheet(
                state = MainUiState(printer = printer, prusaSettings = PrusaSliceSettings()),
                onSettings = { _, _ -> },
                expandedLayout = true,
                modifier = Modifier.fillMaxSize(),
            )
        }

        compose.onNodeWithTag(SETTINGS_LEFT_PANE_TAG).assertExists()
        compose.onNodeWithTag(SETTINGS_RIGHT_PANE_TAG).assertExists()
        inColumn(SETTINGS_LEFT_PANE_TAG, "Perimeters & shells").assertIsDisplayed()
        inColumn(SETTINGS_RIGHT_PANE_TAG, "Perimeters & shells").assertDoesNotExist()
        // The fifth of the eight sections opens the right column.
        inColumn(SETTINGS_RIGHT_PANE_TAG, "Support material").assertIsDisplayed()
        // The reset belongs to the profile block, in the left column, and the
        // confirmation it raises is one dialog for the whole sheet.
        inColumn(SETTINGS_LEFT_PANE_TAG, "Reset to engine defaults").assertExists()
        inColumn(SETTINGS_RIGHT_PANE_TAG, "Reset to engine defaults").assertDoesNotExist()
    }

    @Test
    fun theOrcaSheetSplitsIntoTwoColumns() {
        compose.setContent {
            OrcaSettingsSheet(
                state = MainUiState(printer = printer, orcaSettings = OrcaSliceSettings()),
                onSettings = { _, _ -> },
                expandedLayout = true,
                modifier = Modifier.fillMaxSize(),
            )
        }

        compose.onNodeWithTag(SETTINGS_LEFT_PANE_TAG).assertExists()
        compose.onNodeWithTag(SETTINGS_RIGHT_PANE_TAG).assertExists()
        inColumn(SETTINGS_LEFT_PANE_TAG, "Walls and shells").assertIsDisplayed()
        inColumn(SETTINGS_RIGHT_PANE_TAG, "Walls and shells").assertDoesNotExist()
        // The fifth of the eight sections opens the right column.
        inColumn(SETTINGS_RIGHT_PANE_TAG, "Skirt and brim").assertIsDisplayed()
    }
}
