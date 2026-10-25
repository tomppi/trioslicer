package com.tomppi.enderslicer.solidcraft

import org.json.JSONObject
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * One line each way: the transport under the engine's MCP session.
 *
 * The engine speaks JSON-RPC 2.0 as one JSON message per line on its standard input and
 * output (docs/mcp.md). Split out of [McpClient] so the client's framing, ids and timeouts
 * can be exercised without a child process.
 */
interface McpTransport : Closeable {
    /** Writes one message and its newline; blocking. */
    fun write(line: String)

    /** Reads one message, or null at end of stream; blocking. */
    fun readLine(): String?

    override fun close()
}

/** The transport a child process gives us: its stdout in, its stdin out. */
class StreamMcpTransport(
    private val input: InputStream,
    private val output: OutputStream,
) : McpTransport {
    private val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
    private val writer = output
    private val writeLock = Any()

    override fun write(line: String) {
        synchronized(writeLock) {
            writer.write(line.toByteArray(Charsets.UTF_8))
            writer.write('\n'.code)
            writer.flush()
        }
    }

    override fun readLine(): String? = reader.readLine()

    override fun close() {
        // The streams underneath, never the BufferedReader: a readLine blocked on a live
        // child process holds the reader's lock, and closing the wrapper waits for it - which
        // is a deadlock on the very case this has to handle (stopping an engine that is idle).
        // Closing the stdin/stdout streams is what unblocks the read and ends the reader.
        runCatching { input.close() }
        runCatching { output.close() }
    }
}

/** Something the engine session could not do, in the words the UI shows. */
open class McpException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

/** The engine accepted the request but did not answer inside the caller's budget. */
class McpTimeoutException(
    val method: String,
    val timeoutMillis: Long,
) : McpException("the SolidCraft engine did not answer " + method + " within " + timeoutMillis + " ms")

/** The pipe to the engine broke - usually because its process exited. */
class McpTransportException(message: String, cause: Throwable? = null) : McpException(message, cause)

/** The engine answered with a JSON-RPC error rather than a result. */
class McpRemoteException(
    val code: Int,
    val remoteMessage: String,
) : McpException("the SolidCraft engine refused the request: " + remoteMessage)

/**
 * A newline-delimited JSON-RPC 2.0 client over a blocking pair of streams.
 *
 * The engine is a child process, so reading and writing happen on different threads: one
 * reader thread takes the replies, and each request waits on its own future in a map keyed by
 * the id it was sent with. Requests may overlap (nothing serialises them beyond the transport's
 * write lock), which is what keeps a long export from blocking a status poll beside it.
 *
 * Failures are surfaced, never swallowed: a JSON-RPC error becomes [McpRemoteException] with the
 * engine's own code and message, a call that does not answer inside its budget becomes
 * [McpTimeoutException], a broken pipe becomes [McpTransportException], and every waiter is
 * failed when the stream ends so a dead engine cannot leave a caller hanging forever.
 */
class McpClient(
    private val transport: McpTransport,
    /** Diagnostics; the engine's own logging is on stderr, which the process owner drains. */
    private val log: (String) -> Unit = {},
) : Closeable {

    private val nextId = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<JSONObject>>()

    @Volatile
    private var closed = false

    @Volatile
    private var reader: Thread? = null

    /** Starts the reader thread. Calling it twice is a no-op. */
    fun start(): McpClient {
        if (reader != null) return this
        synchronized(this) {
            if (reader != null) return this
            reader = thread(name = "solidcraft-mcp-reader", isDaemon = true) { readLoop() }
        }
        return this
    }

    /** True once [close] ran or the transport reached end of stream. */
    fun isClosed(): Boolean = closed

    /**
     * Sends one request and returns its result.
     *
     * @throws McpTimeoutException when no answer arrives within timeoutMillis
     * @throws McpRemoteException when the engine answers with a JSON-RPC error
     * @throws McpTransportException when the pipe is broken or already closed
     */
    fun request(
        method: String,
        params: JSONObject = JSONObject(),
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): JSONObject {
        check(!closed) { "the SolidCraft engine session is closed" }
        val id = nextId.incrementAndGet()
        val future = CompletableFuture<JSONObject>()
        pending[id] = future
        val message = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
        try {
            transport.write(message.toString())
        } catch (error: Throwable) {
            pending.remove(id)
            throw McpTransportException("could not write " + method + " to the SolidCraft engine", error)
        }
        return await(method, id, future, timeoutMillis)
    }

    /** Sends a notification: no id, and by the protocol no answer is owed. */
    fun notify(method: String, params: JSONObject = JSONObject()) {
        check(!closed) { "the SolidCraft engine session is closed" }
        val message = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
        try {
            transport.write(message.toString())
        } catch (error: Throwable) {
            throw McpTransportException("could not write " + method + " to the SolidCraft engine", error)
        }
    }

    private fun await(method: String, id: Long, future: CompletableFuture<JSONObject>, timeoutMillis: Long): JSONObject {
        try {
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            // The id is forgotten so a reply that arrives later is logged and discarded rather
            // than delivered to whoever holds the next request with that id.
            pending.remove(id)
            throw McpTimeoutException(method, timeoutMillis)
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            pending.remove(id)
            throw McpTransportException("interrupted while waiting for " + method, interrupted)
        } catch (failed: ExecutionException) {
            pending.remove(id)
            val cause = failed.cause
            if (cause is McpException) throw cause
            throw McpTransportException(method + " failed in the SolidCraft engine session", cause ?: failed)
        }
    }

    private fun readLoop() {
        while (true) {
            val line = try {
                transport.readLine()
            } catch (error: IOException) {
                failAll(McpTransportException("lost the connection to the SolidCraft engine", error))
                return
            } catch (error: Throwable) {
                failAll(McpTransportException("the SolidCraft engine's output could not be read", error))
                return
            } ?: run {
                failAll(McpTransportException("the SolidCraft engine closed its output (the process exited)"))
                return
            }
            if (line.isBlank()) continue
            dispatch(line)
        }
    }

    /**
     * One message. A malformed line is logged and skipped rather than killing the session: one
     * bad line must not take every request waiting behind it down with it. (The engine writes
     * only protocol messages to stdout; anything else is worth a log line, not a panic.)
     */
    private fun dispatch(line: String) {
        val message = try {
            JSONObject(line)
        } catch (error: Throwable) {
            log("ignoring a line from the engine that is not JSON: " + line.take(200))
            return
        }
        // A server-initiated request has both an id and a method. This client offers nothing,
        // so answer as JSON-RPC says an unknown method is answered.
        if (message.has("method")) {
            if (message.has("id")) {
                val reply = JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", message.opt("id"))
                    .put("error", JSONObject()
                        .put("code", -32601)
                        .put("message", "method " + message.optString("method") + " is not offered by this client"))
                runCatching { transport.write(reply.toString()) }
                log("refused server request " + message.optString("method"))
            }
            return
        }
        val id = message.opt("id")
        val key = when (id) {
            is Number -> id.toLong()
            is String -> id.toLongOrNull()
            else -> null
        }
        if (key == null) {
            log("ignoring an engine message with no usable id: " + line.take(200))
            return
        }
        val future = pending.remove(key)
        if (future == null) {
            // A timed-out request's answer: the design may still have changed, and the caller
            // was told the call's fate was unknown, so this is only a diagnostic.
            log("ignoring a late answer for request " + key)
            return
        }
        val error = message.optJSONObject("error")
        if (error != null) {
            future.completeExceptionally(
                McpRemoteException(error.optInt("code", -1), error.optString("message", "no message")),
            )
        } else if (message.has("result")) {
            future.complete(message.optJSONObject("result") ?: JSONObject())
        } else {
            future.completeExceptionally(
                McpTransportException("the engine answered request " + key + " with neither a result nor an error"),
            )
        }
    }

    private fun failAll(reason: McpException) {
        closed = true
        val waiters = pending.keys.toList()
        for (id in waiters) {
            pending.remove(id)?.completeExceptionally(reason)
        }
        if (waiters.isNotEmpty()) log("failed " + waiters.size + " pending engine request(s): " + reason.message)
    }

    override fun close() {
        closed = true
        runCatching { transport.close() }
        failAll(McpTransportException("the SolidCraft engine session was closed"))
        val thread = reader
        reader = null
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(500) }
        }
    }

    companion object {
        /** A tools call may legitimately run a boolean kernel for a while. */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 120_000L
    }
}
