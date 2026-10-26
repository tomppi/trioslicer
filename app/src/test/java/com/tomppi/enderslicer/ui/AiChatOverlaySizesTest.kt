package com.tomppi.enderslicer.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.cadagent.AgentToolTrace
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The floating chat's sizes on a JVM: bubble, panel, full screen - with the expand/collapse
 * controls and the system Back gesture.
 *
 * The activity rule is the Back gesture's: the chat registers its own handler while composed, so
 * back from full screen collapses and a second back closes - exactly the contract the phone walk
 * shows.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AiChatOverlaySizesTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var stops = 0
    private var sends = 0

    private fun toolRow(): AiChatMessage = AiChatMessage(
        fromUser = false,
        text = "",
        kind = AiChatEntryKind.TOOL,
        tool = AgentToolTrace(
            callId = 1,
            tool = "execute",
            name = "solid.box",
            arguments = "{length=40, width=30, height=10}",
            running = false,
            durationMillis = 12,
            outcome = "1 body created (Body1)",
        ),
    )

    private fun inApp(busy: Boolean): InAppChat = InAppChat(
        messages = listOf(
            AiChatMessage(fromUser = true, text = "build a 40 x 30 x 10 box"),
            toolRow(),
            AiChatMessage(fromUser = false, text = "the box is on the plate"),
        ),
        busy = busy,
        status = null,
        authorLine = "Author: nobody",
        configured = true,
        onSend = { sends++ },
        onStop = { stops++ },
        onCopyTranscript = { },
    )

    private fun show(initial: ChatSize = ChatSize.BUBBLE, busy: Boolean = false) {
        compose.setContent {
            var size by remember { mutableStateOf(initial) }
            var expanded by remember { mutableStateOf(ChatSize.PANEL) }
            var closed by remember { mutableStateOf(false) }
            if (closed) {
                Text("chat closed")
            } else {
                AiChatOverlay(
                    messages = emptyList(),
                    onSend = { sendCounter++ },
                    onUploadImage = { },
                    onClose = { closed = true },
                    onStop = { harnessStops++ },
                    inApp = inApp(busy),
                    size = size,
                    lastExpandedSize = expanded,
                    onSizeChange = { wanted ->
                        size = wanted
                        expanded = ChatSizes.rememberExpanded(expanded, wanted)
                    },
                    onBack = { action ->
                        when (action) {
                            ChatBackAction.TO_PANEL -> {
                                size = ChatSize.PANEL
                                expanded = ChatSize.PANEL
                            }
                            ChatBackAction.CLOSE -> closed = true
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        compose.waitForIdle()
    }

    private var sendCounter = 0
    private var harnessStops = 0

    @Test
    fun theThreeSizesWalkBubbleToPanelToFullAndBack() {
        show(ChatSize.BUBBLE)

        compose.onNodeWithContentDescription("Open AI chat").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Expand chat").assertIsDisplayed().performClick()

        // Full screen: the same surface, the top bar and the readable transcript.
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Collapse chat").assertIsDisplayed()
        compose.onNodeWithText("Author: nobody").assertIsDisplayed()
        compose.onNodeWithText("solid.box").assertExists()
        compose.onNodeWithText("1 body created (Body1)").assertExists()

        compose.onNodeWithContentDescription("Collapse chat").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Expand chat").assertIsDisplayed()
        compose.onNodeWithText("solid.box").assertExists()

        compose.onNodeWithContentDescription("Hide chat").performClick()
        compose.onNodeWithContentDescription("Open AI chat").assertIsDisplayed()
    }

    @Test
    fun backFromFullScreenCollapsesToThePanelAndTheSecondBackCloses() {
        show(ChatSize.PANEL)
        compose.onNodeWithContentDescription("Expand chat").performClick()
        compose.onNodeWithContentDescription("Collapse chat").assertIsDisplayed()

        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
        // Back from full screen is a collapse, not a close.
        compose.onNodeWithContentDescription("Expand chat").assertIsDisplayed()
        compose.onNodeWithText("chat closed").assertDoesNotExist()

        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
        compose.onNodeWithText("chat closed").assertIsDisplayed()
    }

    @Test
    fun aRunningTurnIsUntouchedByAResize() {
        stops = 0
        sends = 0
        show(ChatSize.PANEL, busy = true)

        // Running: the tool row and Stop are both there.
        compose.onNodeWithText("solid.box").assertExists()
        compose.onNodeWithText("Stop").assertIsDisplayed()

        compose.onNodeWithContentDescription("Expand chat").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("solid.box").assertExists()
        compose.onNodeWithText("the box is on the plate").assertExists()
        compose.onNodeWithText("Stop").assertIsDisplayed()
        assertEquals("resizing must not stop the turn", 0, stops)
        assertEquals("resizing must not send anything", 0, sends)

        compose.onNodeWithContentDescription("Collapse chat").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("solid.box").assertExists()
        compose.onNodeWithText("Stop").assertIsDisplayed()
        assertEquals(0, stops)

        compose.onNodeWithText("Stop").performClick()
        assertEquals("Stop still cancels the same turn", 1, stops)
    }
}
