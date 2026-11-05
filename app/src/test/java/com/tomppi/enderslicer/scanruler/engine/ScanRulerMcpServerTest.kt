package com.tomppi.enderslicer.scanruler.engine

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ScanRulerMcpServerTest {

    private lateinit var link: ScanRulerPageLink
    private lateinit var page: FakePage
    private lateinit var server: ScanRulerMcpServer
    private val token = "mcp-token"

    @Before
    fun setUp() {
        link = ScanRulerPageLink(token = "page-token", expectedOrigins = { emptySet() }, port = 0)
        link.start()
        page = FakePage("page-token").connect(link.boundPort)
        page.sendJson(page.hello())
        page.awaitText() // welcome
        assertTrue(link.awaitPage(2_000))
        server = ScanRulerMcpServer(
            token = token,
            pageLink = link,
            status = {
                ScanRulerEmbeddedState(
                    phase = ScanRulerPhase.READY,
                    pagePort = 9881,
                    websocketPort = link.boundPort,
                    paired = true,
                    toolsReady = true,
                    toolCount = link.catalog.size,
                )
            },
            port = 0,
        )
    }

    @After
    fun tearDown() {
        server.close()
        page.close()
        link.close()
    }

    private fun request(method: String, params: JSONObject = JSONObject(), id: Int = 1): JSONObject =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params).put("token", token)

    private fun call(line: JSONObject, notify: (JSONObject) -> Unit = {}): JSONObject =
        JSONObject(server.handleLine(line.toString(), notify)!!)

    @Test
    fun initializeNamesTheScanRulerServer() {
        val answer = call(request("initialize"))
        val info = answer.getJSONObject("result").getJSONObject("serverInfo")
        assertEquals("scanruler", info.getString("name"))
        assertEquals("2024-11-05", answer.getJSONObject("result").getString("protocolVersion"))
    }

    @Test
    fun aMissingOrWrongTokenIsRefused() {
        val missing = JSONObject(server.handleLine(JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "initialize").toString())!!)
        assertEquals(-32001, missing.getJSONObject("error").getInt("code"))
        val wrong = JSONObject()
            .put("jsonrpc", "2.0").put("id", 2).put("method", "initialize").put("token", "not-the-token")
        assertEquals(-32001, JSONObject(server.handleLine(wrong.toString())!!).getJSONObject("error").getInt("code"))
    }

    @Test
    fun toolsListMapsThePageCatalogueAndAddsTheHelpers() {
        val tools = call(request("tools/list")).getJSONObject("result").getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertTrue(names.contains("scan_open"))
        assertTrue(names.contains("workspace_set"))
        assertTrue(names.contains("reverse_note_list"))
        assertTrue(names.contains("scanruler_status"))
        assertTrue(names.contains("scanruler_wait"))
        val scanOpen = tools.let { array ->
            (0 until array.length()).map { array.getJSONObject(it) }.first { it.getString("name") == "scan_open" }
        }
        assertEquals("object", scanOpen.getJSONObject("inputSchema").getString("type"))
    }

    @Test
    fun statusReportsThePageStateWithoutCallingThePage() {
        val result = call(request("tools/call", JSONObject().put("name", "scanruler_status")))
            .getJSONObject("result")
        val text = result.getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(text.contains("\"phase\":\"ready\""))
        assertTrue(text.contains("\"paired\":true"))
    }

    @Test
    fun toolsCallForwardsTheCommandAndReturnsTheText() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<JSONObject> {
                call(
                    request(
                        "tools/call",
                        JSONObject().put("name", "workspace_set").put("arguments", JSONObject().put("workspace", "reverse")),
                    ),
                )
            }
            val request = JSONObject(page.awaitText())
            assertEquals("run", request.getString("method"))
            assertEquals("workspace.set", request.getString("name"))
            page.sendJson(JSONObject().put("id", request.getInt("id")).put("result", JSONObject().put("workspace", "reverse")))
            val answer = future.get(5, TimeUnit.SECONDS)
            val text = answer.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text")
            assertTrue(text.contains("\"workspace\":\"reverse\""))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun progressBecomesANotificationWhenTheCallerAskedForIt() {
        val notifications = Collections.synchronizedList(ArrayList<JSONObject>())
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<JSONObject> {
                call(
                    request(
                        "tools/call",
                        JSONObject().put("name", "workspace_set")
                            .put("arguments", JSONObject().put("workspace", "reverse"))
                            .put("progressToken", "token-1"),
                    ),
                ) { notifications += it }
            }
            val request = JSONObject(page.awaitText())
            page.sendJson(JSONObject().put("event", "progress").put("id", request.getInt("id")).put("text", "switching"))
            page.sendJson(JSONObject().put("id", request.getInt("id")).put("result", JSONObject()))
            future.get(5, TimeUnit.SECONDS)
            assertEquals(1, notifications.size)
            assertEquals("notifications/progress", notifications.first().getString("method"))
            assertEquals("token-1", notifications.first().getJSONObject("params").getString("progressToken"))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun anUnknownToolOrCommandIsAnInvalidParamsError() {
        val unknown = call(request("tools/call", JSONObject().put("name", "no_such_tool")))
        assertEquals(-32602, unknown.getJSONObject("error").getInt("code"))
    }

    @Test
    fun aFileResultWithoutAMimeTypeGetsTheOneFromItsExtension() {
        val captured = Collections.synchronizedList(ArrayList<List<String>>())
        val withSink = ScanRulerMcpServer(
            token = token,
            pageLink = link,
            status = { ScanRulerEmbeddedState(phase = ScanRulerPhase.READY, paired = true, toolsReady = true) },
            binarySink = { _, name, mimeType ->
                captured += listOf(name, mimeType)
                JSONObject().put("name", name).put("mimeType", mimeType).put("size", 3).put("uri", "content://x")
            },
            port = 0,
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<JSONObject> {
                JSONObject(withSink.handleLine(
                    request("tools/call", JSONObject().put("name", "scan_open")).toString(),
                )!!)
            }
            val asked = JSONObject(page.awaitText())
            assertEquals("scan.open", asked.getString("name"))
            page.sendJson(JSONObject()
                .put("id", asked.getInt("id"))
                .put("binary", "result.file.bytes")
                .put("result", JSONObject().put("file", JSONObject()
                    .put("name", "measurement.step")
                    .put("bytes", JSONObject().put("size", 3)))))
            page.sendBinary(byteArrayOf(1, 2, 3))
            val answer = future.get(5, TimeUnit.SECONDS)
            assertEquals("application/step", answer.getJSONObject("result").getJSONObject("structuredContent")
                .getJSONObject("file").getJSONObject("bytes").getString("mimeType"))
            assertEquals(listOf("measurement.step", "application/step"), captured.first())
        } finally {
            executor.shutdownNow()
            withSink.close()
        }
    }

    @Test
    fun aFileResultKeepsTheMimeTypeThePageNamed() {
        val captured = Collections.synchronizedList(ArrayList<List<String>>())
        val withSink = ScanRulerMcpServer(
            token = token,
            pageLink = link,
            status = { ScanRulerEmbeddedState(phase = ScanRulerPhase.READY, paired = true, toolsReady = true) },
            binarySink = { _, name, mimeType ->
                captured += listOf(name, mimeType)
                JSONObject().put("name", name).put("mimeType", mimeType).put("size", 3).put("uri", "content://x")
            },
            port = 0,
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<JSONObject> {
                JSONObject(withSink.handleLine(
                    request("tools/call", JSONObject().put("name", "scan_open")).toString(),
                )!!)
            }
            val asked = JSONObject(page.awaitText())
            page.sendJson(JSONObject()
                .put("id", asked.getInt("id"))
                .put("binary", "result.file.bytes")
                .put("result", JSONObject().put("file", JSONObject()
                    .put("name", "measurement.step")
                    .put("mimeType", "model/step")
                    .put("bytes", JSONObject().put("size", 3)))))
            page.sendBinary(byteArrayOf(1, 2, 3))
            future.get(5, TimeUnit.SECONDS)
            assertEquals(listOf("measurement.step", "model/step"), captured.first())
        } finally {
            executor.shutdownNow()
            withSink.close()
        }
    }

    @Test
    fun aPageRefusalIsAnEngineUnavailableError() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<JSONObject> {
                call(request("tools/call", JSONObject().put("name", "scan_open")))
            }
            val request = JSONObject(page.awaitText())
            page.sendJson(JSONObject().put("id", request.getInt("id")).put(
                "error",
                JSONObject().put("code", "refused").put("message", "measurements held"),
            ))
            val answer = future.get(5, TimeUnit.SECONDS)
            assertEquals(-32002, answer.getJSONObject("error").getInt("code"))
            assertTrue(answer.getJSONObject("error").getString("message").contains("measurements held"))
        } finally {
            executor.shutdownNow()
        }
    }
}
