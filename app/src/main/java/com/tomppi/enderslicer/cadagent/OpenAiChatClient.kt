package com.tomppi.enderslicer.cadagent

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * One OpenAI-compatible chat-completions call, over plain HTTP(S).
 *
 * The app's other network clients are [HttpURLConnection] too (the harness, Moonraker,
 * OctoPrint), and for the same reason: no dependency, no dispatcher to keep alive, and a request
 * that can be disconnected from the UI thread. This client is one POST and one parsed reply; the
 * conversation loop and the tool calls live in [CadAgent], so a fake server can answer a scripted
 * turn while the loop is exercised for real.
 *
 * The API key is a credential: it is sent in the Authorization header, never logged, and any
 * text that came back from the endpoint is redacted against it before it becomes an error
 * message - a provider that echoes the key in its error body must not turn that into a log line
 * or a chat message.
 */
class OpenAiChatClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val connectTimeoutMillis: Int = CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = READ_TIMEOUT_MILLIS,
    private val log: (String) -> Unit = {},
) {

    @Volatile
    private var active: HttpURLConnection? = null

    @Volatile
    private var cancelled = false

    /**
     * Ends the request in flight and refuses to start another.
     *
     * A blocking read on a phone has no other cancellation: disconnecting the connection is what
     * unblocks it, and the flag is what turns the resulting IOException into the named
     * "cancelled" rather than a transport failure the sheet would show as an error.
     */
    fun cancel() {
        cancelled = true
        runCatching { active?.disconnect() }
    }

    /**
     * Sends one completion and returns the reply's `message` object.
     *
     * @throws AgentException with a named code for every failure: `invalid-url`,
     *   `unauthorized`, `forbidden`, `endpoint-not-found`, `rate-limited`,
     *   `server-error`, `http-error`, `timeout`, `transport`, `malformed-response`,
     *   `cancelled`.
     */
    fun complete(messages: JSONArray, tools: JSONArray): JSONObject {
        if (cancelled) throw cancelledException()
        val endpoint = endpointFor(baseUrl)
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", false)
        if (tools.length() > 0) {
            body.put("tools", tools).put("tool_choice", "auto")
        }
        val startedAt = System.currentTimeMillis()
        val connection = try {
            (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = connectTimeoutMillis
                readTimeout = readTimeoutMillis
                useCaches = false
                doInput = true
                doOutput = true
                instanceFollowRedirects = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Connection", "close")
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer " + apiKey)
            }
        } catch (error: Throwable) {
            throw transportFailure(error)
        }
        active = connection
        try {
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            // Buffered, not streaming: a 401 answered mid-stream makes HttpURLConnection drop
            // the response body ("cannot retry ... in streaming mode"), and this class needs
            // that body - it carries the provider's own sentence about the key.
            connection.outputStream.use { output -> output.write(bytes) }
            val status = connection.responseCode
            val text = readBody(connection, success = status in 200..299)
            log(
                "chat completion: HTTP " + status + " in " +
                    (System.currentTimeMillis() - startedAt) + " ms",
            )
            if (status !in 200..299) throw httpFailure(status, text)
            return assistantMessage(text)
        } catch (error: AgentException) {
            throw error
        } catch (error: SocketTimeoutException) {
            if (cancelled) throw cancelledException()
            throw AgentException(
                "timeout",
                "The endpoint did not answer within " + (readTimeoutMillis / 1000) + " s. " +
                    "A local model that is loading can be slow once; a hosted one that takes " +
                    "this long is not answering - check the base URL.",
                error,
            )
        } catch (error: IOException) {
            if (cancelled) throw cancelledException()
            throw transportFailure(error)
        } finally {
            active = null
            runCatching { connection.disconnect() }
        }
    }

    private fun transportFailure(error: Throwable): AgentException {
        val reason = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        return AgentException(
            "transport",
            "Could not reach the endpoint (" + redact(reason) + "). Check the base URL and that " +
                "this phone has a route to it.",
            error,
        )
    }

    private fun cancelledException(): AgentException =
        AgentException("cancelled", "Stopped")

    /**
     * `<base>/chat/completions`, accepting a base URL that already names the endpoint.
     *
     * A user pasting `https://api.openai.com/v1` and one pasting the full
     * `.../v1/chat/completions` should both work: appending the path twice is the mistake this
     * avoids, and it is the one a paste makes.
     */
    internal fun endpointFor(baseUrl: String): String {
        val address = baseUrl.trim().trimEnd('/')
        val problem = AgentSettings(baseUrl = address).baseUrlProblem()
        if (problem != null) {
            throw AgentException("invalid-url", "The endpoint's base URL cannot be used: " + problem + ".")
        }
        return if (address.endsWith("/chat/completions")) address else address + "/chat/completions"
    }

    /**
     * One response body, under a hard cap.
     *
     * The read timeout bounds idle time, not length: a wrong host or a captive portal can answer
     * slowly forever, and buffering that is an OOM on a phone. 16 MiB is far past any legitimate
     * completion and small enough to refuse before it hurts.
     */
    internal fun readAll(stream: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = stream.read(chunk)
            if (read <= 0) break
            total += read
            if (total > MAX_RESPONSE_BYTES) {
                throw AgentException(
                    "malformed-response",
                    "The endpoint's answer exceeded the " + (MAX_RESPONSE_BYTES / (1024 * 1024)) +
                        " MB limit; the address is not answering as a chat-completions endpoint.",
                )
            }
            buffer.write(chunk, 0, read)
        }
        return buffer.toByteArray()
    }

    private fun readBody(connection: HttpURLConnection, success: Boolean): String {
        val declared = connection.contentLengthLong
        if (declared > MAX_RESPONSE_BYTES) {
            throw AgentException(
                "malformed-response",
                "The endpoint's answer declares " + declared + " bytes, past the " +
                    (MAX_RESPONSE_BYTES / (1024 * 1024)) + " MB limit.",
            )
        }
        val stream = (if (success) connection.inputStream else connection.errorStream) ?: return ""
        val bytes = stream.use { readAll(it) }
        // A body shorter than the Content-Length it announced means the connection died
        // mid-answer. That is a transport failure, not a malformed endpoint: naming it transport
        // is what lets the one safe retry re-send the request, and what stops a half answer
        // being parsed as if the endpoint had answered nonsense.
        if (declared >= 0 && bytes.size.toLong() != declared) {
            throw AgentException(
                "transport",
                "The endpoint's answer ended after " + bytes.size + " of " + declared +
                    " bytes - the connection died mid-answer.",
                IOException("premature end of response body"),
            )
        }
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * The reply's message, or a named error saying what was wrong with the reply.
     *
     * A 200 is not proof that an OpenAI-compatible endpoint answered: a proxy login page, a
     * plain /completions endpoint and a truncated body all arrive this way, and each gets its
     * own sentence instead of an empty answer in the chat.
     */
    private fun assistantMessage(body: String): JSONObject {
        val parsed = runCatching { JSONObject(body) }.getOrNull()
            ?: throw AgentException(
                "malformed-response",
                "The endpoint answered HTTP 200 with something that is not JSON: \"" +
                    redact(body).take(160) + "\". A chat-completions endpoint answers JSON.",
            )
        val choices = parsed.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            val error = parsed.opt("error")
            if (error != null) {
                throw AgentException(
                    "http-error",
                    "The endpoint returned an error instead of a completion: " + serverMessage(body),
                )
            }
            throw AgentException(
                "malformed-response",
                "The endpoint answered with no choices. Is the base URL the API root of an " +
                    "OpenAI-compatible /chat/completions endpoint?",
            )
        }
        val first = choices.optJSONObject(0)
            ?: throw AgentException("malformed-response", "The endpoint's first choice is not an object.")
        val message = first.optJSONObject("message")
        if (message == null) {
            if (first.opt("text") != null) {
                throw AgentException(
                    "malformed-response",
                    "The reply looks like a /completions answer, not /chat/completions; this app " +
                        "needs a chat-completions endpoint.",
                )
            }
            throw AgentException("malformed-response", "The reply has no message object.")
        }
        return message
    }

    /** The HTTP status, in the words the user can act on. */
    private fun httpFailure(status: Int, body: String): AgentException {
        val server = serverMessage(body)
        val detail = if (server.isBlank()) "" else " The endpoint said: " + server
        return when (status) {
            401 -> AgentException(
                "unauthorized",
                "The endpoint refused the API key (HTTP 401). Check the key in the CAD agent " +
                    "settings." + detail,
            )
            403 -> AgentException(
                "forbidden",
                "The endpoint refused this request (HTTP 403). The key may not have access to " +
                    "the model \"" + model + "\"." + detail,
            )
            404 -> AgentException(
                "endpoint-not-found",
                "The endpoint was not found (HTTP 404). The base URL is the API root - e.g. " +
                    "https://api.openai.com/v1 - and the app appends /chat/completions." + detail,
            )
            400 -> {
                // A provider says "context length" / "maximum context" in its own message. Name
                // the real cause instead of leaving "HTTP 400" for the user to decode: the fix
                // is a new conversation or a smaller request, not a different key or URL.
                val contextLimit = CONTEXT_LENGTH_MARKERS.any { marker ->
                    server.contains(marker, ignoreCase = true)
                }
                if (contextLimit) {
                    AgentException(
                        "context-length",
                        "The request no longer fits the model's context window (HTTP 400). Start " +
                            "a new conversation or ask for one smaller change." + detail,
                    )
                } else {
                    AgentException(
                        "http-error",
                        "The endpoint refused the request as malformed (HTTP 400)." + detail,
                    )
                }
            }
            429 -> AgentException(
                "rate-limited",
                "The endpoint is rate limiting (HTTP 429). Wait a moment and ask again." + detail,
            )
            in 500..599 -> AgentException(
                "server-error",
                "The endpoint failed (HTTP " + status + "). That is not this app's error; try " +
                    "again." + detail,
            )
            else -> AgentException("http-error", "The endpoint returned HTTP " + status + "." + detail)
        }
    }

    /**
     * The endpoint's own error sentence, if its body carries one, redacted and bounded.
     *
     * Redaction matters here specifically: a provider echoing the Authorization header in a 401
     * body would otherwise put the key into the sheet's error line and into any log that line
     * reaches. The key never leaves this class in plain text.
     */
    private fun serverMessage(body: String): String {
        val parsed = runCatching { JSONObject(body) }.getOrNull()
        val raw = when {
            parsed == null -> body
            else -> parsed.optJSONObject("error")?.optString("message")
                ?.takeIf { it.isNotBlank() }
                ?: (parsed.opt("error") as? String)
                ?: parsed.optString("message").takeIf { it.isNotBlank() }
                ?: ""
        }
        return redact(raw).replace('\n', ' ').trim().take(300)
    }

    /** Removes the key from anything that might be shown or logged. */
    internal fun redact(text: String): String {
        if (apiKey.isBlank()) return text
        return text.replace(apiKey, "[key]")
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 20_000
        private const val READ_TIMEOUT_MILLIS = 180_000

        internal const val MAX_RESPONSE_BYTES = 16L * 1024L * 1024L

        /**
         * How providers word a context-length refusal, so a 400 can be named for what it is.
         * OpenAI, DeepSeek and local servers all say one of these in `error.message`.
         */
        private val CONTEXT_LENGTH_MARKERS = listOf(
            "context length",
            "context_length",
            "maximum context",
            "context window",
            "too many tokens",
            "token limit",
        )
    }
}
