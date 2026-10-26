package com.tomppi.enderslicer.solidcraft

import com.tomppi.enderslicer.cadagent.AgentToolHost
import org.json.JSONObject

/**
 * The in-app agent's tools, executed against the engine session this app already owns.
 *
 * Same catalogue, same results as the bridge an external agent uses - but the call never leaves
 * the process: no socket, no token, no adb forward and no second device. The tool list is the
 * engine's own `tools/list` answer, fetched by the sheet before a turn, so a tool the engine
 * adds appears in both paths without an app change.
 *
 * This is also where the plate stays live. A tool call that may have changed the design is
 * turned into a [DocumentDelta] exactly as the bridge does, and handed to the app's live-view
 * handler, so an in-app edit repaints like any other.
 *
 * A tool failure is the text the model receives, never an exception for the UI.
 */
class SolidCraftAgentHost(
    private val session: () -> SolidCraftSession?,
    private val onDocumentChanged: (DocumentDelta) -> Unit,
    private val log: (String) -> Unit = {},
) : AgentToolHost {

    override fun call(name: String, arguments: JSONObject): String {
        // The catalogue does not offer screenshot, and the bridge refuses it for the same
        // reason: the plate is the viewport the user is looking at.
        if (name == "screenshot") {
            return "the plate is the viewport: the app renders this document itself, and the " +
                "engine's screenshot would be a second picture of a camera the user does not " +
                "see. Do not ask for a picture; look at the plate."
        }
        val live = session() ?: return "the CAD engine is not running: press Connect agent in " +
            "the CAD agent sheet and ask again."
        val result = try {
            live.callTool(name, arguments, timeoutFor(name))
        } catch (error: McpException) {
            return "the CAD engine could not run " + name + ": " +
                (error.message ?: "no reason given")
        } catch (error: Throwable) {
            return "the app could not run " + name + ": " +
                (error.message ?: error.javaClass.simpleName)
        }
        val delta = runCatching { SolidCraftTools.deltaFor(name, arguments, result) }
            .onFailure { log("could not read the change report of " + name + ": " + it.message) }
            .getOrNull()
        if (delta != null && !delta.isEmpty) {
            runCatching { onDocumentChanged(delta) }
                .onFailure { log("the live-view handler failed: " + it.message) }
        }
        val text = McpToolResults.text(result)
        return when {
            McpToolResults.isError(result) -> "the CAD engine refused " + name + ": " + text
            // A phone's model context is not unbounded: inspect_design on a big document can be
            // far past what the endpoint will accept. The cut is named so the model knows.
            text.length > MAX_RESULT_CHARS ->
                text.take(MAX_RESULT_CHARS) + "\n… (result truncated at " +
                    MAX_RESULT_CHARS + " characters)"
            else -> text
        }
    }

    /** The engine's own budgets, the same ones the app's file paths use. */
    private fun timeoutFor(name: String): Long = when (name) {
        "export", "save", "open" -> SolidCraftSession.EXPORT_TIMEOUT_MILLIS
        else -> SolidCraftSession.DEFAULT_TIMEOUT_MILLIS
    }

    companion object {
        /** 64k characters of tool result is roughly 16k tokens on a phone model. */
        const val MAX_RESULT_CHARS = 64_000
    }
}
