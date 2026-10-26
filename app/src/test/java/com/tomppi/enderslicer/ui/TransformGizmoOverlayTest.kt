package com.tomppi.enderslicer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.viewer.TransformGizmoMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The floating transform card: the three modes, Done, and the Drop action beside
 * them that puts the selected object back on the build plate.
 *
 * Drop is an action, not a mode, so it is pinned here separately from the mode
 * row: it calls the panel's own callback, a disabled Drop shows the sentence that
 * says why, and the undo row is the same one that names a model drop.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class TransformGizmoOverlayTest {

    @get:Rule
    val compose = createComposeRule()

    private fun overlay(
        mode: TransformGizmoMode = TransformGizmoMode.MOVE,
        undoLabel: String? = null,
        canUndo: Boolean = false,
        dropEnabled: Boolean = true,
        dropDisabledReason: String? = null,
        onDrop: () -> Unit = {},
        onDone: () -> Unit = {},
        onUndo: () -> Unit = {},
        onMode: (TransformGizmoMode) -> Unit = {},
    ) {
        compose.setContent {
            TransformGizmoOverlay(
                mode = mode,
                readout = null,
                scalePercent = 100,
                compact = false,
                canUndo = canUndo,
                undoLabel = undoLabel,
                onUndo = onUndo,
                onMode = onMode,
                onScalePercent = {},
                onScaleFinished = {},
                onDone = onDone,
                onDrop = onDrop,
                dropEnabled = dropEnabled,
                dropDisabledReason = dropDisabledReason,
                objectCount = 1,
                onOpenMultiObject = {},
                modelName = "Case",
                actionsEnabled = true,
                onSplit = {},
                onRemove = {},
            )
        }
    }

    @Test
    fun dropSitsBesideDoneAndCallsTheDropAction() {
        var drops = 0
        var dones = 0
        overlay(onDrop = { drops++ }, onDone = { dones++ })

        compose.onNodeWithText("Rotate").assertIsDisplayed()
        compose.onNodeWithText("Move").assertIsDisplayed()
        compose.onNodeWithText("Scale").assertIsDisplayed()
        compose.onNodeWithText("Drop").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()

        compose.onNodeWithText("Drop").performClick()
        assertEquals(1, drops)
        // The Drop press is not a way out of the card.
        assertEquals(0, dones)

        compose.onNodeWithText("Done").performClick()
        assertEquals(1, dones)
    }

    @Test
    fun aDisabledDropSaysWhyAndDoesNothing() {
        var drops = 0
        overlay(
            dropEnabled = false,
            dropDisabledReason = "Nothing to drop: no model is selected",
            onDrop = { drops++ },
        )

        compose.onNodeWithText("Drop").assertIsDisplayed()
        compose.onNodeWithText("Nothing to drop: no model is selected").assertIsDisplayed()
        compose.onNodeWithText("Drop").performClick()
        assertEquals(0, drops)
    }

    @Test
    fun theDropUndoRowIsTheSameUndoThePanelAlreadyOffers() {
        var undos = 0
        overlay(canUndo = true, undoLabel = "Model dropped to the build plate", onUndo = { undos++ })

        compose.onNodeWithText("Undo Model dropped to the build plate").assertIsDisplayed()
        compose.onNodeWithText("Undo Model dropped to the build plate").performClick()
        assertEquals(1, undos)
    }

    @Test
    fun theModesStillSwitchAndDropStaysAnAction() {
        var chosen: TransformGizmoMode? = null
        overlay(onMode = { chosen = it })

        compose.onNodeWithText("Scale").performClick()
        assertEquals(TransformGizmoMode.SCALE, chosen)
    }
}
