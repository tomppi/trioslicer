package com.tomppi.enderslicer.scanruler.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread

/**
 * The WebSocket server the embedded page connects to (upstream mcp/src/link.js + page
 * src/commands/bridge.ts).
 *
 * The page is served on 127.0.0.1 by [ScanRulerAssetServer], so its origin counts as "on this
 * computer": it skips the HTTP hello fetch, pairs with #agent=<port>:<token> and opens one
 * WebSocket. This class accepts exactly one page with that token, reads its hello (commands,
 * plugins, viewport), answers welcome, then runs commands and forwards progress. Files travel
 * as JSON + the next binary frame, as the page's framing.ts does; see [ScanRulerFrames].
 *
 * No external WebSocket dependency: the RFC 6455 handshake and frame rules the page needs are
 * implemented here, which keeps a library out of the APK and lets the link be tested on the JVM.
 */
internal class ScanRulerPageLink(
    private val token: String,
    private val expectedOrigins: () -> Set<String>,
    private val port: Int = DEFAULT_PORT,
    private val log: (String) -> Unit = {},
    private val serverVersion: String = "1.15.0",
    private val helloTimeoutMillis: Long = 15_000,
    private val commandTimeoutMillis: Long = 10 * 60_000,
    private val pingIntervalMillis: Long = 30_000,
    private val maxMessageBytes: Int = MAX_PAYLOAD,
) : Closeable {

    companion object {
        const val DEFAULT_PORT = 9880
        const val MAX_PAYLOAD = 512 * 1024 * 1024
        const val REFUSED_BAD_TOKEN = 4001
        const val REFUSED_ORIGIN = 4003
        const val REFUSED_NO_HELLO = 4008
        const val REFUSED_TAKEN = 4009
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /**
         * A close frame's reason is capped at 123 UTF-8 bytes (RFC 6455), and the cut never
         * splits a character in half.
         */
        internal fun closeReasonBytes(reason: String): ByteArray {
            val bytes = reason.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size <= 123) return bytes
            var end = 123
            var start = end - 1
            while (start > 0 && (bytes[start].toInt() and 0xc0) == 0x80) start--
            val lead = bytes[start].toInt() and 0xff
            val length = if (lead >= 0xf0) 4 else if (lead >= 0xe0) 3 else if (lead >= 0xc0) 2 else 1
            if (start + length > end) end = start
            return bytes.copyOf(end)
        }
    }

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var page: PageConnection? = null
    @Volatile private var pageHello: JSONObject? = null
    @Volatile private var commands: List<ScanRulerCommandCatalog.Command> = emptyList()

    /** Called when a page pairs (true) or goes away (false). */
    @Volatile var onPageChanged: ((Boolean) -> Unit)? = null

    /** Called with the parsed command catalogue whenever a page says hello. */
    @Volatile var onCommands: ((List<ScanRulerCommandCatalog.Command>) -> Unit)? = null

    private val pending = ConcurrentHashMap<Long, Pending>()
    private val nextId = AtomicLong(1)
    private val runLock = ReentrantLock(true)
    /** Pairing is one decision at a time: the single-page rule is not a check-then-act. */
    private val pairLock = Any()
    @Volatile private var pageReady = CountDownLatch(1)

    val boundPort: Int get() = server?.localPort ?: 0
    val isPaired: Boolean get() = page != null
    val catalog: List<ScanRulerCommandCatalog.Command> get() = commands
    val hello: JSONObject? get() = pageHello

    @Synchronized
    fun start() {
        if (running) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port.coerceAtLeast(0)))
        server = socket
        running = true
        thread(name = "scanruler-page-accept", isDaemon = true) {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Throwable) {
                    break
                }
                thread(name = "scanruler-page-conn", isDaemon = true) { serve(client) }
            }
        }
        log("ScanRuler page link listening on 127.0.0.1:" + socket.localPort)
    }

    /** Blocks until a page pairs, or the timeout passes. */
    fun awaitPage(timeoutMillis: Long): Boolean =
        pageReady.await(timeoutMillis, TimeUnit.MILLISECONDS)

    /** The page's command catalogue as MCP tools. */
    fun mcpTools(): JSONArray = ScanRulerCommandCatalog.mcpTools(commands)

    /**
     * Run one command on the page. Blocks until the page answers; page progress lines are
     * forwarded to [onProgress].
     */
    fun run(
        name: String,
        input: JSONObject = JSONObject(),
        binaryPath: String? = null,
        job: String? = null,
        deferred: Boolean = false,
        timeoutMillis: Long = commandTimeoutMillis,
        onProgress: (String) -> Unit = {},
    ): JSONObject {
        runLock.lock()
        try {
            val connection = page ?: throw ScanRulerLinkException("No ScanRuler page is connected.")
            val id = nextId.getAndIncrement()
            val message = JSONObject()
                .put("id", id)
                .put("method", "run")
                .put("name", name)
                .put("input", input)
            if (!job.isNullOrBlank()) message.put("job", job)
            if (deferred) message.put("deferred", true)
            val waiter = Pending(onProgress)
            pending[id] = waiter
            try {
                for (frame in ScanRulerFrames.encode(message, binaryPath)) connection.send(frame)
            } catch (error: Throwable) {
                pending.remove(id)
                throw ScanRulerLinkException("The ScanRuler page link broke: " + (error.message ?: error.javaClass.simpleName), error)
            }
            val answered = waiter.latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
            pending.remove(id)
            if (!answered) throw ScanRulerCommandException("timeout", "The ScanRuler page did not answer " + name + " within " + (timeoutMillis / 1000) + " s.")
            waiter.error?.let { throw ScanRulerCommandException(it.optString("code", "error"), it.optString("message", "The page refused " + name + ".")) }
            return waiter.result ?: JSONObject()
        } finally {
            runLock.unlock()
        }
    }

    /** Ask the page for its current command catalogue. */
    fun requestList(timeoutMillis: Long = 15_000): JSONArray {
        val connection = page ?: throw ScanRulerLinkException("No ScanRuler page is connected.")
        val id = nextId.getAndIncrement()
        val waiter = Pending {}
        pending[id] = waiter
        try {
            connection.send(ScanRulerFrames.Frame.Text(JSONObject().put("id", id).put("method", "list").toString()))
            if (!waiter.latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw ScanRulerCommandException("timeout", "The ScanRuler page did not answer its command list.")
            }
            waiter.error?.let { throw ScanRulerCommandException(it.optString("code", "error"), it.optString("message", "list refused")) }
            return waiter.resultArray ?: JSONArray()
        } finally {
            pending.remove(id)
        }
    }

    override fun close() {
        running = false
        runCatching { server?.close() }
        server = null
        page?.close()
        page = null
        pageHello = null
        commands = emptyList()
        pending.values.forEach { it.fail("The ScanRuler engine stopped.") }
        pending.clear()
        pageReady.countDown()
    }

    private class Pending(val onProgress: (String) -> Unit) {
        val latch = CountDownLatch(1)
        @Volatile var result: JSONObject? = null
        @Volatile var resultArray: JSONArray? = null
        @Volatile var error: JSONObject? = null

        fun fail(message: String) {
            if (error == null && result == null && resultArray == null) {
                error = JSONObject().put("code", "unavailable").put("message", message)
            }
            latch.countDown()
        }
    }

    private inner class PageConnection(private val socket: Socket) : Closeable {
        private val input = BufferedInputStream(socket.getInputStream())
        private val output = BufferedOutputStream(socket.getOutputStream())
        private val writeLock = Any()
        @Volatile private var closed = false
        @Volatile private var lastFrameAt = System.currentTimeMillis()

        fun send(frame: ScanRulerFrames.Frame) {
            synchronized(writeLock) {
                if (closed) throw IllegalStateException("page connection closed")
                when (frame) {
                    is ScanRulerFrames.Frame.Text -> writeFrame(0x1, frame.text.toByteArray(StandardCharsets.UTF_8))
                    is ScanRulerFrames.Frame.Binary -> writeFrame(0x2, frame.bytes)
                }
                output.flush()
            }
        }

        private fun writeFrame(opcode: Int, payload: ByteArray) {
            output.write(0x80 or opcode)
            when {
                payload.size < 126 -> output.write(payload.size)
                payload.size <= 0xffff -> {
                    output.write(126)
                    output.write(payload.size ushr 8)
                    output.write(payload.size and 0xff)
                }
                else -> {
                    output.write(127)
                    var size = payload.size.toLong()
                    for (shift in 56 downTo 0 step 8) {
                        output.write(((size ushr shift) and 0xff).toInt())
                    }
                }
            }
            output.write(payload)
        }

        fun readLoop() {
            val decoder = ScanRulerFrames.Decoder()
            var fragmentOpcode = 0
            val fragment = ByteArrayOutputStream()
            var fragmentBytes = 0
            // Reassembly is bounded like a frame: a message no phone could hold fails the link
            // instead of buffering without a cap.
            fun appendFragment(part: ByteArray) {
                fragmentBytes += part.size
                if (fragmentBytes > maxMessageBytes) throw IllegalStateException("WebSocket message too large")
                fragment.write(part)
            }
            while (running && !closed) {
                val first = input.read()
                if (first < 0) break
                val second = input.read()
                if (second < 0) break
                val fin = first and 0x80 != 0
                val opcode = first and 0x0f
                val masked = second and 0x80 != 0
                var length = (second and 0x7f).toLong()
                if (length == 126L) {
                    length = ((input.read() shl 8) or input.read()).toLong()
                } else if (length == 127L) {
                    length = 0L
                    repeat(8) { length = (length shl 8) or input.read().toLong() }
                }
                if (length < 0 || length > MAX_PAYLOAD) throw IllegalStateException("WebSocket frame too large")
                val mask = if (masked) ByteArray(4) { input.read().toByte() } else null
                val payload = ByteArray(length.toInt())
                var read = 0
                while (read < payload.size) {
                    val count = input.read(payload, read, payload.size - read)
                    if (count < 0) throw EOFException("page closed mid-frame")
                    read += count
                }
                if (mask != null) {
                    for (index in payload.indices) {
                        payload[index] = (payload[index].toInt() xor mask[index and 3].toInt()).toByte()
                    }
                }
                lastFrameAt = System.currentTimeMillis()
                when (opcode) {
                    0x8 -> {
                        close(1000, "bye")
                        return
                    }
                    0x9 -> {
                        synchronized(writeLock) {
                            writeFrame(0xA, payload)
                            output.flush()
                        }
                    }
                    0xA -> {}
                    0x1, 0x2 -> {
                        if (!fin) {
                            fragmentOpcode = opcode
                            appendFragment(payload)
                        } else {
                            handleFrame(opcode, payload, decoder)
                        }
                    }
                    0x0 -> {
                        appendFragment(payload)
                        if (fin) {
                            handleFrame(fragmentOpcode, fragment.toByteArray(), decoder)
                            fragment.reset()
                            fragmentBytes = 0
                            fragmentOpcode = 0
                        }
                    }
                    else -> break
                }
            }
        }

        /**
         * Server pings keep the link honest: the page's WebSocket stack answers pings on its own,
         * so a connection that stays silent for a whole interval after one is a squatter, not a
         * page, and dies. Any frame back - a pong included - keeps it alive.
         */
        fun livenessLoop() {
            while (running && !closed) {
                val sentAt = System.currentTimeMillis()
                runCatching {
                    synchronized(writeLock) {
                        writeFrame(0x9, ByteArray(0))
                        output.flush()
                    }
                }.onFailure { return }
                Thread.sleep(pingIntervalMillis)
                if (!closed && lastFrameAt < sentAt) {
                    close(1000, "No traffic after ping.")
                    log("ScanRuler page connection stayed silent after a ping; closed")
                    return
                }
            }
        }

        private fun handleFrame(opcode: Int, payload: ByteArray, decoder: ScanRulerFrames.Decoder) {
            if (opcode == 0x2) {
                receive(decoder.binary(payload))
            } else {
                val message = decoder.text(String(payload, StandardCharsets.UTF_8))
                if (message != null) receive(message)
            }
        }

        private fun receive(message: JSONObject) {
            val hello = message.optJSONObject("hello")
            if (page === null || hello != null) {
                synchronized(pairLock) {
                    if (hello != null && page !== null && page !== this) {
                        refuse(REFUSED_TAKEN, "Another ScanRuler page is already connected.")
                        return
                    }
                    if (page === null) {
                        if (hello == null) {
                            refuse(REFUSED_NO_HELLO, "The page did not say hello.")
                            return
                        }
                        if (hello.optString("token") != token) {
                            refuse(REFUSED_BAD_TOKEN, "Its pairing token is not this one's.")
                            return
                        }
                        val parsed = ScanRulerCommandCatalog.parse(hello.optJSONArray("commands"))
                        pageHello = hello
                        commands = parsed
                        page = this
                        pageReady.countDown()
                        runCatching {
                            send(ScanRulerFrames.Frame.Text(
                                JSONObject().put("welcome", JSONObject().put("server", "scanruler-mcp").put("version", serverVersion)).toString(),
                            ))
                        }
                        onCommands?.invoke(parsed)
                        onPageChanged?.invoke(true)
                        log("ScanRuler page paired (" + parsed.size + " commands)")
                        return
                    }
                }
            }
            if (page !== this) return
            val event = message.optString("event")
            if (event == "progress") {
                pending[message.optLong("id", -1)]?.onProgress?.invoke(message.optString("text"))
                return
            }
            if (event == "visibility") return
            val id = message.optLong("id", -1)
            val waiter = pending[id] ?: return
            val error = message.optJSONObject("error")
            if (error != null) {
                waiter.error = error
            } else if (message.has("result")) {
                when (val result = message.opt("result")) {
                    is JSONObject -> waiter.result = result
                    is JSONArray -> waiter.resultArray = result
                    null -> waiter.result = JSONObject()
                    else -> waiter.result = JSONObject().put("value", result)
                }
            } else {
                waiter.result = JSONObject()
            }
            waiter.latch.countDown()
        }

        fun refuse(code: Int, reason: String) {
            if (closed) return
            runCatching {
                synchronized(writeLock) {
                    writeFrame(0x1, JSONObject().put("refused", reason).toString().toByteArray(StandardCharsets.UTF_8))
                    output.flush()
                }
            }
            close(code, reason)
            log("Refused ScanRuler page: " + reason)
        }

        override fun close() {
            close(1000, "")
        }

        fun close(code: Int, reason: String = "") {
            if (closed) return
            closed = true
            runCatching {
                synchronized(writeLock) {
                    val body = closeReasonBytes(reason)
                    val payload = ByteArray(2 + body.size)
                    payload[0] = ((code ushr 8) and 0xff).toByte()
                    payload[1] = (code and 0xff).toByte()
                    body.copyInto(payload, 2)
                    writeFrame(0x8, payload)
                    output.flush()
                }
            }
            runCatching { socket.close() }
        }
    }

    private fun serve(socket: Socket) {
        var connection: PageConnection? = null
        try {
            socket.tcpNoDelay = true
            val handshake = readHandshake(socket.getInputStream()) ?: run {
                socket.close()
                return
            }
            val key = handshake.headers["sec-websocket-key"]
            val origin = handshake.headers["origin"]
            val allowed = origin == null ||
                expectedOrigins().any { it.equals(origin, ignoreCase = true) } ||
                origin.startsWith("http://127.0.0.1:") ||
                origin.startsWith("http://localhost:")
            if (key == null || !allowed) {
                writeHttpError(socket.getOutputStream(), 403, "Forbidden")
                socket.close()
                return
            }
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(StandardCharsets.UTF_8)),
            )
            val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " + accept + "\r\n\r\n"
            socket.getOutputStream().write(response.toByteArray(StandardCharsets.UTF_8))
            socket.getOutputStream().flush()
            val pageConnection = PageConnection(socket)
            connection = pageConnection
            thread(name = "scanruler-page-hello-timeout", isDaemon = true) {
                Thread.sleep(helloTimeoutMillis)
                // This connection's own hello is what counts: an established workspace still
                // refuses the connections that never say hello, and a just-paired page is left
                // alone (the welcome must not be followed by a 4008).
                if (page !== pageConnection) pageConnection.refuse(REFUSED_NO_HELLO, "No hello.")
            }
            thread(name = "scanruler-page-liveness", isDaemon = true) {
                pageConnection.livenessLoop()
            }
            pageConnection.readLoop()
        } catch (error: Throwable) {
            if (running) log("ScanRuler page connection ended: " + (error.message ?: error.javaClass.simpleName))
        } finally {
            connection?.close()
            if (page === connection) {
                page = null
                pageHello = null
                commands = emptyList()
                pageReady = CountDownLatch(1)
                pending.values.forEach { it.fail("The ScanRuler page closed the connection before it answered.") }
                pending.clear()
                onPageChanged?.invoke(false)
                log("ScanRuler page disconnected")
            }
        }
    }

    private data class Handshake(val path: String, val headers: Map<String, String>)

    private fun readHandshake(input: InputStream): Handshake? {
        val builder = ByteArrayOutputStream()
        val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (builder.size() < 16 * 1024) {
            val byte = input.read()
            if (byte < 0) return null
            builder.write(byte)
            val bytes = builder.toByteArray()
            if (bytes.size >= 4 && bytes.copyOfRange(bytes.size - 4, bytes.size).contentEquals(terminator)) {
                val lines = String(bytes, StandardCharsets.UTF_8).split("\r\n")
                val request = lines.firstOrNull()?.split(' ') ?: return null
                if (request.size < 2 || !request[0].equals("GET", true)) return null
                val headers = HashMap<String, String>()
                for (line in lines.drop(1)) {
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                return Handshake(request[1], headers)
            }
        }
        return null
    }

    private fun writeHttpError(output: OutputStream, status: Int, reason: String) {
        val text = reason.toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 " + status + " " + reason + "\r\nContent-Length: " + text.size + "\r\nConnection: close\r\n\r\n"
        runCatching {
            output.write(head.toByteArray(StandardCharsets.UTF_8))
            output.write(text)
            output.flush()
        }
    }
}
