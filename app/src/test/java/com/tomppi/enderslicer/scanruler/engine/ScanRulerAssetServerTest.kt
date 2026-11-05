package com.tomppi.enderslicer.scanruler.engine

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets

class ScanRulerAssetServerTest {

    private lateinit var server: ScanRulerAssetServer
    private val files = mapOf(
        "scanruler/index.html" to "<html>page</html>".toByteArray(),
        "scanruler/assets/app.js" to "console.log(1)".toByteArray(),
        "scanruler/assets/app.wasm" to byteArrayOf(0, 0x61, 0x73, 0x6d),
        "scanruler/assets/font.woff2" to byteArrayOf(1, 2, 3),
        "scanruler/plus+name.txt" to "plus".toByteArray(),
    )

    @Before
    fun setUp() {
        server = ScanRulerAssetServer(
            assetBytes = { path -> files[path] },
            bootstrapHtml = { "<html>bootstrap</html>" },
            preferredPort = 0,
        )
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun get(path: String, on: ScanRulerAssetServer = server): Triple<Int, String?, ByteArray> {
        val connection = URL("http://127.0.0.1:" + on.boundPort + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 3_000
        connection.readTimeout = 3_000
        connection.requestMethod = "GET"
        return try {
            val status = connection.responseCode
            val type = connection.contentType
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            tripleOf(status, type, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }

    private fun tripleOf(a: Int, b: String?, c: ByteArray) = Triple(a, b, c)

    /** A request line sent as it stands - for the targets a URL client will not produce. */
    private fun rawGet(target: String, on: ScanRulerAssetServer = server): Pair<Int, String> {
        val socket = Socket("127.0.0.1", on.boundPort)
        socket.soTimeout = 3_000
        val text = try {
            val out = socket.getOutputStream()
            out.write(
                ("GET " + target + " HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                    .toByteArray(StandardCharsets.UTF_8),
            )
            out.flush()
            socket.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
        } finally {
            socket.close()
        }
        val status = text.substringAfter("HTTP/1.1 ").substringBefore(' ').toInt()
        return status to text.substringAfter("\r\n\r\n", "")
    }

    @Test
    fun theBuiltPageIsServedFromTheLoopbackOrigin() {
        val (status, type, body) = get("/assets/scanruler/index.html")
        assertEquals(200, status)
        assertEquals("text/html; charset=utf-8", type)
        assertEquals("<html>page</html>", String(body))
    }

    @Test
    fun wasmIsServedAsApplicationWasm() {
        val (status, type, body) = get("/assets/scanruler/assets/app.wasm")
        assertEquals(200, status)
        assertEquals("application/wasm", type)
        assertArrayEquals(byteArrayOf(0, 0x61, 0x73, 0x6d), body)
    }

    @Test
    fun scriptsAndFontsGetTheirTypes() {
        assertEquals("text/javascript; charset=utf-8", get("/assets/scanruler/assets/app.js").second)
        assertEquals("font/woff2", get("/assets/scanruler/assets/font.woff2").second)
    }

    @Test
    fun aMissingAssetIs404() {
        assertEquals(404, get("/assets/scanruler/nope.js").first)
    }

    @Test
    fun aTraversalPathIs404() {
        assertEquals(404, get("/assets/scanruler/%2e%2e/secret").first)
    }

    @Test
    fun theBootstrapPageIsServedAtItsPath() {
        val (status, type, body) = get("/__scanruler_bootstrap")
        assertEquals(200, status)
        assertTrue(type!!.startsWith("text/html"))
        assertTrue(String(body).contains("bootstrap"))
    }

    @Test
    fun healthSaysOk() {
        assertEquals(200, get("/__scanruler_health").first)
    }

    @Test
    fun anAbsoluteFormRequestTargetServesTheAsset() {
        val (status, body) = rawGet("http://127.0.0.1:" + server.boundPort + "/assets/scanruler/index.html")
        assertEquals(200, status)
        assertEquals("<html>page</html>", body)
    }

    @Test
    fun aMalformedPercentEscapeIsA400NotADeadConnection() {
        assertEquals(400, rawGet("/assets/scanruler/%zz").first)
        assertEquals(400, rawGet("/assets/scanruler/incomplete%").first)
    }

    @Test
    fun aPlusInThePathStaysAPlus() {
        val (status, body) = rawGet("/assets/scanruler/plus+name.txt")
        assertEquals(200, status)
        assertEquals("plus", body)
    }

    @Test
    fun theBootstrapPageIsServedOnlyToTheSecretHolder() {
        val guarded = ScanRulerAssetServer(
            assetBytes = { path -> files[path] },
            bootstrapHtml = { "<html>bootstrap</html>" },
            preferredPort = 0,
            bootstrapSecret = "s3cret",
        )
        guarded.start()
        try {
            assertEquals(404, rawGet("/__scanruler_bootstrap", guarded).first)
            assertEquals(404, rawGet("/__scanruler_bootstrap?secret=wrong", guarded).first)
            val (status, body) = rawGet("/__scanruler_bootstrap?secret=s3cret", guarded)
            assertEquals(200, status)
            assertTrue(body.contains("bootstrap"))
        } finally {
            guarded.close()
        }
    }
}
