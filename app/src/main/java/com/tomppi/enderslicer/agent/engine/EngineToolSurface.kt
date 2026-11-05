package com.tomppi.enderslicer.agent.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * One engine behind the unified MCP server.
 *
 * The server owns the socket, the token, the selector and the tool scoping; a surface owns
 * one engine's tool catalogue and the route to it. The three engines answer two different
 * protocols - SolidCraft is MCP over the app's own stdio session, Blender and the CAD engine
 * are their own type/params wire - and the surface is what makes them look like one to the
 * agent on the far side.
 */
internal interface EngineToolSurface {

    /** The selector's id: "solidcraft", "cad" or "blender". */
    val id: String

    /** What the selector's result names first. */
    val displayName: String

    /** Whether the engine is up right now - the selector says so rather than guessing. */
    fun isRunning(): Boolean

    /**
     * How to reach this engine's tools and what rules govern them - the body of the
     * engine_select answer. It is the one place the agent learns how this engine behaves:
     * where its exports land, what the plate does, who may edit.
     */
    fun reachAndRules(): String

    /** This engine's tools, in MCP tools/list shape, using the engine's own names. */
    fun tools(): JSONArray

    /**
     * Run one tool. Returns the MCP tools/call result shape
     * ({"content": [{"type": "text", ...}], "isError": false}).
     *
     * @throws EngineTimeoutException when the engine accepted the call but did not answer.
     * @throws EngineUnavailableException when the engine is not running or the call failed.
     */
    fun call(name: String, arguments: JSONObject, timeoutMillis: Long): JSONObject

    /** Forward one notification (only SolidCraft's stdio session has a use for these). */
    fun notify(method: String, params: JSONObject) {}
}

/** The engine accepted the call but did not answer in time: it may still have run. */
internal class EngineTimeoutException(val timeoutMillis: Long) :
    Exception("engine timeout after " + timeoutMillis + " ms")

/** The engine is not running, or its call failed outright. */
internal class EngineUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** The engine answered with its own JSON-RPC error: its code and message pass through. */
internal class EngineRemoteException(val code: Int, message: String) : Exception(message)

/**
 * One result line the way the MCP tools/call shape wants it: the engine's answer as text,
 * with the answer's JSON as structured content when it is an object.
 */
internal object EngineToolResults {
    fun text(message: String, isError: Boolean = false): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message)))
        .put("isError", isError)

    fun json(result: JSONObject): JSONObject = JSONObject()
        .put("content", JSONArray().put(
            JSONObject().put("type", "text").put("text", result.toString()),
        ))
        .put("structuredContent", result)
        .put("isError", false)

    fun error(message: String): JSONObject = text(message, isError = true)
}
