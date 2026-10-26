package com.tomppi.enderslicer.solidcraft

import org.json.JSONObject
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * The engine session's raw MCP request surface, which the bridge forwards onto.
 *
 * [SolidCraftEngine] implements this over the stdio client; tests implement it with a lambda,
 * so the bridge's token check, id rewriting, error mapping and change reporting are exercised
 * without a process.
 */
interface McpForwarder {
    fun request(method: String, params: JSONObject, timeoutMillis: Long): JSONObject
    fun notify(method: String, params: JSONObject)
}

/**
 * The engine's MCP server, on a loopback socket, for the agent that is not this app.
 *
 * The engine speaks MCP over its own stdio, which only the app can reach. The existing engines
 * are drivable from outside the app (Blender on 9876, the Python CAD engine on 9877) and their
 * agent skills depend on that: a bearer token in a private file, an adb forward, and a skill.
 * This is the same boundary for SolidCraft, on 9878: every message must carry the token, the
 * socket is bound to loopback only, and the app can stop it.
 *
 * Two things happen here besides forwarding:
 *
 *  - **The plate is kept live.** After a tools/call that may have changed the design, the
 *    engine's created/changed/removed body report is turned into a [DocumentDelta] and handed to
 *    the app, which re-tessellates only those bodies. This is what makes an agent edit appear as
 *    the model changing shape rather than as a file being loaded.
 *  - **The engine's screenshot tool is refused.** It renders with the engine's own GPU view;
 *    the app's viewport is the only renderer, and the engine's camera state is not the user's.
 *
 * Each client connection is line-delimited JSON-RPC 2.0, like the engine's stdio: one message
 * per line, ids rewritten on the way in and back on the way out, notifications forwarded without
 * a reply. The bridge is not a transport for two different engine sessions - every connection
 * multiplexes onto the one engine process the app started.
 *
 * **One author.** The document may be edited by the app's in-app agent or by external agents
 * through this bridge, never both at once; [SolidCraftAuthorship] owns that rule. This class
 * supplies the two facts the rule needs: how many external connections are attached right now,
 * and whether a new one may attach. While the in-app agent has a turn in flight, a new connection
 * is answered with a named `AUTHORSHIP_BUSY` JSON-RPC error and closed - never queued, and never
 * by disconnecting a client that is already attached. [closeExternalClients] is what the sheet's
 * **Take over** calls.
 */
class SolidCraftBridge(
    private val token: String?,
    private val forwarder: McpForwarder,
    /** Called after a successful tool call that changed the design. */
    private val onDocumentChanged: (DocumentDelta) -> Unit,
    /** The one-author rule this bridge's live connections feed. */
    private val authorship: SolidCraftAuthorship = SolidCraftAuthorship(),
    private val port: Int = DEFAULT_PORT,
    private val log: (String) -> Unit = {},
) : Closeable {

    private var serverSocket: ServerSocket? = null
    private val connections = ConcurrentHashMap.newKeySet<Socket>()

    /** The connections currently counted as attached external authors. */
    private val externalClients = ConcurrentHashMap.newKeySet<Socket>()

    init {
        authorship.bindExternalCloser { closeExternalClients() }
    }

    @Volatile
    private var running = false

    private var acceptThread: Thread? = null

    /** The port actually bound, after [start]; 0 when stopped. */
    @Volatile
    var boundPort: Int = 0
        private set

    /** Starts accepting on 127.0.0.1:port. Blocking only while the socket binds. */
    @Synchronized
    fun start() {
        if (running) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName(HOST), port))
        serverSocket = socket
        boundPort = socket.localPort
        authorship.noteBridgePort(boundPort)
        running = true
        acceptThread = thread(name = "solidcraft-bridge-accept", isDaemon = true) {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Throwable) {
                    break
                }
                connections.add(client)
                thread(name = "solidcraft-bridge-client", isDaemon = true) {
                    serve(client)
                }
            }
        }
        log("CAD agent bridge listening on 127.0.0.1:" + boundPort)
    }

    override fun close() {
        running = false
        // The external connections are detached before the server goes: a Take over and an
        // engine stop both leave the authorship at nobody rather than at a dead client.
        runCatching { closeExternalClients() }
        authorship.bindExternalCloser(null)
        authorship.noteBridgePort(0)
        runCatching { serverSocket?.close() }
        serverSocket = null
        boundPort = 0
        for (socket in connections) runCatching { socket.close() }
        connections.clear()
        externalClients.clear()
        acceptThread = null
    }

    /**
     * Closes every live external connection and returns how many there were.
     *
     * The authorship is decremented for each one before its socket is closed, so a state read
     * right after this returns is already "nobody". The outbound agent sees EOF and has to
     * reconnect; nothing is queued for it.
     */
    fun closeExternalClients(): Int {
        var closed = 0
        for (socket in externalClients.toList()) {
            if (externalClients.remove(socket)) {
                authorship.detachExternal()
                closed++
            }
            runCatching { socket.close() }
        }
        return closed
    }

    /** How many external connections are attached, for tests and diagnostics. */
    fun externalClientCount(): Int = externalClients.size

    private fun serve(socket: Socket) {
        // The count the sheet and the in-app agent read is live connections, not a history:
        // it goes up when this socket becomes an author and down when the socket ends, whatever
        // ended it. A connection that arrives while the in-app agent is driving never becomes an
        // author: it gets one named error and is closed.
        val attached = authorship.attachExternal()
        if (attached) {
            externalClients.add(socket)
        }
        try {
            socket.soTimeout = 0
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            val writer: OutputStream = socket.getOutputStream()
            while (running) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                if (!attached) {
                    writeReply(writer, refusalFor(line))
                    break
                }
                val reply = handleLine(line)
                if (reply != null) writeReply(writer, reply)
            }
        } catch (error: Throwable) {
            log("agent connection ended: " + (error.message ?: error.javaClass.simpleName))
        } finally {
            if (externalClients.remove(socket)) authorship.detachExternal()
            connections.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun writeReply(writer: OutputStream, reply: String) {
        synchronized(writer) {
            writer.write(reply.toByteArray(Charsets.UTF_8))
            writer.write('\n'.code)
            writer.flush()
        }
    }

    /**
     * The named refusal for a connection opened while the in-app agent is driving.
     *
     * It answers the client's first message with a JSON-RPC error carrying its own id, so an
     * outbound agent sees exactly why and when to retry instead of a silent drop or a stalled
     * read. The connection is closed right after the answer.
     */
    private fun refusalFor(line: String): String {
        val message = runCatching { JSONObject(line) }.getOrNull()
            ?: return jsonRpcError(null, PARSE_ERROR, "parse error: the bridge expects one JSON object per line")
        return jsonRpcError(message.opt("id"), AUTHORSHIP_BUSY, authorship.refusalMessage())
    }

    /**
     * One request line in, at most one reply line out.
     *
     * Public because it is the whole protocol surface: the test drives it directly with a fake
     * forwarder, which is how the token, id, error and live-update contracts are pinned without
     * a socket in the way.
     */
    fun handleLine(line: String): String? {
        val message = try {
            JSONObject(line)
        } catch (_: Throwable) {
            return jsonRpcError(null, PARSE_ERROR, "parse error: the bridge expects one JSON object per line")
        }
        if (!authorized(message)) {
            return jsonRpcError(
                message.opt("id"),
                UNAUTHORIZED,
                "unauthorized: send the token the app wrote to files/solidcraft/solidcraft_mcp_token.txt",
            )
        }
        // The one-author rule is checked here as well as on the socket, so a caller that drives
        // handleLine directly cannot bypass it. The message names who is driving and what to do.
        if (!authorship.externalAllowed()) {
            return jsonRpcError(message.opt("id"), AUTHORSHIP_BUSY, authorship.refusalMessage())
        }
        val method = message.optString("method")
        val params = message.optJSONObject("params") ?: JSONObject()
        if (method.isBlank()) {
            // A response to a request of ours, or junk: this bridge sends no requests.
            if (message.has("result") || message.has("error")) return null
            return jsonRpcError(message.opt("id"), INVALID_REQUEST, "missing method")
        }
        val id = message.opt("id")
        if (id == null) {
            // A notification: notifications/initialized is the one that matters.
            runCatching { forwarder.notify(method, params) }
                .onFailure { log("could not forward notification " + method + ": " + it.message) }
            return null
        }
        if (method == "tools/call" && params.optString("name") == "screenshot") {
            return replyResult(
                id,
                McpToolResults.errorResult(
                    "the plate is the viewport: the app renders this document itself, and the " +
                        "engine's screenshot would be a second picture of a camera the user does not see. " +
                        "Ask the user to look at the plate.",
                ),
            )
        }
        return try {
            val timeout = if (method == "tools/call") TOOL_TIMEOUT_MILLIS else REQUEST_TIMEOUT_MILLIS
            val result = forwarder.request(method, params, timeout)
            if (method == "tools/call") reportChange(params, result)
            replyResult(id, result)
        } catch (timeout: McpTimeoutException) {
            // The call was accepted; the engine may well have run it. Say both things, the way
            // the engine's own protocol does, so the agent inspects rather than retries blindly.
            jsonRpcError(
                id,
                ENGINE_TIMEOUT,
                "the SolidCraft engine did not answer " + method + " within " + timeout.timeoutMillis +
                    " ms. The call may still have run: inspect the design before sending it again.",
            )
        } catch (remote: McpRemoteException) {
            jsonRpcError(id, remote.code, remote.remoteMessage)
        } catch (other: McpException) {
            jsonRpcError(id, ENGINE_UNAVAILABLE, other.message ?: "the SolidCraft engine failed")
        } catch (error: Throwable) {
            jsonRpcError(id, INTERNAL_ERROR, error.message ?: error.javaClass.simpleName)
        }
    }

    /** The live-view half: turn a successful edit's body report into a repaint. */
    private fun reportChange(params: JSONObject, result: JSONObject) {
        val tool = params.optString("name")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        val delta = runCatching { SolidCraftTools.deltaFor(tool, arguments, result) }
            .onFailure { log("could not read the change report of " + tool + ": " + it.message) }
            .getOrNull() ?: return
        if (delta.isEmpty) return
        runCatching { onDocumentChanged(delta) }
            .onFailure { log("the live-view handler failed: " + it.message) }
    }

    private fun authorized(message: JSONObject): Boolean {
        val expected = token?.trim().orEmpty()
        if (expected.isEmpty()) return false
        val supplied = message.optString("token").ifBlank {
            message.optJSONObject("params")?.optString("token").orEmpty()
        }
        return supplied.trim() == expected
    }

    private fun replyResult(id: Any?, result: JSONObject): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id ?: JSONObject.NULL)
            .put("result", result)
            .toString()

    private fun jsonRpcError(id: Any?, code: Int, message: String): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))
        .toString()

    companion object {
        /** The existing engines hold 9876 and 9877; this one is the third. */
        const val DEFAULT_PORT = 9878

        private const val HOST = "127.0.0.1"
        private const val TOOL_TIMEOUT_MILLIS = 300_000L
        private const val REQUEST_TIMEOUT_MILLIS = 60_000L

        private const val PARSE_ERROR = -32700
        private const val INVALID_REQUEST = -32600
        private const val INTERNAL_ERROR = -32603
        private const val UNAUTHORIZED = -32001
        private const val ENGINE_UNAVAILABLE = -32002
        private const val ENGINE_TIMEOUT = -32003

        /** The named refusal while the in-app agent is the document's author. */
        const val AUTHORSHIP_BUSY = -32010
    }
}
