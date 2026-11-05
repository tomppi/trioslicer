package com.tomppi.enderslicer.agent.engine

import java.net.InetAddress
import java.net.ServerSocket
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire engines' fail-fast contract: a stopped engine answers by name in about a second,
 * never wedging a read for the tool timeout. The engine's own socket can listen while the
 * engine is not serving - the gate is the readiness probe, not the connect.
 */
class WireEngineSurfaceTest {

    private fun surface(port: Int): WireEngineSurface = WireEngineSurface(
        id = "cad",
        displayName = "CAD engine",
        catalog = EngineCatalogs.cadTools(),
        rules = EngineCatalogs.reachAndRules("cad"),
        client = EngineWireClient(port = port, token = { "token" }),
    )

    @Test
    fun aStoppedEngineAnswersByNameWithoutTouchingTheWire() {
        // Port 1 is refused instantly, so a wire attempt would say "unavailable" too - the
        // named message is what proves the gate answered instead of the wire.
        val started = System.currentTimeMillis()
        val error = runCatching {
            surface(port = 1).call("execute_code", JSONObject(), 300_000)
        }.exceptionOrNull()
        val elapsed = System.currentTimeMillis() - started

        assertTrue(error is EngineUnavailableException)
        assertTrue((error as EngineUnavailableException).message.orEmpty().contains("is not started"))
        assertTrue("the answer is prompt, not a tool timeout: " + elapsed + " ms", elapsed < 5_000)
    }

    @Test
    fun everyCallerOnAnEngineSharesOneConnection() {
        // The engine serves one connection at a time: rival sockets starved each other -
        // readiness lied between two successful calls and an open screen held the engine
        // out of the agent's reach. One client per engine is the whole fix.
        val engine = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        var accepts = 0
        kotlin.concurrent.thread(isDaemon = true) {
            while (!engine.isClosed) {
                val client = runCatching { engine.accept() }.getOrNull() ?: break
                accepts++
                kotlin.concurrent.thread(isDaemon = true) {
                    runCatching {
                        val reader = client.getInputStream().bufferedReader()
                        while (true) {
                            if (reader.readLine() == null) break
                            val answer = JSONObject().put("status", "success").put("result", JSONObject())
                            client.getOutputStream().apply {
                                write((answer.toString() + "\n").toByteArray())
                                flush()
                            }
                        }
                    }
                    runCatching { client.close() }
                }
            }
        }
        try {
            val port = engine.localPort
            val surfaceClient = EngineWireClient.shared("127.0.0.1", port) { "token" }
            val surface = WireEngineSurface(
                id = "cad",
                displayName = "CAD engine",
                catalog = EngineCatalogs.cadTools(),
                rules = EngineCatalogs.reachAndRules("cad"),
                client = surfaceClient,
            )
            val preview = com.tomppi.enderslicer.modelling.EnginePreviewClient(
                host = "127.0.0.1",
                port = port,
                tokenFile = null,
            )
            val importer = com.tomppi.enderslicer.modelling.EnginePreviewClient(
                host = "127.0.0.1",
                port = port,
                tokenFile = null,
            )

            surface.call("get_scene_info", JSONObject(), 5_000)
            preview.command("ping", JSONObject(), 5_000)
            importer.command("ping", JSONObject(), 5_000)
            surface.call("get_metrics", JSONObject(), 5_000)

            assertEquals("every caller rides one engine connection", 1, accepts)
            preview.close()
            importer.close()
        } finally {
            runCatching { engine.close() }
        }
    }

    @Test
    fun readinessTrustsRecentContactAndDoesNotProbeBetweenCalls() {
        // A minimal engine at the far end of the wire: every line is answered.
        val engine = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        var pings = 0
        kotlin.concurrent.thread(isDaemon = true) {
            while (!engine.isClosed) {
                val client = runCatching { engine.accept() }.getOrNull() ?: break
                kotlin.concurrent.thread(isDaemon = true) {
                    runCatching {
                        val reader = client.getInputStream().bufferedReader()
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (JSONObject(line).optString("type") == "ping") pings++
                            val answer = JSONObject().put("status", "success").put("result", JSONObject())
                            client.getOutputStream().apply {
                                write((answer.toString() + "\n").toByteArray())
                                flush()
                            }
                        }
                    }
                    runCatching { client.close() }
                }
            }
        }
        try {
            val surface = WireEngineSurface(
                id = "cad",
                displayName = "CAD engine",
                catalog = EngineCatalogs.cadTools(),
                rules = EngineCatalogs.reachAndRules("cad"),
                client = EngineWireClient(port = engine.localPort, token = { "token" }),
            )

            surface.call("get_scene_info", JSONObject(), 5_000)
            assertTrue("contact is the truth", surface.isRunning())
            surface.call("get_metrics", JSONObject(), 5_000)
            assertTrue(surface.isRunning())
            assertEquals(
                "only the first contact pings, over the surface's own connection",
                1,
                pings,
            )
        } finally {
            runCatching { engine.close() }
        }
    }

    @Test
    fun aRunningEngineWithADeadWireStillReportsUnavailable() {
        val error = runCatching {
            surface(port = 1).call("execute_code", JSONObject(), 2_000)
        }.exceptionOrNull()

        assertTrue("expected an unavailable error, got " + error, error is EngineUnavailableException)
    }

    @Test
    fun theServerMapsAStoppedEngineToTheNamedErrorCode() {
        val stopped = object : EngineToolSurface {
            override val id: String = "solidcraft"
            override val displayName: String = "CAD engine"
            override fun isRunning(): Boolean = false
            override fun reachAndRules(): String = "rules"
            override fun tools(): JSONArray = JSONArray()
            override fun call(name: String, arguments: JSONObject, timeoutMillis: Long): JSONObject {
                throw EngineUnavailableException("CAD engine is not started - start it in the app's menu, then ask again")
            }
        }
        val server = EngineMcpServer("secret", listOf(stopped))
        val reply = JSONObject(
            requireNotNull(
                server.handleLine(
                    JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "tools/call")
                        .put("token", "secret")
                        .put("params", JSONObject().put("name", "execute_code")).toString(),
                ),
            ),
        )

        val error = reply.optJSONObject("error")
        assertTrue("reply had no error object: " + reply, error != null)
        assertTrue("the error names the state: " + error, error!!.getInt("code") == -32002)
        assertTrue("message: " + error, error.getString("message").contains("is not started"))
    }
}
