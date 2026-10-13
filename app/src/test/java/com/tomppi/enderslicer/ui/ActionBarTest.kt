package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The brick phone's action block, expanded and collapsed.
 *
 * Expanded it is the bar it has always been. Collapsed it is a small floating arrow
 * over the model and nothing else - no bar, no surface, no background spanning the
 * screen - so the strip the bar stood on goes back to the plate.
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

    private var hides = 0
    private var expands = 0

    @Test
    fun expandedItCarriesTheRowsAndTheFoldArrow() {
        compose.setContent {
            ActionBar(
                state = MainUiState(printer = printer, settings = SlicerSettings()),
                nonPlanarEnabled = false,
                conicalEnabled = false,
                sliceBlockedReason = null,
                onSlice = {},
                onExportGcode = {},
                onTools = {},
                onToggle = { hides++ },
            )
        }

        compose.onNodeWithText("Slice").assertIsDisplayed()
        compose.onNodeWithText("Export G-code").assertIsDisplayed()
        compose.onNodeWithText("Model tools · move, rotate, scale, paint").assertIsDisplayed()
        compose.onNodeWithContentDescription("Hide the actions").assertIsDisplayed().performClick()
        assertEquals("the fold arrow asks the plate to collapse the bar", 1, hides)
    }

    /**
     * Collapsed: the arrow alone, in a small target, with nothing drawn around it.
     * The plate behind it is the caller's, and this only takes the 48dp it needs.
     */
    @Test
    fun collapsedItIsASmallArrowAndNothingElse() {
        compose.setContent {
            Box(modifier = Modifier.fillMaxSize()) {
                ActionBarHandle(
                    onExpand = { expands++ },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp),
                )
            }
        }

        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val handle = compose.onNodeWithContentDescription("Show the actions").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the handle is a small target, it was ${handle.width} of ${root.width}",
            handle.width < root.width / 4f,
        )
        compose.onNodeWithText("Slice").assertDoesNotExist()
        compose.onNodeWithText("Export G-code").assertDoesNotExist()
        compose.onNodeWithText("Model tools · move, rotate, scale, paint").assertDoesNotExist()

        compose.onNodeWithContentDescription("Show the actions").performClick()
        assertEquals(1, expands)
    }

    /**
     * The plate itself: the bottom bar is drawn only while it is expanded, and the
     * collapsed plate draws the small handle instead - so there is no full-width
     * surface left behind when it is folded.
     */
    @Test
    fun theCollapsedPlateDrawsNoBottomBar() {
        val app = source("ui/EnderSlicerApp.kt")

        assertTrue(
            "the Scaffold's bottom bar is guarded by the expanded state",
            app.contains("!expandedLayout && platePanels.actionBarShowsRows"),
        )
        assertTrue(
            "and the collapsed plate draws the floating handle",
            app.contains("if (!expandedLayout && !platePanels.actionBarShowsRows)") &&
                app.contains("ActionBarHandle("),
        )
    }

    private fun source(relative: String): String {
        val candidates = listOf(
            File("src/main/java/com/tomppi/enderslicer/$relative"),
            File("app/src/main/java/com/tomppi/enderslicer/$relative"),
        )
        val file = candidates.firstOrNull(File::isFile)
            ?: error("Unable to locate source file for $relative from " + File(".").absolutePath)
        return file.readText()
    }
}
