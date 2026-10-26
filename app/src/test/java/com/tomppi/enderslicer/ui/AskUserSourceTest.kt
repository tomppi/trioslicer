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
 * Behaviour is covered by [AskUserFlowTest], [CadAgentAskUserTest] and
 * [CadQuestionNotificationTest]; this pins the two things a future change can quietly break:
 * the waiting flag is set only by the ask_user path, and every dismissal path clears it - and the
 * runbook the agent actually receives explains when to ask and that asking ends the turn.
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
    fun everyDismissalPathClearsTheWaitAndTheRailBadgeIsOnlyForIt() {
        val app = source("com/tomppi/enderslicer/ui/EnderSlicerApp.kt")
        // Answer, dismiss, any new prompt, document close, and the two Convert presses.
        assertTrue(
            "the answer clears it before starting the next turn",
            app.contains("onAnswerQuestion = { answer ->") && app.contains("viewModel.clearPendingQuestion()"),
        )
        assertTrue(app.contains("onDismissQuestion = { viewModel.clearPendingQuestion() }"))
        assertTrue("a new prompt clears it", app.contains("// A new prompt ends any wait"))
        assertTrue(
            "closing the document clears it",
            app.contains("onCloseDocument = {\n                    viewModel.clearPendingQuestion()"),
        )
        val clears = Regex("viewModel\\.clearPendingQuestion\\(\\)").findAll(app).count()
        assertTrue("all clearing sites are present: " + clears, clears >= 7)

        assertTrue(
            "the rail badge is only the waiting question with the chat closed",
            app.contains("askAiBadge = viewModel.pendingQuestion != null && !aiChatOpen"),
        )
        assertTrue(
            "the notification is only posted for a waiting question, chat closed, app backgrounded",
            app.contains("CadQuestionNotification.post(context, waiting)") &&
                app.contains("!aiChatOpen && !appResumed"),
        )
        assertTrue(app.contains("CadQuestionNotification.cancel(context)"))
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
