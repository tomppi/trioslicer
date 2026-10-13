package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.model.ExtraSettingSpec
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The picker behind the sidebar's Add button: what is already on the sidebar is
 * marked and can be taken off, what can be added is offered, and a setting whose
 * row would have no editor says so instead of being added dead.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp")
class SessionSidebarPickerTest {

    @get:Rule
    val compose = createComposeRule()

    private val wallLineCount = ExtraSettingSpec(
        key = "wall_line_count",
        label = "Wall Line Count",
        defaultValue = "1",
        numeric = true,
        minimum = 0.0,
        maximum = 999.0,
    )
    private val retractionEnable = ExtraSettingSpec(
        key = "retraction_enable",
        label = "Enable Retraction",
        defaultValue = "true",
        boolean = true,
    )
    private val startGcode = ExtraSettingSpec(
        key = "machine_start_gcode",
        label = "Start G-code",
        defaultValue = "G28",
    )

    private val added = mutableListOf<String>()
    private val removed = mutableListOf<String>()
    private val moved = mutableListOf<Pair<String, Int>>()

    private fun show(
        chosen: List<String>,
        specs: List<ExtraSettingSpec> = listOf(wallLineCount, retractionEnable),
        blocked: Set<String> = emptySet(),
    ) {
        compose.setContent {
            SessionSidebarPicker(
                engineLabel = "Cura",
                specs = specs,
                chosen = chosen,
                values = mapOf("wall_line_count" to "4"),
                blockedKeys = blocked,
                onAdd = { added += it },
                onRemove = { removed += it },
                onMove = { id, delta -> moved += id to delta },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    @Test
    fun aSettingWithNoEditorSaysSoInsteadOfOfferingADeadRow() {
        show(chosen = listOf(SIDEBAR_LAYER_HEIGHT), specs = listOf(wallLineCount, startGcode))

        compose.onNodeWithText("no sidebar editor: this is a text setting, not a number or a switch")
            .assertExists()
        // The number can be added, the text setting cannot: no Add beside it.
        compose.onAllNodesWithText("Add").assertCountEquals(1)
    }

    @Test
    fun addingASettingAsksForItByItsEngineKey() {
        show(chosen = emptyList(), specs = listOf(wallLineCount, startGcode))

        compose.onNodeWithText("Add").performClick()
        assertEquals(listOf("wall_line_count"), added)
    }

    @Test
    fun aSettingAlreadyOnTheSidebarIsMarkedAndCanBeTakenOff() {
        show(chosen = listOf("wall_line_count"))

        compose.onNodeWithText("on the sidebar", substring = true).assertExists()
        compose.onNodeWithText("Remove").performClick()
        assertEquals(listOf("wall_line_count"), removed)
        // One setting is left that can be added: the one already on the sidebar is
        // offered as a removal instead.
        compose.onAllNodesWithText("Add").assertCountEquals(1)
    }

    @Test
    fun theSidebarsOwnRowsAreListedAndCanBeMoved() {
        show(chosen = listOf(SIDEBAR_LAYER_HEIGHT, SIDEBAR_INFILL))

        compose.onNodeWithText("Layer height").assertExists()
        compose.onNodeWithContentDescription("Move Layer height down").performClick()
        assertEquals(listOf(SIDEBAR_LAYER_HEIGHT to 1), moved)
    }

    @Test
    fun aSettingTheAppManagesIsNamedAsThatRatherThanOffered() {
        show(chosen = emptyList(), blocked = setOf("wall_line_count"))

        compose.onNodeWithText("the app manages this setting on this engine").assertExists()
        compose.onAllNodesWithText("Add").assertCountEquals(1)
    }
}
