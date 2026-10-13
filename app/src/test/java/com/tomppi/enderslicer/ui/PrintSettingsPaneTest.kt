package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.model.PrinterDefinition
import com.tomppi.enderslicer.model.SlicerSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * The print settings' two shapes: the phone's one column and the foldable's split.
 *
 * The split is not an index and a detail pane - **both panes are settings**, each
 * with its own section selector and its own scroll, so twice the settings are on
 * the screen at once. These hold that: two panes side by side on different
 * sections, one pane's choice leaving the other alone, an edit surviving a section
 * change, a long section scrolling inside its own pane, every row that is not a
 * category still reachable, and the phone still the one column it has always been.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class PrintSettingsPaneTest {

    @get:Rule
    val compose = createComposeRule()

    private val printer = PrinterDefinition(
        name = "Modified Ender 3 V2",
        widthMm = 220.0, depthMm = 220.0, heightMm = 250.0,
        buildPlateShape = "rectangular", originAtCenter = false,
        heatedBed = true, heatedBuildVolume = false,
        gcodeFlavor = "Marlin", extruders = 1,
        nozzleSizeMm = 0.4, filamentDiameterMm = 1.75,
        printheadXMinMm = -26.0, printheadYMinMm = -32.0,
        printheadXMaxMm = 32.0, printheadYMaxMm = 34.0,
        gantryHeightMm = 25.0,
    )

    private val settings = mutableStateOf(SlicerSettings())
    private val registered = mutableListOf<String>()
    private var openedAllSettings = 0

    private fun show(expandedLayout: Boolean) {
        compose.setContent {
            CategorizedSettingsSheet(
                state = MainUiState(printer = printer, settings = settings.value),
                onSettings = { key, change ->
                    registered += key
                    settings.value = change(settings.value)
                },
                onResetOverrides = {},
                expandedLayout = expandedLayout,
                onOpenAllSettings = { openedAllSettings++ },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    /** A node of [tag]'s pane that says [text]: this pane holds that row. */
    private fun inPane(tag: String, text: String): SemanticsNodeInteraction =
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)))

    /** The section [tag]'s pane shows, as its selector's own button reads. */
    private fun chooseSection(tag: String, from: String, to: String) {
        inPane(tag, "Section: $from").performClick()
        compose.onNodeWithText(to).performClick()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1000dp-h800dp")
    fun theSplitIsTwoPanesOfSettingsEachWithItsOwnSection() {
        show(expandedLayout = true)

        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val left = compose.onNodeWithTag(PRINT_SETTINGS_LEFT_PANE_TAG).fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithTag(PRINT_SETTINGS_RIGHT_PANE_TAG).fetchSemanticsNode().boundsInRoot

        assertTrue("the left pane starts on the left, its edge was ${left.left}", left.left < root.width / 4f)
        assertTrue(
            "the right pane reaches the right edge, it was ${right.right} of ${root.width}",
            right.right > root.width * 3f / 4f,
        )
        assertTrue(
            "the panes meet at the divider, they were ${left.right} and ${right.left}",
            right.left - left.right > 0f && right.left - left.right < 60f,
        )
        assertTrue(
            "both panes are one height, they were ${left.height} and ${right.height}",
            abs(left.height - right.height) < 2f,
        )
        assertTrue(
            "the split fills the height it is given, it was ${left.height} of ${root.height}",
            left.height > root.height * 0.5f,
        )

        // Both panes are settings - the first field of two different sections - and
        // each carries the selector that chose it.
        inPane(PRINT_SETTINGS_LEFT_PANE_TAG, "Section: Quality").assertIsDisplayed()
        inPane(PRINT_SETTINGS_LEFT_PANE_TAG, "Layer height (mm)").assertIsDisplayed()
        inPane(PRINT_SETTINGS_RIGHT_PANE_TAG, "Section: Walls and top/bottom").assertIsDisplayed()
        inPane(PRINT_SETTINGS_RIGHT_PANE_TAG, "Wall thickness (mm)").assertIsDisplayed()
        compose.onNodeWithText("Wall thickness (mm)").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1000dp-h800dp")
    fun onePanesChoiceLeavesTheOtherPaneAlone() {
        show(expandedLayout = true)

        chooseSection(PRINT_SETTINGS_LEFT_PANE_TAG, "Quality", "Supports")

        inPane(PRINT_SETTINGS_LEFT_PANE_TAG, "Section: Supports").assertIsDisplayed()
        inPane(PRINT_SETTINGS_LEFT_PANE_TAG, "Generate supports").assertIsDisplayed()
        inPane(PRINT_SETTINGS_LEFT_PANE_TAG, "Layer height (mm)").assertDoesNotExist()
        // The right pane is where it was, selector and fields alike.
        inPane(PRINT_SETTINGS_RIGHT_PANE_TAG, "Section: Walls and top/bottom").assertIsDisplayed()
        inPane(PRINT_SETTINGS_RIGHT_PANE_TAG, "Wall thickness (mm)").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1000dp-h800dp")
    fun anEditMadeInAPaneSurvivesASectionChange() {
        show(expandedLayout = true)

        val field = inPane(PRINT_SETTINGS_LEFT_PANE_TAG, "Layer height (mm)")
        field.performTextReplacement("0.3")
        assertEquals(
            "an edit applies immediately, exactly as it does on the phone",
            listOf(SlicerSettings.Keys.LAYER_HEIGHT),
            registered,
        )
        assertEquals(0.3, settings.value.layerHeightMm, 1e-9)

        chooseSection(PRINT_SETTINGS_LEFT_PANE_TAG, "Quality", "Infill")
        inPane(PRINT_SETTINGS_LEFT_PANE_TAG, "Infill density (%)").assertIsDisplayed()

        chooseSection(PRINT_SETTINGS_LEFT_PANE_TAG, "Infill", "Quality")
        val back = inPane(PRINT_SETTINGS_LEFT_PANE_TAG, "Layer height (mm)")
        back.assertIsDisplayed()
        assertEquals(
            "the section comes back at the value it was left at",
            "0.3",
            back.fetchSemanticsNode().config[SemanticsProperties.EditableText].text,
        )
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1000dp-h800dp")
    fun aLongSectionScrollsInsideItsOwnPaneAndNeverMovesTheOther() {
        show(expandedLayout = true)

        val before = compose.onNodeWithTag(PRINT_SETTINGS_RIGHT_PANE_TAG).fetchSemanticsNode().boundsInRoot
        // Quality is far taller than the pane: its last row is only reachable by the
        // pane's own scroll.
        inPane(PRINT_SETTINGS_LEFT_PANE_TAG, "Slicing tolerance: Middle · closest to surface")
            .performScrollTo()
            .assertIsDisplayed()

        val after = compose.onNodeWithTag(PRINT_SETTINGS_RIGHT_PANE_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the other pane did not move, it was ${before.top} and is ${after.top}",
            abs(before.top - after.top) < 1f && abs(before.height - after.height) < 1f,
        )
        val left = compose.onNodeWithTag(PRINT_SETTINGS_LEFT_PANE_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "the scrolled pane kept its own frame, it was ${before.height} and is ${left.height}",
            abs(before.height - left.height) < 1f,
        )
        inPane(PRINT_SETTINGS_LEFT_PANE_TAG, "Section: Quality").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1000dp-h800dp")
    fun everyRowThatIsNotACategoryIsStillReachableInAPane() {
        show(expandedLayout = true)

        // The header block is a section like the rest, so either selector reaches it.
        chooseSection(PRINT_SETTINGS_RIGHT_PANE_TAG, "Walls and top/bottom", "Printer & profile")

        compose.onNode(
            hasText("Printer: ", substring = true) and
                hasAnyAncestor(hasTestTag(PRINT_SETTINGS_RIGHT_PANE_TAG)),
        ).assertExists()
        inPane(PRINT_SETTINGS_RIGHT_PANE_TAG, "Import settings from Cura profile (.curaprofile)")
            .performScrollTo().assertIsDisplayed()
        inPane(PRINT_SETTINGS_RIGHT_PANE_TAG, "Import settings from Cura project (.3mf)")
            .performScrollTo().assertIsDisplayed()
        inPane(PRINT_SETTINGS_RIGHT_PANE_TAG, "Add more settings (all settings)")
            .performScrollTo().assertIsDisplayed()
        inPane(PRINT_SETTINGS_RIGHT_PANE_TAG, "Reset all app overrides")
            .performScrollTo().assertIsDisplayed()

        // And it still does what it says.
        inPane(PRINT_SETTINGS_RIGHT_PANE_TAG, "Add more settings (all settings)").performClick()
        assertEquals("the all-settings sheet is asked for", 1, openedAllSettings)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp")
    fun aPhoneKeepsTheOneColumn() {
        show(expandedLayout = false)

        compose.onNodeWithTag(PRINT_SETTINGS_LEFT_PANE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(PRINT_SETTINGS_RIGHT_PANE_TAG).assertDoesNotExist()
        compose.onNodeWithText("Section: Quality").assertDoesNotExist()

        // The header block first, then the categories as the collapsible cards they
        // have always been, with Quality the one that is open.
        compose.onNodeWithText("Import settings from Cura profile (.curaprofile)").assertIsDisplayed()
        compose.onNodeWithText("Walls and top/bottom").assertExists()
        compose.onNodeWithText("Layer height (mm)").assertExists()
        compose.onNodeWithText("Wall thickness (mm)").assertDoesNotExist()
    }
}
