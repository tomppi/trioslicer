package com.tomppi.enderslicer.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.cadagent.AgentQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The question card in the chat: the question, one chip per option, the waiting line, and the
 * composer for free text when the question allows it.
 *
 * Answering clears the waiting flag (the answer is sent as the next user message by the host,
 * which is exactly what the app's answer callback does), and Dismiss clears it without a prompt.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AskUserFlowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var viewModel: MainViewModel

    private fun host() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.application.applicationInfo.nativeLibraryDir =
                java.io.File(activity.filesDir, "lib").apply { mkdirs() }.absolutePath
            viewModel = ViewModelProvider(activity)[MainViewModel::class.java]
            activity.setContent {
                EnderSlicerTheme { QuestionChatHost(viewModel) }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun theCardShowsTheQuestionItsChipsAndTheWaitingLine() {
        host()
        viewModel.onInAppQuestion(AgentQuestion("Which material should the lid use?", listOf("PLA", "PETG")))
        compose.waitForIdle()

        // The panel's transcript scrolls, so a card can sit just outside the visible part.
        compose.onNodeWithText("The agent asks").assertExists()
        compose.onNodeWithText("Which material should the lid use?").assertExists()
        compose.onNodeWithText("PLA").assertExists()
        compose.onNodeWithText("PETG").assertExists()
        compose.onNodeWithText("The agent is waiting on you.").assertExists()
        // The composer takes the free-text answer.
        compose.onNodeWithText("Type your answer").assertExists()
    }

    @Test
    fun tappingAChipAnswersTheQuestionAndClearsTheWait() {
        host()
        viewModel.onInAppQuestion(AgentQuestion("Which material should the lid use?", listOf("PLA", "PETG")))
        compose.waitForIdle()

        compose.onNodeWithText("PETG").performClick()
        compose.waitForIdle()

        assertNull("the wait is cleared by the answer", viewModel.pendingQuestion)
        val last = viewModel.cadConversations.inApp.last()
        assertTrue("the answer is sent as a user message", last.fromUser)
        assertEquals("PETG", last.text)
        // A chip answer says where it came from; it must not read as a typed answer.
        compose.onNodeWithText("Answered from the options.").assertExists()
    }

    @Test
    fun aTypedAnswerSaysAnswered() {
        host()
        viewModel.onInAppQuestion(AgentQuestion("Which material should the lid use?", listOf("PLA", "PETG")))
        compose.waitForIdle()

        compose.onNode(hasSetTextAction()).performTextInput("PETG")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitForIdle()

        assertNull("a typed answer clears the wait", viewModel.pendingQuestion)
        compose.onNodeWithText("Answered.").assertExists()
    }

    @Test
    fun dismissClearsTheWaitWithoutSendingAPrompt() {
        host()
        viewModel.onInAppQuestion(AgentQuestion("Which material should the lid use?", listOf("PLA", "PETG")))
        compose.waitForIdle()

        compose.onNodeWithText("Dismiss").performClick()
        compose.waitForIdle()

        assertNull(viewModel.pendingQuestion)
        assertTrue(
            "dismissing is not an answer: no user message is added",
            viewModel.cadConversations.inApp.none { it.fromUser },
        )
        // A dismissed card is history that was never answered - the old wording said
        // "Answered." for every non-waiting card, which is the confusion the audit found.
        compose.onNodeWithText("Dismissed - the agent was not given an answer.").assertExists()
        compose.onNodeWithText("Answered.").assertDoesNotExist()
    }

    @Test
    fun aClosedOptionSetClosesTheComposer() {
        host()
        viewModel.onInAppQuestion(
            AgentQuestion(
                question = "Which material?",
                options = listOf("PLA", "PETG"),
                allowText = false,
            ),
        )
        compose.waitForIdle()

        compose.onNodeWithText("Pick an option above").assertExists()
        compose.onNodeWithText("Dismiss").assertExists()
        // There is nothing to type: the send button is disabled and the composer is closed.
        compose.onNodeWithContentDescription("Send").assertIsNotEnabled()
    }
}

/** The real overlay, bound to the ViewModel's question state; a chip answer is a user message. */
@androidx.compose.runtime.Composable
private fun QuestionChatHost(viewModel: MainViewModel) {
    AiChatOverlay(
        messages = viewModel.cadConversations.harness,
        onSend = {},
        onClose = {},
        inApp = InAppChat(
            messages = viewModel.cadConversations.inApp,
            busy = viewModel.cadBusy,
            status = viewModel.cadStatus,
            backendLine = "In-app - fake-model at example.test",
            configured = true,
            onSend = {},
            question = viewModel.pendingQuestion,
            onAnswerQuestion = { answer, fromOptions ->
                resolveQuestion(
                    viewModel,
                    if (fromOptions) AiQuestionOutcome.ANSWERED_FROM_OPTIONS else AiQuestionOutcome.ANSWERED,
                )
                viewModel.clearPendingQuestion()
                viewModel.cadConversations = viewModel.cadConversations.appendInApp(
                    AiChatMessage(fromUser = true, text = answer),
                )
            },
            onDismissQuestion = {
                resolveQuestion(viewModel, AiQuestionOutcome.DISMISSED)
                viewModel.clearPendingQuestion()
            },
        ),
        source = ChatSource.IN_APP,
        authorLine = "Author: nobody",
        modifier = Modifier.fillMaxSize(),
    )
}

/** Record how the transcript's waiting question was resolved, as the app's callback does. */
private fun resolveQuestion(viewModel: MainViewModel, outcome: AiQuestionOutcome) {
    viewModel.cadConversations = viewModel.cadConversations.copy(
        inApp = viewModel.cadConversations.inApp.markQuestionResolved(
            viewModel.pendingQuestion,
            outcome,
        ),
    )
}
