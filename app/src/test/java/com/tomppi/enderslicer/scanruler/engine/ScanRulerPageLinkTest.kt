package com.tomppi.enderslicer.scanruler.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.EOFException
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ScanRulerPageLinkTest {

    private var link: ScanRulerPageLink? = null
    private var page: FakePage? = null
    private val extras = ArrayList<FakePage>()

    @After
    fun tearDown() {
        extras.forEach { it.close() }
        page?.close()
        link?.close()
    }

    private fun startLink(
        token: String = "page-token",
        helloTimeoutMillis: Long = 5_000,
        pingIntervalMillis: Long = 30_000,
        maxMessageBytes: Int = ScanRulerPageLink.MAX_PAYLOAD,
    ): ScanRulerPageLink {
        val started = ScanRulerPageLink(
            token = token,
            expectedOrigins = { emptySet() },
            port = 0,
            helloTimeoutMillis = helloTimeoutMillis,
            pingIntervalMillis = pingIntervalMillis,
            maxMessageBytes = maxMessageBytes,
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

    @Test
    fun aFragmentedHelloIsReassembledWithinTheCap() {
        val started = startLink(maxMessageBytes = 4_096)
        val fake = FakePage("page-token").connect(started.boundPort)
        page = fake
        val hello = fake.hello().toString()
        fake.socket.sendText(hello.substring(0, hello.length / 2), false).get(5, TimeUnit.SECONDS)
        fake.socket.sendText(hello.substring(hello.length / 2), true).get(5, TimeUnit.SECONDS)
        assertEquals("welcome", JSONObject(fake.awaitText()).keys().next())
        assertTrue(started.awaitPage(2_000))
    }

    @Test
    fun aReassembledMessagePastTheCapFailsTheConnection() {
        val started = startLink(maxMessageBytes = 64)
        val fake = FakePage("page-token").connect(started.boundPort)
        page = fake
        runCatching {
            fake.socket.sendText("x".repeat(50), false).get(5, TimeUnit.SECONDS)
            fake.socket.sendText("y".repeat(50), true).get(5, TimeUnit.SECONDS)
        }
        fake.awaitGone(3_000)
        assertFalse(started.isPaired)
    }

    @Test
    fun aSilentConnectionIsRefusedEvenWhileAPageIsPaired() {
        val started = startLink(helloTimeoutMillis = 1_000)
        pair(started)
        val second = FakePage("page-token").connect(started.boundPort)
        extras += second
        assertEquals(ScanRulerPageLink.REFUSED_NO_HELLO, second.awaitClosed(3_000).code)
        assertTrue(started.isPaired)
    }

    @Test
    fun onlyOneOfTheSimultaneousHellosPairs() {
        val started = startLink()
        val clients = (1..4).map { FakePage("page-token").connect(started.boundPort) }
        extras += clients
        val barrier = CyclicBarrier(clients.size)
        val welcomed = AtomicInteger()
        val problems = Collections.synchronizedList(ArrayList<Throwable>())
        val threads = clients.map { client ->
            thread {
                try {
                    barrier.await()
                    client.sendJson(client.hello())
                    if (JSONObject(client.awaitText(5_000)).has("welcome")) welcomed.incrementAndGet()
                } catch (error: Throwable) {
                    problems += error
                }
            }
        }
        threads.forEach { it.join(10_000) }
        assertTrue("every hello was answered: " + problems, problems.isEmpty())
        assertEquals("exactly one page pairs", 1, welcomed.get())
        assertTrue(started.awaitPage(2_000))
    }

    @Test
    fun aPairedConnectionThatStaysSilentAfterAPingDiesAndFreesTheSlot() {
        val started = startLink(pingIntervalMillis = 250)
        val squatter = SilentPage(started.boundPort, "page-token")
        try {
            squatter.sayHello()
            val paired = System.currentTimeMillis() + 3_000
            while (!started.isPaired && System.currentTimeMillis() < paired) Thread.sleep(10)
            assertTrue("the silent client paired", started.isPaired)
            assertTrue("a connection that never answers a ping is closed", squatter.awaitDeath(5_000))
            val freed = System.currentTimeMillis() + 3_000
            while (started.isPaired && System.currentTimeMillis() < freed) Thread.sleep(10)
            assertFalse("the pairing slot is free again", started.isPaired)
            pair(started).close()
        } finally {
            squatter.close()
        }
    }

    @Test
    fun aPairedPageThatAnswersPingsKeepsTheSlot() {
        val started = startLink(pingIntervalMillis = 200)
        val fake = pair(started)
        try {
            Thread.sleep(1_000)
            assertTrue("the page answers pings and stays paired", started.isPaired)
        } finally {
            fake.close()
        }
    }

    @Test
    fun aCloseReasonIsCappedAt123Utf8BytesOnACharacterBoundary() {
        assertArrayEquals(
            "hi".toByteArray(StandardCharsets.UTF_8),
            ScanRulerPageLink.closeReasonBytes("hi"),
        )
        for (reason in listOf("é".repeat(200), "😀".repeat(200), "a".repeat(200))) {
            val body = ScanRulerPageLink.closeReasonBytes(reason)
            assertTrue("capped at 123 bytes, was " + body.size, body.size <= 123)
            val text = String(body, StandardCharsets.UTF_8)
            assertFalse("never a half character", text.contains("\uFFFD"))
            assertEquals(reason.substring(0, text.length), text)
        }
    }

    /** A WebSocket client that pairs and then never answers a ping - the squatter case. */
    private class SilentPage(port: Int, private val token: String) {
        private val socket = Socket("127.0.0.1", port)

        init {
            socket.soTimeout = 3_000
            val out = socket.getOutputStream()
            out.write((
                "GET / HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nUpgrade: websocket\r\n" +
                    "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n"
                ).toByteArray(StandardCharsets.UTF_8))
            out.flush()
            val input = socket.getInputStream()
            val head = StringBuilder()
            while (!head.toString().endsWith("\r\n\r\n")) {
                val byte = input.read()
                if (byte < 0) throw EOFException("the handshake ended early")
                head.append(byte.toChar())
            }
            check(head.toString().startsWith("HTTP/1.1 101")) { "no upgrade: " + head }
        }

        fun sayHello() {
            val text = JSONObject()
                .put("hello", JSONObject().put("token", token).put("commands", JSONArray()))
                .toString()
            val payload = text.toByteArray(StandardCharsets.UTF_8)
            val mask = byteArrayOf(1, 2, 3, 4)
            val out = socket.getOutputStream()
            out.write(0x81)
            out.write(0x80 or payload.size)
            out.write(mask)
            for (index in payload.indices) out.write(payload[index].toInt() xor mask[index and 3].toInt())
            out.flush()
        }

        /** True when the server ended the connection - close frame or dropped socket - in time. */
        fun awaitDeath(timeoutMillis: Long): Boolean {
            socket.soTimeout = timeoutMillis.toInt()
            return try {
                drainUntilClosed()
                true
            } catch (_: SocketTimeoutException) {
                false
            }
        }

        private fun drainUntilClosed() {
            val input = socket.getInputStream()
            while (true) {
                val first = input.read()
                if (first < 0 || first and 0x0f == 0x8) return
                val second = input.read()
                if (second < 0) return
                var length = (second and 0x7f).toLong()
                if (length == 126L) length = ((input.read() shl 8) or input.read()).toLong()
                if (length == 127L) {
                    length = 0
                    repeat(8) { length = (length shl 8) or input.read().toLong() }
                }
                var remaining = length
                while (remaining > 0) {
                    val count = input.read(ByteArray(remaining.coerceAtMost(4_096).toInt()))
                    if (count < 0) return
                    remaining -= count
                }
            }
        }

        fun close() {
            runCatching { socket.close() }
        }
    }
}
