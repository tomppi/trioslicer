package com.tomppi.enderslicer.cadagent

import org.json.JSONArray
import org.json.JSONObject

/**
 * One in-process tool call, run by the app. Implemented by
 * [com.tomppi.enderslicer.solidcraft.SolidCraftAgentHost] over the running engine session; a fake
 * implements it in tests.
 *
 * The contract is one-way: [call] returns what the model receives and never throws. A tool
 * failure is a result for the model, not an exception for the UI - the model is the thing that
 * can read the engine's sentence and try something else. [Result.failed] is what turns the same
 * call into a red row on a chat surface; [Result.outcome] is the row's one line when the host
 * already knows it (the web tools do), otherwise [AgentToolSummary] reads it from the text.
 */
fun interface AgentToolHost {
    fun call(name: String, arguments: JSONObject): Result

    /** What a tool call produced: the model's text, whether it failed, and its row's line. */
    data class Result(
        val text: String,
        val failed: Boolean = false,
        val outcome: String? = null,
    )
}

/**
 * The in-app agent: a bounded tool-calling loop over an OpenAI-compatible endpoint.
 *
 * The loop is the boring shape, on purpose: system prompt and prior turns, the user's new
 * message, then repeat - send the conversation, and if the reply asks for tools, run them,
 * append their results and send again; stop when the reply is a message with no tool calls.
 * [maxToolRounds] is the bound: a model that keeps asking for tools stops with a named error
 * instead of looping for the life of the battery.
 *
 * Two tool sources meet here. The engine's tools go through [toolHost] (in process, no bridge);
 * the app's own web_search and web_fetch go through [WebToolClient], which calls the same
 * endpoint with the same key. The web tools are optional: a 401/403/404/405 on their route
 * removes them from the catalogue for the rest of the conversation and leaves one named
 * sentence - never a silent failure, and never a fake empty result.
 *
 * Every call is traced, start and end, through [run]'s `onToolTrace`: that is what a chat
 * surface draws as a tool row while the turn is still running, so the user can watch the agent
 * work instead of guessing from a spinner.
 *
 * Everything the model can see is a [JSONObject] in the OpenAI chat shape. Streaming is not
 * used: a non-streaming call is one request and one answer, and a cancel is a disconnect rather
 * than an abandoned read - fewer states to get wrong in the first cut.
 */
class CadAgent(
    private val toolHost: AgentToolHost,
    private val runbook: String,
    private val maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS,
    /** How long to wait before the one safe retry of a model call. */
    private val retryDelayMillis: Long = RETRY_DELAY_MILLIS,
    private val log: (String) -> Unit = {},
    private val clientFactory: (StoredAgentSettings) -> OpenAiChatClient = { settings ->
        OpenAiChatClient(
            baseUrl = settings.settings.baseUrl,
            apiKey = settings.apiKey,
            model = settings.settings.model,
            log = log,
        )
    },
    private val webClientFactory: (StoredAgentSettings) -> WebToolClient = { settings ->
        WebToolClient(
            baseUrl = settings.settings.baseUrl,
            apiKey = settings.apiKey,
            log = log,
        )
    },
) {

    /** The conversation so far, as text turns only (system is added on every run). */
    private val conversation = mutableListOf<JSONObject>()

    private val lock = Any()

    @Volatile
    private var client: OpenAiChatClient? = null

    @Volatile
    private var webClient: WebToolClient? = null

    @Volatile
    private var cancelled = false

    /** What the endpoint has said about the web tool routes. */
    @Volatile
    var webAvailability: AgentWebTools.Availability = AgentWebTools.Availability.UNKNOWN
        private set

    /** The named sentence for a removed web tool, or null while they are available/unknown. */
    @Volatile
    var webToolsNotice: String? = null
        private set

    /** How many text turns the agent remembers across runs. */
    val rememberedTurns: Int
        get() = synchronized(conversation) { conversation.size }

    /**
     * Ends the run in flight.
     *
     * Safe from any thread: requests in flight are disconnected and the loop stops at the next
     * boundary. A tool call already handed to the engine cannot be interrupted - the engine is
     * mid-boolean - so the cancel reports after it returns, and the design may have changed.
     */
    fun cancel() {
        cancelled = true
        client?.cancel()
        webClient?.cancel()
    }

    /**
     * Forgets the conversation and what the endpoint said about search.
     *
     * Called when the endpoint or the key changes: a different server may well offer the tool
     * routes the previous one refused.
     */
    fun reset() {
        synchronized(conversation) { conversation.clear() }
        webAvailability = AgentWebTools.Availability.UNKNOWN
        webToolsNotice = null
    }

    /**
     * Runs one turn and returns the agent's final message.
     *
     * Blocking: call on a background thread.
     *
     * @param onActivity plain-words lines for the sheet ("searching for …", "reading …"), the
     *   places where the agent leaves the phone for the web.
     * @param onToolTrace called twice per tool call - as it starts (running) and as it ends with
     *   its duration and outcome - so a chat surface can draw the work as it happens. The same
     *   callback is safe to call from a router that marshals to the UI thread; exceptions from
     *   it never break the turn.
     * @param onRetry called when a model call failed with a transport error or a timeout before
     *   any tool ran, and is about to be retried once.
     * @throws AgentException with a named code when the conversation could not continue (not
     *   configured, refused, timeout, malformed reply, cancelled, tool-round limit).
     */
    fun run(
        settings: StoredAgentSettings,
        catalog: AgentToolCatalog,
        userText: String,
        onActivity: (String) -> Unit = {},
        onToolTrace: (AgentToolTrace) -> Unit = {},
        onRetry: (String) -> Unit = {},
    ): String {
        val problem = settings.settings.baseUrlProblem()
        if (problem != null) {
            throw AgentException("not-configured", "The in-app agent has no endpoint yet: " + problem + ".")
        }
        if (settings.settings.model.isBlank()) {
            throw AgentException("not-configured", "The in-app agent has no model name yet. Add one in the CAD agent settings.")
        }
        if (catalog.isEmpty) {
            throw AgentException(
                "no-tools",
                "The CAD engine returned no tools this app can offer, so the agent has nothing " +
                    "to build with. Restart the engine and try again.",
            )
        }
        synchronized(lock) {
            cancelled = false
            val chat = clientFactory(settings)
            client = chat
            val web = if (catalog.includesWebTools) webClientFactory(settings).also { webClient = it } else null
            try {
                return converse(
                    chat = chat,
                    web = web,
                    catalog = catalog,
                    userText = userText,
                    apiKey = settings.apiKey,
                    endpointHost = endpointHost(settings.settings.baseUrl),
                    onActivity = onActivity,
                    onToolTrace = onToolTrace,
                    onRetry = onRetry,
                )
            } finally {
                client = null
                webClient = null
            }
        }
    }

    private fun converse(
        chat: OpenAiChatClient,
        web: WebToolClient?,
        catalog: AgentToolCatalog,
        userText: String,
        apiKey: String,
        endpointHost: String,
        onActivity: (String) -> Unit,
        onToolTrace: (AgentToolTrace) -> Unit,
        onRetry: (String) -> Unit,
    ): String {
        val messages = JSONArray()
        messages.put(
            JSONObject().put("role", "system").put("content", AgentRunbook.systemPrompt(runbook)),
        )
        synchronized(conversation) { conversation.forEach { messages.put(it) } }
        val userMessage = JSONObject().put("role", "user").put("content", userText)
        messages.put(userMessage)

        var rounds = 0
        var callIds = 0
        // What the user-visible error row needs: which model round of this turn failed, and
        // whether it is still safe to re-send the same request.
        var modelRound = 0
        var toolsRunInTurn = 0
        var retried = false
        while (true) {
            if (cancelled) throw AgentException("cancelled", "Stopped")
            // A tool list that follows what the endpoint has told us: once the web routes are
            // known to be missing, the next request no longer carries them.
            val activeCatalog = if (webAvailability == AgentWebTools.Availability.UNAVAILABLE) {
                catalog.withoutWebTools()
            } else {
                catalog
            }
            modelRound++
            val roundStartedAt = System.currentTimeMillis()
            val reply = try {
                chat.complete(messages, activeCatalog.tools)
            } catch (error: AgentException) {
                if (error.code == "cancelled") throw error
                // One safe retry: only for a transport failure or a timeout, only before any
                // tool of this turn has run, and only once. A re-sent request cannot repeat an
                // edit the app has not executed; once a tool has run, the same rule protects the
                // design.
                val safeToRetry = (error.code == "timeout" || error.code == "transport") &&
                    toolsRunInTurn == 0 && !retried
                if (!safeToRetry) {
                    throw namedFailure(
                        error = error,
                        host = endpointHost,
                        round = modelRound,
                        startedAt = roundStartedAt,
                        toolsRunInTurn = toolsRunInTurn,
                        retried = retried,
                    )
                }
                retried = true
                runCatching { onRetry(RETRY_NOTICE) }
                log("chat completion failed (" + error.code + "); retrying once")
                Thread.sleep(retryDelayMillis)
                try {
                    chat.complete(messages, activeCatalog.tools)
                } catch (second: AgentException) {
                    if (second.code == "cancelled") throw second
                    throw namedFailure(
                        error = second,
                        host = endpointHost,
                        round = modelRound,
                        startedAt = roundStartedAt,
                        toolsRunInTurn = toolsRunInTurn,
                        retried = true,
                    )
                }
            }
            val content = (reply.opt("content") as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val toolCalls = reply.optJSONArray("tool_calls")
            if (toolCalls == null || toolCalls.length() == 0) {
                if (content == null) {
                    throw AgentException(
                        "malformed-response",
                        "The endpoint answered with neither a message nor a tool call.",
                    )
                }
                remember(userText, content)
                log("agent answered after " + rounds + " tool round(s)")
                return content
            }
            rounds++
            if (rounds > maxToolRounds) {
                throw AgentException(
                    "tool-round-limit",
                    "The agent ran " + maxToolRounds + " tool rounds without settling on an " +
                        "answer, so it stopped after round " + maxToolRounds + ". You can send " +
                        "one more message - 'continue' - and it will carry on from where it " +
                        "stopped, or split the request into a smaller one.",
                )
            }
            messages.put(assistantEcho(content, toolCalls))
            for (index in 0 until toolCalls.length()) {
                if (cancelled) throw AgentException("cancelled", "Stopped")
                val call = toolCalls.optJSONObject(index) ?: continue
                val id = call.optString("id").takeIf { it.isNotBlank() } ?: ("call_" + index)
                val function = call.optJSONObject("function")
                val name = function?.optString("name")?.trim().orEmpty()
                val argumentsText = when (val arguments = function?.opt("arguments")) {
                    null -> ""
                    is String -> arguments
                    else -> arguments.toString()
                }
                val callId = ++callIds
                val result = traceCall(callId, name, argumentsText, apiKey, onToolTrace) {
                    executeTool(catalog, web, name, argumentsText, onActivity)
                }
                toolsRunInTurn++
                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", id)
                        .put("content", result.text),
                )
                if (cancelled) throw AgentException("cancelled", "Stopped")
            }
        }
    }

    /**
     * The host the error rows name: host and port, never the path or the key.
     *
     * A transcript is copied out of the chat and pasted somewhere else, so "which endpoint was
     * this" has to be in the row itself.
     */
    internal fun endpointHost(baseUrl: String): String {
        val address = baseUrl.trim().trimEnd('/')
        val url = runCatching { java.net.URL(address) }.getOrNull() ?: return "the endpoint"
        val host = url.host?.takeIf { it.isNotBlank() } ?: return "the endpoint"
        return if (url.port == -1) host else host + ":" + url.port
    }

    /**
     * One model-call failure, in the words a pasted transcript can be diagnosed from.
     *
     * The row itself carries the endpoint's host and the model round, because a transcript
     * copied out of the chat has no logcat beside it. A timeout or a transport failure also
     * names how long it waited and says the one thing that helps: a backgrounded app can be
     * frozen by Android, while the screen being off is fine - the turn's own foreground
     * service and wake lock keep the work alive.
     */
    private fun namedFailure(
        error: AgentException,
        host: String,
        round: Int,
        startedAt: Long,
        toolsRunInTurn: Int,
        retried: Boolean,
    ): AgentException {
        if (error.code == "cancelled") return error
        val original = error.message?.takeIf { it.isNotBlank() } ?: "the request failed"
        if (error.code != "timeout" && error.code != "transport") {
            return AgentException(
                error.code,
                "Round " + round + " at " + host + ": " + original,
                error.cause,
            )
        }
        val elapsed = ((System.currentTimeMillis() - startedAt + 999) / 1000).coerceAtLeast(1)
        val what = if (error.code == "timeout") "The endpoint did not answer" else "Could not reach the endpoint"
        val retryPart = when {
            toolsRunInTurn > 0 ->
                " The turn had already changed the design, so it was not retried - re-sending " +
                    "the same request could repeat an edit."
            retried -> " The one retry failed too."
            else -> ""
        }
        return AgentException(
            error.code,
            what + " after " + elapsed + " s (round " + round + " at " + host + ")." +
                retryPart + " " + BACKGROUND_HINT + " (" + original + ")",
            error.cause,
        )
    }

    /**
     * Runs one call between its start and end rows.
     *
     * The rows are emitted around the execution itself, so a slow boolean shows as a running
     * row for as long as it really takes, and a failure shows as an ended red row. The trace
     * callback is wrapped: a UI mistake must not take the agent's turn with it.
     */
    private fun traceCall(
        callId: Int,
        name: String,
        argumentsText: String,
        apiKey: String,
        onToolTrace: (AgentToolTrace) -> Unit,
        execute: () -> AgentToolHost.Result,
    ): AgentToolHost.Result {
        val parsed = objectOf(argumentsText)
        val displayName = AgentToolSummary.displayName(name, parsed)
        val summary = AgentToolSummary.redact(
            AgentToolSummary.arguments(name, parsed, argumentsText),
            apiKey,
        )
        val started = AgentToolTrace(
            callId = callId,
            tool = name,
            name = displayName,
            arguments = summary,
            running = true,
        )
        runCatching { onToolTrace(started) }
        val startedAt = System.currentTimeMillis()
        val result = try {
            execute()
        } catch (error: AgentException) {
            if (error.code == "cancelled") {
                AgentToolHost.Result(TOOL_ERROR + "the turn was stopped.", failed = true, outcome = "stopped")
            } else {
                AgentToolHost.Result(TOOL_ERROR + (error.message ?: "the tool failed"), failed = true)
            }
        } catch (error: Throwable) {
            AgentToolHost.Result(
                TOOL_ERROR + "the app could not run " + name + ": " +
                    (error.message ?: error.javaClass.simpleName),
                failed = true,
            )
        }
        val outcome = AgentToolSummary.redact(
            result.outcome ?: AgentToolSummary.outcome(name, result.text, result.failed),
            apiKey,
        )
        runCatching {
            onToolTrace(
                started.copy(
                    running = false,
                    durationMillis = (System.currentTimeMillis() - startedAt).coerceAtLeast(0),
                    outcome = outcome,
                    failed = result.failed,
                ),
            )
        }
        return result
    }

    /**
     * Runs one tool call.
     *
     * A malformed call is reported *to the model* rather than thrown at the UI: wrong JSON in
     * the arguments, a name the app does not offer, a name it deliberately hides. The model can
     * read that and correct itself on the next round, which is the whole point of the loop; the
     * same call's row is red.
     */
    private fun executeTool(
        catalog: AgentToolCatalog,
        web: WebToolClient?,
        name: String,
        argumentsText: String,
        onActivity: (String) -> Unit,
    ): AgentToolHost.Result {
        if (name.isEmpty()) {
            return AgentToolHost.Result(
                TOOL_ERROR + "the reply contained a tool call with no function name.",
                failed = true,
                outcome = "no function name",
            )
        }
        if (name !in catalog.names) {
            val why = if (name in catalog.hiddenNames) {
                "this app does not offer " + name + " (the plate is the viewport, so the " +
                    "engine's screenshot is never used). The tools available are: " +
                    catalog.names.joinToString(", ") + "."
            } else {
                "there is no tool named \"" + name + "\". The tools available are: " +
                    catalog.names.joinToString(", ") + "."
            }
            return AgentToolHost.Result(TOOL_ERROR + why, failed = true, outcome = "not offered")
        }
        val arguments = objectOf(argumentsText) ?: return AgentToolHost.Result(
            TOOL_ERROR + "the arguments for " + name + " were not a JSON object:\n" +
                argumentsText.take(400) + "\nSend one JSON object.",
            failed = true,
            outcome = "arguments were not JSON",
        )
        if (name == AgentWebTools.SEARCH || name == AgentWebTools.FETCH) {
            return runWebTool(web, name, arguments, onActivity)
        }
        return try {
            toolHost.call(name, arguments)
        } catch (error: Throwable) {
            AgentToolHost.Result(
                TOOL_ERROR + "the app could not run " + name + ": " +
                    (error.message ?: error.javaClass.simpleName),
                failed = true,
            )
        }
    }

    /**
     * Runs one web tool call.
     *
     * The result is the tool's formatted, untrusted-wrapped text plus the row's outcome (how
     * many results, how much text). A route the endpoint does not offer marks the capability
     * unavailable - which removes the tools from the next request, leaves [webToolsNotice] for
     * the sheet and a red row naming the HTTP status - and the model still gets a result it can
     * act on. Transient failures stay red tool rows and keep the capability.
     */
    private fun runWebTool(
        web: WebToolClient?,
        name: String,
        arguments: JSONObject,
        onActivity: (String) -> Unit,
    ): AgentToolHost.Result {
        if (webAvailability == AgentWebTools.Availability.UNAVAILABLE) {
            return AgentToolHost.Result(
                TOOL_ERROR + (webToolsNotice ?: "web search is not available on this endpoint") +
                    " Build from the user's own words instead.",
                failed = true,
                outcome = webToolsNotice ?: "not available on this endpoint",
            )
        }
        if (web == null) {
            return AgentToolHost.Result(
                TOOL_ERROR + "web search is not available: it is switched off in the CAD agent " +
                    "settings, or this turn was started without it.",
                failed = true,
                outcome = "switched off",
            )
        }
        val started = System.currentTimeMillis()
        return try {
            when (name) {
                AgentWebTools.SEARCH -> {
                    val query = arguments.optString("query").trim()
                    if (query.isEmpty()) {
                        return AgentToolHost.Result(
                            TOOL_ERROR + "web_search needs a non-empty query string.",
                            failed = true,
                            outcome = "no query",
                        )
                    }
                    onActivity("searching the web for \"" + query.take(120) + "\"…")
                    val results = web.search(
                        query = query,
                        count = arguments.optInt("count", AgentWebTools.MAX_RESULTS),
                        oriQuestion = arguments.optString("ori_question"),
                    )
                    webAvailability = AgentWebTools.Availability.AVAILABLE
                    webToolsNotice = null
                    log("web search for \"" + query.take(80) + "\" in " + (System.currentTimeMillis() - started) + " ms")
                    AgentToolHost.Result(
                        text = results.text,
                        outcome = resultLine(results.resultCount),
                    )
                }
                AgentWebTools.FETCH -> {
                    val url = arguments.optString("url").trim()
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        return AgentToolHost.Result(
                            TOOL_ERROR + "web_fetch needs an absolute http or https URL.",
                            failed = true,
                            outcome = "not a URL",
                        )
                    }
                    onActivity("reading " + url.take(160) + " …")
                    val page = web.fetch(url)
                    webAvailability = AgentWebTools.Availability.AVAILABLE
                    webToolsNotice = null
                    log("web fetch of " + url.take(120) + " in " + (System.currentTimeMillis() - started) + " ms")
                    AgentToolHost.Result(
                        text = page.text,
                        outcome = page.characters.toString() + " characters read" +
                            if (page.text.contains("page truncated at")) ", truncated" else "",
                    )
                }
                else -> AgentToolHost.Result(
                    TOOL_ERROR + "there is no web tool named \"" + name + "\".",
                    failed = true,
                    outcome = "unknown tool",
                )
            }
        } catch (error: AgentException) {
            if (error.code == "web-unavailable") {
                webAvailability = AgentWebTools.Availability.UNAVAILABLE
                webToolsNotice = error.message
                log("web tools removed: " + (error.message ?: "the endpoint does not offer them"))
                return AgentToolHost.Result(
                    TOOL_ERROR + (error.message ?: "web search is not available on this endpoint") +
                        " The web tools were removed for this conversation; say in one line that " +
                        "search is unavailable here and continue with the user's own dimensions.",
                    failed = true,
                    outcome = httpOutcome(error.message),
                )
            }
            if (error.code == "cancelled") throw error
            AgentToolHost.Result(
                TOOL_ERROR + (error.message ?: "the web tool failed"),
                failed = true,
            )
        } catch (error: Throwable) {
            AgentToolHost.Result(
                TOOL_ERROR + "the web tool failed: " + (error.message ?: error.javaClass.simpleName),
                failed = true,
            )
        }
    }

    /** "3 results" / "1 result", the search row's one line. */
    private fun resultLine(count: Int): String =
        count.toString() + (if (count == 1) " result" else " results")

    /** "HTTP 404 - this endpoint does not offer web search", from the tool's own sentence. */
    private fun httpOutcome(message: String?): String {
        val text = (message ?: "").trim().ifEmpty { "this endpoint does not offer web search" }
        val status = Regex("\\(HTTP (\\d{3})\\)").find(text)?.groupValues?.get(1)
        if (status == null) return text.take(120)
        val sentence = text.substringBefore(" (HTTP").trim()
        return "HTTP " + status + " - " + sentence
    }

    private fun objectOf(argumentsText: String): JSONObject? {
        if (argumentsText.isBlank()) return JSONObject()
        return try {
            JSONObject(argumentsText)
        } catch (error: Throwable) {
            null
        }
    }

    /**
     * The assistant turn echoed back to the endpoint.
     *
     * Providers require the tool_calls they returned to come back verbatim before the tool
     * results; the content is the string they sent or null when they sent none, because a model
     * that called a tool without talking must not be answered as if it had.
     */
    private fun assistantEcho(content: String?, toolCalls: JSONArray): JSONObject = JSONObject()
        .put("role", "assistant")
        .put("content", content ?: JSONObject.NULL)
        .put("tool_calls", toolCalls)

    private fun remember(userText: String, answer: String) {
        synchronized(conversation) {
            conversation += JSONObject().put("role", "user").put("content", userText)
            conversation += JSONObject().put("role", "assistant").put("content", answer)
            val keep = MAX_REMEMBERED_TURNS * 2
            while (conversation.size > keep) conversation.removeAt(0)
        }
    }

    companion object {
        /** Tool rounds one user turn may take before the loop calls it a runaway. */
        const val DEFAULT_MAX_TOOL_ROUNDS = 8

        /** The one retry line the chat transcript records before re-sending a model call. */
        const val RETRY_NOTICE = "the endpoint did not answer; retrying once"

        /**
         * What a backgrounded turn must not be blamed on.
         *
         * The screen going off is fine: a turn holds its own foreground service and a bounded
         * partial wake lock. The app being frozen by Android is the failure mode this sentence
         * points at, and the fix is the user's - keep the app running.
         */
        const val BACKGROUND_HINT =
            "The screen may be off, that is fine; the app must still be running - a backgrounded " +
                "app can be frozen by Android."

        /** How long the one retry waits before re-sending the same request. */
        const val RETRY_DELAY_MILLIS = 1_500L

        /** Text turns kept for context: ten exchanges is plenty for one document's edits. */
        const val MAX_REMEMBERED_TURNS = 10

        /** Marks a result the model should read as "this call did not work". */
        const val TOOL_ERROR = "tool error: "
    }
}
