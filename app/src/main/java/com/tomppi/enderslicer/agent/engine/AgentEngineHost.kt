package com.tomppi.enderslicer.agent.engine

import com.tomppi.enderslicer.cadagent.AgentToolHost
import org.json.JSONArray
import org.json.JSONObject

/**
 * The in-app agent's tool host over one of the wire engines (the CAD engine or Blender).
 *
 * The in-app agent's host seam is one call in and one result out; the wire engines answer
 * their own {"status","result"|"message"} shape and [WireEngineSurface] has already turned
 * that into a tool result. What is left here is the honesty rules: a timeout says the call
 * may still have run, an unavailable engine names its menu, and no call ever throws at the
 * model - it gets a sentence and can try again.
 */
internal class WireEngineAgentHost(
    private val surface: EngineToolSurface,
    private val log: (String) -> Unit = {},
) : AgentToolHost {

    /** The engine's catalogue as the engine described it - the same shape the sessions give. */
    fun toolDefinitions(): JSONArray = surface.tools()

    override fun call(name: String, arguments: JSONObject): AgentToolHost.Result {
        return try {
            val result = surface.call(name, arguments, TOOL_TIMEOUT_MILLIS)
            val text = result.optJSONArray("content")?.optJSONObject(0)?.optString("text")
                ?: result.toString()
            AgentToolHost.Result(text, failed = result.optBoolean("isError"), outcome = if (result.optBoolean("isError")) "engine error" else null)
        } catch (timeout: EngineTimeoutException) {
            AgentToolHost.Result(
                "The " + surface.displayName + " engine did not answer " + name + " within " +
                    timeout.timeoutMillis + " ms. The call may still have run: inspect the " +
                    "scene before sending it again.",
                failed = true,
                outcome = "timeout",
            )
        } catch (unavailable: EngineUnavailableException) {
            AgentToolHost.Result(
                "The " + surface.displayName + " engine is not running - start it in the app's " +
                    "menu, then ask again.",
                failed = true,
                outcome = "not running",
            )
        } catch (error: Throwable) {
            log("in-app " + surface.id + " tool " + name + " failed: " + (error.message ?: error.javaClass.simpleName))
            AgentToolHost.Result(
                "The " + surface.displayName + " engine could not run " + name + ": " +
                    (error.message ?: error.javaClass.simpleName),
                failed = true,
                outcome = "failed",
            )
        }
    }

    companion object {
        private const val TOOL_TIMEOUT_MILLIS = 300_000L
    }
}
