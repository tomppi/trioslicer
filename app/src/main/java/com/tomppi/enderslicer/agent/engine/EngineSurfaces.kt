package com.tomppi.enderslicer.agent.engine

import com.tomppi.enderslicer.solidcraft.DocumentDelta
import com.tomppi.enderslicer.solidcraft.McpForwarder
import com.tomppi.enderslicer.solidcraft.McpRemoteException
import com.tomppi.enderslicer.solidcraft.McpTimeoutException
import com.tomppi.enderslicer.solidcraft.SolidCraftTools
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Blender or CAD engine behind the tool surface: the engine's own command names, its
 * type/params wire, its answers as MCP results. The wire answers are the engine's own
 * {"status", "result" | "message"} shape; a success becomes the tool's result and a failure
 * becomes the tool's named error, so the agent reads one shape whatever the engine is.
 */
internal class WireEngineSurface(
    override val id: String,
    override val displayName: String,
    private val catalog: JSONArray,
    private val rules: String,
    private val client: EngineWireClient,
) : EngineToolSurface {

    /**
     * The last wire contact that proved the engine is alive. The engine's socket serves one
     * client at a time - this surface's own - so a probe on a second connection starves and
     * readiness flapped while the engine answered commands: "not started" between two
     * successful calls. Contact is the truth; the ping runs on this surface's own connection
     * when contact is stale, never on a second one.
     */
    @Volatile
    private var lastContactAt: Long = 0L

    override fun isRunning(): Boolean {
        if (System.currentTimeMillis() - lastContactAt < CONTACT_TRUST_MILLIS) return true
        return runCatching {
            client.command("ping", JSONObject(), PING_TIMEOUT_MILLIS.toInt())
                .optString("status") == "success"
        }.getOrDefault(false).also { ok -> if (ok) lastContactAt = System.currentTimeMillis() }
    }

    override fun reachAndRules(): String = rules

    override fun tools(): JSONArray = catalog

    override fun call(name: String, arguments: JSONObject, timeoutMillis: Long): JSONObject {
        // A stopped engine must answer by name in about a second. The engine's own socket can
        // be listening while the engine is not serving (the app holds the port), so dispatching
        // blindly wedges a read for the whole tool timeout instead of saying "not started".
        if (!isRunning()) {
            throw EngineUnavailableException(
                displayName + " is not started - start it in the app's menu, then ask again",
            )
        }
        val reply = try {
            client.command(name, arguments, (timeoutMillis.coerceIn(1L, Int.MAX_VALUE.toLong())).toInt())
                .also { lastContactAt = System.currentTimeMillis() }
        } catch (timeout: EngineTimeoutException) {
            lastContactAt = 0L
            throw timeout
        } catch (unavailable: EngineUnavailableException) {
            lastContactAt = 0L
            throw unavailable
        }
        val status = reply.optString("status")
        if (status == "success") {
            val result = reply.opt("result")
            return when (result) {
                is JSONObject -> EngineToolResults.json(result)
                JSONObject.NULL, null -> EngineToolResults.json(JSONObject())
                else -> EngineToolResults.json(JSONObject().put("value", result.toString()))
            }
        }
        return EngineToolResults.error(reply.optString("message", "the engine refused " + name))
    }

    private companion object {
        /** A successful wire contact keeps readiness true this long before pinging again. */
        private const val CONTACT_TRUST_MILLIS = 30_000L
        private const val PING_TIMEOUT_MILLIS = 1_000L
    }
}

/**
 * The SolidCraft engine behind the tool surface: real MCP, forwarded onto the app's own stdio
 * session. The engine owns its tool list (a tool it adds appears here without an app change),
 * and the live-view contract rides along with every edit.
 */
internal class SolidCraftToolSurface(
    private val forwarder: () -> McpForwarder?,
    private val onDocumentChanged: (DocumentDelta) -> Unit,
) : EngineToolSurface {

    override val id: String = "solidcraft"
    override val displayName: String = "SolidCraft"

    override fun isRunning(): Boolean = forwarder() != null

    override fun reachAndRules(): String = EngineCatalogs.reachAndRules(id)

    override fun tools(): JSONArray {
        val session = forwarder() ?: return JSONArray()
        val result = session.request("tools/list", JSONObject(), REQUEST_TIMEOUT_MILLIS)
        return result.optJSONArray("tools") ?: JSONArray()
    }

    override fun call(name: String, arguments: JSONObject, timeoutMillis: Long): JSONObject {
        if (name == "screenshot") {
            // The plate is the viewport: the engine's screenshot is a second picture of a
            // camera the user does not see. Refused with the sentence the bridge always gave.
            return EngineToolResults.error(
                "the plate is the viewport: the app renders this document itself, and the " +
                    "engine's screenshot would be a second picture of a camera the user does not see. " +
                    "Ask the user to look at the plate.",
            )
        }
        val session = forwarder() ?: throw EngineUnavailableException("the SolidCraft engine is not running")
        val params = JSONObject().put("name", name).put("arguments", arguments)
        val result = try {
            session.request("tools/call", params, timeoutMillis)
        } catch (timeout: McpTimeoutException) {
            throw EngineTimeoutException(timeout.timeoutMillis)
        } catch (remote: McpRemoteException) {
            throw EngineRemoteException(remote.code, remote.remoteMessage)
        }
        reportChange(name, arguments, result)
        return result
    }

    override fun notify(method: String, params: JSONObject) {
        runCatching { forwarder()?.notify(method, params) }
    }

    /** The live-view half: turn a successful edit's body report into a plate repaint. */
    private fun reportChange(tool: String, arguments: JSONObject, result: JSONObject) {
        val delta = runCatching { SolidCraftTools.deltaFor(tool, arguments, result) }.getOrNull() ?: return
        if (delta.isEmpty) return
        runCatching { onDocumentChanged(delta) }
    }

    companion object {
        private const val REQUEST_TIMEOUT_MILLIS = 60_000L
    }
}
