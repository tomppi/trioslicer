package com.tomppi.enderslicer.ui

/**
 * The chat as plain text: the messages and the tool rows, in order.
 *
 * This is what **Copy transcript** puts on the clipboard so the user can send a bad turn to a
 * human or another agent. Tool rows are written the way they are read - name, argument summary,
 * duration, outcome - so the copy explains what the agent did, not only what it said.
 *
 * Plain-words activity lines (the sheet's "searching the web for …") are left out: the tool row
 * they belong to already carries the query and the result, and the copy should not say the same
 * thing twice.
 *
 * No credential can reach this text: tool rows are redacted where they are built
 * ([com.tomppi.enderslicer.cadagent.AgentToolSummary]), and the endpoint's key is never a chat
 * message in the first place.
 */
fun chatTranscriptText(messages: List<AiChatMessage>): String = buildString {
    append("TrioSlicer chat transcript")
    messages.forEach { message ->
        val tool = message.tool
        when {
            tool != null -> {
                append('\n')
                append("tool: ").append(tool.name)
                if (tool.arguments.isNotBlank()) append(' ').append(tool.arguments)
                if (tool.running) {
                    append(" · running")
                } else {
                    append(" · ").append(tool.durationMillis).append(" ms")
                    if (tool.outcome.isNotBlank()) append(" · ").append(tool.outcome)
                }
                if (tool.failed) append(" · FAILED")
            }
            message.kind == AiChatEntryKind.ACTIVITY -> Unit
            message.fromUser -> append('\n').append("You: ").append(message.text)
            else -> append('\n').append("Agent: ").append(message.text)
        }
    }
}
