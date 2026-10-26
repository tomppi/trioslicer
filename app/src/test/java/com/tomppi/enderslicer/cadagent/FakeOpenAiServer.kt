package com.tomppi.enderslicer.cadagent

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * A hermetic OpenAI-compatible endpoint for the in-app agent's tests.
 *
 * It is a real HTTP server on 127.0.0.1, so the client's request shape, bearer header, status
 * handling and body parsing are exercised as written rather than mocked. Each request is
 * recorded (path, Authorization header, body, parsed body) and answered by [responder], which a
 * test scripts per conversation. No real network, no provider, no key.
 */
class FakeOpenAiServer : Closeable {

    data class RecordedRequest(
        val path: String,
        val authorization: String?,
        val body: String,
        val json: JSONObject?,
    )

    data class Reply(
        val status: Int,
        val body: String,
        val contentType: String = "application/json",
    )

    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    private val pool = Executors.newCachedThreadPool()

    /** Every request the app made, in order. */
    val requests = CopyOnWriteArrayList<RecordedRequest>()

    /** The scripted answer; the default is one final message so a chat completes unchanged. */
    @Volatile
    var responder: (RecordedRequest) -> Reply = { Reply(200, finalAnswer("done").body) }

    fun start(): FakeOpenAiServer {
        server.createContext("/") { exchange: HttpExchange -> handle(exchange) }
        server.executor = pool
        server.start()
        return this
    }

    val baseUrl: String get() = "http://127.0.0.1:" + server.address.port + "/v1"

    private fun handle(exchange: HttpExchange) {
        val body = exchange.requestBody.bufferedReader().readText()
        val request = RecordedRequest(
            path = exchange.requestURI.path,
            authorization = exchange.requestHeaders.getFirst("Authorization"),
            body = body,
            json = runCatching { JSONObject(body) }.getOrNull(),
        )
        requests += request
        val reply = responder(request)
        val bytes = reply.body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", reply.contentType)
        exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        server.stop(0)
        pool.shutdownNow()
    }

    companion object {
        /** One final assistant message. */
        fun finalAnswer(text: String): Reply = Reply(200, completionJson(text))

        /** One assistant message asking for a single tool call. */
        fun toolCall(name: String, arguments: String, id: String = "call_1"): Reply {
            val call = JSONObject()
                .put("id", id)
                .put("type", "function")
                .put(
                    "function",
                    JSONObject().put("name", name).put("arguments", arguments),
                )
            val message = JSONObject()
                .put("role", "assistant")
                .put("content", JSONObject.NULL)
                .put("tool_calls", JSONArray().put(call))
            return Reply(200, envelope(message))
        }

        /** A 401 whose body echoes the key, to prove the app does not repeat it. */
        fun unauthorized(echo: String): Reply = Reply(
            401,
            JSONObject().put(
                "error",
                JSONObject().put("message", "Invalid API key: " + echo),
            ).toString(),
        )

        private fun completionJson(text: String): String =
            envelope(JSONObject().put("role", "assistant").put("content", text))

        private fun envelope(message: JSONObject): String = JSONObject()
            .put("id", "chatcmpl-test")
            .put("object", "chat.completion")
            .put("model", "fake-model")
            .put(
                "choices",
                JSONArray().put(
                    JSONObject()
                        .put("index", 0)
                        .put("message", message)
                        .put("finish_reason", "stop"),
                ),
            )
            .toString()
    }
}
