package com.tomppi.enderslicer.scanruler.engine

import com.tomppi.enderslicer.cadagent.AgentToolHost
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * The in-app agent's tool host while the ScanRuler view is selected.
 *
 * The whole catalogue is the page's own commands, so the agent works the live page exactly as
 * an external MCP agent does: no second WebView, no MCP socket, no duplicated state. Tool names
 * are the page command names with dots -> underscores (the same mapping the MCP facade uses),
 * resolved against the page's own catalogue. Every call is a normal one-way tool result; the
 * model never gets an exception, it gets the page's sentence and can try again.
 */
class ScanRulerAgentHost(
    private val session: ScanRulerEmbeddedSession,
    private val log: (String) -> Unit = {},
) : AgentToolHost {

    override fun call(name: String, arguments: JSONObject): AgentToolHost.Result {
        return try {
            when (name) {
                "scanruler_status" -> AgentToolHost.Result(session.statusSummary())
                "scanruler_wait" -> AgentToolHost.Result(
                    session.waitForPageText(arguments.optLong("timeoutMs", 20_000L).coerceIn(0L, 120_000L)),
                )
                else -> {
                    val command = session.commandForTool(name)
                        ?: return AgentToolHost.Result(
                            "The ScanRuler page does not offer " + name + " right now.",
                            failed = true,
                            outcome = "not offered",
                        )
                    val result = runBlocking { session.command(command.name, arguments) }
                    AgentToolHost.Result(result.toString())
                }
            }
        } catch (error: ScanRulerCommandException) {
            AgentToolHost.Result(
                "ScanRuler refused " + name + ": " + (error.message ?: error.code),
                failed = true,
                outcome = "page error",
            )
        } catch (error: ScanRulerLinkException) {
            AgentToolHost.Result(
                "The ScanRuler page is not connected: " + (error.message ?: "link unavailable"),
                failed = true,
                outcome = "not connected",
            )
        } catch (error: Throwable) {
            log("in-app ScanRuler tool " + name + " failed: " + (error.message ?: error.javaClass.simpleName))
            AgentToolHost.Result(
                "ScanRuler could not run " + name + ": " + (error.message ?: error.javaClass.simpleName),
                failed = true,
                outcome = "failed",
            )
        }
    }
}

/** The tool catalogue the in-app agent should send for this conversation. */
fun ScanRulerEmbeddedSession.agentToolDefinitions(): JSONArray = toolDefinitions()
