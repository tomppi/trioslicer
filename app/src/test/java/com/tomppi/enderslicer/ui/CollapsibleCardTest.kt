package com.tomppi.enderslicer.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The Plate's floating cards on a JVM: folded, a card is nothing but its
 * chevron, and that chevron is what opens it again.
 */
@RunWith(AndroidJUnit4::class)
// SDK 35 for the same reason as the other Compose tests: Robolectric only
// drives 36/37 on a Java 21 test JVM, and this module's unit tests run on 17.
@Config(sdk = [35])
class CollapsibleCardTest {

    @get:Rule
    val compose = createComposeRule()

    private var toggles = 0

    private fun show(expanded: Boolean) {
        compose.setContent {
            CollapsibleCard(
                title = "Print session",
                expanded = expanded,
                onToggle = { toggles++ },
            ) {
                Text("body")
            }
        }
    }

    @Test
    fun foldedCardShowsOnlyItsChevron() {
        show(expanded = false)

        // No title, no content: the card is the arrow.
        compose.onNodeWithText("Print session").assertDoesNotExist()
        compose.onNodeWithText("body").assertDoesNotExist()

        compose.onNodeWithContentDescription("Expand Print session").assertIsDisplayed().performClick()

        assertEquals(1, toggles)
    }

    @Test
    fun openCardShowsHeaderAndContentAndFoldsFromTheHeader() {
        show(expanded = true)

        compose.onNodeWithText("Print session").assertIsDisplayed()
        compose.onNodeWithText("body").assertIsDisplayed()

        compose.onNodeWithText("Print session").performClick()

        assertEquals(1, toggles)
    }
}
