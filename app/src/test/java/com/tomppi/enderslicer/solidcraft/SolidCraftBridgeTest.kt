package com.tomppi.enderslicer.solidcraft

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The agent bridge's contract: the token boundary, id rewriting, error mapping, and - the
 * reason it exists - the live-view notification after an edit.
 */
class SolidCraftBridgeTest {

    private class FakeForwarder : McpForwarder {
        var lastMethod: String? = null
        var lastParams: JSONObject? = null
        val notifications = mutableListOf<String>()
        var onRequest: (String, JSONObject, Long) -> JSONObject = { _, _, _ ->
            JSONObject().put("serverside", true)
        }

        override fun request(method: String, params: JSONObject, timeoutMillis: Long): JSONObject {
            lastMethod = method
            lastParams = params
            return onRequest(method, params, timeoutMillis)
        }

        override fun notify(method: String, params: JSONObject) {
            notifications += method
        }
    }

    private fun toolCallResult(text: String, isError: Boolean = false): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .apply { if (isError) put("isError", true) }

    private fun line(message: JSONObject): String = message.toString()

    @Test
    fun aRequestWithoutTheTokenIsRefusedAndNeverForwarded() {
        val forwarder = FakeForwarder()
        val bridge = SolidCraftBridge("secret", forwarder, onDocumentChanged = {})
        val reply = JSONObject(
            bridge.handleLine(
                line(
                    JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "initialize")
                        .put("params", JSONObject()),
                ),
            )!!,
        )
        assertEquals(-32001, reply.getJSONObject("error").getInt("code"))
        assertNull(forwarder.lastMethod)
    }

    @Test
    fun theTokenIsAcceptedAtTheTopLevelAndInParams() {
        val forwarder = FakeForwarder()
        val bridge = SolidCraftBridge("secret", forwarder, onDocumentChanged = {})
        val first = JSONObject(
            bridge.handleLine(
                line(
                    JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/list")
                        .put("token", "secret").put("params", JSONObject()),
                ),
            )!!,
        )
        assertEquals(1, first.getInt("id"))
        assertTrue(first.has("result"))
        val second = JSONObject(
            bridge.handleLine(
                line(
                    JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/list")
                        .put("params", JSONObject().put("token", "secret")),
                ),
            )!!,
        )
        assertEquals(2, second.getInt("id"))
    }

    @Test
    fun theClientsIdIsPreservedAcrossTheForwardedCall() {
        val forwarder = FakeForwarder()
        forwarder.onRequest = { _, _, _ ->
            JSONObject().put("protocolVersion", "2025-06-18").put("serverInfo", JSONObject().put("name", "solidcraft"))
        }
        val bridge = SolidCraftBridge("secret", forwarder, onDocumentChanged = {})
        val reply = JSONObject(
            bridge.handleLine(
                line(
                    JSONObject().put("jsonrpc", "2.0").put("id", 77).put("method", "initialize")
                        .put("token", "secret").put("params", JSONObject()),
                ),
            )!!,
        )
        assertEquals(77, reply.getInt("id"))
        assertEquals("solidcraft", reply.getJSONObject("result").getJSONObject("serverInfo").getString("name"))
        assertEquals("initialize", forwarder.lastMethod)
    }

    @Test
    fun aNotificationIsForwardedWithNoReply() {
        val forwarder = FakeForwarder()
        val bridge = SolidCraftBridge("secret", forwarder, onDocumentChanged = {})
        val reply = bridge.handleLine(
            line(
                JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")
                    .put("token", "secret").put("params", JSONObject()),
            ),
        )
        assertNull(reply)
        assertEquals(listOf("notifications/initialized"), forwarder.notifications)
    }

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
        val bridge = SolidCraftBridge("secret", forwarder, onDocumentChanged = { deltas += it })
        bridge.handleLine(
            line(
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", 5)
                    .put("method", "tools/call")
                    .put("token", "secret")
                    .put(
                        "params",
                        JSONObject()
                            .put("name", "execute")
                            .put(
                                "arguments",
                                JSONObject().put("command", "solid.box").put("params", JSONObject()),
                            ),
                    ),
            ),
        )
        assertEquals(1, deltas.size)
        assertEquals(listOf("Body1"), deltas[0].createdBodies)
        assertEquals("solid.box", deltas[0].command)
    }

    @Test
    fun aReadOnlyCallReportsNoDelta() {
        val forwarder = FakeForwarder()
        forwarder.onRequest = { _, _, _ -> toolCallResult(JSONObject().put("bodies", JSONArray()).toString()) }
        val deltas = mutableListOf<DocumentDelta>()
        val bridge = SolidCraftBridge("secret", forwarder, onDocumentChanged = { deltas += it })
        bridge.handleLine(
            line(
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", 6)
                    .put("method", "tools/call")
                    .put("token", "secret")
                    .put(
                        "params",
                        JSONObject().put("name", "inspect_design").put("arguments", JSONObject()),
                    ),
            ),
        )
        assertTrue("a read-only tool must not repaint", deltas.isEmpty())
    }

    @Test
    fun theEnginesScreenshotToolIsRefusedAndNeverForwarded() {
        val forwarder = FakeForwarder()
        val bridge = SolidCraftBridge("secret", forwarder, onDocumentChanged = {})
        val reply = JSONObject(
            bridge.handleLine(
                line(
                    JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", 9)
                        .put("method", "tools/call")
                        .put("token", "secret")
                        .put("params", JSONObject().put("name", "screenshot").put("arguments", JSONObject())),
                ),
            )!!,
        )
        assertTrue(reply.getJSONObject("result").optBoolean("isError"))
        assertNull(forwarder.lastMethod)
    }

    @Test
    fun aToolTheAppHasNeverHeardOfIsForwardedByTheBridge() {
        // The bridge forwards the engine's tools/list verbatim: an external agent sees a tool a
        // later engine adds without an app change, and only tools/call is policy-checked.
        val forwarder = FakeForwarder()
        forwarder.onRequest = { _, _, _ ->
            JSONObject().put(
                "tools",
                JSONArray()
                    .put(JSONObject().put("name", "screenshot"))
                    .put(JSONObject().put("name", "weldment_create")),
            )
        }
        val bridge = SolidCraftBridge("secret", forwarder, onDocumentChanged = {})
        val reply = JSONObject(
            bridge.handleLine(
                line(
                    JSONObject().put("jsonrpc", "2.0").put("id", 21).put("method", "tools/list")
                        .put("token", "secret").put("params", JSONObject()),
                ),
            )!!,
        )
        val tools = reply.getJSONObject("result").getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertEquals(listOf("screenshot", "weldment_create"), names)
        assertEquals("tools/list", forwarder.lastMethod)
    }

    @Test
    fun anEngineTimeoutIsReportedAsUncertainRatherThanRetried() {
        val forwarder = FakeForwarder()
        forwarder.onRequest = { _, _, _ ->
            throw McpTimeoutException("tools/call", 250)
        }
        val bridge = SolidCraftBridge("secret", forwarder, onDocumentChanged = {})
        val reply = JSONObject(
            bridge.handleLine(
                line(
                    JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("id", 3)
                        .put("method", "tools/call")
                        .put("token", "secret")
                        .put(
                            "params",
                            JSONObject().put("name", "execute")
                                .put("arguments", JSONObject().put("command", "solid.box")),
                        ),
                ),
            )!!,
        )
        val error = reply.getJSONObject("error")
        assertEquals(-32003, error.getInt("code"))
        assertTrue(error.getString("message").contains("may still have run"))
    }

    @Test
    fun anEngineJsonRpcErrorKeepsItsCodeAndMessage() {
        val forwarder = FakeForwarder()
        forwarder.onRequest = { _, _, _ -> throw McpRemoteException(-32602, "missing tool name") }
        val bridge = SolidCraftBridge("secret", forwarder, onDocumentChanged = {})
        val reply = JSONObject(
            bridge.handleLine(
                line(
                    JSONObject().put("jsonrpc", "2.0").put("id", 4).put("method", "tools/call")
                        .put("token", "secret").put("params", JSONObject()),
                ),
            )!!,
        )
        assertEquals(-32602, reply.getJSONObject("error").getInt("code"))
        assertEquals("missing tool name", reply.getJSONObject("error").getString("message"))
    }

    @Test
    fun aMalformedLineIsAParseError() {
        val bridge = SolidCraftBridge("secret", FakeForwarder(), onDocumentChanged = {})
        val reply = JSONObject(bridge.handleLine("{ not json")!!)
        assertEquals(-32700, reply.getJSONObject("error").getInt("code"))
    }

    @Test
    fun aLineWithNoMethodIsInvalid() {
        val bridge = SolidCraftBridge("secret", FakeForwarder(), onDocumentChanged = {})
        val reply = JSONObject(
            bridge.handleLine(line(JSONObject().put("jsonrpc", "2.0").put("id", 1).put("token", "secret")))!!,
        )
        assertEquals(-32600, reply.getJSONObject("error").getInt("code"))
    }

    @Test
    fun theLoopbackSocketServesARequestAndClosesCleanly() {
        val forwarder = FakeForwarder()
        forwarder.onRequest = { _, _, _ -> JSONObject().put("protocolVersion", "2025-06-18") }
        val bridge = SolidCraftBridge("secret", forwarder, onDocumentChanged = {}, port = 0)
        try {
            bridge.start()
            assertTrue("bridge did not bind", bridge.boundPort > 0)
            java.net.Socket("127.0.0.1", bridge.boundPort).use { socket ->
                socket.soTimeout = 5_000
                val writer = socket.getOutputStream()
                val reader = socket.getInputStream().bufferedReader()
                val request = JSONObject()
                    .put("jsonrpc", "2.0").put("id", 12).put("method", "initialize")
                    .put("token", "secret").put("params", JSONObject())
                writer.write((request.toString() + "\n").toByteArray())
                writer.flush()
                val reply = JSONObject(reader.readLine())
                assertEquals(12, reply.getInt("id"))
                assertTrue(reply.has("result"))
            }
        } finally {
            bridge.close()
        }
    }
}