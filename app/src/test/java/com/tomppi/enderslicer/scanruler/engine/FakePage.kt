package com.tomppi.enderslicer.scanruler.engine

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** A stand-in for the ScanRuler page on the other end of [ScanRulerPageLink]. */
internal class FakePage(private val token: String = "page-token") : WebSocket.Listener {

    sealed class Event {
        data class Text(val text: String) : Event()
        data class Binary(val bytes: ByteArray) : Event()
        data class Closed(val code: Int, val reason: String) : Event()
        data class Failed(val error: Throwable) : Event()
    }

    private val webSocket = AtomicReference<WebSocket>()
    private val events = LinkedBlockingQueue<Event>()
    private val textBuffer = StringBuilder()
    private val binaryBuffer = ByteArrayOutputStream()

    val socket: WebSocket get() = webSocket.get() ?: error("not connected")

    fun connect(port: Int, timeoutSeconds: Long = 5): FakePage {
        val ws = HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(URI.create("ws://127.0.0.1:" + port + "/"), this)
            .get(timeoutSeconds, TimeUnit.SECONDS)
        webSocket.set(ws)
        return this
    }

    override fun onOpen(webSocket: WebSocket) {
        webSocket.request(1)
    }

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        textBuffer.append(data)
        if (last) {
            events.put(Event.Text(textBuffer.toString()))
            textBuffer.clear()
        }
        webSocket.request(1)
        return null
    }

    override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
        val bytes = ByteArray(data.remaining())
        data.get(bytes)
        binaryBuffer.write(bytes)
        if (last) {
            events.put(Event.Binary(binaryBuffer.toByteArray()))
            binaryBuffer.reset()
        }
        webSocket.request(1)
        return null
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
        events.put(Event.Closed(statusCode, reason))
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        events.put(Event.Failed(error))
    }

    fun sendText(text: String) {
        socket.sendText(text, true).get(5, TimeUnit.SECONDS)
    }

    fun sendJson(message: JSONObject) = sendText(message.toString())

    fun sendBinary(bytes: ByteArray) {
        socket.sendBinary(ByteBuffer.wrap(bytes), true).get(5, TimeUnit.SECONDS)
    }

    fun hello(commands: JSONArray = defaultCommands(), token: String = this.token): JSONObject = JSONObject()
        .put(
            "hello",
            JSONObject()
                .put("app", "ScanRuler")
                .put("version", "0.9.4")
                .put("token", token)
                .put("commands", commands)
                .put("plugins", JSONArray().put("reverse"))
                .put("viewport", true)
                .put("hidden", false)
                .put("pageId", "page-1")
                .put("since", "2026-11-01T00:00:00Z"),
        )

    fun awaitText(timeoutMillis: Long = 5_000): String {
        val end = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val remaining = end - System.currentTimeMillis()
            if (remaining <= 0) throw AssertionError("timed out waiting for a page frame")
            when (val event = events.poll(remaining, TimeUnit.MILLISECONDS) ?: throw AssertionError("timed out")) {
                is Event.Text -> return event.text
                is Event.Binary -> continue
                is Event.Closed -> throw AssertionError("closed instead of text: " + event.code + " " + event.reason)
                is Event.Failed -> throw AssertionError("failed instead of text", event.error)
            }
        }
    }

    fun awaitBinary(timeoutMillis: Long = 5_000): ByteArray {
        val end = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val remaining = end - System.currentTimeMillis()
            if (remaining <= 0) throw AssertionError("timed out waiting for page bytes")
            when (val event = events.poll(remaining, TimeUnit.MILLISECONDS) ?: throw AssertionError("timed out")) {
                is Event.Binary -> return event.bytes
                is Event.Text -> continue
                is Event.Closed -> throw AssertionError("closed instead of bytes")
                is Event.Failed -> throw AssertionError("failed instead of bytes", event.error)
            }
        }
    }

    fun awaitClosed(timeoutMillis: Long = 5_000): Event.Closed {
        val end = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val remaining = end - System.currentTimeMillis()
            if (remaining <= 0) throw AssertionError("timed out waiting for close")
            when (val event = events.poll(remaining, TimeUnit.MILLISECONDS) ?: throw AssertionError("timed out")) {
                is Event.Closed -> return event
                is Event.Failed -> throw AssertionError("failed instead of close", event.error)
                else -> continue
            }
        }
    }

    fun close() {
        runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "test over") }
        runCatching { webSocket.get().abort() }
    }

    companion object {
        fun defaultCommands(): JSONArray = JSONArray().apply {
            put(
                JSONObject()
                    .put("name", "workspace.set")
                    .put("title", "Show a workspace")
                    .put("description", "Put a workspace on screen.")
                    .put("input", JSONObject("""{"type":"object","properties":{"workspace":{"type":"string"}},"required":["workspace"]}"""))
                    .put("readOnly", false)
                    .put("returnsFile", false),
            )
            put(
                JSONObject()
                    .put("name", "scan.open")
                    .put("title", "Open a scan")
                    .put("description", "Open a mesh as the scan.")
                    .put("input", JSONObject("""{"type":"object","properties":{"bytes":{"type":"string","format":"binary"},"name":{"type":"string"}}}"""))
                    .put("readOnly", false)
                    .put("returnsFile", false),
            )
            put(
                JSONObject()
                    .put("name", "reverse.note.list")
                    .put("title", "Read the notes")
                    .put("description", "The notes pinned on the part.")
                    .put("input", JSONObject("""{"type":"object","properties":{}}"""))
                    .put("readOnly", true)
                    .put("returnsFile", false),
            )
        }
    }
}
