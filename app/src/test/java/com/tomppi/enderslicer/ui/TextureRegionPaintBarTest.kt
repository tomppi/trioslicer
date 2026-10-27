package com.tomppi.enderslicer.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The studio's own region bar.
 *
 * It is NOT the support-paint bar: the title, the one-line hint, the two Draw/Erase buttons (no
 * Block) and the live count are what tell the user which tool they are in, and Done is the way
 * back to the studio with the region kept.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w400dp-h800dp")
class TextureRegionPaintBarTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun itSaysTextureRegionAndPaintsBothWaysWithALiveCount() {
        val brushes = mutableListOf<TextureRegionBrush>()
        var done = 0
        compose.setContent {
            TextureRegionPaintBar(
                brush = TextureRegionBrush.DRAW,
                paintedTriangles = 1234,
                meshTriangles = 5000,
                onBrush = { brushes += it },
                onDone = { done++ },
            )
        }
        compose.waitForIdle()

        compose.onNodeWithText("Texture region").assertIsDisplayed()
        compose.onNodeWithText("Drag on the model to choose which faces get the texture.")
            .assertIsDisplayed()
        compose.onNodeWithText("1,234 of 5,000 triangles painted").assertIsDisplayed()
        compose.onNodeWithText("Block").assertDoesNotExist()

        compose.onNodeWithText("Erase").performClick()
        assertEquals(listOf(TextureRegionBrush.ERASE), brushes)
        compose.onNodeWithText("Draw").performClick()
        assertEquals(listOf(TextureRegionBrush.ERASE, TextureRegionBrush.DRAW), brushes)

        compose.onNodeWithTag(TEXTURE_REGION_DONE_TAG).assertIsDisplayed().performClick()
        assertEquals(1, done)
    }

    @Test
    fun eraseIsHighlightedWhileItIsTheArmedBrush() {
        compose.setContent {
            TextureRegionPaintBar(
                brush = TextureRegionBrush.ERASE,
                paintedTriangles = 0,
                meshTriangles = 100,
                onBrush = {},
                onDone = {},
            )
        }
        compose.waitForIdle()
        compose.onNodeWithText("0 of 100 triangles painted").assertIsDisplayed()
        compose.onNodeWithText("Erase").assertIsDisplayed()
    }
}
