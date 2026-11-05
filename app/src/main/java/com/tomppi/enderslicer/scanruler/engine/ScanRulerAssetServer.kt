package com.tomppi.enderslicer.scanruler.engine

import android.content.Context
import android.content.res.AssetManager
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * The loopback origin the embedded page is served from.
 *
 * The full 1.14 screen keeps using WebViewAssetLoader at https://appassets.androidplatform.net.
 * The embedded engine cannot: the page treats localhost/127.0.0.1 as "on this computer", skips
 * the http hello fetch that mixed-content blocking would kill, and pairs by #agent=... with the
 * WebSocket server. So this serves the same vendored tree (assets/scanruler) from
 * http://127.0.0.1:<port>/assets/scanruler/... with the MIME types the page needs
 * (application/wasm for .wasm especially), plus a same-origin bootstrap page that writes the
 * agent-link/localStorage prefs and redirects to index.html with the pairing fragment. That
 * page carries the pairing secret, so a [bootstrapSecret] serves it only to requests that
 * carry the same secret as a query parameter.
 *
 * Loopback only, GET only, no directory access beyond the pinned asset tree. The asset bytes are
 * still verified by scripts/verify-scanruler-assets.py; this server must never edit them.
 */
class ScanRulerAssetServer(
    private val assetBytes: (String) -> ByteArray?,
    private val bootstrapHtml: () -> String,
    private val preferredPort: Int = DEFAULT_PORT,
    private val log: (String) -> Unit = {},
    /** When set, the bootstrap page answers only requests carrying ?secret=<value>; null keeps it open. */
    private val bootstrapSecret: String? = null,
) : Closeable {

    companion object {
        const val DEFAULT_PORT = 9881
        const val PAGE_PREFIX = "/assets/scanruler/"
        const val BOOTSTRAP_PATH = "/__scanruler_bootstrap"
        const val BOOTSTRAP_SECRET_PARAM = "secret"
        const val HEALTH_PATH = "/__scanruler_health"
    }

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    private val threads = AtomicInteger()

    val boundPort: Int get() = server?.localPort ?: 0
    val pageOrigin: String get() = "http://127.0.0.1:" + boundPort

    @Synchronized
    fun start() {
        if (running) return
        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), preferredPort), 16)
            }
        } catch (_: Throwable) {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 16)
            }
        }
        server = socket
        running = true
        thread(name = "scanruler-assets-accept", isDaemon = true) {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Throwable) {
                    break
                }
                thread(name = "scanruler-assets-" + threads.incrementAndGet(), isDaemon = true) {
                    serve(client)
                }
            }
        }
        log("ScanRuler page origin on http://127.0.0.1:" + socket.localPort)
    }

    override fun close() {
        running = false
        runCatching { server?.close() }
        server = null
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = 15_000
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                writeResponse(output, 400, "Bad Request", "text/plain", "bad request".toByteArray())
                return
            }
            val method = parts[0].uppercase()
            val target = parts[1]
            // Drain headers (not used, but the request must be read before answering).
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
            }
            if (method != "GET") {
                writeResponse(output, 405, "Method Not Allowed", "text/plain", "GET only".toByteArray())
                return
            }
            val path = try {
                decodePath(requestPath(target))
            } catch (error: IllegalArgumentException) {
                writeResponse(output, 400, "Bad Request", "text/plain", "bad request".toByteArray())
                return
            }
            when {
                path == BOOTSTRAP_PATH -> {
                    if (bootstrapAllowed(target.substringAfter('?', ""))) {
                        writeResponse(
                            output, 200, "OK", "text/html; charset=utf-8", bootstrapHtml().toByteArray(StandardCharsets.UTF_8),
                        )
                    } else {
                        writeResponse(output, 404, "Not Found", "text/plain", "not found".toByteArray())
                    }
                }
                path == HEALTH_PATH -> writeResponse(
                    output, 200, "OK", "application/json", ("{\"ok\":true}").toByteArray(StandardCharsets.UTF_8),
                )
                path.startsWith(PAGE_PREFIX) -> serveAsset(path.removePrefix(PAGE_PREFIX), output)
                else -> writeResponse(output, 404, "Not Found", "text/plain", "not found".toByteArray())
            }
        } catch (_: Throwable) {
            // A closed WebView aborts requests; nothing useful to report for those.
        } finally {
            runCatching { socket.close() }
        }
    }

    /** A request line may name its target in absolute-form; only its path is routed. */
    private fun requestPath(target: String): String {
        val scheme = target.indexOf("://")
        if (scheme < 0) return target.substringBefore('?')
        val slash = target.indexOf('/', scheme + 3)
        return if (slash < 0) "/" else target.substring(slash).substringBefore('?')
    }

    /** Percent-decoding as a URI path sees it: '+' is a literal plus, a malformed escape is refused. */
    private fun decodePath(rawPath: String): String {
        val bytes = ByteArrayOutputStream()
        var index = 0
        while (index < rawPath.length) {
            if (rawPath[index] == '%') {
                if (index + 2 >= rawPath.length) throw IllegalArgumentException("incomplete percent escape")
                val high = Character.digit(rawPath[index + 1], 16)
                val low = Character.digit(rawPath[index + 2], 16)
                if (high < 0 || low < 0) throw IllegalArgumentException("malformed percent escape")
                bytes.write((high shl 4) or low)
                index += 3
            } else {
                val next = rawPath.indexOf('%', index).let { if (it < 0) rawPath.length else it }
                bytes.write(rawPath.substring(index, next).toByteArray(StandardCharsets.UTF_8))
                index = next
            }
        }
        return String(bytes.toByteArray(), StandardCharsets.UTF_8)
    }

    /** The bootstrap page carries the pairing secret, so it is served only to its holder. */
    private fun bootstrapAllowed(query: String): Boolean {
        val secret = bootstrapSecret ?: return true
        return query.split('&').any {
            it.substringBefore('=') == BOOTSTRAP_SECRET_PARAM && it.substringAfter('=', "") == secret
        }
    }

    private fun serveAsset(relative: String, output: OutputStream) {
        val cleaned = relative.trimStart('/')
        if (cleaned.isEmpty() || cleaned.split('/').any { it == ".." || it.isEmpty() }) {
            writeResponse(output, 404, "Not Found", "text/plain", "not found".toByteArray())
            return
        }
        val assetPath = "scanruler/" + cleaned
        val bytes = assetBytes(assetPath)
        if (bytes == null) {
            writeResponse(output, 404, "Not Found", "text/plain", "not found".toByteArray())
            return
        }
        writeResponse(output, 200, "OK", mimeType(cleaned), bytes)
    }

    private fun mimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json", "map" -> "application/json"
        "wasm" -> "application/wasm"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "ico" -> "image/x-icon"
        "txt" -> "text/plain; charset=utf-8"
        "3mf" -> "application/octet-stream"
        else -> "application/octet-stream"
    }

    private fun writeResponse(
        output: OutputStream,
        status: Int,
        reason: String,
        contentType: String,
        body: ByteArray,
    ) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(StandardCharsets.UTF_8))
        output.write(body)
        output.flush()
    }

    private fun readLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 16 * 1024) {
            val byte = input.read()
            if (byte < 0) return if (bytes.size() == 0) null else bytes.toString(StandardCharsets.UTF_8.name())
            if (byte == '\n'.code) {
                val text = bytes.toString(StandardCharsets.UTF_8.name())
                return text.removeSuffix("\r")
            }
            bytes.write(byte)
        }
        return null
    }
}

/** Build the asset reader for the APK's assets/scanruler tree. */
fun scanRulerAssetReader(context: Context): (String) -> ByteArray? {
    val assets: AssetManager = context.assets
    return { path ->
        try {
            assets.open(path).use { it.readBytes() }
        } catch (_: Throwable) {
            null
        }
    }
}
