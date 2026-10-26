package com.tomppi.enderslicer.cadagent

/**
 * A failure the in-app agent can name.
 *
 * The sheet shows [message] as its error line and [code] is what callers and tests branch on.
 * A tool failure is deliberately not one of these: it goes back to the model as a tool result,
 * because the model is the thing that can act on it. Every code here means the conversation
 * itself could not continue - the endpoint was not configured, refused the key, answered
 * something that is not a chat completion, or was cancelled.
 */
class AgentException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
