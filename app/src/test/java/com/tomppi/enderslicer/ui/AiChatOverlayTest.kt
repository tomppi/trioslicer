package com.tomppi.enderslicer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.cadagent.AgentToolTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The floating chat on a JVM: two sources, tool rows, the author line, Stop and the transcript.
 *
 * The harness path is a first-class assertion here, not a by-product: switching to Harness must
 * still show the launch-URL setup and still stop through the harness's own Stop, exactly as it
 * did before the in-app source existed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AiChatOverlayTest {

    @get:Rule
    val compose = createComposeRule()

    private var harnessStops = 0

    private fun boxRow(): AiChatMessage = AiChatMessage(
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

    private fun inApp(
        messages: List<AiChatMessage> = emptyList(),
        busy: Boolean = false,
        pausedReason: String? = null,
        configured: Boolean = true,
        onSend: (String) -> Unit = {},
        onStop: () -> Unit = {},
        onCopyTranscript: (String) -> Unit = {},
    ): InAppChat = InAppChat(
        messages = messages,
        busy = busy,
        status = null,
        authorLine = "Author: nobody",
        pausedReason = pausedReason,
        configured = configured,
        onSend = onSend,
        onStop = onStop,
        onCopyTranscript = onCopyTranscript,
    )

    private fun show(
        inApp: InAppChat?,
        harnessMessages: List<AiChatMessage> = emptyList(),
        harnessBusy: Boolean = false,
        setup: ChatSetup? = ChatSetup(address = "", onAddressChange = {}, onConnect = {}),
    ) {
        compose.setContent {
            AiChatOverlay(
                messages = harnessMessages,
                onSend = {},
                onUploadImage = {},
                onClose = {},
                onStop = { harnessStops++ },
                setup = setup,
                busy = harnessBusy,
                inApp = inApp,
                modifier = Modifier.fillMaxSize(),
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun theInAppSourceIsTheDefaultAndShowsTheConversationWithItsToolRows() {
        show(
            inApp(
                messages = listOf(
                    AiChatMessage(fromUser = true, text = "a 40 x 30 x 10 box with 3 mm fillets"),
                    boxRow(),
                    AiChatMessage(fromUser = false, text = "the box is on the plate"),
                ),
            ),
        )

        compose.onNodeWithText("Author: nobody").assertIsDisplayed()
        // The transcript scrolls; a message is present even when the panel's height puts it
        // just outside the visible part of the scroll.
        compose.onNodeWithText("a 40 x 30 x 10 box with 3 mm fillets").assertExists()
        compose.onNodeWithText("solid.box").assertExists()
        compose.onNodeWithText("1 body created (Body1)").assertExists()
        compose.onNodeWithText("the box is on the plate").assertExists()
        // The harness setup is not this source's content.
        compose.onNodeWithText("Launch URL").assertDoesNotExist()
    }

    @Test
    fun switchingToHarnessKeepsTheHarnessPathIntactAndComesBack() {
        show(inApp(messages = listOf(boxRow())))

        compose.onNodeWithText("Harness").performClick()
        compose.onNodeWithText("Launch URL").assertIsDisplayed()
        compose.onNodeWithText("Author: nobody").assertDoesNotExist()

        compose.onNodeWithText("CAD agent (in-app)").performClick()
        compose.onNodeWithText("solid.box").assertIsDisplayed()
        compose.onNodeWithText("Author: nobody").assertIsDisplayed()
    }

    @Test
    fun theHarnessStopStaysTheHarnessStop() {
        // A configured harness has no setup panel, so its composer and Stop are shown.
        show(inApp(), harnessBusy = true, setup = null)
        compose.onNodeWithText("Harness").performClick()
        compose.onNodeWithText("Stop").assertIsDisplayed().performClick()
        assertEquals("the harness path still stops itself", 1, harnessStops)
    }

    @Test
    fun stopWhileTheInAppAgentRunsCancelsThatTurn() {
        var stops = 0
        show(inApp(busy = true, onStop = { stops++ }))
        compose.onNodeWithText("Stop").assertIsDisplayed().performClick()
        assertEquals(1, stops)
    }

    @Test
    fun copyTranscriptHandsOverTheMessagesAndTheToolRows() {
        var copied: String? = null
        show(
            inApp(
                messages = listOf(
                    AiChatMessage(fromUser = true, text = "a 40 x 30 x 10 box with 3 mm fillets"),
                    boxRow(),
                    AiChatMessage(fromUser = false, text = "the box is on the plate"),
                ),
                onCopyTranscript = { copied = it },
            ),
        )

        compose.onNodeWithText("Copy transcript").assertIsDisplayed().performClick()

        val text = copied
        assertNotNull("the caller gets transcript text to copy", text)
        assertTrue(text!!.contains("You: a 40 x 30 x 10 box with 3 mm fillets"))
        assertTrue("the tool row is in the copy: " + text, text.contains("tool: solid.box"))
        assertTrue(text.contains("1 body created (Body1)"))
        assertTrue(text.contains("Agent: the box is on the plate"))
        assertFalse("no credential can be in it", text.contains("sk-"))
    }

    @Test
    fun anAttachedExternalAgentPausesTheChatAndSaysSoInTheHeader() {
        show(
            inApp(
                pausedReason = "external agent connected - in-app agent paused - take the " +
                    "document back in the CAD agent sheet.",
            ),
        )

        compose.onNodeWithText("Author: nobody · in-app agent paused").assertIsDisplayed()
        compose.onNodeWithText(
            "external agent connected - in-app agent paused - take the document back in the " +
                "CAD agent sheet.",
        ).assertIsDisplayed()
        // The composer cannot send while another author has the document.
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }
}
