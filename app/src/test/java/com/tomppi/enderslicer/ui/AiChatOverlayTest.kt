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
 * The floating chat on a JVM: two backends, two conversations.
 *
 * The harness path is a first-class assertion here, not a by-product: switching to Harness shows
 * the harness session's own messages and its own setup, and stopping it is the harness Stop. The
 * in-app source shows the in-app conversation with its tool rows and nothing from the harness -
 * the two are separate lists and switching never shows the other one.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AiChatOverlayTest {

    @get:Rule
    val compose = createComposeRule()

    private var stops = 0

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
        configured: Boolean = true,
    ): InAppChat = InAppChat(
        messages = messages,
        busy = busy,
        status = null,
        backendLine = "In-app - fake-model at example.test",
        configured = configured,
        onSend = {},
    )

    private fun show(
        inApp: InAppChat?,
        harnessMessages: List<AiChatMessage> = emptyList(),
        harnessBusy: Boolean = false,
        harnessLine: String = "Harness - session connected",
        setup: ChatSetup? = null,
        pausedReason: String? = null,
        onCopyTranscript: (String) -> Unit = {},
    ) {
        compose.setContent {
            AiChatOverlay(
                messages = harnessMessages,
                onSend = {},
                onClose = {},
                onStop = { stops++ },
                setup = setup,
                busy = harnessBusy,
                inApp = inApp,
                authorLine = "Author: nobody",
                authorPausedReason = pausedReason,
                harnessLine = harnessLine,
                onCopyTranscript = onCopyTranscript,
                modifier = Modifier.fillMaxSize(),
            )
        }
        compose.waitForIdle()
    }

    @Test
    fun theInAppSourceIsTheDefaultAndShowsOnlyItsOwnConversationWithToolRows() {
        show(
            inApp(
                messages = listOf(
                    AiChatMessage(fromUser = true, text = "a 40 x 30 x 10 box with 3 mm fillets"),
                    boxRow(),
                    AiChatMessage(fromUser = false, text = "the box is on the plate"),
                ),
            ),
            harnessMessages = listOf(
                AiChatMessage(fromUser = true, text = "the Pi harness was asked about a case"),
                AiChatMessage(fromUser = false, text = "harness only reply"),
            ),
        )

        // The backend line names the phone's own agent and its endpoint.
        compose.onNodeWithText("In-app - fake-model at example.test").assertIsDisplayed()
        compose.onNodeWithText("Author: nobody").assertIsDisplayed()
        compose.onNodeWithText("solid.box").assertExists()
        compose.onNodeWithText("1 body created (Body1)").assertExists()
        compose.onNodeWithText("the box is on the plate").assertExists()
        // The harness conversation is not this source's content.
        compose.onNodeWithText("the Pi harness was asked about a case").assertDoesNotExist()
        compose.onNodeWithText("harness only reply").assertDoesNotExist()
        compose.onNodeWithText("Launch URL").assertDoesNotExist()
    }

    @Test
    fun switchingTheSourceShowsTheRightConversationAndNeverTheOther() {
        show(
            inApp(messages = listOf(AiChatMessage(fromUser = false, text = "in-app only reply"))),
            harnessMessages = listOf(AiChatMessage(fromUser = false, text = "harness only reply")),
        )
        // Default: in-app.
        compose.onNodeWithText("in-app only reply").assertExists()
        compose.onNodeWithText("harness only reply").assertDoesNotExist()

        compose.onNodeWithText("Harness").performClick()
        compose.onNodeWithText("harness only reply").assertExists()
        compose.onNodeWithText("in-app only reply").assertDoesNotExist()
        compose.onNodeWithText("Harness - session connected").assertIsDisplayed()

        compose.onNodeWithText("CAD agent (in-app)").performClick()
        compose.onNodeWithText("in-app only reply").assertExists()
        compose.onNodeWithText("harness only reply").assertDoesNotExist()
    }

    @Test
    fun eachBackendHasItsOwnEmptyState() {
        show(inApp(), harnessLine = "Harness - not connected")

        compose.onNodeWithText("No in-app conversation yet - ask for a change. Every tool " +
            "the in-app agent runs will show up here as a row.").assertIsDisplayed()

        compose.onNodeWithText("Harness").performClick()
        compose.onNodeWithText(
            "No harness session - connect the harness from the Harness tab.",
        ).assertIsDisplayed()
    }

    @Test
    fun theHeaderNamesTheBackendAndItsStateNotJustTheSwitch() {
        show(inApp(), harnessLine = "Harness - not connected")

        compose.onNodeWithText("In-app - fake-model at example.test").assertIsDisplayed()
        // The author line stays in both sources; it is the document's, not a backend's.
        compose.onNodeWithText("Author: nobody").assertIsDisplayed()

        compose.onNodeWithText("Harness").performClick()
        compose.onNodeWithText("Harness - not connected").assertIsDisplayed()
        compose.onNodeWithText("Author: nobody").assertIsDisplayed()
    }

    @Test
    fun theHarnessSetupStillCoversTheComposerWhenItIsNotConnected() {
        show(
            inApp(),
            setup = ChatSetup(address = "", onAddressChange = {}, onConnect = {}),
        )
        // In-app has its composer; the harness setup is not this source's content.
        compose.onNodeWithText("Launch URL").assertDoesNotExist()

        compose.onNodeWithText("Harness").performClick()
        compose.onNodeWithText("Launch URL").assertIsDisplayed()
    }

    @Test
    fun stopStopsTheShownBackend() {
        stops = 0
        show(inApp(busy = true), harnessBusy = true)
        // In-app running.
        compose.onNodeWithText("Stop").assertIsDisplayed().performClick()
        assertEquals(1, stops)
        // The same Stop button serves the harness when that source is shown.
        compose.onNodeWithText("Harness").performClick()
        compose.onNodeWithText("Stop").assertIsDisplayed().performClick()
        assertEquals(2, stops)
    }

    @Test
    fun copyTranscriptCopiesTheShownBackend() {
        var copied: String? = null
        show(
            inApp(
                messages = listOf(
                    AiChatMessage(fromUser = true, text = "a 40 x 30 x 10 box with 3 mm fillets"),
                    boxRow(),
                    AiChatMessage(fromUser = false, text = "the box is on the plate"),
                ),
            ),
            harnessMessages = listOf(AiChatMessage(fromUser = false, text = "harness only reply")),
            onCopyTranscript = { copied = it },
        )

        compose.onNodeWithText("Copy transcript").performClick()
        val inAppText = copied
        assertNotNull(inAppText)
        assertTrue(inAppText!!.contains("You: a 40 x 30 x 10 box with 3 mm fillets"))
        assertTrue("the tool row is in the in-app copy: " + inAppText, inAppText.contains("tool: solid.box"))
        assertFalse("no harness text in the in-app copy", inAppText.contains("harness only reply"))

        compose.onNodeWithText("Harness").performClick()
        compose.onNodeWithText("Copy transcript").performClick()
        val harnessText = copied
        assertNotNull(harnessText)
        assertTrue(harnessText!!.contains("Agent: harness only reply"))
        assertFalse("no tool rows in the harness copy", harnessText.contains("tool: solid.box"))
        assertFalse("no credential", harnessText.contains("sk-"))
    }

    @Test
    fun anAttachedExternalAgentPausesTheInAppChat() {
        show(
            inApp(),
            pausedReason = "external agent connected - in-app agent paused - take the " +
                "document back in the CAD agent sheet.",
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
