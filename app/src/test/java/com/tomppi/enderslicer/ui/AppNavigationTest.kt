package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The app's navigation on a JVM: the same four destinations, drawn as the bottom
 * bar on a phone and as a rail once the window is wide enough for one.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AppNavigationTest {

    @get:Rule
    val compose = createComposeRule()

    private fun show() {
        compose.setContent {
            NavigationSuiteScaffold(
                navigationSuiteItems = { AppTabItems(AppTab.PLATE) {} },
            ) {
                Box(modifier = Modifier.fillMaxSize())
            }
        }
    }

    /** Where the first destination's label sits is what tells a rail from a bar. */
    private fun firstLabelTopFraction(): Float {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithText("Plate").fetchSemanticsNode().boundsInRoot
        return (label.top - root.top) / root.height
    }

    @Test
    @Config(sdk = [35], qualifiers = "w460dp-h900dp")
    fun aPhoneKeepsTheBottomBar() {
        show()

        compose.onNodeWithText("Settings").assertExists()
        compose.onNodeWithText("Print").assertExists()
        compose.onNodeWithText("More").assertExists()
        assertTrue(
            "a phone's first destination is along the bottom, was at " +
                "${firstLabelTopFraction()}",
            firstLabelTopFraction() > 0.8f,
        )
    }

    // The wide layout's rail is the app's own [SessionRail], not the suite's:
    // see SessionRailTest, which pins the destinations and the session in it.
}
