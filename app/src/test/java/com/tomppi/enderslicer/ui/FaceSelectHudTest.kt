package com.tomppi.enderslicer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.solidcraft.FacePoint
import com.tomppi.enderslicer.solidcraft.FaceSurface
import com.tomppi.enderslicer.solidcraft.SelectedFace
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The face chip over the plate: it names the selected face and clears it in one tap.
 *
 * The chip is the human's half of the selection - the face itself is amber in the renderer - so
 * what it must say is pinned here: body, face and index, surface, area, which way it faces from
 * the camera, and a single Clear.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class FaceSelectHudTest {

    @get:Rule
    val compose = createComposeRule()

    private fun face(): SelectedFace = SelectedFace(
        body = "Body1",
        index = 1,
        name = "F1:-y",
        surface = FaceSurface.PLANAR,
        exact = true,
        areaMm2 = 480.0,
        centroid = FacePoint(20.0, 0.0, 6.0),
        normal = FacePoint(0.0, -1.0, 0.0),
        pickPoint = FacePoint(20.0, 0.0, 6.0),
        cylinderRadiusMm = null,
        viewWords = "facing you",
    )

    @Test
    fun theChipNamesTheFaceAndClearsInOneTap() {
        var clears = 0
        var dones = 0
        compose.setContent {
            FaceSelectHud(
                face = face(),
                picking = true,
                onClear = { clears++ },
                onDone = { dones++ },
            )
        }
        compose.onNodeWithText("Selected face").assertIsDisplayed()
        val line = face().hudLine()
        compose.onNodeWithText(line).assertIsDisplayed()
        // The three facts the user needs to trust the tint: body/face, surface and area, direction.
        assertEquals(true, line.contains("Body1 · F1:-y (face 1)"))
        assertEquals(true, line.contains("planar"))
        assertEquals(true, line.contains("480 mm²"))
        assertEquals(true, line.contains("facing you"))

        compose.onNodeWithText("Clear").performClick()
        assertEquals("Clear is one tap", 1, clears)
        compose.onNodeWithText("Done").performClick()
        assertEquals(1, dones)
    }

    @Test
    fun pickingWithNoFaceYetSaysWhatToDo() {
        var dones = 0
        compose.setContent {
            FaceSelectHud(
                face = null,
                picking = true,
                onClear = {},
                onDone = { dones++ },
            )
        }
        compose.onNodeWithText("Select a face").assertIsDisplayed()
        compose.onNodeWithText(
            "Tap a face on the plate: the plate tints it amber, the engine selects it, " +
                "and the next message to the agent names it.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        assertEquals(1, dones)
    }
}
