package com.tomppi.enderslicer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.data.PlatePreferences
import com.tomppi.enderslicer.model.SlicerEngine
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * What the plate sheet promises about object labels.
 *
 * "Label each object in the G-code" is on by default, and this build of CuraEngine writes no
 * object markers at all - only ;MESH: comments naming a staging file the workspace deletes - so
 * the switch did nothing on the engine the app ships as its default. The promise is withdrawn
 * on that engine with the note the panel shows; PrusaSlicer and OrcaSlicer keep it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MultiObjectSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show(engine: SlicerEngine) {
        compose.setContent {
            MultiObjectSheet(
                preferences = PlatePreferences(),
                onPreferences = {},
                onArrangeNow = {},
                objectCount = 2,
                engine = engine,
                onDismiss = {},
            )
        }
    }

    @Test
    fun curaEngineSaysTheLabelSwitchDoesNothingThere() {
        show(SlicerEngine.CURA)

        compose.onNodeWithText("emits no M486", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun prusaEngineMakesThePromiseAndKeepsItQuiet() {
        show(SlicerEngine.PRUSA)

        compose.onNodeWithText("emits no M486", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Label each object in the G-code").performScrollTo().assertIsDisplayed()
    }
}
