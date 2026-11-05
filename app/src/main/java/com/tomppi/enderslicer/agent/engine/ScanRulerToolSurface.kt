package com.tomppi.enderslicer.agent.engine

import com.tomppi.enderslicer.scanruler.engine.ScanRulerCommandException
import com.tomppi.enderslicer.scanruler.engine.ScanRulerEmbeddedSession
import com.tomppi.enderslicer.scanruler.engine.ScanRulerLinkException
import org.json.JSONArray
import org.json.JSONObject

/**
 * The embedded ScanRuler workspace behind the unified server's tool surface.
 *
 * The page's catalogue is the tool list (the same mcpTools mapping the old 9879 facade used),
 * so an external agent and the in-app agent drive the live page through one name set. The
 * session is supplied lazily: the surface exists whether or not the workspace is open, and a
 * call before it is answers with the sentence that says so.
 */
internal class ScanRulerToolSurface(
    private val session: () -> ScanRulerEmbeddedSession?,
) : EngineToolSurface {

    override val id: String = "scanruler"
    override val displayName: String = "ScanRuler"

    override fun isRunning(): Boolean = session() != null

    override fun reachAndRules(): String = EngineCatalogs.reachAndRules(id)

    override fun tools(): JSONArray = session()?.toolDefinitions() ?: JSONArray()

    override fun call(name: String, arguments: JSONObject, timeoutMillis: Long): JSONObject {
        val live = session()
            ?: throw EngineUnavailableException(
                "the ScanRuler workspace is not open - open it from the plate's View switch first",
            )
        return try {
            when (name) {
                "scanruler_status" -> EngineToolResults.text(live.statusSummary())
                "scanruler_wait" -> EngineToolResults.text(
                    live.waitForPageText(
                        arguments.optLong("timeoutMs", 20_000L).coerceIn(0L, 120_000L),
                    ),
                )
                else -> {
                    val command = live.commandForTool(name)
                        ?: return EngineToolResults.error(
                            "The ScanRuler page does not offer " + name + " right now.",
                        )
                    val result = kotlinx.coroutines.runBlocking {
                        live.command(command.name, arguments)
                    }
                    EngineToolResults.json(result)
                }
            }
        } catch (error: ScanRulerCommandException) {
            EngineToolResults.error("ScanRuler refused " + name + ": " + (error.message ?: error.code))
        } catch (error: ScanRulerLinkException) {
            EngineToolResults.error("The ScanRuler page is not connected: " + (error.message ?: "link unavailable"))
        }
    }
}
