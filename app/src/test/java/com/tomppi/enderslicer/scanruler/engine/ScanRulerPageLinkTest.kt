package com.tomppi.enderslicer.scanruler.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ScanRulerPageLinkTest {

    private var link: ScanRulerPageLink? = null
    private var page: FakePage? = null

    @After
    fun tearDown() {
        page?.close()
        link?.close()
    }

    private fun startLink(token: String = "page-token", helloTimeoutMillis: Long = 5_000): ScanRulerPageLink {
        val started = ScanRulerPageLink(
            token = token,
            expectedOrigins = { emptySet() },
            port = 0,
            helloTimeoutMillis = helloTimeoutMillis,
        )
        started.start()
        link = started
        return started
    }

    private fun pair(started: ScanRulerPageLink): FakePage {
        val fake = FakePage("page-token").connect(started.boundPort)
        fake.sendJson(fake.hello())
        page = fake
        assertEquals("welcome", JSONObject(fake.awaitText()).keys().next())
        assertTrue(started.awaitPage(2_000))
        return fake
    }

    @Test
    fun helloIsWelcomedAndTheCommandCatalogueIsExposed() {
        val started = startLink()
        val fake = pair(started)
        assertEquals(3, started.catalog.size)
        assertEquals(listOf("reverse.note.list", "scan.open", "workspace.set"), started.catalog.map { it.name }.sorted())
        assertEquals("scan_open", started.catalog.first { it.name == "scan.open" }.toolName)
        assertEquals("reverse_note_list", started.catalog.first { it.name == "reverse.note.list" }.toolName)
        // The tool schema is the page's own schema.
        val scanTool = started.mcpTools().let { tools ->
            (0 until tools.length()).map { tools.getJSONObject(it) }.first { it.getString("name") == "scan_open" }
        }
        assertEquals("object", scanTool.getJSONObject("inputSchema").getString("type"))
        fake.close()
    }

    @Test
    fun runForwardsProgressAndReturnsThePageResult() {
        val started = startLink()
        val fake = pair(started)
        val progress = Collections.synchronizedList(ArrayList<String>())
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<JSONObject> {
                started.run("workspace.set", JSONObject().put("workspace", "reverse"), timeoutMillis = 5_000) { progress += it }
            }
            val request = JSONObject(fake.awaitText())
            assertEquals(1, request.getInt("id"))
            assertEquals("run", request.getString("method"))
            assertEquals("workspace.set", request.getString("name"))
            assertEquals("reverse", request.getJSONObject("input").getString("workspace"))
            fake.sendJson(JSONObject()
                .put("event", "progress")
                .put("id", request.getInt("id"))
                .put("text", "Showing reverse"))
            fake.sendJson(JSONObject()
                .put("id", request.getInt("id"))
                .put("result", JSONObject().put("workspace", "reverse")))
            val result = future.get(5, TimeUnit.SECONDS)
            assertEquals("reverse", result.getString("workspace"))
            assertEquals(listOf("Showing reverse"), progress)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun binaryInputAndBinaryResultTravelInSideFrames() {
        val started = startLink()
        val fake = pair(started)
        val mesh = ByteArray(32) { (it + 1).toByte() }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<JSONObject> {
                started.run(
                    "scan.open",
                    JSONObject().put("bytes", mesh).put("name", "plate.stl"),
                    binaryPath = "input.bytes",
                    timeoutMillis = 5_000,
                )
            }
            val request = JSONObject(fake.awaitText())
            assertEquals("input.bytes", request.getString("binary"))
            assertEquals(32, request.getJSONObject("input").getJSONObject("bytes").getInt("size"))
            assertArrayEquals(mesh, fake.awaitBinary())
            val file = ByteArray(3) { 9 }
            fake.sendJson(JSONObject()
                .put("id", request.getInt("id"))
                .put("binary", "result.file.bytes")
                .put("result", JSONObject().put(
                    "file",
                    JSONObject().put("name", "measurement.step").put("mimeType", "application/step")
                        .put("bytes", JSONObject().put("size", file.size)),
                )))
            fake.sendBinary(file)
            val result = future.get(5, TimeUnit.SECONDS)
            assertArrayEquals(file, result.getJSONObject("file").get("bytes") as ByteArray)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun aPageErrorBecomesACommandExceptionWithThePageCode() {
        val started = startLink()
        val fake = pair(started)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<Throwable> {
                try {
                    started.run("scan.open", timeoutMillis = 5_000)
                    null
                } catch (error: Throwable) {
                    error
                }
            }
            val request = JSONObject(fake.awaitText())
            fake.sendJson(JSONObject()
                .put("id", request.getInt("id"))
                .put("error", JSONObject().put("code", "refused").put("message", "measurements held")))
            val error = future.get(5, TimeUnit.SECONDS)
            assertTrue(error is ScanRulerCommandException)
            assertEquals("refused", (error as ScanRulerCommandException).code)
            assertTrue(error.message!!.contains("measurements held"))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun aWrongTokenIsRefusedAndClosed() {
        val started = startLink(token = "the-right-token")
        val fake = FakePage("the-wrong-token").connect(started.boundPort)
        page = fake
        fake.sendJson(fake.hello(token = "the-wrong-token"))
        val refused = JSONObject(fake.awaitText())
        assertTrue(refused.has("refused"))
        assertEquals(ScanRulerPageLink.REFUSED_BAD_TOKEN, fake.awaitClosed().code)
        assertFalse(started.isPaired)
    }

    @Test
    fun aSecondPageIsRefusedWhileOneIsPaired() {
        val started = startLink()
        pair(started)
        val second = FakePage("page-token").connect(started.boundPort)
        try {
            second.sendJson(second.hello())
            val refused = JSONObject(second.awaitText())
            assertTrue(refused.has("refused"))
            assertEquals(ScanRulerPageLink.REFUSED_TAKEN, second.awaitClosed().code)
        } finally {
            second.close()
        }
    }

    @Test
    fun aMissingHelloIsRefusedByTheHelloTimeout() {
        val started = startLink(helloTimeoutMillis = 200)
        val fake = FakePage("page-token").connect(started.boundPort)
        page = fake
        assertEquals(ScanRulerPageLink.REFUSED_NO_HELLO, fake.awaitClosed(3_000).code)
    }

    @Test
    fun aRunThatIsNeverAnsweredTimesOut() {
        val started = startLink()
        pair(started)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<Throwable> {
                try {
                    started.run("workspace.set", timeoutMillis = 250)
                    null
                } catch (error: Throwable) {
                    error
                }
            }
            fakePageText()
            val error = future.get(5, TimeUnit.SECONDS)
            assertTrue(error is ScanRulerCommandException)
            assertEquals("timeout", (error as ScanRulerCommandException).code)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun fakePageText() {
        val current = page ?: error("no page")
        current.awaitText()
    }

    @Test
    fun listReturnsThePagesCommandCatalogue() {
        val started = startLink()
        val fake = pair(started)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val future = executor.submit<JSONArray> { started.requestList(5_000) }
            val request = JSONObject(fake.awaitText())
            assertEquals("list", request.getString("method"))
            fake.sendJson(JSONObject().put("id", request.getInt("id")).put("result", FakePage.defaultCommands()))
            val commands = future.get(5, TimeUnit.SECONDS)
            assertEquals(3, commands.length())
            assertEquals("scan.open", commands.getJSONObject(1).getString("name"))
        } finally {
            executor.shutdownNow()
        }
    }
}
