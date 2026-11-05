package com.tomppi.enderslicer.agent.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unified engine MCP server's contract: the token boundary, the selector and the tool
 * scoping it owns, the routing to the three engines, and the error shapes an agent reads.
 *
 * The surfaces are fake here - one per engine, capturing what reached them - so the protocol
 * is exercised without a process. The SolidCraft surface's own contracts (live-view delta,
 * screenshot refusal, error mapping) live in [SolidCraftToolSurfaceTest].
 */
class EngineMcpServerTest {

    private class FakeSurface(override val id: String) : EngineToolSurface {
        override val displayName: String = id
        val calls = mutableListOf<Pair<String, JSONObject>>()
        val notifications = mutableListOf<String>()
        var toolNames: List<String> = listOf(id + "_tool", id + "_execute_code")
        var onCall: (String, JSONObject) -> JSONObject = { name, _ -> JSONObject().put("ran", name) }
        var running: Boolean = true

        override fun isRunning(): Boolean = running
        override fun reachAndRules(): String = "rules for " + id
        override fun tools(): JSONArray = JSONArray().also { list ->
            toolNames.forEach { list.put(JSONObject().put("name", it).put("description", id).put("inputSchema", JSONObject())) }
        }

        override fun call(name: String, arguments: JSONObject, timeoutMillis: Long): JSONObject {
            calls += name to arguments
            return onCall(name, arguments)
        }

        override fun notify(method: String, params: JSONObject) {
            notifications += method
        }
    }

    private fun server(vararg surfaces: FakeSurface): EngineMcpServer =
        EngineMcpServer("secret", surfaces.toList())

    private fun request(id: Int, method: String, params: JSONObject = JSONObject(), token: String? = "secret"): JSONObject {
        val message = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        if (token != null) message.put("token", token)
        return JSONObject(requireNotNull(server().handleLine(message.toString())))
    }

    private fun requestOn(server: EngineMcpServer, id: Int, method: String, params: JSONObject = JSONObject(), token: String? = "secret"): JSONObject {
        val message = JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params)
        if (token != null) message.put("token", token)
        return JSONObject(requireNotNull(server.handleLine(message.toString())))
    }

    @Test
    fun aRequestWithoutTheTokenIsRefusedAndNeverRouted() {
        val solidcraft = FakeSurface("solidcraft")
        val reply = requestOn(server(solidcraft), 1, "tools/list", token = null)

        assertEquals(-32001, reply.getJSONObject("error").getInt("code"))
        assertTrue(solidcraft.calls.isEmpty())
    }

    @Test
    fun theTokenIsAcceptedAtTheTopLevelAndInParams() {
        val solidcraft = FakeSurface("solidcraft")
        val server = server(solidcraft)
        val topLevel = requestOn(server, 1, "tools/list")
        val inParams = JSONObject(
            requireNotNull(
                server.handleLine(
                    JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/list")
                        .put("params", JSONObject().put("token", "secret")).toString(),
                ),
            ),
        )

        assertTrue(topLevel.has("result"))
        assertTrue(inParams.has("result"))
    }

    @Test
    fun theClientsIdIsPreservedAcrossTheRoutedCall() {
        val solidcraft = FakeSurface("solidcraft")
        val reply = requestOn(server(solidcraft), 42, "tools/call", JSONObject().put("name", "solidcraft_tool"))

        assertEquals(42, reply.getInt("id"))
        assertEquals("solidcraft_tool", solidcraft.calls.first().first)
    }

    @Test
    fun aNotificationIsForwardedToTheActiveEngineWithNoReply() {
        val solidcraft = FakeSurface("solidcraft")
        val message = JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")
            .put("params", JSONObject()).put("token", "secret")

        assertNullish(server(solidcraft).handleLine(message.toString()))
        assertEquals(listOf("notifications/initialized"), solidcraft.notifications)
    }

    @Test
    fun theSelectorToolsAreAlwaysListedAndTheActiveEnginesToolsFollow() {
        val solidcraft = FakeSurface("solidcraft")
        val cad = FakeSurface("cad")
        val server = server(solidcraft, cad)

        val before = requestOn(server, 1, "tools/list").getJSONObject("result").getJSONArray("tools")
        val names = (0 until before.length()).map { before.getJSONObject(it).getString("name") }

        assertTrue(names.containsAll(listOf("engine_select", "engine_status")))
        assertTrue(names.containsAll(solidcraft.toolNames))
        assertTrue(names.none { it.startsWith("cad_") })
    }

    @Test
    fun engineSelectRescopesTheToolListAndNamesHowToReachTheEngine() {
        val solidcraft = FakeSurface("solidcraft")
        val cad = FakeSurface("cad")
        val server = server(solidcraft, cad)

        val selected = requestOn(
            server, 1, "tools/call",
            JSONObject().put("name", "engine_select")
                .put("arguments", JSONObject().put("engine", "cad")),
        ).getJSONObject("result")

        val text = selected.getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue("the answer names the engine: " + text, text.contains("Selected engine: cad"))
        assertTrue("and how to reach it: " + text, text.contains("rules for cad"))
        assertEquals("cad", server.activeEngine()?.id)

        val after = requestOn(server, 2, "tools/list").getJSONObject("result").getJSONArray("tools")
        val names = (0 until after.length()).map { after.getJSONObject(it).getString("name") }
        assertTrue(names.containsAll(cad.toolNames))
        assertTrue(names.none { it.startsWith("solidcraft_") })
    }

    @Test
    fun theDefaultEngineIsSolidcraft() {
        val server = server(FakeSurface("solidcraft"))
        assertEquals("solidcraft", server.activeEngine()?.id)
    }

    @Test
    fun anUnknownEngineIsRefusedByTheSelector() {
        val reply = requestOn(
            server(FakeSurface("solidcraft")), 1, "tools/call",
            JSONObject().put("name", "engine_select")
                .put("arguments", JSONObject().put("engine", "sketch")),
        )

        assertEquals(-32602, reply.getJSONObject("error").getInt("code"))
    }

    @Test
    fun toolsCallRoutesToTheSelectedEngineOnly() {
        val solidcraft = FakeSurface("solidcraft")
        val blender = FakeSurface("blender")
        val server = server(solidcraft, blender)

        requestOn(server, 1, "tools/call", JSONObject().put("name", "execute_code"))
        requestOn(
            server, 2, "tools/call",
            JSONObject().put("name", "engine_select")
                .put("arguments", JSONObject().put("engine", "blender")),
        )
        requestOn(server, 3, "tools/call", JSONObject().put("name", "execute_code"))

        assertEquals(listOf("execute_code"), solidcraft.calls.map { it.first })
        assertEquals(listOf("execute_code"), blender.calls.map { it.first })
    }

    @Test
    fun engineStatusNamesTheSelectionAndEveryEnginesReadiness() {
        val solidcraft = FakeSurface("solidcraft")
        val cad = FakeSurface("cad").also { it.running = false }
        val server = server(solidcraft, cad)

        val text = requestOn(server, 1, "tools/call", JSONObject().put("name", "engine_status"))
            .getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")

        assertTrue(text.contains("Selected engine: solidcraft"))
        assertTrue(text.contains("cad: not started"))
    }

    @Test
    fun anEngineTimeoutIsReportedAsUncertainRatherThanCertain() {
        val solidcraft = FakeSurface("solidcraft")
        solidcraft.onCall = { _, _ -> throw EngineTimeoutException(300_000) }

        val reply = requestOn(server(solidcraft), 1, "tools/call", JSONObject().put("name", "slow_tool"))
        val error = reply.getJSONObject("error")

        assertEquals(-32003, error.getInt("code"))
        assertTrue(error.getString("message").contains("may still have run"))
    }

    @Test
    fun anEngineErrorKeepsItsCodeAndMessage() {
        val solidcraft = FakeSurface("solidcraft")
        solidcraft.onCall = { _, _ -> throw EngineRemoteException(-32602, "the engine said no") }

        val reply = requestOn(server(solidcraft), 1, "tools/call", JSONObject().put("name", "any"))
        val error = reply.getJSONObject("error")

        assertEquals(-32602, error.getInt("code"))
        assertEquals("the engine said no", error.getString("message"))
    }

    @Test
    fun aMalformedLineIsAParseError() {
        val reply = JSONObject(requireNotNull(server().handleLine("not json")))
        assertEquals(-32700, reply.getJSONObject("error").getInt("code"))
    }

    @Test
    fun aLineWithNoMethodIsInvalid() {
        val reply = requestOn(server(), 1, "").getJSONObject("error")
        assertEquals(-32600, reply.getInt("code"))
    }

    @Test
    fun initializeAdvertisesTheToolsCapability() {
        val result = request(1, "initialize").getJSONObject("result")

        assertEquals("trioslicer-engines", result.getJSONObject("serverInfo").getString("name"))
        assertTrue(result.getJSONObject("capabilities").getJSONObject("tools").getBoolean("listChanged"))
    }

    private fun assertNullish(value: String?) = assertEquals(null, value)

    @Test
    fun aReplyIsOneBufferedLine() {
        val out = java.io.StringWriter()
        server().writeReply(java.io.BufferedWriter(out), "{\"jsonrpc\":\"2.0\",\"id\":1}")
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1}\n", out.toString())
    }

    @Test
    fun theConnectionWriterIsBufferedSoOneReplyIsOneWrite() {
        val source = listOf(
            java.io.File("src/main/java/com/tomppi/enderslicer/agent/engine/EngineMcpServer.kt"),
            java.io.File("app/src/main/java/com/tomppi/enderslicer/agent/engine/EngineMcpServer.kt"),
        ).firstOrNull(java.io.File::isFile)?.readText() ?: error("EngineMcpServer.kt not found")
        assertTrue(
            "one buffered write per reply: the object and its newline written straight to the " +
                "raw stream are two TCP segments and cost ~44 ms per call on an adb-forwarded door",
            source.contains("socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)"),
        )
        assertTrue(source.contains("writer.write(reply)"))
        assertTrue(source.contains("writer.newLine()"))
        assertTrue(source.contains("writer.flush()"))
    }
}
