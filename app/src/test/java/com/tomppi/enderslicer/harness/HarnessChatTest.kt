package com.tomppi.enderslicer.harness

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a conversation out of the `session/list` projection.
 *
 * The shapes here are the ones that actually bite: ids echoed inside other
 * sessions' turn text, a session the harness has forgotten, and a turn that
 * ended without a reply because it was cancelled.
 */
class HarnessChatTest {

    private fun session(
        id: String,
        running: Boolean = false,
        turns: List<Pair<String, String>> = emptyList(),
    ): JSONObject = JSONObject()
        .put("sessionId", id)
        .put("running", running)
        .put(
            "projections",
            JSONObject().put(
                "values",
                JSONObject().put(
                    "turnOutline",
                    JSONArray().apply {
                        turns.forEachIndexed { index, turn ->
                            put(
                                JSONObject()
                                    .put("turn", index + 1)
                                    .put("seq", index + 1)
                                    .put("prompt", turn.first)
                                    .put("response", turn.second),
                            )
                        }
                    },
                ),
            ),
        )

    private fun listOfSessions(vararg items: JSONObject): JSONObject =
        JSONObject().put("items", JSONArray().apply { items.forEach { put(it) } })

    @Test
    fun findsTheSessionTheResponseNamesRatherThanOneItsTextMentions() {
        // Session ids appear inside other sessions' turn text. A search for the
        // string finds the quote; only the item's own field identifies it.
        val list = listOfSessions(
            session("session-chatty", turns = listOf("tell session-wanted to stop" to "done")),
            session("session-wanted", running = true),
        )

        val state = HarnessChat.stateOf(list, "session-wanted")

        assertTrue(state.exists)
        assertTrue(state.running)
        assertEquals(0, state.turns.size)
    }

    @Test
    fun aSessionTheHarnessHasForgottenDoesNotExist() {
        val list = listOfSessions(session("session-other"))

        val state = HarnessChat.stateOf(list, "session-gone")

        assertFalse(state.exists)
        assertFalse(state.running)
        assertTrue(state.turns.isEmpty())
    }

    @Test
    fun anEmptyListIsAMissingSessionNotAnEmptyConversation() {
        // The distinction is the whole point: blanking the chat on a failed
        // lookup erases a conversation that was working.
        assertFalse(HarnessChat.stateOf(JSONObject(), "session-any").exists)
    }

    @Test
    fun aCancelledTurnIsNotAnsweredAndNotRunning() {
        // What a cancel leaves behind: a prompt with a blank response, and the
        // session no longer running. Reading this as "still in flight" is what
        // kept the composer busy until its own timeout.
        val list = listOfSessions(
            session("session-app", turns = listOf("build the thing" to "")),
        )

        val state = HarnessChat.stateOf(list, "session-app")

        assertTrue(state.exists)
        assertFalse(state.running)
        assertFalse(state.answered)
        assertEquals(1, state.turns.size)
    }

    @Test
    fun aQueuedTurnIsRunningBeforeItIsAnswered() {
        val list = listOfSessions(
            session("session-app", running = true, turns = listOf("build the thing" to "")),
        )

        val state = HarnessChat.stateOf(list, "session-app")

        assertTrue(state.running)
        assertFalse(state.answered)
    }

    @Test
    fun aFinishedTurnIsAnswered() {
        val list = listOfSessions(
            session("session-app", turns = listOf("build the thing" to "here is the model")),
        )

        val state = HarnessChat.stateOf(list, "session-app")

        assertTrue(state.answered)
        assertFalse(state.running)
    }

    @Test
    fun turnOrderIsOldestFirstSoTheNewestDecides() {
        val list = listOfSessions(
            session(
                "session-app",
                turns = listOf("first" to "answered", "second" to ""),
            ),
        )

        val state = HarnessChat.stateOf(list, "session-app")

        assertEquals(2, state.turns.size)
        assertEquals("first", state.turns[0].prompt)
        assertEquals("second", state.turns[1].prompt)
        assertFalse(state.answered)
    }

    @Test
    fun messagesSkipAnUnansweredPromptAndKeepBothSidesOfAnAnsweredOne() {
        val turns = listOf(
            HarnessChat.Turn(prompt = "asked", response = "answered", sequence = 1),
            HarnessChat.Turn(prompt = "still waiting", response = "", sequence = 2),
        )

        val messages = HarnessChat.toMessages(turns)

        // An answered turn contributes both sides; the unanswered one
        // contributes its prompt and no empty reply.
        assertEquals(3, messages.size)
        assertEquals("asked", messages[0].text)
        assertTrue(messages[0].fromUser)
        assertEquals("answered", messages[1].text)
        assertFalse(messages[1].fromUser)
        assertEquals("still waiting", messages[2].text)
        assertTrue(messages[2].fromUser)
    }

    @Test
    fun fullPromptsAreUsedWhenEveryTurnAgreesWithTheLog() {
        val turns = listOf(
            HarnessChat.Turn(prompt = "Build a bracket that fits the…", response = "clipped", sequence = 1),
        )
        val full = "Build a bracket that fits the rail on the left side of the printer, with two M3 holes"

        val messages = HarnessChat.transcript(
            turns = turns,
            prompts = listOf(full),
            responses = mapOf(1 to "a much longer answer"),
        )

        assertEquals(
            listOf(
                ChatMessage(fromUser = true, text = full),
                ChatMessage(fromUser = false, text = "a much longer answer"),
            ),
            messages,
        )
    }

    @Test
    fun aMultiLinePromptStillMatchesItsCollapsedPreview() {
        // The projection joins a turn's text blocks and collapses whitespace
        // before it clips, so a raw string comparison would call every long
        // prompt a mismatch and quietly fall back to the clipped preview.
        val turns = listOf(
            HarnessChat.Turn(prompt = "Check the printer, then print the cube…", response = "done", sequence = 1),
        )
        val full = "Check the printer,\n\nthen print the cube and tell me when it is done"

        val messages = HarnessChat.transcript(turns, listOf(full), emptyMap())

        assertEquals(full, messages.single { it.fromUser }.text)
    }

    @Test
    fun aTurnWithoutAPromptBesideATurnWithTwoDoesNotShiftTheTranscript() {
        // The mixed case. A background-job notice starts a turn of its own and is
        // filtered out of the prompt list; a queued STOP_INSTRUCTION adds a
        // second prompt to the next turn. Both lists then have the same length
        // with everything after the first shifted, and the old count-only
        // pairing put the cube request above the notice's answer and the STOP
        // above the cube's answer, dropping the request itself.
        val turns = listOf(
            HarnessChat.Turn(prompt = "", response = "Background job finished", sequence = 1),
            HarnessChat.Turn(prompt = "Make a 20 mm cube", response = "Here is the cube", sequence = 2),
        )
        val prompts = listOf(
            "Make a 20 mm cube",
            "Stop. Do not continue the previous task and do not start a new one.",
        )

        val messages = HarnessChat.transcript(turns, prompts, emptyMap())

        assertEquals(3, messages.size)
        assertFalse(messages[0].fromUser)
        assertEquals("Background job finished", messages[0].text)
        assertTrue(messages[1].fromUser)
        assertEquals("Make a 20 mm cube", messages[1].text)
        assertFalse(messages[2].fromUser)
        assertEquals("Here is the cube", messages[2].text)
    }

    @Test
    fun aPromptThatDoesNotMatchItsTurnIsNotUsed() {
        val turns = listOf(
            HarnessChat.Turn(prompt = "the real prompt", response = "answer", sequence = 1),
        )

        val messages = HarnessChat.transcript(turns, listOf("somebody else's prompt"), emptyMap())

        assertEquals("the real prompt", messages.first().text)
        assertTrue(messages.first().fromUser)
    }

    @Test
    fun anExtraPromptInTheLogMakesEveryTurnUseItsOwnPreview() {
        val turns = listOf(
            HarnessChat.Turn(prompt = "first", response = "one", sequence = 1),
            HarnessChat.Turn(prompt = "second", response = "two", sequence = 2),
        )

        val messages = HarnessChat.transcript(
            turns = turns,
            prompts = listOf("first", "Stop. Do not continue the previous task.", "second"),
            responses = emptyMap(),
        )

        assertEquals(listOf("first", "second"), messages.filter { it.fromUser }.map { it.text })
    }

    @Test
    fun fullPromptsComeFromTheLogInOrderAndSkipPluginMessages() {
        // The log holds other people's messages too - the harness writes
        // plugin-sourced events into the same stream - and pairing one of those
        // with a turn is the corruption this pairing rule exists to avoid.
        val page = page(
            userMessage(source = "user", text = "first"),
            userMessage(source = "plugin", text = "a notice nobody typed"),
            userMessage(source = "user", text = "second"),
        )

        assertEquals(listOf("first", "second"), HarnessChat.fullPromptsOf(page))
    }

    @Test
    fun aReplyIsNotInWhileTheProjectionStillShowsThePreviousTurn() {
        // The race that made the spinner and the Stop button vanish: right
        // after a prompt is accepted, the newest published turn is still the
        // previous one - and it is answered.
        val list = listOfSessions(
            session("session-app", turns = listOf("first" to "answered")),
        )

        val state = HarnessChat.stateOf(list, "session-app")

        assertFalse(HarnessChat.hasNewTurn(state, turnsAtPrompt = 1))
        assertFalse(HarnessChat.replyIsIn(state, turnsAtPrompt = 1))
    }

    @Test
    fun aReplyIsInOnceANewerTurnIsAnswered() {
        val list = listOfSessions(
            session("session-app", turns = listOf("first" to "answered", "second" to "done")),
        )

        val state = HarnessChat.stateOf(list, "session-app")

        assertTrue(HarnessChat.hasNewTurn(state, turnsAtPrompt = 1))
        assertTrue(HarnessChat.replyIsIn(state, turnsAtPrompt = 1))
    }

    @Test
    fun aPublishedButUnansweredTurnIsNotAReply() {
        val list = listOfSessions(
            session("session-app", running = true, turns = listOf("first" to "answered", "second" to "")),
        )

        val state = HarnessChat.stateOf(list, "session-app")

        assertTrue(HarnessChat.hasNewTurn(state, turnsAtPrompt = 1))
        assertFalse(HarnessChat.replyIsIn(state, turnsAtPrompt = 1))
    }

    @Test
    fun anUnknownBaselineFallsBackToTheLooserTest() {
        // After a reconnect there is nothing to compare against, and never
        // calling a turn answered would be worse than being early.
        val list = listOfSessions(
            session("session-app", turns = listOf("only" to "answered")),
        )

        val state = HarnessChat.stateOf(list, "session-app")

        assertTrue(HarnessChat.replyIsIn(state, turnsAtPrompt = -1))
    }

    private fun page(vararg events: JSONObject): JSONObject =
        JSONObject().put(
            "records",
            JSONArray().apply {
                events.forEach { put(JSONObject().put("type", "event").put("event", it)) }
            },
        )

    private fun assistant(turn: Int, vararg blocks: Pair<String, String>): JSONObject = JSONObject()
        .put("type", "assistant/message")
        .put(
            "data",
            JSONObject().put("turn", turn).put(
                "message",
                JSONObject().put(
                    "content",
                    JSONArray().apply {
                        blocks.forEach { put(JSONObject().put("type", it.first).put("text", it.second)) }
                    },
                ),
            ),
        )

    private fun userMessage(source: String, text: String): JSONObject = JSONObject()
        .put("type", "user/message")
        .put(
            "data",
            JSONObject()
                .put("source", JSONObject().put("kind", source))
                .put(
                    "content",
                    JSONArray().put(JSONObject().put("type", "text").put("text", text)),
                ),
        )

    @Test
    fun fullResponsesCarryTheSpokenTextAndNotTheReasoning() {
        // An assistant message holds the agent's private working as well as its
        // reply. Concatenating every block would put the thinking in the chat.
        val page = page(assistant(1, "reasoning" to "let me think about it", "text" to "here is the answer"))

        assertEquals(mapOf(1 to "here is the answer"), HarnessChat.fullResponsesOf(page))
    }

    @Test
    fun fullResponsesKeepTheLastTextOfATurn() {
        // A turn narrates as it goes; only its closing line is the answer.
        val page = page(
            assistant(1, "text" to "starting"),
            assistant(1, "text" to "the real answer"),
        )

        assertEquals(mapOf(1 to "the real answer"), HarnessChat.fullResponsesOf(page))
    }

    @Test
    fun fullResponsesKeyOnTurnNumber() {
        val page = page(
            assistant(1, "text" to "first"),
            assistant(2, "text" to "second"),
        )

        assertEquals(mapOf(1 to "first", 2 to "second"), HarnessChat.fullResponsesOf(page))
    }

    @Test
    fun fullResponsesIgnoreEverythingThatIsNotAnAssistantMessage() {
        val injected = JSONObject()
            .put("type", "user/message")
            .put(
                "data",
                JSONObject().put(
                    "message",
                    JSONObject().put(
                        "content",
                        JSONArray().put(JSONObject().put("type", "text").put("text", "not a reply")),
                    ),
                ),
            )
        val page = page(injected, JSONObject().put("type", "step/start"), assistant(1, "text" to "reply"))

        assertEquals(mapOf(1 to "reply"), HarnessChat.fullResponsesOf(page))
    }

    @Test
    fun fullResponsesTreatAMissingRecordArrayAsEmpty() {
        assertTrue(HarnessChat.fullResponsesOf(JSONObject()).isEmpty())
    }

    @Test
    fun fullResponsesJoinSeveralTextBlocksInOrder() {
        val page = page(assistant(1, "text" to "one", "text" to "two"))

        assertEquals("one\n\ntwo", HarnessChat.fullResponsesOf(page)[1])
    }
}
