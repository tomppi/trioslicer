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
 *
 * No image data can reach it either. A plate render is named by its size and dimensions in the
 * transcript ("the plate render is attached: 768×1660 px, 84 KB"), and [withoutImageData] is a
 * second guarantee: if a data URL ever reaches a message, the copy says it was omitted rather
 * than pasting hundreds of kilobytes of base64 into the clipboard.
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
            message.kind == AiChatEntryKind.ERROR -> {
                append('\n')
                append("error: ").append(withoutImageData(message.text))
            }
            message.fromUser -> append('\n').append("You: ").append(withoutImageData(message.text))
            else -> append('\n').append("Agent: ").append(withoutImageData(message.text))
        }
    }
}

/** A data URL, as an OpenAI image content part carries it. */
private val IMAGE_DATA_URL = Regex("data:image/[A-Za-z0-9.+-]+;base64,[A-Za-z0-9+/=]+")

/**
 * Replaces any inline image payload with a short marker.
 *
 * The transcript names a picture by its size, never by its bytes; this is the belt to that
 * braces - a copied transcript is text a human or another agent reads, and a base64 payload
 * would be both unreadable and enormous.
 */
internal fun withoutImageData(text: String): String =
    if (text.contains("base64,")) text.replace(IMAGE_DATA_URL, "[image data omitted]") else text
