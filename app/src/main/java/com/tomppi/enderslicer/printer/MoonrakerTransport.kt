package com.tomppi.enderslicer.printer

import android.util.Log
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64

/**
 * A Klipper host on the network, presented as the byte pipe the client already reads.
 *
 * The app has two ways to reach a printer: klippy inside the phone over a unix socket, and this -
 * klippy on a computer, over Moonraker's WebSocket. The screens, the protocol and the client's
 * framing are the same in both cases, so the difference is kept here, in the pipe: frames in,
 * ETX-terminated messages out, and the translation in [MoonrakerDialect] applied on the way past.
 * Not one screen has to know which host it is talking to.
 *
 * Three things this does that a plain socket would not:
 *
 *  - it answers the calls klippy's own socket has and Moonraker does not, so the client's
 *    subscription to the console succeeds and the output arrives as notifications instead;
 *  - it translates what Moonraker pushes into the shapes the client reads, including the status
 *    update whose payload is an array there and an object here;
 *  - it keeps the wire alive: a WebSocket that is not answered is closed by the server, and a
 *    print that is being watched is a connection that must not drop.
 */
internal class MoonrakerTransport(
    private val host: String,
    private val port: Int = DEFAULT_PORT,
    private val apiKey: String? = null,
    private val path: String = DEFAULT_PATH,
    /** How long a read waits before waking up to keep the connection alive. */
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) : KlipperTransport {

    private val frameLock = Any()
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    /** Messages ready to be read, each already terminated the way the client expects. */
    private val ready = ArrayDeque<ByteArray>()

    /** What is left of a message being read into the client's buffer. */
    private var serving = ByteArray(0)

    /** Frame bytes that arrived without a whole frame in them yet. */
    private var partial = ByteArray(0)

    /** A fragmented message being put back together, and its opcode. */
    private var assembling = ByteArray(0)
    private var assemblingOpcode = 0

    override fun connect(timeoutMs: Int) {
        val connection = Socket()
        connection.tcpNoDelay = true
        connection.connect(InetSocketAddress(host, port), timeoutMs)
        connection.soTimeout = readTimeoutMs
        socket = connection
        input = connection.getInputStream()
        output = connection.getOutputStream()
        val key = handshake()
        verifyHandshake(key)
    }

    override fun read(buffer: ByteArray): Int {
        while (serving.isEmpty()) {
            if (ready.isNotEmpty()) {
                serving = ready.removeFirst()
                break
            }
            if (!pump()) return -1
        }
        val count = minOf(buffer.size, serving.size)
        serving.copyInto(buffer, 0, 0, count)
        serving = serving.copyOfRange(count, serving.size)
        return count
    }

    override fun write(bytes: ByteArray) {
        // The client writes one request per ETX-terminated message, and Moonraker wants one
        // WebSocket text frame per request.
        for (message in splitOnEtx(bytes)) {
            val local = runCatching { JSONObject(String(message)) }.getOrNull() ?: continue
            val translated = MoonrakerDialect.request(local)
            if (translated == null) {
                // Answered here: klippy has this method, Moonraker does not, and the client is
                // entitled to a reply to the request it made rather than a timeout.
                ready += terminate(
                    JSONObject()
                        .put("id", local.optInt("id"))
                        .put("result", JSONObject())
                        .toString()
                        .toByteArray(),
                )
            } else {
                sendFrame(KlipperWebSocket.OPCODE_TEXT, translated.toString().toByteArray())
            }
        }
    }

    override fun close() {
        runCatching { sendFrame(KlipperWebSocket.OPCODE_CLOSE, ByteArray(0)) }
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
        ready.clear()
        serving = ByteArray(0)
        partial = ByteArray(0)
        assembling = ByteArray(0)
    }

    /**
     * One read from the wire, turned into messages if it held any.
     *
     * False means the stream is over: a close frame from the server, or a socket that has gone.
     */
    private fun pump(): Boolean {
        val stream = input ?: return false
        val chunk = ByteArray(READ_BUFFER_BYTES)
        val count = try {
            stream.read(chunk)
        } catch (timeout: SocketTimeoutException) {
            //
            // An idle socket is not a closed one.
            //
            // A printer host says nothing at all while nothing is happening, and this timeout
            // exists so that the reader wakes up rather than blocking for ever. Waking up is
            // the moment to say something - a ping keeps the connection alive through whatever
            // is between here and the host - and not the moment to conclude the host has gone.
            //
            // It was concluded, once: the read loop ended on the first quiet thirty seconds,
            // the app went on believing it was connected, and a print pressed a minute later
            // was never sent. Nothing reached klippy, and the host still had the socket open,
            // which is what that failure looked like from both ends.
            //
            keepAlive()
            return true
        } catch (e: Exception) {
            Log.i(TAG, "the remote host stopped answering: ${e.message}")
            return false
        }
        if (count < 0) return false
        partial += chunk.copyOfRange(0, count)
        // decode() leaves a frame longer than the protocol allows unconsumed, so without this the
        // buffer grows for as long as the peer keeps sending.
        if (partial.size > KlipperWebSocket.MAX_FRAME_BYTES) {
            throw IllegalStateException("the host sent more than a frame's worth of bytes")
        }
        val (frames, rest) = KlipperWebSocket.decode(partial)
        partial = rest
        for (frame in frames) {
            val payload = when {
                frame.opcode == KlipperWebSocket.OPCODE_CONTINUATION -> {
                    // Fragments are legal protocol, so this grows as fast as the peer sends them.
                    assembling += frame.payload
                    if (assembling.size > KlipperWebSocket.MAX_FRAME_BYTES) {
                        throw IllegalStateException("the host sent a message past the size limit")
                    }
                    if (!frame.fin) continue
                    val whole = assembling
                    assembling = ByteArray(0)
                    whole
                }
                frame.opcode == KlipperWebSocket.OPCODE_TEXT ||
                    frame.opcode == KlipperWebSocket.OPCODE_BINARY -> {
                    if (!frame.fin) {
                        assembling = frame.payload
                        assemblingOpcode = frame.opcode
                        continue
                    }
                    frame.payload
                }
                else -> ByteArray(0)
            }
            when (frame.opcode) {
                KlipperWebSocket.OPCODE_PING -> sendFrame(KlipperWebSocket.OPCODE_PONG, frame.payload)
                KlipperWebSocket.OPCODE_CLOSE -> return false
                KlipperWebSocket.OPCODE_PONG -> Unit
                else -> accept(payload)
            }
        }
        return true
    }

    /**
     * A message from Moonraker, as the client will read it.
     *
     * A reply passes through untouched - it carries an id, and the client unwraps its result
     * either way. A notification is one of Moonraker's, and the dialect turns it into what the
     * client's parser knows. Anything else Moonraker says is dropped rather than decoded by
     * guesswork.
     */
    private fun accept(payload: ByteArray) {
        if (payload.isEmpty()) return
        val message = runCatching { JSONObject(String(payload)) }.getOrNull() ?: return
        if (!message.has("method")) {
            ready += terminate(payload)
            return
        }
        for (translated in MoonrakerDialect.notifications(message)) {
            ready += terminate(translated.toString().toByteArray())
        }
    }

    /** The handshake, and the key it used, which the accept value has to match. */
    private fun handshake(): String {
        val request = KlipperWebSocket.handshakeRequest(host, port, path, apiKey)
        output?.write(request.toByteArray())
        output?.flush()
        return request.lineSequence()
            .first { it.startsWith("Sec-WebSocket-Key:") }
            .substringAfter(':')
            .trim()
    }

    /** Read the response headers, and refuse anything that is not a WebSocket upgrade. */
    private fun verifyHandshake(key: String) {
        val stream = input ?: error("no connection")
        val headers = StringBuilder()
        while (!headers.endsWith("\r\n\r\n")) {
            val next = stream.read()
            if (next < 0) error("the host closed the connection during the handshake")
            headers.append(next.toChar())
            if (headers.length > MAX_HANDSHAKE_BYTES) error("the handshake never ended")
        }
        val text = headers.toString()
        val status = text.lineSequence().first()
        if (!status.contains("101")) {
            error("the host refused the WebSocket: $status")
        }
        val accept = text.lineSequence()
            .firstOrNull { it.startsWith("Sec-WebSocket-Accept:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
        if (accept != KlipperWebSocket.handshakeAccept(key)) {
            error("the host answered with the wrong accept value")
        }
    }

    /**
     * One frame, on a lock.
     *
     * Two threads write here already - the reader answers pings and the caller sends
     * requests - and a frame is not a unit the socket keeps together: interleaved halves are
     * a protocol error the host answers by closing. The keepalive adds a third writer on the
     * same schedule, so the lock is what makes any of it safe.
     */
    private fun sendFrame(opcode: Int, payload: ByteArray) {
        synchronized(frameLock) {
            output?.write(KlipperWebSocket.encode(opcode, payload))
            output?.flush()
        }
    }

    /** A ping, so an idle connection stays a connection. */
    private fun keepAlive() {
        runCatching { sendFrame(KlipperWebSocket.OPCODE_PING, ByteArray(0)) }
            .onFailure { Log.i(TAG, "the keepalive did not go out: ${it.message}") }
    }

    /** klippy's messages are ETX-terminated in both directions. */
    private fun splitOnEtx(bytes: ByteArray): List<ByteArray> {
        val messages = mutableListOf<ByteArray>()
        var start = 0
        for (index in bytes.indices) {
            if (bytes[index] == KlipperProtocol.ETX) {
                if (index > start) messages += bytes.copyOfRange(start, index)
                start = index + 1
            }
        }
        if (start < bytes.size) messages += bytes.copyOfRange(start, bytes.size)
        return messages
    }

    private fun terminate(json: ByteArray): ByteArray = json + byteArrayOf(KlipperProtocol.ETX)

    companion object {
        private const val TAG = "KlipperRemote"

        /** Moonraker's port, and the path its WebSocket lives at. */
        const val DEFAULT_PORT = 7125
        const val DEFAULT_PATH = "/websocket"

        private const val READ_BUFFER_BYTES = 8 * 1024
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_HANDSHAKE_BYTES = 8 * 1024
    }
}
