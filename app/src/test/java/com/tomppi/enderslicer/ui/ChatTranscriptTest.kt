package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.cadagent.AgentToolTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text behind **Copy transcript**: messages and tool rows in order, no credential.
 *
 * This is what the user hands to a human or another agent when a turn looks wrong, so the
 * ordering, the tool row detail and the absence of anything secret are pinned here without a
 * device.
 */
class ChatTranscriptTest {

    private fun toolRow(
        callId: Int = 1,
        name: String = "solid.box",
        arguments: String = "{length=40, width=30, height=10}",
        durationMillis: Long = 12,
        outcome: String = "1 body created (Body1)",
        failed: Boolean = false,
        running: Boolean = false,
    ): AiChatMessage = AiChatMessage(
        fromUser = false,
        text = "",
        kind = AiChatEntryKind.TOOL,
        tool = AgentToolTrace(
            callId = callId,
            tool = "execute",
            name = name,
            arguments = arguments,
            running = running,
            durationMillis = durationMillis,
            outcome = outcome,
            failed = failed,
        ),
    )

    @Test
    fun messagesAndToolRowsAppearInTheOrderTheyHappened() {
        val text = chatTranscriptText(
            listOf(
                AiChatMessage(fromUser = true, text = "a 40 x 30 x 10 box with 3 mm fillets"),
                toolRow(),
                AiChatMessage(fromUser = false, text = "the box is on the plate"),
            ),
        )
        assertTrue(text.startsWith("TrioSlicer chat transcript"))
        assertTrue(text.contains("You: a 40 x 30 x 10 box with 3 mm fillets"))
        assertTrue("the row is named: " + text, text.contains("tool: solid.box"))
        assertTrue("the row carries its arguments", text.contains("{length=40, width=30, height=10}"))
        assertTrue("the row carries its duration", text.contains("12 ms"))
        assertTrue("the row carries its outcome", text.contains("1 body created (Body1)"))
        assertTrue(text.contains("Agent: the box is on the plate"))
        assertTrue(
            "what was asked, then what ran, then what was said",
            text.indexOf("You:") < text.indexOf("tool:") && text.indexOf("tool:") < text.indexOf("Agent: the box"),
        )
    }

    @Test
    fun aFailedRowIsMarkedAndKeepsItsSentence() {
        val text = chatTranscriptText(
            listOf(
                toolRow(
                    name = "web_search",
                    arguments = "\"M3 head\"",
                    outcome = "HTTP 404 - this endpoint does not offer web search",
                    failed = true,
                ),
            ),
        )
        assertTrue(text.contains("FAILED"))
        assertTrue(text.contains("HTTP 404 - this endpoint does not offer web search"))
    }

    @Test
    fun aRunningRowSaysRunningRatherThanInventingAnOutcome() {
        val text = chatTranscriptText(listOf(toolRow(running = true)))
        assertTrue(text.contains("tool: solid.box"))
        assertTrue(text.contains("running"))
        assertFalse(text.contains("1 body created"))
    }

    @Test
    fun theSheetOnlyActivityLinesAreLeftOutOfTheTranscript() {
        val text = chatTranscriptText(
            listOf(
                AiChatMessage(
                    fromUser = false,
                    text = "searching the web for \"M3 head\"…",
                    kind = AiChatEntryKind.ACTIVITY,
                ),
                toolRow(name = "web_search", arguments = "\"M3 head\"", outcome = "1 result"),
            ),
        )
        assertFalse("the tool row already says it", text.contains("searching the web for"))
        assertTrue(text.contains("tool: web_search"))
    }

    @Test
    fun anErrorRowCarriesTheHostAndRoundIntoTheCopy() {
        val text = chatTranscriptText(
            listOf(
                AiChatMessage(fromUser = true, text = "make the lid a snap fit"),
                AiChatMessage(
                    fromUser = false,
                    text = "The endpoint did not answer after 183 s (round 3 at api.example.test). " +
                        "The screen may be off, that is fine; the app must still be running.",
                    kind = AiChatEntryKind.ERROR,
                ),
            ),
        )
        assertTrue("the failure is a line of the copy: " + text, text.contains("error: The endpoint did not answer"))
        assertTrue("the host is in it: " + text, text.contains("api.example.test"))
        assertTrue("the round is in it: " + text, text.contains("round 3"))
        assertTrue("the seconds are in it: " + text, text.contains("183 s"))
        assertTrue("the background hint is in it: " + text, text.contains("app must still be running"))
    }

    @Test
    fun nothingShapedLikeACredentialAppears() {
        // The rows are redacted where they are built; this pins the second half - the builder
        // adds no header, key or Authorization text of its own.
        val text = chatTranscriptText(
            listOf(
                AiChatMessage(fromUser = true, text = "build a box"),
                toolRow(arguments = "{length=40}", outcome = "1 body created (Body1)"),
            ),
        )
        assertFalse(text.contains("sk-"))
        assertFalse(text.lowercase().contains("authorization"))
        assertFalse(text.lowercase().contains("bearer"))
    }
}
