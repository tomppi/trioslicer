package com.tomppi.enderslicer.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.model.OrcaSliceSettings
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.PrusaSliceSettings
import com.tomppi.enderslicer.model.SlicerEngine
import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The phone's sidebar panel: one handle while it is closed, the rail itself while
 * it is open, and the rail's own editors working there exactly as they do in the
 * fold's column.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class PhoneSessionPanelTest {

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

    private var settings by mutableStateOf(SlicerSettings(layerHeightMm = 0.20))
    private val keys = mutableListOf<String>()
    private var expanded = 0
    private var collapsed = 0
    private var addSettings = 0

    /** The rail the app hands the panel, on a phone-sized window. */
    @Composable
    private fun rail() {
        val current = settings
        SessionRail(
            selected = AppTab.PLATE,
            onSelect = {},
            engine = SlicerEngine.CURA,
            state = MainUiState(
                printer = printer,
                settings = current,
                prusaSettings = PrusaSliceSettings(),
                orcaSettings = OrcaSliceSettings(),
            ),
            gcodeAvailable = false,
            sliceBlockedReason = null,
            onSlice = {},
            onExportGcode = {},
            onTools = {},
            onSettings = { key, change ->
                keys += key
                settings = change(current)
            },
            onPrusaSettings = { key, _ -> keys += key },
            onOrcaSettings = { key, _ -> keys += key },
            onExtraSetting = { key, _ -> keys += key },
            onAddSettings = { addSettings++ },
        )
    }

    private fun show(open: Boolean, content: @Composable () -> Unit = { Text("rail body") }) {
        compose.setContent {
            PhoneSessionPanel(
                open = open,
                onExpand = { expanded++ },
                onCollapse = { collapsed++ },
                rail = content,
            )
        }
    }

    @Test
    fun theClosedPanelIsOneHandleThatOpensIt() {
        show(open = false)

        compose.onNodeWithContentDescription("Show the sidebar").assertIsDisplayed().performClick()
        assertEquals("the handle is the way in", 1, expanded)
        compose.onNodeWithContentDescription("Hide the sidebar").assertDoesNotExist()
    }

    @Test
    fun theOpenPanelShowsTheRailAndItsOwnArrow() {
        show(open = true)

        compose.onNodeWithText("rail body").assertIsDisplayed()
        compose.onNodeWithContentDescription("Hide the sidebar").assertIsDisplayed().performClick()
        assertEquals("and its arrow is the way out", 1, collapsed)
        compose.onNodeWithContentDescription("Show the sidebar").assertDoesNotExist()
    }

    /**
     * The rail's + is in the panel too - one composable, so the phone's door into
     * the sidebar picker is the same one the fold's column has.
     */
    @Test
    fun thePanelCarriesTheRailsPlus() {
        show(open = true, content = { rail() })

        compose.onNodeWithTag(SESSION_RAIL_ADD_TAG).assertIsDisplayed().performClick()
        assertEquals("the panel's + opens the picker", 1, addSettings)
        // The panel's own handle is still there above it.
        compose.onNodeWithContentDescription("Hide the sidebar").assertIsDisplayed()
    }

    /** The rail's editor round-trips in the panel the same way it does in the column. */
    @Test
    fun theRailsEditorWorksInThePanel() {
        show(open = true, content = { rail() })

        compose.onNodeWithText("Layer height").performScrollTo().performClick()
        compose.onNodeWithTag(SESSION_EDITOR_TAG).assertIsDisplayed()
        compose.onNodeWithText("+").performClick()

        assertEquals(listOf(SlicerSettings.Keys.LAYER_HEIGHT), keys)
        assertEquals(0.21, settings.layerHeightMm, 1e-9)
    }
}
