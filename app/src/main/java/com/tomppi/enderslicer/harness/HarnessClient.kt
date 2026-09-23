package com.tomppi.enderslicer.harness

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Client for the DeepSeek harness HTTP API.
 *
 * Every shape here was verified against a live harness rather than inferred,
 * because the conventions are not guessable:
 *
 *  - **Routes use slashes.** `/api/session/list`, not `/api/session.list`. The
 *    dot form 404s, and the envelope's `method` must match the endpoint string
 *    exactly - the gateway rejects any mismatch by name.
 *  - **The payload is double-wrapped:** `{ "args": { <field>: { ... } } }`.
 *    A bare object is refused with "must contain exactly one plain-object args
 *    field", and the field is named per endpoint (`_request` for session/list,
 *    `request` for create and prompt).
 *  - **Nothing is returned unless `result.ok`** - failures arrive as a
 *    structured error beside it, not as an HTTP status.
 *
 * Authentication exchanges a launch token for a session cookie, and the app
 * holds both halves. The harness prints a `?token=…` URL exactly once, at
 * startup; loading that URL against the same origin answers a 303 whose
 * Set-Cookie is a 30-day session. The token is only ever what the user pasted,
 * and the cookie is kept for the next launch because it outlives a harness
 * restart while the token does not. Neither is read from a public asset: the
 * `auth.json` this client used to read handed a session to anyone who could
 * reach the port.
 */
class HarnessClient(
    private val baseUrl: String,
    /** Launch token from the harness's printed URL; the app's only credential. */
    private val launchToken: String = "",
    /** Set only once the user accepted plain HTTP to a machine other than this one. */
    private val allowCleartext: Boolean = false,
    initialCookie: String? = null,
) {

    /** Session cookie from the token exchange; every later call carries it. */
    @Volatile
    var cookie: String? = initialCookie
        private set

    init {
        require(baseUrl.isNotBlank()) { "Harness address is empty" }
        // An http address on the local machine is not observable from anywhere
        // else; one on the network is, and the cookie plus every prompt would
        // travel in the clear. The UI asks first and says so; this is the same
        // decision enforced where the request is actually made.
        if (isUnencryptedRemote(baseUrl) && !allowCleartext) {
            throw HarnessException(
                "Refusing to send the harness session over plain HTTP to another machine",
                "insecure-transport",
            )
        }
    }

    /**
     * Establishes the session cookie, reusing one that still works.
     *
     * @returns the cookie every later call carries.
     * @throws HarnessException when no launch token was ever pasted, or when
     *   neither the stored cookie nor the token is accepted.
     */
    fun authenticate(): String {
        cookie?.let { if (indexAcceptsCookie()) return it }
        cookie = null
        if (launchToken.isBlank()) {
            throw HarnessException(
                "No launch token for this harness yet. Paste the ?token=… URL the " +
                    "launcher printed into the address field, then connect.",
                "auth",
            )
        }
        val exchanged = redeem(launchToken) ?: throw HarnessException(
            "The harness refused the launch token. It mints a new one every time it " +
                "starts, so paste the launch URL from the launcher's latest run.",
            "auth",
        )
        cookie = exchanged
        return exchanged
    }

    /**
     * One Remote call.
     *
     * @param endpoint - slash form, e.g. `session/list`.
     * @param field - the args field this endpoint names, e.g. `_request`.
     * @param request - the request body for that field.
     * @throws HarnessException on a refused call or a transport failure.
     */
    fun call(endpoint: String, field: String, request: JSONObject): JSONObject {
        val rpcId = UUID.randomUUID().toString()
        val envelope = JSONObject()
            .put("type", "client-request")
            .put("rpcId", rpcId)
            .put("method", endpoint)
            .put(
                "payload",
                JSONObject().put("args", JSONObject().put(field, request)),
            )
        val body = post(
            path = "/api/$endpoint",
            contentType = "application/json; charset=utf-8",
            bytes = envelope.toString().toByteArray(Charsets.UTF_8),
        )
        val parsed = JSONObject(body)
        val echoed = parsed.optString("rpcId")
        check(echoed == rpcId) { "harness rpcId mismatch for $endpoint: sent $rpcId, got $echoed" }
        val result = parsed.optJSONObject("result") ?: JSONObject()
        if (!result.optBoolean("ok")) {
            val error = result.optJSONObject("error")
            throw HarnessException(
                error?.optString("message")?.takeIf(String::isNotEmpty)
                    ?: "Harness refused $endpoint",
                error?.optString("code").orEmpty(),
            )
        }
        return result.optJSONObject("value") ?: JSONObject()
    }

    /**
     * The directory the harness suggests rooting new sessions in.
     *
     * The address says where the harness is; this says where a client should work,
     * which a freshly installed app has no way to know. The launcher publishes it
     * as a public asset of the same origin - a path, not a credential.
     *
     * @returns the suggested path, or an empty string when the harness publishes none -
     *   an older launcher, or a server started by hand, answers 404 and that is not an
     *   error: the caller keeps the workspace it already had.
     */
    fun suggestedWorkspace(): String =
        runCatching { workspaceFromDefaults(get("${origin()}/defaults.json")) }.getOrDefault("")

    /** Sessions known to the harness. */
    fun listSessions(): JSONObject = call("session/list", "_request", JSONObject())

    /**
     * Creates a session, optionally rooted at [cwd].
     *
     * @returns the new `sessionId`.
     */
    fun createSession(cwd: String? = null): String {
        val request = JSONObject()
        cwd?.let { request.put("cwd", it) }
        return call("session/create", "request", request).optString("sessionId")
            .takeIf(String::isNotEmpty)
            ?: throw HarnessException("session/create returned no sessionId", "shape")
    }

    /**
     * One backwards page of a session's log, with each message verbatim.
     *
     * `session/list` carries only the `turnOutline` projection, which clips
     * every turn to roughly a hundred characters - enough to drive a chat, not
     * enough to read an answer that runs to several thousand. This reads the
     * same log through `session/page`.
     *
     * @param throughSeq inclusive log cut. `session/list` reports it as
     *   `projections.asOfSeq`. It is required: passing `-1` collapses the
     *   range to nothing rather than meaning "latest".
     */
    fun pageMessages(sessionId: String, throughSeq: Long, maxMessages: Int): JSONObject {
        val request = JSONObject()
            .put("address", JSONObject().put("kind", "session").put("sessionId", sessionId))
            .put("throughSeq", throughSeq)
            .put("maxMessages", maxMessages)
        return call("session/page", "request", request)
    }

    /**
     * Stops a session's running turn.
     *
     * Accepting the call is not the same as stopping: the harness acknowledges
     * before the turn unwinds, and this only ever stops the *agent* - never the
     * processes it launched. A background job that finishes afterwards delivers
     * a completion notice, and a notice into a session with no live turn starts
     * a new one.
     */
    fun cancelSession(sessionId: String): JSONObject =
        call("session/cancel", "request", JSONObject().put("sessionId", sessionId))

    /**
     * Sends a message into a session.
     *
     * `requestId` is client-minted and required - omitting it fails boundary
     * validation with no hint as to which field is missing.
     */
    fun prompt(sessionId: String, text: String, receiptId: String? = null): JSONObject {
        val request = JSONObject()
            .put("requestId", UUID.randomUUID().toString())
            .put("sessionId", sessionId)
            .put("mode", "queue")
            .put("content", promptContent(text, receiptId))
        return call("session/prompt", "request", request)
    }

    /**
     * Stages raw bytes against a session, which is how an image reaches the
     * harness. A plain HTTP route, not a Remote call: it refuses anything that
     * is not `application/octet-stream`.
     */
    fun uploadFile(sessionId: String, name: String, bytes: ByteArray): JSONObject {
        val query = "?sessionId=" + encode(sessionId) + "&name=" + encode(name)
        val response = post(
            path = "/api/session/uploadFileBinary$query",
            contentType = "application/octet-stream",
            bytes = bytes,
        )
        val parsed = JSONObject(response)
        if (!parsed.optBoolean("ok")) {
            val error = parsed.optJSONObject("error")
            throw HarnessException(
                error?.optString("message")?.takeIf(String::isNotEmpty) ?: "Upload rejected",
                error?.optString("code").orEmpty(),
            )
        }
        return parsed.optJSONObject("value") ?: JSONObject()
    }

    /**
     * Exchanges a launch token for the session cookie.
     *
     * The token travels as the only query parameter of the index URL, which is
     * the one request shape that performs the exchange. Redirects are not
     * followed: the cookie arrives on the 303 itself, and the destination page
     * is of no interest to an API client.
     *
     * @returns the cookie, or null when the harness did not answer with one.
     */
    private fun redeem(token: String): String? {
        return try {
            val connection = open("/?token=" + encode(token), "GET")
            try {
                if (connection.responseCode == 303) cookieOf(connection) else null
            } finally {
                connection.disconnect()
            }
        } catch (error: java.net.MalformedURLException) {
            // Deliberately not the exception message: it quotes the whole URL,
            // token included, and the token is the harness's entire credential.
            throw HarnessException("The harness address is not a usable URL", "auth")
        } catch (error: java.io.IOException) {
            throw HarnessException("Could not reach the harness: " + detail(error), "auth")
        }
    }

    /**
     * Asks the index for its page with whatever cookie this client holds.
     *
     * A valid cookie is served the index; anything else gets the harness 401,
     * the same gate every API call passes through. This is the cheapest request
     * that answers whether the stored cookie is still good, which is what
     * decides whether the launch token has to be spent at all.
     */
    private fun indexAcceptsCookie(): Boolean = runCatching {
        val connection = open("/", "GET")
        try {
            connection.responseCode == 200
        } finally {
            connection.disconnect()
        }
        // A harness that cannot be reached at all is not a "no": falling through
        // spends the launch token, and that path reports the network error.
    }.getOrDefault(false)

    /** One diagnostic line that can never quote the launch token. */
    private fun detail(error: Throwable): String =
        error.message.orEmpty().replace(launchToken, "(token)").take(120).ifBlank { "no response" }

    private fun get(path: String): String {
        val connection = open(path, "GET")
        return try {
            val code = connection.responseCode
            val text = readBody(connection, success = code in 200..299)
            if (code !in 200..299) throw HarnessException("Harness HTTP $code for $path", "http-$code")
            text
        } finally {
            connection.disconnect()
        }
    }

    private fun post(path: String, contentType: String, bytes: ByteArray): String {
        val connection = open(path, "POST")
        return try {
            connection.setRequestProperty("Content-Type", contentType)
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            val text = readBody(connection, success = code in 200..299)
            if (code !in 200..299) {
                throw HarnessException("Harness HTTP $code for $path: " + text.take(200), "http-$code")
            }
            text
        } finally {
            connection.disconnect()
        }
    }

    private fun open(path: String, method: String): HttpURLConnection {
        val url = if (path.startsWith("http")) URL(path) else URL(baseUrl.trimEnd('/') + path)
        require(url.protocol == "http" || url.protocol == "https") {
            "Harness URL must be http or https"
        }
        return (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            // Read the 303 rather than chasing it; the cookie is on the response.
            instanceFollowRedirects = false
            useCaches = false
            doInput = true
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Connection", "close")
            cookie?.let { setRequestProperty("Cookie", it) }
        }
    }

    private fun cookieOf(connection: HttpURLConnection): String? =
        connection.headerFields
            ?.entries
            ?.firstOrNull { it.key?.equals("Set-Cookie", ignoreCase = true) == true }
            ?.value
            ?.firstOrNull()
            ?.substringBefore(';')

    /**
     * One response body, refusing anything past [MAX_RESPONSE_BYTES].
     *
     * The declared Content-Length is checked first so an oversized body is
     * refused before a byte is buffered, but it is not trusted on its own: a
     * chunked or lying sender is what the capped read below is for.
     */
    private fun readBody(connection: HttpURLConnection, success: Boolean): String {
        val declared = connection.contentLengthLong
        if (declared > MAX_RESPONSE_BYTES) {
            throw responseTooLarge("it declares $declared bytes")
        }
        val stream = (if (success) connection.inputStream else connection.errorStream) ?: return ""
        return stream.use { readAll(it) }
    }

    /**
     * Reads a stream into a string under a hard size cap.
     *
     * READ_TIMEOUT_MILLIS bounds idle time, not length, so a wrong host, a
     * captive portal or a hostile server can keep a request answered forever,
     * and buffering that is an OOM. OctoPrint's client caps the same read for
     * the same reason.
     */
    internal fun readAll(stream: java.io.InputStream): String {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = stream.read(chunk)
            if (read <= 0) break
            total += read
            if (total > MAX_RESPONSE_BYTES) throw responseTooLarge("it reached $total bytes")
            buffer.write(chunk, 0, read)
        }
        return buffer.toString("UTF-8")
    }

    /**
     * The harness was not the one answering.
     *
     * The size is the evidence: an API reply is a JSON envelope, and the
     * largest legitimate one is a page of long agent messages.
     */
    private fun responseTooLarge(detail: String): HarnessException = HarnessException(
        "Harness response exceeded the ${MAX_RESPONSE_BYTES / (1024 * 1024)} MB limit ($detail); " +
            "the address is not answering as the harness",
        "http-response-too-large",
    )

    private fun origin(): String {
        val url = URL(baseUrl.trimEnd('/'))
        val port = if (url.port == -1) "" else ":" + url.port
        return url.protocol + "://" + url.host + port
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 60_000

        /** Loopback in IPv4, which plain HTTP cannot be observed from elsewhere. */
        private val LOOPBACK_V4 = Regex("^127\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}$")

        /**
         * True when [address] would carry the session in the clear to another machine.
         *
         * Plain HTTP to this device cannot be observed from anywhere else, so
         * loopback needs nobody's permission. Every other host can be watched -
         * a shared network, or anything that can answer for the name - and the
         * session cookie is a 30-day credential, so the user is asked first and
         * the answer is kept per address.
         */
        fun isUnencryptedRemote(address: String): Boolean {
            val url = runCatching { URL(address.trim()) }.getOrNull() ?: return false
            if (!url.protocol.equals("http", ignoreCase = true)) return false
            val host = url.host?.lowercase()?.trim('[', ']')?.takeIf(String::isNotEmpty) ?: return false
            return host != "localhost" && host != "::1" && !LOOPBACK_V4.matches(host)
        }

        /**
         * Sixteen times the biggest reply the app asks for.
         *
         * A page of 120 long agent messages is a few megabytes; anything past
         * 16 MiB is not a harness envelope, and buffering it on a phone heap is
         * the failure this bound exists to prevent.
         */
        private const val MAX_RESPONSE_BYTES = 16L * 1024L * 1024L

        /**
         * The content blocks for one prompt: the attachment first, then the text.
         *
         * A staged upload reaches the agent only through a `file` part naming
         * the receipt that `uploadFileBinary` returned. The receipt is
         * single-use - the harness binds it to the prompt that names it - and it
         * is scoped to the session it was uploaded against.
         */
        fun promptContent(text: String, receiptId: String?): JSONArray {
            val content = JSONArray()
            receiptId?.takeIf(String::isNotBlank)?.let {
                content.put(JSONObject().put("type", "file").put("receiptId", it))
            }
            content.put(JSONObject().put("type", "text").put("text", text))
            return content
        }

        /**
         * The workspace named by a `defaults.json` document.
         *
         * Blank when the document names none, names only whitespace, or is not JSON at
         * all - the three ways a server that does not publish client defaults answers.
         */
        fun workspaceFromDefaults(document: String): String =
            runCatching { JSONObject(document).optString("workspace").trim() }.getOrDefault("")
    }
}

/** A harness call that failed, with the harness's own error code when it gave one. */
class HarnessException(message: String, val code: String = "") : Exception(message)
