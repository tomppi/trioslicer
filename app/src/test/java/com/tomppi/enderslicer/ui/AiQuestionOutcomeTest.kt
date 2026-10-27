package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.cadagent.AgentQuestion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The question card's history label must match how the question actually ended.
 *
 * The card reads [AiChatMessage.questionOutcome]; whoever resolves the wait records the outcome
 * with [markQuestionResolved]. The match is by question identity, so two questions with the same
 * words - or a question at the same position in a re-asked list - can never inherit each other's
 * label. This is the mechanism behind the "a dismissed card must not say Answered" fix; the
 * rendered wording itself is covered by [AskUserFlowTest].
 */
class AiQuestionOutcomeTest {

    @Test
    fun theDefaultIsStillWaiting() {
        val message = AiChatMessage(
            fromUser = false,
            text = "Which material?",
            kind = AiChatEntryKind.QUESTION,
            question = AgentQuestion("Which material?"),
        )
        assertEquals(AiQuestionOutcome.PENDING, message.questionOutcome)
    }

    @Test
    fun aQuestionIsMatchedByIdentityNotByItsWording() {
        val answered = AgentQuestion("Which material?")
        val dismissedTwin = AgentQuestion("Which material?")
        val transcript = listOf(
            AiChatMessage(
                fromUser = false,
                text = answered.question,
                kind = AiChatEntryKind.QUESTION,
                question = answered,
            ),
            AiChatMessage(
                fromUser = false,
                text = dismissedTwin.question,
                kind = AiChatEntryKind.QUESTION,
                question = dismissedTwin,
            ),
        )

        val marked = transcript.markQuestionResolved(answered, AiQuestionOutcome.ANSWERED_FROM_OPTIONS)

        assertEquals(
            AiQuestionOutcome.ANSWERED_FROM_OPTIONS,
            marked[0].questionOutcome,
        )
        assertEquals(
            "a question with the same words is a different question",
            AiQuestionOutcome.PENDING,
            marked[1].questionOutcome,
        )
    }

    @Test
    fun everyOutcomeIsRecordedAndNothingElseChanges() {
        val resolved = AgentQuestion("Which material?", listOf("PLA", "PETG"))
        val userMessage = AiChatMessage(fromUser = true, text = "PETG")
        val transcript = listOf(
            userMessage,
            AiChatMessage(
                fromUser = false,
                text = resolved.question,
                kind = AiChatEntryKind.QUESTION,
                question = resolved,
            ),
        )

        val marked = transcript.markQuestionResolved(resolved, AiQuestionOutcome.DISMISSED)

        assertEquals("the other messages are untouched", userMessage, marked[0])
        assertSame("the question message keeps everything but its outcome", resolved, marked[1].question)
        assertEquals(AiQuestionOutcome.DISMISSED, marked[1].questionOutcome)
        assertTrue("the question's own words survive", marked[1].text.contains("Which material?"))
    }

    @Test
    fun aMissingQuestionIsANoOp() {
        val transcript = listOf(AiChatMessage(fromUser = true, text = "hello"))
        assertEquals(
            transcript,
            transcript.markQuestionResolved(null, AiQuestionOutcome.IGNORED),
        )
    }
}
