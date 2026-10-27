package com.tomppi.enderslicer.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomppi.enderslicer.cadagent.AgentRunbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * The ask_user wiring, as a source contract plus the shipped runbook.
 *
 * Behaviour is covered by [AskUserFlowTest], [CadAgentAskUserTest], [CadQuestionNotificationTest]
 * and [ChatOpenRequestTest]; this pins the responsibilities a future change can quietly break:
 * one waiting flag, every clearing path accounted for, an attention path that opens the in-app
 * conversation (the only one that draws the card), a one-shot notification request, and the
 * runbook the agent actually receives.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AskUserSourceTest {

    private fun source(relative: String): String {
        val file = File("src/main/java", relative)
        assertTrue("missing " + file.path, file.isFile)
        return file.readText()
    }

    @Test
    fun onlyTheAskUserPathSetsTheWaitingFlag() {
        val viewModel = source("com/tomppi/enderslicer/ui/MainViewModel.kt")
        assertEquals(
            "the waiting flag is set in exactly one place: the question arriving from ask_user",
            1,
            Regex("pendingQuestion = question").findAll(viewModel).count(),
        )
        assertEquals(
            "and cleared in exactly one place",
            1,
            Regex("pendingQuestion = null").findAll(viewModel).count(),
        )
        assertTrue(viewModel.contains("fun onInAppQuestion(question: AgentQuestion)"))
        assertTrue(viewModel.contains("fun clearPendingQuestion()"))
    }

    @Test
    fun everyClearingPathIsAccountedForAndTheCardHistoryReportsIt() {
        val app = source("com/tomppi/enderslicer/ui/EnderSlicerApp.kt")
        // Answer, dismiss, any new prompt, document close, and the two Convert presses.
        assertTrue(
            "the answer records how it came in before clearing the wait",
            app.contains("onAnswerQuestion = { answer, fromOptions ->") &&
                app.contains("AiQuestionOutcome.ANSWERED_FROM_OPTIONS") &&
                app.contains("viewModel.clearPendingQuestion()"),
        )
        assertTrue(
            "dismissing records a dismissal, not an answer",
            app.contains("onDismissQuestion = {") &&
                app.contains("AiQuestionOutcome.DISMISSED"),
        )
        assertTrue(
            "a question cleared by any other path is marked as not answered",
            app.contains("fun resolvePendingQuestion(outcome: AiQuestionOutcome)") &&
                app.contains("markQuestionResolved(") &&
                app.contains("AiQuestionOutcome.IGNORED"),
        )
        assertTrue("a new prompt clears it", app.contains("// A new prompt ends any wait"))
        assertTrue(
            "closing the document clears it before closing",
            app.contains("onCloseDocument = {") &&
                app.contains(
                    "viewModel.clearPendingQuestion()\n                    viewModel.closeCadDocument()",
                ),
        )
        val clears = Regex("viewModel\\.clearPendingQuestion\\(\\)").findAll(app).count()
        assertTrue("all clearing sites are present: " + clears, clears >= 7)

        assertTrue(
            "the badge is for a waiting question the in-app pane is not showing",
            app.contains("askAiBadge = viewModel.pendingQuestion != null") &&
                app.contains("viewModel.chatSource == ChatSource.IN_APP"),
        )
        assertTrue(
            "the notification is posted only while the app is in the background, whatever source",
            app.contains("CadQuestionNotification.post(context, waiting)") &&
                app.contains("waiting != null && !appResumed"),
        )
        assertTrue(
            "and cancelled when the question is gone",
            app.contains("CadQuestionNotification.cancel(context)"),
        )
    }

    @Test
    fun openingTheChatForAQuestionSwitchesToTheConversationThatDrawsTheCard() {
        val app = source("com/tomppi/enderslicer/ui/EnderSlicerApp.kt")
        assertTrue(
            "the door switches the source before opening",
            app.contains("fun openChatForAttention()") &&
                app.contains("viewModel.chatSource = ChatSource.IN_APP") &&
                app.contains("setAgentMode(AgentMode.IN_APP)"),
        )
        assertTrue(
            "the rail badge and the notification both use that door",
            app.contains("onAskAi = { openChatForAttention() }") &&
                app.contains("if (ChatOpenRequest.takeUnhandled() != null) openChatForAttention()"),
        )
    }

    @Test
    fun aNotificationRequestIsConsumedOnceSoARecreateDoesNotReplayIt() {
        val app = source("com/tomppi/enderslicer/ui/EnderSlicerApp.kt")
        assertTrue(
            "the composition consumes the tick it acts on",
            app.contains("val chatOpenTicks by ChatOpenRequest.ticks.collectAsStateWithLifecycle()") &&
                app.contains("ChatOpenRequest.takeUnhandled()"),
        )
        assertFalse(
            "the old sticky rule opened the chat on every recreate",
            app.contains("if (chatOpenTicks > 0) aiChatOpen = true"),
        )
        val openRequest = source("com/tomppi/enderslicer/ui/ChatOpenRequest.kt")
        assertTrue(
            "the handled tick lives beside the counter",
            openRequest.contains("fun takeUnhandled(): Int?") &&
                openRequest.contains("private val handled = AtomicInteger(0)"),
        )
    }

    @Test
    fun aColdStartWithNoPendingQuestionRemovesItsStaleNotification() {
        val activity = source("com/tomppi/enderslicer/MainActivity.kt")
        assertTrue(
            "the create path cancels a notification the app can no longer back with a question",
            activity.contains("if (slicerViewModel.pendingQuestion == null) {") &&
                activity.contains("CadQuestionNotification.cancel(this)"),
        )
    }

    @Test
    fun theShippedRunbookExplainsWhenToAskAndThatAskingEndsTheTurn() {
        val shipped = AgentRunbook.load(ApplicationProvider.getApplicationContext<Context>())
        assertTrue("the runbook names the tool: " + shipped.take(80), shipped.contains("ask_user"))
        assertTrue(
            "the runbook says asking ends the turn",
            shipped.contains("Asking ends the turn."),
        )
        assertTrue(
            "the runbook says when to ask",
            shipped.contains("destructive or irreversible"),
        )
        val repoDoc = File("src/main/assets/agent/solidcraft-engine.md").takeIf { it.isFile }
            ?: File("../../docs/skills/solidcraft-engine.md")
        assertTrue("the runbook asset is present", repoDoc.isFile)
    }

    @Test
    fun theNotificationTapOpensTheChatInTheSameActivity() {
        // A second MainActivity would have its own empty ViewModel, so the notification tap must
        // reach onNewIntent of the running one.
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:launchMode=\"singleTop\""))
        val activity = source("com/tomppi/enderslicer/MainActivity.kt")
        assertTrue(activity.contains("consumeOpenChatIntent(intent)"))
        assertTrue(activity.contains("ChatOpenRequest.request()"))
        val notification = source("com/tomppi/enderslicer/ui/CadQuestionNotification.kt")
        assertTrue(notification.contains("ChatOpenRequest.EXTRA_OPEN_AI_CHAT"))
    }

    @Test
    fun theHarnessPathIsNotTouchedByTheInAppTool() {
        // The harness has its own ask_user on the harness side; this app-side tool exists only in
        // the in-app conversation and its card.
        val catalog = source("com/tomppi/enderslicer/cadagent/AgentToolCatalog.kt")
        assertTrue(catalog.contains("const val ASK_USER"))
        assertTrue(catalog.contains("app's own tool that asks the user a question"))
        assertFalse("the harness client itself is not edited for this", source("com/tomppi/enderslicer/ui/AiChatOverlay.kt").contains("harness ask_user"))
    }
}
