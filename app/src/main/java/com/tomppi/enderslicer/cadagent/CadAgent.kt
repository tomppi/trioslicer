package com.tomppi.enderslicer.cadagent

import org.json.JSONArray
import org.json.JSONObject

/**
 * One in-process tool call, run by the app. Implemented by
 * [com.tomppi.enderslicer.solidcraft.SolidCraftAgentHost] over the running engine session; a fake
 * implements it in tests.
 *
 * The contract is one-way: [call] returns the text the model receives and never throws. A tool
 * failure is a result for the model, not an exception for the UI - the model is the thing that
 * can read the engine's sentence and try something else.
 */
fun interface AgentToolHost {
    fun call(name: String, arguments: JSONObject): String
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
 * Everything the model can see is a [JSONObject] in the OpenAI chat shape. Streaming is not
 * used: a non-streaming call is one request and one answer, and a cancel is a disconnect rather
 * than an abandoned read - fewer states to get wrong in the first cut.
 */
class CadAgent(
    private val toolHost: AgentToolHost,
    private val runbook: String,
    private val maxToolRounds: Int = DEFAULT_MAX_TOOL_ROUNDS,
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
     * Blocking: call on a background thread. [onActivity] receives the plain-words lines the
     * sheet shows while the agent works ("searching for …", "reading …"), so the user can see
     * when the agent leaves the phone for the web.
     *
     * @throws AgentException with a named code when the conversation could not continue (not
     *   configured, refused, timeout, malformed reply, cancelled, tool-round limit).
     */
    fun run(
        settings: StoredAgentSettings,
        catalog: AgentToolCatalog,
        userText: String,
        onActivity: (String) -> Unit = {},
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
                return converse(chat, web, catalog, userText, onActivity)
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
        onActivity: (String) -> Unit,
    ): String {
        val messages = JSONArray()
        messages.put(
            JSONObject().put("role", "system").put("content", AgentRunbook.systemPrompt(runbook)),
        )
        synchronized(conversation) { conversation.forEach { messages.put(it) } }
        val userMessage = JSONObject().put("role", "user").put("content", userText)
        messages.put(userMessage)

        var rounds = 0
        while (true) {
            if (cancelled) throw AgentException("cancelled", "Stopped")
            // A tool list that follows what the endpoint has told us: once the web routes are
            // known to be missing, the next request no longer carries them.
            val activeCatalog = if (webAvailability == AgentWebTools.Availability.UNAVAILABLE) {
                catalog.withoutWebTools()
            } else {
                catalog
            }
            val reply = chat.complete(messages, activeCatalog.tools)
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
                    "The agent asked to run tools " + maxToolRounds + " times without giving an " +
                        "answer, so it was stopped. Ask again, or split the request into a smaller one.",
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
                val result = when (name) {
                    AgentWebTools.SEARCH, AgentWebTools.FETCH ->
                        runWebTool(web, name, argumentsText, onActivity)
                    else -> runEngineTool(catalog, name, argumentsText)
                }
                messages.put(
                    JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", id)
                        .put("content", result),
                )
            }
        }
    }

    /**
     * Runs one engine tool call, returning the text the model sees.
     *
     * A malformed call is reported *to the model* rather than thrown at the UI: wrong JSON in
     * the arguments, a name the app does not offer, a name it deliberately hides. The model can
     * read that and correct itself on the next round, which is the whole point of the loop.
     */
    private fun runEngineTool(catalog: AgentToolCatalog, name: String, argumentsText: String): String {
        if (name.isEmpty()) {
            return TOOL_ERROR + "the reply contained a tool call with no function name."
        }
        if (name !in catalog.names) {
            if (name in catalog.hiddenNames) {
                return TOOL_ERROR + "this app does not offer " + name + " (the plate is the " +
                    "viewport, so the engine's screenshot is never used). The tools available " +
                    "are: " + catalog.names.joinToString(", ") + "."
            }
            return TOOL_ERROR + "there is no tool named \"" + name + "\". The tools available " +
                "are: " + catalog.names.joinToString(", ") + "."
        }
        val arguments = objectOf(argumentsText) ?: return TOOL_ERROR +
            "the arguments for " + name + " were not a JSON object:\n" + argumentsText.take(400) +
            "\nSend one JSON object."
        return try {
            toolHost.call(name, arguments)
        } catch (error: Throwable) {
            TOOL_ERROR + "the app could not run " + name + ": " +
                (error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Runs one web tool call.
     *
     * The result is the tool's formatted, untrusted-wrapped text. A route the endpoint does not
     * offer marks the capability unavailable - which removes the tools from the next request and
     * leaves [webToolsNotice] for the sheet - and the model still gets a result it can act on.
     * Transient failures stay tool errors and keep the capability.
     */
    private fun runWebTool(
        web: WebToolClient?,
        name: String,
        argumentsText: String,
        onActivity: (String) -> Unit,
    ): String {
        if (webAvailability == AgentWebTools.Availability.UNAVAILABLE) {
            return TOOL_ERROR + (webToolsNotice ?: "web search is not available on this endpoint") +
                " Build from the user's own words instead."
        }
        if (web == null) {
            return TOOL_ERROR + "web search is not available: it is switched off in the CAD agent " +
                "settings, or this turn was started without it."
        }
        val arguments = objectOf(argumentsText) ?: return TOOL_ERROR +
            "the arguments for " + name + " were not a JSON object:\n" + argumentsText.take(400) +
            "\nSend one JSON object."
        val started = System.currentTimeMillis()
        return try {
            when (name) {
                AgentWebTools.SEARCH -> {
                    val query = arguments.optString("query").trim()
                    if (query.isEmpty()) {
                        return TOOL_ERROR + "web_search needs a non-empty query string."
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
                    results
                }
                AgentWebTools.FETCH -> {
                    val url = arguments.optString("url").trim()
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        return TOOL_ERROR + "web_fetch needs an absolute http or https URL."
                    }
                    onActivity("reading " + url.take(160) + " …")
                    val page = web.fetch(url)
                    webAvailability = AgentWebTools.Availability.AVAILABLE
                    webToolsNotice = null
                    log("web fetch of " + url.take(120) + " in " + (System.currentTimeMillis() - started) + " ms")
                    page
                }
                else -> return TOOL_ERROR + "there is no web tool named \"" + name + "\"."
            }
        } catch (error: AgentException) {
            if (error.code == "web-unavailable") {
                webAvailability = AgentWebTools.Availability.UNAVAILABLE
                webToolsNotice = error.message
                log("web tools removed: " + (error.message ?: "the endpoint does not offer them"))
                return TOOL_ERROR + (error.message ?: "web search is not available on this endpoint") +
                    " The web tools were removed for this conversation; say in one line that " +
                    "search is unavailable here and continue with the user's own dimensions."
            }
            TOOL_ERROR + (error.message ?: "the web tool failed")
        } catch (error: Throwable) {
            TOOL_ERROR + "the web tool failed: " + (error.message ?: error.javaClass.simpleName)
        }
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

        /** Text turns kept for context: ten exchanges is plenty for one document's edits. */
        const val MAX_REMEMBERED_TURNS = 10

        /** Marks a result the model should read as "this call did not work". */
        const val TOOL_ERROR = "tool error: "
    }
}
