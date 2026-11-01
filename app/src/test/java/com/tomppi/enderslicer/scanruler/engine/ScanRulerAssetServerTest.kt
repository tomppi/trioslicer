package com.tomppi.enderslicer.scanruler.engine

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class ScanRulerAssetServerTest {

    private lateinit var server: ScanRulerAssetServer
    private val files = mapOf(
        "scanruler/index.html" to "<html>page</html>".toByteArray(),
        "scanruler/assets/app.js" to "console.log(1)".toByteArray(),
        "scanruler/assets/app.wasm" to byteArrayOf(0, 0x61, 0x73, 0x6d),
        "scanruler/assets/font.woff2" to byteArrayOf(1, 2, 3),
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

    private fun get(path: String): Triple<Int, String?, ByteArray> {
        val connection = URL("http://127.0.0.1:" + server.boundPort + path).openConnection() as HttpURLConnection
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
}
