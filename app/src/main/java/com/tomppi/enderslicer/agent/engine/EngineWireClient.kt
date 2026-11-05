package com.tomppi.enderslicer.agent.engine

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * The engines' own wire: one JSON object in, one JSON object out, shaped
 * {"type": ..., "params": {...}, "token": ...} in and {"status", "result" | "message"} out.
 *
 * One connection per engine, shared by every caller in the app - the tool surfaces, the
 * screen previews, the importers. The engine serves one connection at a time, so a second
 * one starves: readiness read "not started" between two successful calls, and an open
 * screen held the engine out of the agent's reach. One client, one lock, one connection:
 * callers interleave as whole commands.
 *
 * The timeout rule is kept: after a timeout the call is NOT re-sent, because the engine
 * runs what it accepted whether or not the answer arrives.
 */
internal class EngineWireClient(
    private val port: Int,
    private val token: () -> String?,
    private val host: String = "127.0.0.1",
) {
    private val lock = Any()
    private var socket: Socket? = null

    /**
     * One command to the engine.
     *
     * @throws EngineTimeoutException when the engine accepted the call but did not answer.
     * @throws EngineUnavailableException when the engine is not running or the wire failed.
     */
    fun command(type: String, params: JSONObject, timeoutMs: Int): JSONObject {
        synchronized(lock) {
            val body = JSONObject()
                .put("type", type)
                .put("params", params)
            token()?.let { body.put("token", it) }
            val payload = (body.toString() + "\n").toByteArray(StandardCharsets.UTF_8)
            var attempt = 0
            while (true) {
                attempt++
                try {
                    // The connect is inside the retry as well: a refused connection is the
                    // ordinary shape of a stopped engine and must become the named error.
                    val connection = ensureSocket(timeoutMs)
                    connection.getOutputStream().apply { write(payload); flush() }
                    return JSONObject(EngineWireProtocol.readReply(connection.getInputStream()))
                } catch (timeout: SocketTimeoutException) {
                    // The engine runs what it accepted whether or not the answer comes back:
                    // never re-send, the rule the preview clients name.
                    discardSocket()
                    throw EngineTimeoutException(timeoutMs.toLong())
                } catch (error: EngineTimeoutException) {
                    throw error
                } catch (error: Throwable) {
                    // A dead socket is ordinary whenever the engine restarts with the app:
                    // drop it and reconnect once before failing the call.
                    discardSocket()
                    if (attempt >= 2) {
                        throw EngineUnavailableException(
                            error.message ?: error.javaClass.simpleName,
                            error,
                        )
                    }
                }
            }
        }
    }

    private fun ensureSocket(timeoutMs: Int): Socket {
        socket?.let { connection ->
            if (!connection.isClosed && connection.isConnected) {
                // Per command, not per socket: an import needs minutes while a ping needs
                // one second, and whichever connected first must not fix the patience for
                // every later command on that connection.
                connection.soTimeout = timeoutMs
                return connection
            }
        }
        val connection = Socket()
        connection.tcpNoDelay = true
        connection.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
        connection.soTimeout = timeoutMs
        socket = connection
        return connection
    }

    private fun discardSocket() {
        runCatching { socket?.close() }
        socket = null
    }

    fun close() = synchronized(lock) { discardSocket() }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 5_000

        private val sharedClients = ConcurrentHashMap<String, EngineWireClient>()

        /**
         * The one client per engine socket. Every caller in the app - the tool surfaces,
         * the screen previews, the importers - goes through this instance, because the
         * engine serves one connection at a time.
         */
        fun shared(host: String, port: Int, token: () -> String?): EngineWireClient =
            sharedClients.computeIfAbsent(host + ":" + port) {
                EngineWireClient(port = port, token = token, host = host)
            }
    }
}

/**
 * The engine reply parser: read until the bytes parse as one JSON object. Both framings the
 * engines use are covered - a newline-terminated reply parses as soon as it arrives, and a
 * reply without a terminator parses when its object closes.
 */
internal object EngineWireProtocol {

    /**
     * The reply byte limit: a peer that never closes its object must not grow a phone heap.
     * Replies are JSON control messages, not payloads (renders travel through files), so a
     * megabyte is far past any real answer.
     */
    private const val REPLY_BYTE_LIMIT = 1024 * 1024

    /**
     * One reply, bounded, decoded whole and only once it can plausibly be complete. The
     * returned text is the engine's own bytes as a string, terminator included.
     */
    fun readReply(input: InputStream): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) error("The engine closed the connection")
            if (buffer.size() + read > REPLY_BYTE_LIMIT) error("the reply byte limit is " + REPLY_BYTE_LIMIT)
            buffer.write(chunk, 0, read)
            val text = buffer.toString(StandardCharsets.UTF_8.name())
            if (parsesAsOneObject(text)) return text
        }
    }

    private fun parsesAsOneObject(text: String): Boolean = runCatching {
        val trimmed = text.trim()
        trimmed.startsWith("{") && trimmed.endsWith("}") && JSONObject(trimmed).length() >= 0
    }.getOrDefault(false)
}
