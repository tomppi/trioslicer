package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.cadagent.AgentMode
import com.tomppi.enderslicer.cadagent.AgentToolTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two CAD conversations are separate lists: a write on one backend can never touch the
 * other, and a tool row can only exist on the in-app side.
 */
class CadConversationsTest {

    private fun message(text: String, fromUser: Boolean = false) =
        AiChatMessage(fromUser = fromUser, text = text)

    private fun trace(callId: Int, running: Boolean): AgentToolTrace = AgentToolTrace(
        callId = callId,
        tool = "execute",
        name = "solid.box",
        arguments = "{length=40, width=30, height=10}",
        running = running,
        durationMillis = if (running) 0 else 12,
        outcome = if (running) "" else "1 body created (Body1)",
    )

    @Test
    fun anInAppTurnLeavesTheHarnessConversationUntouched() {
        val conversations = CadConversations()
            .appendHarness(message("pi case question", fromUser = true))
            .replaceHarness(listOf(message("pi case question", fromUser = true), message("pi answer")))
            .appendInApp(message("a box", fromUser = true))
            .appendInApp(message("made it"))

        assertEquals(2, conversations.harness.size)
        assertEquals("pi answer", conversations.harness[1].text)
        assertEquals(2, conversations.inApp.size)
        assertEquals("made it", conversations.inApp[1].text)
    }

    @Test
    fun aHarnessPollWritesOnlyTheHarnessList() {
        val before = CadConversations(inApp = listOf(message("in-app only")))
        val after = before.replaceHarness(listOf(message("harness only")))

        assertEquals(listOf("in-app only"), after.inApp.map { it.text })
        assertEquals(listOf("harness only"), after.harness.map { it.text })
    }

    @Test
    fun aToolRowAppearsOnlyInTheInAppConversation() {
        val started = CadConversations().withToolTrace(trace(7, running = true))
        assertEquals(1, started.inApp.size)
        assertEquals("solid.box", started.inApp[0].tool?.name)
        assertTrue("the tool start is running", started.inApp[0].tool?.running == true)
        assertTrue("the harness list stays empty", started.harness.isEmpty())

        val ended = started.withToolTrace(trace(7, running = false))
        assertEquals("the row is replaced in place", 1, ended.inApp.size)
        assertEquals("1 body created (Body1)", ended.inApp[0].tool?.outcome)
        assertTrue(ended.harness.isEmpty())
    }

    @Test
    fun theSourceSelectsItsOwnListAndTheModeMappingRoundTrips() {
        val conversations = CadConversations(
            inApp = listOf(message("in-app")),
            harness = listOf(message("harness")),
        )
        assertEquals("in-app", conversations.messagesFor(ChatSource.IN_APP).single().text)
        assertEquals("harness", conversations.messagesFor(ChatSource.HARNESS).single().text)

        for (mode in AgentMode.entries) {
            assertEquals(mode, mode.chatSource().agentMode())
        }
        for (source in ChatSource.entries) {
            assertEquals(source, source.agentMode().chatSource())
        }
    }
}
