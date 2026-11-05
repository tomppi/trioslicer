package com.tomppi.enderslicer.agent.engine

import com.tomppi.enderslicer.solidcraft.DocumentDelta
import com.tomppi.enderslicer.solidcraft.McpForwarder
import com.tomppi.enderslicer.solidcraft.McpRemoteException
import com.tomppi.enderslicer.solidcraft.McpTimeoutException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SolidCraft surface's contract - the parts of the old per-engine bridge that were about
 * the engine rather than the socket: the live-view delta after an edit, the refused
 * screenshot, and the error shapes kept from the engine.
 */
class SolidCraftToolSurfaceTest {

    private class FakeForwarder : McpForwarder {
        var lastMethod: String? = null
        var lastParams: JSONObject? = null
        var onRequest: (String, JSONObject, Long) -> JSONObject = { _, _, _ -> JSONObject().put("serverside", true) }

        override fun request(method: String, params: JSONObject, timeoutMillis: Long): JSONObject {
            lastMethod = method
            lastParams = params
            return onRequest(method, params, timeoutMillis)
        }

        override fun notify(method: String, params: JSONObject) {}
    }

    private fun toolCallResult(text: String, isError: Boolean = false): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .apply { if (isError) put("isError", true) }

    private fun surface(forwarder: FakeForwarder, deltas: MutableList<DocumentDelta> = mutableListOf()) =
        SolidCraftToolSurface(forwarder = { forwarder }, onDocumentChanged = { deltas += it })

    @Test
    fun anEditThatNamesBodiesReportsADeltaForTheLiveView() {
        val forwarder = FakeForwarder()
        forwarder.onRequest = { _, _, _ ->
            toolCallResult(
                JSONObject()
                    .put("created_bodies", JSONArray().put("Body1"))
                    .put("changed_bodies", JSONArray())
                    .put("removed_bodies", JSONArray())
                    .toString(),
            )
        }
        val deltas = mutableListOf<DocumentDelta>()
        val surface = surface(forwarder, deltas)

        surface.call(
            "execute",
            JSONObject().put("command", "solid.box").put("params", JSONObject()),
            300_000,
        )

        assertEquals(1, deltas.size)
        assertEquals(listOf("Body1"), deltas[0].createdBodies)
        assertEquals("solid.box", deltas[0].command)
    }

    @Test
    fun aReadOnlyCallReportsNoDelta() {
        val forwarder = FakeForwarder()
        forwarder.onRequest = { _, _, _ ->
            toolCallResult(JSONObject().put("metrics", JSONObject()).toString())
        }
        val deltas = mutableListOf<DocumentDelta>()
        val surface = surface(forwarder, deltas)

        surface.call("inspect", JSONObject(), 300_000)

        assertTrue(deltas.isEmpty())
    }

    @Test
    fun theEnginesScreenshotToolIsRefusedAndNeverForwarded() {
        val forwarder = FakeForwarder()
        val result = surface(forwarder).call("screenshot", JSONObject(), 300_000)

        assertTrue(result.getBoolean("isError"))
        assertNull("the engine never hears of it", forwarder.lastMethod)
    }

    @Test
    fun aToolTheAppHasNeverHeardOfIsForwardedToTheEngine() {
        val forwarder = FakeForwarder()
        val result = surface(forwarder).call("solid.unknown_tool", JSONObject().put("x", 1), 300_000)

        assertEquals("tools/call", forwarder.lastMethod)
        assertEquals("solid.unknown_tool", forwarder.lastParams?.optString("name"))
        assertEquals("the engine's own answer passes through", true, result.optBoolean("serverside"))
    }

    @Test
    fun anEngineTimeoutBecomesTheUncertainError() {
        val forwarder = FakeForwarder()
        forwarder.onRequest = { method, _, _ -> throw McpTimeoutException(method, 300_000) }

        val error = runCatching {
            surface(forwarder).call("execute", JSONObject(), 300_000)
        }.exceptionOrNull()

        assertTrue(error is EngineTimeoutException)
        assertEquals(300_000L, (error as EngineTimeoutException).timeoutMillis)
    }

    @Test
    fun anEngineJsonRpcErrorKeepsItsCodeAndMessage() {
        val forwarder = FakeForwarder()
        forwarder.onRequest = { _, _, _ -> throw McpRemoteException(-32602, "the engine said no") }

        val error = runCatching {
            surface(forwarder).call("execute", JSONObject(), 300_000)
        }.exceptionOrNull()

        assertTrue(error is EngineRemoteException)
        assertEquals(-32602, (error as EngineRemoteException).code)
        assertEquals("the engine said no", error.message)
    }

    @Test
    fun theToolsAreTheEnginesOwnList() {
        val forwarder = FakeForwarder()
        forwarder.onRequest = { _, _, _ ->
            JSONObject().put("tools", JSONArray().put(JSONObject().put("name", "solid.box")))
        }

        val tools = surface(forwarder).tools()

        assertEquals(1, tools.length())
        assertEquals("solid.box", tools.getJSONObject(0).getString("name"))
    }
}
