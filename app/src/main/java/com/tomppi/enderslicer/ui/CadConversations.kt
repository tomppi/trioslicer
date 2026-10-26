package com.tomppi.enderslicer.ui

import com.tomppi.enderslicer.cadagent.AgentMode
import com.tomppi.enderslicer.cadagent.AgentToolTrace

/**
 * The CAD document's two conversations, one per backend - never one shared list.
 *
 * The in-app agent appends its own messages (tool rows included) here, and the harness session's
 * reported messages go to the harness list and nowhere else. The overlay renders only the list
 * belonging to its selected source, so a transcript from the harness (a case on a PC, say) can
 * never turn up under "CAD agent (in-app)", and vice versa - they are different conversations,
 * not two windows on one.
 *
 * Every operation is one-sided by construction: there is no "set both", so a bug in one caller
 * can only ever leave the other backend's list untouched.
 */
data class CadConversations(
    /** The app's own agent's conversation, in order, tool rows included. */
    val inApp: List<AiChatMessage> = emptyList(),
    /** The harness session's conversation, as the harness last reported it. */
    val harness: List<AiChatMessage> = emptyList(),
) {
    /** The list the given source renders - and the only list it renders. */
    fun messagesFor(source: ChatSource): List<AiChatMessage> = when (source) {
        ChatSource.IN_APP -> inApp
        ChatSource.HARNESS -> harness
    }

    fun appendInApp(message: AiChatMessage): CadConversations =
        copy(inApp = inApp + message)

    fun appendHarness(message: AiChatMessage): CadConversations =
        copy(harness = harness + message)

    /** A poll of the harness session replaces the harness list and cannot touch the in-app one. */
    fun replaceHarness(messages: List<AiChatMessage>): CadConversations =
        copy(harness = messages)

    /**
     * Puts one tool call's start or end into the in-app conversation, in place.
     *
     * A tool row belongs to the in-app agent only: the harness reports prose turns, and a row
     * appended here must never appear on that side.
     */
    fun withToolTrace(trace: AgentToolTrace): CadConversations {
        val index = inApp.indexOfLast { it.tool?.callId == trace.callId }
        return if (index >= 0) {
            copy(inApp = inApp.toMutableList().also { it[index] = it[index].copy(tool = trace) })
        } else {
            appendInApp(
                AiChatMessage(
                    fromUser = false,
                    text = "",
                    kind = AiChatEntryKind.TOOL,
                    tool = trace,
                ),
            )
        }
    }
}

/** The chat source a CAD conversation mode is shown under. */
fun AgentMode.chatSource(): ChatSource = when (this) {
    AgentMode.IN_APP -> ChatSource.IN_APP
    AgentMode.HARNESS -> ChatSource.HARNESS
}

/** The conversation mode a chat source selects - the one shared selection. */
fun ChatSource.agentMode(): AgentMode = when (this) {
    ChatSource.IN_APP -> AgentMode.IN_APP
    ChatSource.HARNESS -> AgentMode.HARNESS
}
