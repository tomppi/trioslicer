package com.tomppi.enderslicer.scanruler.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The app-side MCP server for the embedded ScanRuler page, on 127.0.0.1:9879.
 *
 * Same boundary as the Blender (9876), CAD (9877) and SolidCraft (9878) bridges: every
 * line-delimited JSON-RPC 2.0 message carries the bearer token from
 * files/scanruler/scanruler_mcp_token.txt, the socket is loopback-only, and AgentBridges
 * publishes it on the "Reachable on the network" switch. One external agent connection at a
 * time; a second is refused by name rather than queued.
 *
 * tools/list is the page's own command catalogue (hello.commands), mapped by the upstream rule
 * dots -> underscores with its JSON Schema passed through, plus scanruler_status and
 * scanruler_wait. tools/call forwards to the page link and maps results/errors; page progress
 * becomes notifications/progress when the caller sent a progressToken. File bytes coming back
 * from the page are written to the app's MediaStore Download/ScanRuler collection and returned
 * as a content URI - never an arbitrary phone path. Input file bytes are not accepted from an
 * external agent (the app's file policy): scans come from the plate or the user's SAF picker.
 */
internal class ScanRulerMcpServer(
    private val token: String?,
    private val pageLink: ScanRulerPageLink,
    private val status: () -> ScanRulerEmbeddedState,
    private val binarySink: ((ByteArray, String, String) -> JSONObject)? = null,
    private val port: Int = DEFAULT_PORT,
    private val log: (String) -> Unit = {},
) : Closeable {

    companion object {
        /** Blender 9876, CAD 9877, SolidCraft 9878; ScanRuler takes the next one. */
        const val DEFAULT_PORT = 9879
        private const val HOST = "127.0.0.1"
        private const val SERVER_VERSION = "1.15.0"
        private const val TOOL_TIMEOUT_MILLIS = 300_000L
        private const val REQUEST_TIMEOUT_MILLIS = 60_000L
        private const val PARSE_ERROR = -32700
        private const val INVALID_REQUEST = -32600
        private const val METHOD_NOT_FOUND = -32601
        private const val INVALID_PARAMS = -32602
        private const val INTERNAL_ERROR = -32603
        private const val UNAUTHORIZED = -32001
        private const val ENGINE_UNAVAILABLE = -32002
        private const val ENGINE_TIMEOUT = -32003
        const val ALREADY_CONNECTED = -32010
    }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private var acceptThread: Thread? = null
    private val active = AtomicReference<Socket?>()
    private val notifier = AtomicReference<((JSONObject) -> Unit)?>(null)

    /** The port actually bound after start(); 0 when stopped. */
    @Volatile var boundPort: Int = 0
        private set

    @Synchronized
    fun start() {
        if (running) return
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(InetAddress.getByName(HOST), port))
        serverSocket = socket
        boundPort = socket.localPort
        running = true
        acceptThread = thread(name = "scanruler-mcp-accept", isDaemon = true) {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (_: Throwable) {
                    break
                }
                if (!active.compareAndSet(null, client)) {
                    runCatching {
                        client.getOutputStream().bufferedWriter(StandardCharsets.UTF_8).use {
                            it.write(jsonRpcError(null, ALREADY_CONNECTED, busyMessage()))
                            it.newLine()
                        }
                    }
                    runCatching { client.close() }
                    continue
                }
                thread(name = "scanruler-mcp-client", isDaemon = true) { serve(client) }
            }
        }
        log("ScanRuler MCP listening on 127.0.0.1:" + boundPort)
    }

    override fun close() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        boundPort = 0
        runCatching { active.getAndSet(null)?.close() }
        acceptThread = null
    }

    private fun serve(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val writer = socket.getOutputStream().bufferedWriter(StandardCharsets.UTF_8)
            val writeLock = Any()
            fun send(line: String) {
                synchronized(writeLock) {
                    writer.write(line)
                    writer.newLine()
                    writer.flush()
                }
            }
            notifier.set { message -> send(message.toString()) }
            while (running) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val reply = handleLine(line) { notification -> send(notification.toString()) }
                if (reply != null) send(reply)
            }
        } catch (_: Throwable) {
            // A client that vanished is normal.
        } finally {
            notifier.set(null)
            active.compareAndSet(socket, null)
            runCatching { socket.close() }
        }
    }

    /** Tells the connected MCP client the page has just named its commands, so it can re-list. */
    fun notifyToolsChanged() {
        notifier.get()?.invoke(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("method", "notifications/tools/list_changed"),
        )
    }

    /**
     * One request line in, at most one reply line out. Public so the token, tool mapping,
     * one-at-a-time and error contracts are tested without a socket.
     */
    fun handleLine(line: String, notify: (JSONObject) -> Unit = {}): String? {
        val message = try {
            JSONObject(line)
        } catch (_: Throwable) {
            return jsonRpcError(null, PARSE_ERROR, "parse error: one JSON object per line")
        }
        if (!authorized(message)) {
            return jsonRpcError(
                message.opt("id"),
                UNAUTHORIZED,
                "unauthorized: send the token the app wrote to files/scanruler/" +
                    ScanRulerTokens.MCP_TOKEN_FILE,
            )
        }
        val method = message.optString("method")
        val params = message.optJSONObject("params") ?: JSONObject()
        val id = message.opt("id")
        if (id == null) return null
        return try {
            when (method) {
                "initialize" -> replyResult(id, initializeResult())
                "ping" -> replyResult(id, JSONObject())
                "tools/list" -> replyResult(id, JSONObject().put("tools", pageLink.mcpTools()))
                "tools/call" -> handleToolCall(id, params, notify)
                else -> jsonRpcError(id, METHOD_NOT_FOUND, "unknown method: " + method)
            }
        } catch (timeout: ScanRulerCommandException) {
            if (timeout.code == "timeout") {
                jsonRpcError(id, ENGINE_TIMEOUT, timeout.message ?: "the ScanRuler page did not answer")
            } else {
                jsonRpcError(id, ENGINE_UNAVAILABLE, timeout.message ?: "the ScanRuler page refused the call")
            }
        } catch (link: ScanRulerLinkException) {
            jsonRpcError(id, ENGINE_UNAVAILABLE, link.message ?: "the ScanRuler page is not connected")
        } catch (error: Throwable) {
            jsonRpcError(id, INTERNAL_ERROR, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun initializeResult(): JSONObject = JSONObject()
        .put("protocolVersion", "2024-11-05")
        .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", true)))
        .put("serverInfo", JSONObject().put("name", "scanruler").put("version", SERVER_VERSION))

    private fun handleToolCall(id: Any?, params: JSONObject, notify: (JSONObject) -> Unit): String {
        val arguments = params.optJSONObject("arguments") ?: JSONObject()
        val progressToken = params.opt("progressToken")
            ?: params.optJSONObject("_meta")?.opt("progressToken")
        val name = params.optString("name")
        when (name) {
            "scanruler_status" -> return replyResult(id, toolResult(statusResult()))
            "scanruler_wait" -> {
                val timeout = arguments.optLong("timeoutMs", 20_000L).coerceIn(0L, 120_000L)
                if (!pageLink.isPaired) pageLink.awaitPage(timeout)
                return replyResult(id, toolResult(statusResult()))
            }
        }
        val command = pageLink.catalog.firstOrNull { it.toolName == name }
            ?: return jsonRpcError(id, INVALID_PARAMS, "unknown ScanRuler tool: " + name)
        val prepared = prepareInput(arguments)
        val onProgress: (String) -> Unit = { text ->
            if (progressToken != null) {
                notify(
                    JSONObject()
                        .put("jsonrpc", "2.0")
                        .put("method", "notifications/progress")
                        .put("params", JSONObject().put("progressToken", progressToken).put("message", text)),
                )
            }
        }
        val result = pageLink.run(
            name = command.name,
            input = prepared.input,
            binaryPath = prepared.binaryPath,
            timeoutMillis = if (command.name == "scan.open") 10 * 60_000L else TOOL_TIMEOUT_MILLIS,
            onProgress = onProgress,
        )
        var files = 0
        val sink = binarySink
        if (sink != null) files = rewriteBinaryValues(result, sink)
        val toolResult = toolResult(result.toString())
        if (files > 0) toolResult.put("structuredContent", result)
        return replyResult(id, toolResult)
    }

    private data class PreparedInput(val input: JSONObject, val binaryPath: String?)

    private fun prepareInput(arguments: JSONObject): PreparedInput {
        val input = JSONObject(arguments.toString())
        val base64 = input.optString("bytesBase64").takeIf { it.isNotBlank() }
            ?: return PreparedInput(input, null)
        input.remove("bytesBase64")
        input.put("bytes", Base64.getDecoder().decode(base64))
        return PreparedInput(input, "input.bytes")
    }

    private fun rewriteBinaryValues(
        result: JSONObject,
        sink: (ByteArray, String, String) -> JSONObject,
    ): Int {
        var count = 0
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> {
                    val keys = value.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val child = value.opt(key)
                        if (child is ByteArray) {
                            val name = value.optString("name").ifBlank { "scanruler-export" }
                            val mime = value.optString("mimeType").ifBlank { "application/octet-stream" }
                            value.put(key, sink(child, name, mime))
                            count++
                        } else {
                            visit(child)
                        }
                    }
                }
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
                else -> {}
            }
        }
        visit(result)
        return count
    }

    private fun statusResult(): String = JSONObject()
        .put("phase", status().phase.name.lowercase())
        .put("paired", status().paired)
        .put("toolsReady", status().toolsReady)
        .put("toolCount", status().toolCount)
        .put("embedded", status().embedded)
        .put("pagePort", status().pagePort)
        .put("websocketPort", status().websocketPort)
        .put("message", status().message)
        .toString()

    private fun toolResult(text: String): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .put("isError", false)

    private fun authorized(message: JSONObject): Boolean {
        val expected = token?.trim().orEmpty()
        if (expected.isEmpty()) return false
        val supplied = message.optString("token").ifBlank {
            message.optJSONObject("params")?.optString("token").orEmpty()
        }
        return supplied.trim() == expected
    }

    private fun busyMessage(): String =
        "another external agent is already connected to ScanRuler; only one may drive the page at a time"

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
}