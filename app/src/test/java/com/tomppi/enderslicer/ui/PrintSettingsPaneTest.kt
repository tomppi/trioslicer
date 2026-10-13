package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
 * The print settings' two shapes: the phone's one column and the wide window's two.
 *
 * The wide window is a **partition** of the phone's menu, not a copy of it: the
 * profile block and the first half of the sections in the left column, the rest in
 * the right, each column with its own scroll and its own open or closed cards. Every
 * section is reachable exactly once, and this compares the rendered sections of the
 * two columns against the phone's own list rather than trusting the split.
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
    private var wide by mutableStateOf(false)

    private fun show() {
        compose.setContent {
            CategorizedSettingsSheet(
                state = MainUiState(printer = printer, settings = settings.value),
                onSettings = { key, change ->
                    registered += key
                    settings.value = change(settings.value)
                },
                onResetOverrides = {},
                expandedLayout = wide,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    private fun goWide() {
        wide = true
        compose.waitForIdle()
    }

    /** A node of [tag]'s column that says [text]: this column holds that row. */
    private fun inPane(tag: String, text: String): SemanticsNodeInteraction =
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)))

    /**
     * Every settings card rendered under [pane] (or anywhere, for the phone's one
     * column), in the order it is drawn - read off the cards' own tags.
     */
    private fun renderedSections(pane: String?): List<String> {
        val sections = SemanticsMatcher("a settings card") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)
                ?.startsWith(SETTINGS_SECTION_TAG_PREFIX) == true
        }
        val matcher = if (pane == null) sections else sections and hasAnyAncestor(hasTestTag(pane))
        return compose.onAllNodes(matcher).fetchSemanticsNodes().map { node ->
            node.config[SemanticsProperties.TestTag].removePrefix(SETTINGS_SECTION_TAG_PREFIX)
        }
    }

    /**
     * The one the user asked for by name: the two columns together are exactly the
     * phone's sections - same names, same order, nothing twice and nothing missing.
     */
    @Test
    @Config(sdk = [35], qualifiers = "w1000dp-h800dp")
    fun theTwoColumnsAreThePhoneMenuPartitioned() {
        show()
        val phone = renderedSections(pane = null)
        assertEquals("the phone stacks every section", PrintSettingsSection.entries.size - 1, phone.size)

        goWide()
        val left = renderedSections(SETTINGS_LEFT_PANE_TAG)
        val right = renderedSections(SETTINGS_RIGHT_PANE_TAG)

        assertEquals(
            "the two columns together are the phone's list, in its order",
            phone,
            left + right,
        )
        assertTrue("both columns carry sections", left.isNotEmpty() && right.isNotEmpty())
        assertTrue(
            "and nothing is in both, it was $left and $right",
            left.intersect(right.toSet()).isEmpty(),
        )
    }

    /** The profile block is the left column's, once: it is not a section. */
    @Test
    @Config(sdk = [35], qualifiers = "w1000dp-h800dp")
    fun theProfileBlockIsOnlyInTheLeftColumn() {
        show()
        goWide()

        listOf("Add more settings (all settings)", "Add settings to the sidebar", "Reset all app overrides")
            .forEach { row ->
                compose.onAllNodesWithText(row).assertCountEquals(1)
                inPane(SETTINGS_LEFT_PANE_TAG, row).assertExists()
            }
        inPane(SETTINGS_LEFT_PANE_TAG, "Import settings from Cura profile (.curaprofile)").assertExists()
        inPane(SETTINGS_RIGHT_PANE_TAG, "Import settings from Cura profile (.curaprofile)").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1000dp-h800dp")
    fun theWideLayoutIsTwoColumnsOfTheMenu() {
        show()
        goWide()

        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val left = compose.onNodeWithTag(SETTINGS_LEFT_PANE_TAG).fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithTag(SETTINGS_RIGHT_PANE_TAG).fetchSemanticsNode().boundsInRoot

        assertTrue("the left column starts on the left, its edge was ${left.left}", left.left < root.width / 4f)
        assertTrue(
            "the right column reaches the right edge, it was ${right.right} of ${root.width}",
            right.right > root.width * 3f / 4f,
        )
        assertTrue(
            "the columns meet at the divider, they were ${left.right} and ${right.left}",
            right.left - left.right > 0f && right.left - left.right < 60f,
        )
        assertTrue(
            "both columns are one height, they were ${left.height} and ${right.height}",
            abs(left.height - right.height) < 2f,
        )
        assertTrue(
            "the columns fill the height they are given, it was ${left.height} of ${root.height}",
            left.height > root.height * 0.5f,
        )
        compose.onNodeWithText("Section: Quality").assertDoesNotExist()
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1000dp-h800dp")
    fun theTwoColumnsScrollAndOpenTheirOwnCards() {
        show()
        goWide()

        // Quality open on the left only: the right column's own card is still shut.
        inPane(SETTINGS_LEFT_PANE_TAG, "Quality").performClick()
        inPane(SETTINGS_LEFT_PANE_TAG, "Layer height (mm)").assertIsDisplayed()
        inPane(SETTINGS_RIGHT_PANE_TAG, "Layer height (mm)").assertDoesNotExist()

        // The right column scrolls to one of its own sections and opens that.
        inPane(SETTINGS_RIGHT_PANE_TAG, "Supports").performScrollTo().performClick()
        inPane(SETTINGS_RIGHT_PANE_TAG, "Generate supports").performScrollTo().assertIsDisplayed()

        // Neither column's scroll moved the other: the left still shows the card it
        // opened, and the two keep their own frames.
        inPane(SETTINGS_LEFT_PANE_TAG, "Layer height (mm)").assertIsDisplayed()
        val left = compose.onNodeWithTag(SETTINGS_LEFT_PANE_TAG).fetchSemanticsNode().boundsInRoot
        val right = compose.onNodeWithTag(SETTINGS_RIGHT_PANE_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("the columns kept their own frames", left.right < right.left)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w1000dp-h800dp")
    fun anEditMadeInAColumnSurvivesACardChange() {
        show()
        goWide()

        inPane(SETTINGS_LEFT_PANE_TAG, "Quality").performClick()
        val field = inPane(SETTINGS_LEFT_PANE_TAG, "Layer height (mm)")
        field.performTextReplacement("0.3")
        assertEquals(
            "an edit applies immediately, exactly as it does on the phone",
            listOf(SlicerSettings.Keys.LAYER_HEIGHT),
            registered,
        )
        assertEquals(0.3, settings.value.layerHeightMm, 1e-9)

        // Shut the card and open another one in the same column, then come back.
        inPane(SETTINGS_LEFT_PANE_TAG, "Quality").performClick()
        inPane(SETTINGS_LEFT_PANE_TAG, "Walls and top/bottom").performScrollTo().performClick()
        inPane(SETTINGS_LEFT_PANE_TAG, "Wall thickness (mm)").assertIsDisplayed()
        inPane(SETTINGS_LEFT_PANE_TAG, "Walls and top/bottom").performScrollTo().performClick()
        inPane(SETTINGS_LEFT_PANE_TAG, "Quality").performScrollTo().performClick()

        val back = inPane(SETTINGS_LEFT_PANE_TAG, "Layer height (mm)")
        back.assertIsDisplayed()
        assertEquals(
            "the card comes back at the value it was left at",
            "0.3",
            back.fetchSemanticsNode().config[SemanticsProperties.EditableText].text,
        )
    }

    @Test
    @Config(sdk = [35], qualifiers = "w411dp-h891dp")
    fun aPhoneKeepsTheOneColumnWithEverything() {
        show()

        compose.onNodeWithTag(SETTINGS_LEFT_PANE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(SETTINGS_RIGHT_PANE_TAG).assertDoesNotExist()
        compose.onNodeWithText("Section: Quality").assertDoesNotExist()

        assertEquals(
            "the phone's column is every section, in the app's own order",
            PrintSettingsSection.entries
                .filter { it != PrintSettingsSection.PRINTER }
                .map { it.label },
            renderedSections(pane = null),
        )
        // The header block first, then the categories as the collapsible cards they
        // have always been, with Quality the one that is open.
        compose.onNodeWithText("Import settings from Cura profile (.curaprofile)").assertIsDisplayed()
        compose.onNodeWithText("Walls and top/bottom").assertExists()
        compose.onNodeWithText("Layer height (mm)").assertExists()
        compose.onNodeWithText("Wall thickness (mm)").assertDoesNotExist()
    }
}
