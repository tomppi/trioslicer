package com.tomppi.enderslicer.harness

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shape of a prompt's content blocks.
 *
 * This is the difference between an image reaching the agent and the agent
 * being handed a message that merely mentions one. Uploading stages the bytes
 * against the session; only a `file` part naming the receipt puts them in the
 * message, and the app used to discard that receipt.
 */
class HarnessClientTest {

    @Test
    fun aPromptWithNoAttachmentIsJustTheText() {
        val content = HarnessClient.promptContent("build this", null)

        assertEquals(1, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("build this", content.getJSONObject(0).getString("text"))
    }

    @Test
    fun anAttachmentIsNamedBeforeTheText() {
        val content = HarnessClient.promptContent("build this", "receipt-1")

        assertEquals(2, content.length())
        assertEquals("file", content.getJSONObject(0).getString("type"))
        assertEquals("receipt-1", content.getJSONObject(0).getString("receiptId"))
        assertEquals("text", content.getJSONObject(1).getString("type"))
        assertEquals("build this", content.getJSONObject(1).getString("text"))
    }

    @Test
    fun aBlankReceiptIsNotAnAttachment() {
        // An upload that yielded nothing usable must not leave an empty receipt
        // in the message: the harness resolves receipts by id and would refuse
        // the whole prompt.
        assertEquals(1, HarnessClient.promptContent("build this", "").length())
        assertEquals(1, HarnessClient.promptContent("build this", "   ").length())
    }

    @Test
    fun aResponseBodyPastTheLimitIsRefusedInsteadOfBuffered() {
        // A wrong host, a captive portal or a hostile server can answer forever:
        // the read timeout bounds idle time only, so the body needs its own cap.
        val error = runCatching {
            HarnessClient("http://127.0.0.1:1")
                .readAll(ByteArrayInputStream(ByteArray(17 * 1024 * 1024)))
        }.exceptionOrNull()

        assertTrue(error is HarnessException)
        assertEquals("http-response-too-large", (error as HarnessException).code)
        assertTrue(error.message.orEmpty().contains("16 MB limit"))
    }

    @Test
    fun aBodyWithinTheLimitIsReadWholeAndDecoded() {
        val text = "{\"ok\":true,\"note\":\"caf\u00e9\"}"
        val read = HarnessClient("http://127.0.0.1:1").readAll(text.toByteArray(Charsets.UTF_8).inputStream())

        assertEquals(text, read)
    }

    @Test
    fun theHarnessCanNameTheWorkspaceNewSessionsBelongIn() {
        // A freshly installed app cannot know a path on the harness host, so the
        // launcher publishes one beside auth.json and a first run needs only the URL.
        assertEquals(
            "/root/src/enderslicercura",
            HarnessClient.workspaceFromDefaults(
                """{"version":1,"issuedAt":"2026-09-19T06:50:20Z","workspace":"/root/src/enderslicercura"}""",
            ),
        )
    }

    @Test
    fun aDefaultsDocumentWithoutAWorkspaceSuggestsNothing() {
        assertEquals("", HarnessClient.workspaceFromDefaults("""{"version":1}"""))
        assertEquals("", HarnessClient.workspaceFromDefaults("""{"workspace":"   "}"""))
    }

    @Test
    fun aDefaultsDocumentThatIsNotJsonSuggestsNothing() {
        // A server started without the launcher answers a 404 page here, and that must
        // leave the workspace the user typed in place rather than fail the connect.
        assertEquals("", HarnessClient.workspaceFromDefaults("<!doctype html><title>404</title>"))
    }

    @Test
    fun aPastedTokenIsExchangedForTheSessionCookie() {
        FakeHarness(token = "abc123").use { harness ->
            val client = HarnessClient(harness.origin, launchToken = "abc123")

            assertEquals(FakeHarness.COOKIE, client.authenticate())
            // The cookie is what every later call carries: without the exchange
            // the API answers 401 and this call throws.
            assertEquals(0, client.listSessions().optJSONArray("items")?.length() ?: -1)
            assertEquals(1, harness.tokenExchanges)
        }
    }

    @Test
    fun aStoredCookieIsReusedWithoutSpendingTheToken() {
        // The cookie outlives a harness restart and the token does not, which is
        // the whole reason keeping it is worth a Keystore entry.
        FakeHarness(token = "abc123").use { harness ->
            val client = HarnessClient(harness.origin, initialCookie = FakeHarness.COOKIE)

            assertEquals(FakeHarness.COOKIE, client.authenticate())
            assertEquals(0, harness.tokenExchanges)
        }
    }

    @Test
    fun aCookieTheHarnessNoLongerAcceptsIsReplacedByTheToken() {
        FakeHarness(token = "abc123").use { harness ->
            val client = HarnessClient(
                harness.origin,
                launchToken = "abc123",
                initialCookie = "dsh-auth-127.0.0.1=expired",
            )

            assertEquals(FakeHarness.COOKIE, client.authenticate())
            assertEquals(1, harness.tokenExchanges)
        }
    }

    @Test
    fun aRotatedTokenSaysWhatToDoAboutIt() {
        FakeHarness(token = "current-token").use { harness ->
            val error = runCatching {
                HarnessClient(harness.origin, launchToken = "yesterdays-token").authenticate()
            }.exceptionOrNull()

            assertTrue(error is HarnessException)
            val refused = error as HarnessException
            assertEquals("auth", refused.code)
            assertTrue(refused.message.orEmpty().contains("new one every time"))
        }
    }

    @Test
    fun withNoTokenAtAllTheMessageSaysWhatToPaste() {
        val error = runCatching {
            HarnessClient("http://127.0.0.1:1").authenticate()
        }.exceptionOrNull()

        assertTrue(error is HarnessException)
        val refused = error as HarnessException
        assertEquals("auth", refused.code)
        assertTrue(refused.message.orEmpty().contains("?token="))
    }

    @Test
    fun aTransportFailureNeverQuotesTheToken() {
        // The token is the harness's entire credential and this message reaches a
        // status line: a connection error must not carry it there.
        val error = runCatching {
            HarnessClient("http://127.0.0.1:1", launchToken = "s3cret-token").authenticate()
        }.exceptionOrNull()

        assertTrue(error is HarnessException)
        assertFalse((error as HarnessException).message.orEmpty().contains("s3cret-token"))
    }

    @Test
    fun plainHttpToAnotherMachineNeedsAnExplicitAcceptance() {
        val refused = runCatching { HarnessClient("http://100.64.0.10:3080") }.exceptionOrNull()

        assertTrue(refused is HarnessException)
        assertEquals("insecure-transport", (refused as HarnessException).code)
        // Accepted, the same address constructs; nothing is contacted here.
        HarnessClient("http://100.64.0.10:3080", allowCleartext = true)
    }

    @Test
    fun loopbackAndHttpsAreNotUnencryptedRemoteAddresses() {
        assertFalse(HarnessClient.isUnencryptedRemote("https://machine.tailnet.ts.net"))
        assertFalse(HarnessClient.isUnencryptedRemote("https://machine.tailnet.ts.net:3080"))
        assertFalse(HarnessClient.isUnencryptedRemote("http://127.0.0.1:3080"))
        assertFalse(HarnessClient.isUnencryptedRemote("http://127.0.0.5:3080"))
        assertFalse(HarnessClient.isUnencryptedRemote("http://localhost:3080"))
        assertFalse(HarnessClient.isUnencryptedRemote("http://[::1]:3080"))
        assertTrue(HarnessClient.isUnencryptedRemote("http://100.64.0.10:3080"))
        assertTrue(HarnessClient.isUnencryptedRemote("http://machine.tailnet.ts.net:3080"))
        // A bare host typed by hand is not a URL yet; it is refused later, by the
        // address check, not silently treated as this machine.
        assertFalse(HarnessClient.isUnencryptedRemote("100.64.0.10:3080"))
    }
}

/**
 * The harness's side of the exchange: one token, one cookie, no auth.json.
 *
 * Only the routes this client uses exist - the index exchange, the index page
 * behind the cookie, and one API call - because what is under test is which
 * credential the client presents, not the harness.
 */
private class FakeHarness(private val token: String) : java.io.Closeable {

    private val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)

    /** Token exchanges served, so a test can tell reuse from a fresh mint. */
    @Volatile
    var tokenExchanges = 0
        private set

    init {
        server.createContext("/") { exchange ->
            val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val path = exchange.requestURI.path
            val query = exchange.requestURI.rawQuery.orEmpty()
            val cookie = exchange.requestHeaders.getFirst("Cookie")
            when {
                path == "/" && query.split("&").any { it == "token=$token" } -> {
                    tokenExchanges++
                    exchange.responseHeaders.add("Set-Cookie", "$COOKIE; Path=/; Max-Age=2592000")
                    exchange.sendResponseHeaders(303, -1)
                }
                path == "/" && cookie == COOKIE -> respond(exchange, 200, "<!doctype html>")
                path == "/api/session/list" && cookie == COOKIE -> {
                    val rpcId = Regex("\"rpcId\":\"([^\"]+)\"").find(body)?.groupValues?.get(1).orEmpty()
                    respond(exchange, 200, "{\"rpcId\":\"$rpcId\",\"result\":{\"ok\":true,\"value\":{\"items\":[]}}}")
                }
                else -> respond(exchange, 401, "unauthorized")
            }
            exchange.close()
        }
        server.start()
    }

    val origin: String get() = "http://127.0.0.1:" + server.address.port

    override fun close() {
        server.stop(0)
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, status: Int, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    companion object {
        const val COOKIE = "dsh-auth-127.0.0.1=test-cookie"
    }
}
