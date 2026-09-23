package com.tomppi.enderslicer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.model.OrcaSliceSettings
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.PrusaSliceSettings
import com.tomppi.enderslicer.model.SlicerEngine
import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The navigation rail on a JVM: the four destinations with the printing session
 * under them in one column, and every value opening the editor that changes it.
 */
@RunWith(AndroidJUnit4::class)
// The fold's window: the rail is only composed at this width, and a small
// Robolectric screen would scroll the session out of it.
@Config(sdk = [35], qualifiers = "w1184dp-h986dp")
class SessionRailTest {

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

    /** The settings the rail reads, so an edit shows up in it. */
    private var settings by mutableStateOf(
        SlicerSettings(layerHeightMm = 0.20, infillDensityPercent = 10.0, infillPattern = "gyroid"),
    )

    private var destination by mutableStateOf(AppTab.PLATE)
    private val selected = mutableListOf<AppTab>()
    private val keys = mutableListOf<String>()

    private var slices = 0
    private var exports = 0

    /** The blocked-slice reason as the rail receives it, driven by a test. */
    private var reason by mutableStateOf<String?>(null)

    private fun show(
        gcodeAvailable: Boolean = false,
        engine: SlicerEngine = SlicerEngine.CURA,
        sliceable: Boolean = false,
        sliceBlockedReason: String? = null,
        busy: Boolean = false,
    ) {
        reason = sliceBlockedReason
        compose.setContent {
            val current = settings
            SessionRail(
                selected = destination,
                onSelect = {
                    selected += it
                    destination = it
                },
                engine = engine,
                state = MainUiState(
                    printer = printer,
                    settings = current,
                    prusaSettings = PrusaSliceSettings(fillPattern = "grid"),
                    orcaSettings = OrcaSliceSettings(sparseInfillPattern = "grid"),
                    modelPath = if (sliceable) "s-hook.stl" else null,
                    engineAvailable = sliceable,
                    isBusy = busy,
                ),
                gcodeAvailable = gcodeAvailable,
                sliceBlockedReason = reason,
                onSlice = { slices++ },
                onExportGcode = { exports++ },
                onSettings = { key, change ->
                    keys += key
                    settings = change(current)
                },
                onTools = {},
                onPrusaSettings = { key, _ -> keys += key },
                onOrcaSettings = { key, _ -> keys += key },
            )
        }
    }

    @Test
    fun theRailCarriesTheDestinationsAndTheSession() {
        show(gcodeAvailable = false)

        AppTab.entries.forEach { tab ->
            compose.onNodeWithText(tab.shortLabel).assertIsDisplayed()
        }
        compose.onNodeWithText("Not sliced").assertIsDisplayed()
        compose.onNodeWithText("Ready").assertDoesNotExist()
        // The values are in the rail; on a short window the column scrolls.
        compose.onNodeWithText("Layer height").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Infill density").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Supports").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Adhesion").performScrollTo().assertIsDisplayed()
    }

    /**
     * The Plate's actions live in the rail now, and they are the *active*
     * engine's: the slice callback is the view model's, which slices with
     * whichever engine is selected, and Export saves that engine's G-code.
     */
    @Test
    fun theActionsLiveInTheRailAndFollowTheModel() {
        show(gcodeAvailable = true, sliceable = true)

        compose.onNodeWithText("Slice again").assertIsDisplayed().performClick()
        assertEquals(1, slices)

        compose.onNodeWithText("Export").assertIsDisplayed().performClick()
        assertEquals(1, exports)

        // A validated slice renames the button, and Model tools needs a mesh
        // this state has not got.
        compose.onNodeWithText("Model tools").assertIsNotEnabled()
    }

    /**
     * A wrapped label has to stay centred in its button. Left to itself it hugs
     * the leading edge of a button it no longer fills, which reads as broken.
     */
    @Test
    fun aWrappedButtonLabelStaysCentred() {
        show()

        val button = compose.onNodeWithText("Model tools").fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithText("Model tools", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot

        assertEquals(
            "the label is centred in its button, was ${label.center.x} vs ${button.center.x}",
            button.center.x,
            label.center.x,
            1.5f,
        )
    }

    /**
     * A reason that clears in a moment - a restored workspace being validated at
     * launch - must not paint a red line that vanishes again.
     */
    @Test
    fun aFleetingReasonIsNeverShown() {
        compose.mainClock.autoAdvance = false
        show(sliceBlockedReason = "Smart Infill is being validated for the current model")

        compose.mainClock.advanceTimeBy(100)
        reason = null
        compose.mainClock.advanceTimeBy(2_000)

        compose.onNodeWithText("Smart Infill is being validated for the current model")
            .assertDoesNotExist()
    }

    @Test
    fun aReasonThatStandsIsShown() {
        compose.mainClock.autoAdvance = false
        show(sliceBlockedReason = "Non-planar and conical slicing are mutually exclusive")

        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText("Non-planar and conical slicing are mutually exclusive")
            .assertDoesNotExist()

        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Non-planar and conical slicing are mutually exclusive")
            .assertIsDisplayed()
    }

    @Test
    fun sliceWaitsForAModel() {
        show(sliceable = false)

        compose.onNodeWithText("Slice").assertIsNotEnabled()
    }

    /** One column: the session sits under the destinations, not beside them. */
    @Test
    fun theSessionSitsUnderTheDestinations() {
        show()

        val plate = compose.onNodeWithText("Plate").fetchSemanticsNode().boundsInRoot
        val layerHeight = compose.onNodeWithText("Layer height").fetchSemanticsNode().boundsInRoot

        assertTrue(
            "the session starts below the destinations, was ${layerHeight.top} vs ${plate.bottom}",
            layerHeight.top > plate.bottom,
        )
        // Same column, not a second one beside it.
        assertTrue(
            "and in the same column, was ${layerHeight.left}..${layerHeight.right} " +
                "against ${plate.left}..${plate.right}",
            layerHeight.left <= plate.right && layerHeight.right >= plate.left,
        )
    }

    /**
     * Material's rail is 80dp and this one stays that width: the session is
     * stacked inside it, not a wider column of its own.
     */
    @Test
    fun theSessionIsStackedInTheRailsOwnWidth() {
        show()

        assertEquals(80.dp, SessionRailWidth)

        // The unmerged tree, so the label and its value are their own nodes
        // rather than one merged tap target.
        compose.onNodeWithText("Layer height").performScrollTo()
        val label = compose.onNodeWithText("Layer height", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val unit = compose.onNodeWithText("mm", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the label sits above its value, was ${label.bottom} vs ${unit.top}",
            label.bottom <= unit.top + 1f,
        )
    }

    @Test
    fun tappingADestinationSelectsIt() {
        show()

        compose.onNodeWithText("Print").performClick()

        assertEquals(listOf(AppTab.PRINT), selected)
    }

    @Test
    fun aValidatedSliceSaysSo() {
        show(gcodeAvailable = true)

        compose.onNodeWithText("Ready").assertIsDisplayed()
        compose.onNodeWithText("Not sliced").assertDoesNotExist()
    }

    /** The value it shows is the value it edits: one tap, one setting write. */
    @Test
    fun tappingAValueOpensItsEditorAndWritesThatSetting() {
        show()

        compose.onNodeWithText("Layer height").performScrollTo().performClick()
        compose.onNodeWithTag(SESSION_EDITOR_TAG).assertIsDisplayed()
        compose.onNodeWithText("+").performClick()

        assertEquals(listOf(SlicerSettings.Keys.LAYER_HEIGHT), keys)
        assertEquals(0.21, settings.layerHeightMm, 1e-9)
    }

    /**
     * The settings setters refuse an edit while the view model is busy, so the tiles
     * have to refuse the tap too: an editor that opens and then drops the change is
     * worse than one that does not open.
     */
    @Test
    fun theValueTilesRefuseATapWhileASliceRuns() {
        show(busy = true)

        compose.onNodeWithText("Layer height").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Layer height").performScrollTo().performClick()

        assertEquals("no setting may be written during a slice", emptyList<String>(), keys)
    }

    @Test
    fun theInfillEditorCarriesBothTheDensityAndThePattern() {
        show()

        compose.onNodeWithText("Infill density").performScrollTo().performClick()
        compose.onNodeWithText("Density").assertIsDisplayed()
        compose.onNodeWithText("Pattern").assertIsDisplayed()

        // A pattern the row is not already showing, so the tap can only be the
        // one in the dialog.
        compose.onNodeWithText("Lightning").performScrollTo().performClick()

        assertEquals(listOf(SlicerSettings.Keys.INFILL_PATTERN), keys)
        assertEquals("lightning", settings.infillPattern)

        // Closing the dialog leaves the new pattern on the rail's own line.
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Lightning").assertIsDisplayed()
    }

    /**
     * A choice applies at once and the dialog stays open, so the check mark has to
     * follow the state. The dialog used to render the SessionValue captured when
     * the tile was tapped, and the tick stayed on the pattern that was active then
     * while the plate behind it changed.
     */
    @Test
    fun aChoiceMadeInTheDialogMovesTheCheckMark() {
        show()

        compose.onNodeWithText("Infill density").performScrollTo().performClick()
        assertTickIsOn("Gyroid")

        compose.onNodeWithText("Lightning").performScrollTo().performClick()

        assertEquals("lightning", settings.infillPattern)
        compose.onNodeWithText("Pattern").assertIsDisplayed()
        assertTickIsOn("Lightning")
    }

    /**
     * The one "Selected" tick sits on the dialog row labelled [label]. That row is
     * the only node carrying both, which keeps the rail's own tile - it shows the
     * same pattern - out of the match.
     */
    private fun assertTickIsOn(label: String) {
        compose.onAllNodesWithContentDescription("Selected", useUnmergedTree = true)
            .assertCountEquals(1)
        compose.onNode(hasText(label) and hasContentDescription("Selected")).assertExists()
    }

    @Test
    fun theRailFollowsTheActiveEngine() {
        show(engine = SlicerEngine.ORCA)

        compose.onNodeWithText("Layer height").performScrollTo().performClick()
        compose.onNodeWithText("+").performClick()

        assertEquals(listOf(OrcaSliceSettings.Keys.LAYER_HEIGHT), keys)
    }
}
