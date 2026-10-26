package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerEngine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * **Ask AI** in the navigation rail: the chat's one primary door.
 *
 * The rail is one composable used twice - the fold's permanent column and the phone's
 * collapsible sidebar - so these tests render it in both shapes and assert the door is there,
 * opens the chat, and leaves the selected destination where it was (it is an action, not a tab).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AskAiRailTest {

    @get:Rule
    val compose = createComposeRule()

    private var asks = 0
    private var selected: AppTab = AppTab.PLATE

    /** The same printer the other UI tests use: the rail only reads names and sizes of it. */
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

    @androidx.compose.runtime.Composable
    private fun rail() {
        SessionRail(
            selected = selected,
            onSelect = { selected = it },
            engine = SlicerEngine.CURA,
            state = MainUiState(printer = printer),
            gcodeAvailable = false,
            sliceBlockedReason = null,
            onSlice = {},
            onExportGcode = {},
            onTools = {},
            onAskAi = { asks++ },
            onSettings = { _, _ -> },
            onPrusaSettings = { _, _ -> },
            onOrcaSettings = { _, _ -> },
            onExtraSetting = { _, _ -> },
            onAddSettings = {},
        )
    }

    @Test
    fun theWideRailsColumnHasAskAiAndItOpensTheChatWithoutChangingTheTab() {
        compose.setContent {
            // The fold's layout: the rail is a permanent column beside the content.
            Row(modifier = Modifier.fillMaxSize()) {
                rail()
                Box(modifier = Modifier.weight(1f))
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Ask AI").assertIsDisplayed().performClick()

        assertEquals("the chat opens", 1, asks)
        assertEquals("the selected destination does not move", AppTab.PLATE, selected)
    }

    @Test
    fun thePhonesCollapsibleSidebarHasAskAiAndItOpensTheChat() {
        compose.setContent {
            PhoneSessionPanel(
                open = true,
                onExpand = {},
                onCollapse = {},
                rail = { rail() },
                modifier = Modifier.fillMaxSize(),
            )
        }
        compose.waitForIdle()

        compose.onNodeWithText("Ask AI").assertIsDisplayed().performClick()

        assertEquals(1, asks)
        assertEquals(AppTab.PLATE, selected)
    }

    @Test
    fun askAiIsAnActionInTheListNotASelectedDestination() {
        compose.setContent {
            Row(modifier = Modifier.fillMaxSize()) {
                rail()
                Box(modifier = Modifier.weight(1f))
            }
        }
        compose.waitForIdle()

        // The neighbouring destinations still behave normally; Ask AI never changes the tab.
        compose.onNodeWithText("Settings").performClick()
        assertEquals(AppTab.SETTINGS, selected)
        compose.onNodeWithText("Ask AI").performClick()
        assertEquals("still Settings after opening the chat", AppTab.SETTINGS, selected)
        assertEquals(1, asks)
    }
}
