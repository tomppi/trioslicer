package com.tomppi.enderslicer.cadagent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The question the tool carries, parsed from the model's arguments. */
class AgentQuestionTest {

    private fun arguments(question: String = "Which material?"): JSONObject =
        JSONObject().put("question", question)

    @Test
    fun theQuestionIsTheOneRequiredPart() {
        assertNull("no question means no card", AgentQuestion.fromArguments(JSONObject()))
        assertNull(AgentQuestion.fromArguments(arguments("   ")))
        assertNull(
            "an essay is not a question the card can show",
            AgentQuestion.fromArguments(arguments("x".repeat(AgentQuestion.MAX_QUESTION_LENGTH + 1))),
        )
        val question = AgentQuestion.fromArguments(arguments("  Which material?  "))
        assertEquals("Which material?", question!!.question)
        assertTrue("free text is the default", question.allowText)
        assertTrue("no options is allowed", question.options.isEmpty())
    }

    @Test
    fun theOptionsAreShortTrimmedStringsAndBounded() {
        val options = JSONArray()
            .put("  PLA ")
            .put("")
            .put("PETG")
        (1..10).forEach { options.put("option-" + it) }
        val question = AgentQuestion.fromArguments(
            arguments().put("options", options),
        )!!
        assertEquals("PLA", question.options.first())
        assertEquals("PETG", question.options[1])
        assertEquals(AgentQuestion.MAX_OPTIONS, question.options.size)
        assertNull(
            "options that are not an array are a tool failure",
            AgentQuestion.fromArguments(arguments().put("options", "PLA")),
        )
    }

    @Test
    fun allowTextIsFalseOnlyWhenTheOptionsAreTheWholeAnswerSet() {
        val closed = AgentQuestion.fromArguments(
            arguments().put("options", JSONArray().put("Yes").put("No")).put("allow_text", false),
        )!!
        assertTrue("a closed question keeps its chips", closed.options.isNotEmpty())
        assertEquals(false, closed.allowText)

        val openBecauseEmpty = AgentQuestion.fromArguments(
            arguments().put("allow_text", false),
        )!!
        assertTrue("a closed set with no options could never be answered", openBecauseEmpty.allowText)
    }
}
