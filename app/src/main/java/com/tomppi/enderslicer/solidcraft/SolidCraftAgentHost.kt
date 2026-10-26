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
 * A tool failure is a [AgentToolHost.Result] with `failed` set - the text for the model and
 * a red row for the chat - never an exception for the UI.
 */
class SolidCraftAgentHost(
    private val session: () -> SolidCraftSession?,
    private val onDocumentChanged: (DocumentDelta) -> Unit,
    private val log: (String) -> Unit = {},
) : AgentToolHost {

    override fun call(name: String, arguments: JSONObject): AgentToolHost.Result {
        // The catalogue does not offer screenshot, and the bridge refuses it for the same
        // reason: the plate is the viewport the user is looking at.
        if (name == "screenshot") {
            return AgentToolHost.Result(
                "the plate is the viewport: the app renders this document itself, and the " +
                    "engine's screenshot would be a second picture of a camera the user does not " +
                    "see. Do not ask for a picture; look at the plate.",
                failed = true,
                outcome = "screenshot is never used",
            )
        }
        val live = session() ?: return AgentToolHost.Result(
            "the CAD engine is not running: press Connect agent in the CAD agent sheet and ask again.",
            failed = true,
            outcome = "the CAD engine is not running",
        )
        val result = try {
            live.callTool(name, arguments, timeoutFor(name))
        } catch (error: McpException) {
            return AgentToolHost.Result(
                "the CAD engine could not run " + name + ": " +
                    (error.message ?: "no reason given"),
                failed = true,
            )
        } catch (error: Throwable) {
            return AgentToolHost.Result(
                "the app could not run " + name + ": " +
                    (error.message ?: error.javaClass.simpleName),
                failed = true,
            )
        }
        val delta = runCatching { SolidCraftTools.deltaFor(name, arguments, result) }
            .onFailure { log("could not read the change report of " + name + ": " + it.message) }
            .getOrNull()
        if (delta != null && !delta.isEmpty) {
            runCatching { onDocumentChanged(delta) }
                .onFailure { log("the live-view handler failed: " + it.message) }
        }
        val text = McpToolResults.text(result)
        val failed = McpToolResults.isError(result)
        return when {
            failed -> AgentToolHost.Result(
                "the CAD engine refused " + name + ": " + text,
                failed = true,
            )
            // A phone's model context is not unbounded: inspect_design on a big document can be
            // far past what the endpoint will accept. The cut is named so the model knows.
            text.length > MAX_RESULT_CHARS -> AgentToolHost.Result(
                text.take(MAX_RESULT_CHARS) + "\n… (result truncated at " +
                    MAX_RESULT_CHARS + " characters)",
            )
            else -> AgentToolHost.Result(text)
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
