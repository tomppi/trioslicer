package com.tomppi.enderslicer.agent.engine

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's one agent door: an MCP server that talks to all three modelling engines and a
 * selector that says which one the conversation is for.
 *
 * Before this, every engine had its own door - Blender on 9876, the Python CAD engine on
 * 9877, SolidCraft on 9878 - each with its own token, its own skill, its own hand-over line.
 * This is the one door: one token, one pasted line, and [EngineCatalogs.selectorTools]'s
 * engine_select, whose answer tells the agent which engine it is driving, how to reach that
 * engine's tools, and the rules that govern them. tools/list then shows only the selected
 * engine's tools (their own names), and switches emit tools/list_changed.
 *
 * SolidCraft is the default and keeps its contracts through its own message bridge: one
 * author at a time (-32010 while the in-app agent works), the live plate after every edit,
 * and no screenshot - the plate is the viewport.
 *
 * Each client connection is line-delimited JSON-RPC 2.0 carrying the token on every message.
 * [handleLine] is public because it is the whole protocol surface: the test drives it with
 * fake surfaces, which is how the selector, scoping, routing and error contracts are pinned
 * without a socket in the way.
 */
internal class EngineMcpServer(
    private val token: String?,
    private val surfaces: List<EngineToolSurface>,
    /** The SolidCraft document's one-author rule; its connections count as external authors. */
    private val authorship: com.tomppi.enderslicer.solidcraft.SolidCraftAuthorship? = null,
    private val port: Int = DEFAULT_PORT,
    private val log: (String) -> Unit = {},
) : Closeable {

    private val byId = surfaces.associateBy { it.id }
    private val connections = ConcurrentHashMap.newKeySet<Socket>()

    /** The buffered writer per connection: one reply flushes as one write (see [writeReply]). */
    private val writers = java.util.concurrent.ConcurrentHashMap<Socket, BufferedWriter>()

    /** The connections counted as attached external authors of the SolidCraft document. */
    private val attachedClients = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    private var activeId: String = DEFAULT_ENGINE

    @Volatile
    private var running = false

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    /** The port actually bound, after [start]; 0 when stopped. */
    @Volatile
    var boundPort: Int = 0
        private set

    /** The engine the selector currently points at. */
    fun activeEngine(): EngineToolSurface? = byId[activeId]

    /** Starts accepting on 127.0.0.1:port. Blocking only while the socket binds. */
    @Synchronized
    fun start() {
        if (running) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName(HOST), port))
        serverSocket = socket
        boundPort = socket.localPort
        running = true
        acceptThread = thread(name = "engine-mcp-accept", isDaemon = true) {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Throwable) {
                    break
                }
                connections.add(client)
                thread(name = "engine-mcp-client", isDaemon = true) {
                    serve(client)
                }
            }
        }
        log("engine MCP listening on 127.0.0.1:" + boundPort)
    }

    override fun close() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        connections.forEach { runCatching { it.close() } }
        connections.clear()
        writers.clear()
        acceptThread = null
        boundPort = 0
    }

    private fun serve(socket: Socket) {
        // One connection is one external author of the SolidCraft document while it is up:
        // the same counting the old per-engine bridge did, so the in-app agent and an
        // external one can never edit the document at once. A connection the authorship
        // refuses to count still works - its SolidCraft calls answer -32010 per call.
        val attached = authorship?.attachExternal() == true
        if (attached) attachedClients.add(socket)
        try {
            socket.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            // Buffered so one reply is one flush: writing the object and its newline straight
            // to the raw stream is two writes, two TCP segments, and ~44 ms per message on an
            // adb-forwarded connection (delayed-ACK on the second segment). Measured 44 ms ->
            // ~1 ms per ping; every external agent call pays it.
            val writer = socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
            writers[socket] = writer
            while (running) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val reply = handleLine(line, socket)
                if (reply != null) writeReply(writer, reply)
            }
        } catch (_: Throwable) {
            // A client that vanished is normal.
        } finally {
            connections.remove(socket)
            writers.remove(socket)
            if (attachedClients.remove(socket)) authorship?.detachExternal()
            runCatching { socket.close() }
        }
    }

    /**
     * Close every connection counted as an external author and detach it, answering how many
     * went. What the authorship's Take over calls: the document hands back to the in-app agent.
     */
    fun closeAttachedConnections(): Int {
        var closed = 0
        attachedClients.toList().forEach { socket ->
            if (attachedClients.remove(socket)) {
                authorship?.detachExternal()
                runCatching { socket.close() }
                closed++
            }
        }
        return closed
    }

    /** One reply, one flush: a buffered writer turns the object and its newline into one write. */
    internal fun writeReply(writer: BufferedWriter, reply: String) {
        synchronized(writer) {
            writer.write(reply)
            writer.newLine()
            writer.flush()
        }
    }

    /**
     * One request line in, at most one reply line out. Public as the whole protocol surface:
     * the test drives it directly with fake surfaces.
     */
    fun handleLine(line: String, connection: Socket? = null): String? {
        val message = try {
            JSONObject(line)
        } catch (_: Throwable) {
            return jsonRpcError(null, PARSE_ERROR, "parse error: one JSON object per line")
        }
        if (!authorized(message)) {
            return jsonRpcError(
                message.opt("id"),
                UNAUTHORIZED,
                "unauthorized: send the token the app wrote to files/agent/" + TOKEN_FILE,
            )
        }
        val method = message.optString("method")
        val params = message.optJSONObject("params") ?: JSONObject()
        if (method.isBlank()) {
            if (message.has("result") || message.has("error")) return null
            return jsonRpcError(message.opt("id"), INVALID_REQUEST, "missing method")
        }
        val id = message.opt("id")
        if (id == null) {
            // A notification: the SolidCraft stdio session is the only surface that wants one.
            runCatching { activeEngine()?.notify(method, params) }
            return null
        }
        return try {
            when (method) {
                "initialize" -> replyResult(id, initializeResult())
                "ping" -> replyResult(id, JSONObject())
                "tools/list" -> replyResult(id, JSONObject().put("tools", toolsList()))
                "tools/call" -> replyResult(id, callTool(params, connection))
                else -> jsonRpcError(id, METHOD_NOT_FOUND, "the engine server does not implement " + method)
            }
        } catch (refusal: EngineGateRefusal) {
            jsonRpcError(id, refusal.code, refusal.message ?: "refused")
        } catch (timeout: EngineTimeoutException) {
            val engine = activeEngine()?.displayName ?: "the engine"
            jsonRpcError(
                id,
                ENGINE_TIMEOUT,
                "the " + engine + " engine did not answer within " + timeout.timeoutMillis +
                    " ms. The call may still have run: inspect the result before sending it again.",
            )
        } catch (unavailable: EngineUnavailableException) {
            jsonRpcError(id, ENGINE_UNAVAILABLE, unavailable.message ?: "the engine is unavailable")
        } catch (remote: EngineRemoteException) {
            jsonRpcError(id, remote.code, remote.message ?: "the engine refused the call")
        } catch (error: Throwable) {
            jsonRpcError(id, INTERNAL_ERROR, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun initializeResult(): JSONObject = JSONObject()
        .put("protocolVersion", PROTOCOL_VERSION)
        .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", true)))
        .put("serverInfo", JSONObject().put("name", SERVER_NAME).put("version", SERVER_VERSION))

    /** The selector's tools always; then the active engine's own. */
    private fun toolsList(): JSONArray {
        val tools = EngineCatalogs.selectorTools()
        val engine = activeEngine() ?: return tools
        val engineTools = engine.tools()
        for (index in 0 until engineTools.length()) {
            tools.put(engineTools.optJSONObject(index))
        }
        return tools
    }

    private fun callTool(params: JSONObject, connection: Socket?): JSONObject {
        val name = params.optString("name")
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        when (name) {
            "engine_select" -> return selectEngine(arguments)
            "engine_status" -> return EngineToolResults.text(statusText())
        }
        val engine = activeEngine()
            ?: throw EngineUnavailableException("no engine is selected")
        if (engine.id == "solidcraft") {
            // The one-author rule: the document may be edited by the in-app agent or by an
            // attached external connection, never both. A connection whose attach lost a race
            // to an in-app turn attaches at its first SolidCraft call instead, so no editing
            // connection is ever uncounted; the message names who is driving and what to do.
            if (connection != null && !attachedClients.contains(connection)) {
                if (authorship?.attachExternal() == true) {
                    attachedClients.add(connection)
                } else {
                    throw EngineGateRefusal(AUTHORSHIP_BUSY, authorship?.refusalMessage()
                        ?: "the document is busy; retry when its author is done")
                }
            }
            authorship?.let { owner ->
                if (!owner.externalAllowed()) throw EngineGateRefusal(AUTHORSHIP_BUSY, owner.refusalMessage())
            }
        }
        return engine.call(name, arguments, TOOL_TIMEOUT_MILLIS)
    }

    /**
     * The selector itself: switches the engine, tells every connected client the tool list
     * changed, and answers with how to reach the engine and the rules that govern it.
     */
    private fun selectEngine(arguments: JSONObject): JSONObject {
        val wanted = arguments.optString("engine").trim().lowercase()
        val id = when (wanted) {
            "occt", "python cad", "cad engine" -> "cad"
            else -> wanted
        }
        val engine = byId[id]
            ?: throw EngineGateRefusal(
                INVALID_PARAMS,
                "unknown engine " + wanted + ": choose solidcraft, cad or blender",
            )
        activeId = id
        val answer = EngineCatalogs.selectorAnswer(engine.displayName, engine.reachAndRules(), readinessOf(engine))
        broadcastToolsChanged()
        return EngineToolResults.text(answer)
    }

    private fun statusText(): String {
        val active = activeEngine()
        val lines = surfaces.map { surface ->
            surface.displayName + ": " + (if (surface.isRunning()) "running" else "not started") +
                (if (active?.id == surface.id) " (selected)" else "")
        }
        return "Selected engine: " + (active?.displayName ?: "none") + ". " + lines.joinToString("; ") + "."
    }

    private fun readinessOf(engine: EngineToolSurface): String =
        if (engine.isRunning()) "running" else "not started - start it in the app first"

    /** Every connected client hears that the tool list changed with the selection. */
    private fun broadcastToolsChanged() {
        val notification = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", "notifications/tools/list_changed")
            .toString()
        writers.values.forEach { writer ->
            runCatching { writeReply(writer, notification) }
        }
    }

    private fun authorized(message: JSONObject): Boolean {
        val expected = token?.trim().orEmpty()
        if (expected.isEmpty()) return false
        val supplied = message.optString("token").ifBlank {
            message.optJSONObject("params")?.optString("token").orEmpty()
        }
        return supplied.trim() == expected
    }

    private fun replyResult(id: Any?, result: JSONObject): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("result", result)
        .toString()

    private fun jsonRpcError(id: Any?, code: Int, message: String): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))
        .toString()

    /** A named refusal with its own JSON-RPC code - the one-author rule uses this. */
    class EngineGateRefusal(val code: Int, message: String) : Exception(message)

    companion object {
        /** The port the SolidCraft bridge handed over before this became the whole door. */
        const val DEFAULT_PORT = 9878
        const val DEFAULT_ENGINE = "solidcraft"
        const val TOKEN_FILE = "engines_mcp_token.txt"

        private const val HOST = "127.0.0.1"
        private const val SERVER_NAME = "trioslicer-engines"
        private const val SERVER_VERSION = "1.0.0"
        private const val PROTOCOL_VERSION = "2024-11-05"
        private const val TOOL_TIMEOUT_MILLIS = 300_000L

        private const val PARSE_ERROR = -32700
        private const val INVALID_REQUEST = -32600
        private const val INVALID_PARAMS = -32602
        private const val METHOD_NOT_FOUND = -32601
        private const val INTERNAL_ERROR = -32603
        private const val UNAUTHORIZED = -32001
        private const val ENGINE_UNAVAILABLE = -32002
        private const val ENGINE_TIMEOUT = -32003
        const val AUTHORSHIP_BUSY = -32010

        /** The token file in files/agent/, minted the way the other engines' tokens are. */
        fun tokenFile(filesDir: java.io.File): java.io.File =
            java.io.File(java.io.File(filesDir, "agent"), TOKEN_FILE)

        /** Loads the token, creating a fresh one on first use. Null when the file cannot be written. */
        fun ensureToken(filesDir: java.io.File): String? = runCatching {
            val file = tokenFile(filesDir)
            val existing = file.takeIf { it.isFile }?.readText()?.trim()
            if (!existing.isNullOrEmpty()) {
                existing
            } else {
                val created = UUID.randomUUID().toString().replace("-", "")
                file.parentFile?.mkdirs()
                file.writeText(created)
                file.setReadable(false, false)
                file.setReadable(true, true)
                created
            }
        }.getOrNull()
    }
}
