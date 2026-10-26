package com.tomppi.enderslicer.cadagent

import org.json.JSONArray
import org.json.JSONObject

/**
 * One question the in-app agent asked with the `ask_user` tool.
 *
 * The tool is the app's, not the engine's: the engine cannot see the user and has no UI. Asking
 * **ends the turn** - there is no blocking wait anywhere, and the user's answer arrives as the
 * next user message, which starts a new turn. The card the chat shows for it carries the question,
 * one chip per option and, when [allowText] is on, the composer for a free answer.
 *
 * This is the in-app agent's version of the harness's own ask-user mechanism; the harness path is
 * untouched by it.
 */
data class AgentQuestion(
    /** The question, as the user reads it. Never blank or [fromArguments] returns null. */
    val question: String,
    /** Short answers the user can pick with one tap; may be empty (free text only). */
    val options: List<String> = emptyList(),
    /** False only when the options are the whole answer set; then the composer is closed. */
    val allowText: Boolean = true,
) {
    /**
     * The tool result the model would have read had the turn continued.
     *
     * The turn does not continue: this exists so the contract is written down in one place and so
     * a test can pin the sentence. It says the question was asked, the turn is over, and the
     * answer starts the next turn - the model must not wait or repeat the question.
     */
    val resultText: String
        get() = "The question was asked and this turn is over. The user's answer will arrive as " +
            "the next user message and start the next turn - do not wait for it in this turn, " +
            "and do not repeat the question."

    companion object {
        /** A chat card shows one chip per option; twelve still wrap on a phone. */
        const val MAX_OPTIONS = 12

        /** A question longer than this is not a question the chat can show; it is an essay. */
        const val MAX_QUESTION_LENGTH = 1_000

        /**
         * Parses the tool's arguments, or returns null when they cannot describe a question.
         *
         * The caller reports the null to the model as a tool failure it can correct, rather than
         * throwing at the UI: a malformed question is the model's mistake to fix, not silence.
         */
        fun fromArguments(arguments: JSONObject): AgentQuestion? {
            val question = arguments.optString("question").trim()
            if (question.isEmpty() || question.length > MAX_QUESTION_LENGTH) return null
            val rawOptions = arguments.opt("options")
            val options = ArrayList<String>()
            if (rawOptions is JSONArray) {
                for (index in 0 until rawOptions.length()) {
                    val option = rawOptions.optString(index).trim()
                    if (option.isNotEmpty()) options += option.take(80)
                    if (options.size == MAX_OPTIONS) break
                }
            } else if (rawOptions != null && rawOptions !is JSONArray) {
                return null
            }
            // A closed set with no options could never be answered; the composer stays open.
            val allowText = if (arguments.has("allow_text")) {
                arguments.optBoolean("allow_text", true)
            } else {
                true
            }
            return AgentQuestion(
                question = question,
                options = options,
                allowText = allowText || options.isEmpty(),
            )
        }
    }
}
