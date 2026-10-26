package com.tomppi.enderslicer.cadagent

import org.json.JSONArray
import org.json.JSONObject

/**
 * The tools the in-app agent may call: the engine's catalogue plus the app's own tools.
 *
 * The SolidCraft engine is the one owner of its tool surface. `tools/list` over the child
 * process's stdio returns names, descriptions and JSON schemas, and two consumers read that one
 * answer - this agent, in process, and an external agent through the bridge. Nothing about the
 * engine's tools is restated here: a tool the engine adds appears in both places without a change
 * to the app.
 *
 * What the app does own is the *policy* of what it offers. [HIDDEN_TOOLS] are the engine tools
 * that do not apply to a phone whose plate is the viewport and whose session is this app's. The
 * app's own tools live beside them: the two web tools ([AgentWebTools]) call the user's
 * configured endpoint, and [PLATE_SCREENSHOT] renders the plate with the app's own renderer and
 * attaches the picture to the next message - the engine's `screenshot` stays hidden, because the
 * plate is the viewport and the engine's camera is not the user's.
 */
class AgentToolCatalog private constructor(
    /** The engine's tools, exactly as the engine described them, converted to the OpenAI shape. */
    private val engineTools: JSONArray,
    /** The app's own tool that renders the plate (see [PLATE_SCREENSHOT]). */
    private val plateTools: JSONArray,
    /** The app's own tools that call the configured endpoint (web search and fetch). */
    private val webTools: JSONArray,
    /** The app's own tool that asks the user a question (see [ASK_USER]). */
    private val askTools: JSONArray,
    /** Engine tools deliberately not offered here. */
    val hiddenNames: Set<String>,
    /** Engine tools skipped because their name is not a legal function name. */
    val skippedNames: Set<String>,
) {
    /** OpenAI function definitions: the engine's tools first, then the app's web tools. */
    val tools: JSONArray = JSONArray()

    /** Every name offered, for checking a tool call before running it. */
    val names: Set<String> = LinkedHashSet()

    init {
        for (source in listOf(engineTools, plateTools, webTools, askTools)) {
            for (index in 0 until source.length()) {
                val definition = source.optJSONObject(index) ?: continue
                tools.put(definition)
                definition.optJSONObject("function")?.optString("name")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { (names as LinkedHashSet<String>).add(it) }
            }
        }
    }

    val isEmpty: Boolean get() = names.isEmpty()

    /** True when the endpoint's web tools are part of this catalogue. */
    val includesWebTools: Boolean get() = webTools.length() > 0

    /** True when the app's plate-render tool is part of this catalogue. */
    val includesPlateScreenshot: Boolean get() = plateTools.length() > 0

    /** True when the app's ask-the-user tool is part of this catalogue. */
    val includesAskUser: Boolean get() = askTools.length() > 0

    /** A copy with the app's web tools removed, for an endpoint that does not offer them. */
    fun withoutWebTools(): AgentToolCatalog =
        AgentToolCatalog(engineTools, plateTools, JSONArray(), askTools, hiddenNames, skippedNames)

    companion object {
        /**
         * The engine tools this app does not offer the in-app agent.
         *
         *  - **screenshot** renders with the engine's own camera and GPU view. The plate is the
         *    viewport the user is actually looking at; the app refuses this tool on the bridge
         *    for the same reason.
         *  - **crash_reports** reads the standalone desktop app's crash directory, which does not
         *    exist here.
         *  - **running** and **pending** describe requests bridged from a standalone app or from
         *    the bridge; an in-process call has no such queue.
         *  - **skill_reference** reads SolidCraft's bundled desktop skill documents, which are
         *    not packaged in this APK - the runbook is what this agent gets instead.
         */
        val HIDDEN_TOOLS: Set<String> = setOf(
            "screenshot",
            "crash_reports",
            "running",
            "pending",
            "skill_reference",
        )

        /**
         * The app's own plate render, offered beside the engine's tools.
         *
         * It is not an engine tool: the SolidCraft engine cannot see the user's camera, and its
         * own `screenshot` is refused for exactly that reason. This one draws the plate view the
         * user is looking at, with the selected face tinted, and the runtime attaches the picture
         * to the next message of the same turn.
         */
        const val PLATE_SCREENSHOT = "plate_screenshot"

        /**
         * The app's own question tool: it shows the user a question card and ends the turn.
         *
         * Asking is not an engine call and there is no wait on it. The answer starts the next
         * turn as a normal user message; the model that asked is told exactly that in the tool
         * result so it never blocks or repeats the question in the same turn.
         */
        const val ASK_USER = "ask_user"

        /** The question as an OpenAI function tool: question, optional options, allow_text. */
        fun askUserDefinition(): JSONObject = JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", ASK_USER)
                    .put(
                        "description",
                        "Ask the user a question and END THE TURN. Use it when a requirement is " +
                            "genuinely ambiguous, when an edit is destructive or irreversible, " +
                            "or when a dimension or material the user has not given is needed to " +
                            "proceed. The question shows as a card with one chip per option and a " +
                            "composer for free text. The user's answer arrives as the next user " +
                            "message and starts the next turn: never wait for it, never repeat " +
                            "the question in the same turn, and do not ask for anything you can " +
                            "read from inspect_design or measure.",
                    )
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject()
                                    .put(
                                        "question",
                                        JSONObject()
                                            .put("type", "string")
                                            .put(
                                                "description",
                                                "The question, one sentence, in the user's words.",
                                            ),
                                    )
                                    .put(
                                        "options",
                                        JSONObject()
                                            .put("type", "array")
                                            .put("items", JSONObject().put("type", "string"))
                                            .put(
                                                "description",
                                                "Optional short answers as chips; use them for a " +
                                                    "choice, and keep each to a few words.",
                                            ),
                                    )
                                    .put(
                                        "allow_text",
                                        JSONObject()
                                            .put("type", "boolean")
                                            .put(
                                                "description",
                                                "Whether the user may also type a free answer " +
                                                    "(default true). Set false only when the " +
                                                    "options are the whole answer set.",
                                            ),
                                    ),
                            )
                            .put("required", JSONArray().put("question"))
                            .put("additionalProperties", false),
                    ),
            )

        /** The plate render as an OpenAI function tool; no parameters, nothing to get wrong. */
        fun plateScreenshotDefinition(): JSONObject = JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", PLATE_SCREENSHOT)
                    .put(
                        "description",
                        "Render the plate at the user's current camera and attach the picture to " +
                            "the next message of this turn. Call it when what the part looks like " +
                            "matters - a shape, a surface, which face is picked - instead of " +
                            "guessing from the description. It reports the plate's geometry in one " +
                            "line and the image follows; a selected face is tinted amber in it.",
                    )
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", JSONObject())
                            .put("additionalProperties", false),
                    ),
            )

        /** OpenAI's function-name rule; a name outside it would fail the whole request. */
        private val FUNCTION_NAME = Regex("^[a-zA-Z0-9_-]{1,64}$")

        /**
         * Builds the catalogue from the engine's `tools/list` answer.
         *
         * The engine's entry is {name, title, description, inputSchema}; OpenAI's function tool
         * is {type, function: {name, description, parameters}}. The schema is passed through
         * unchanged, so the model sees exactly the contract the engine publishes - including
         * `additionalProperties: false` and the per-command envelope the runbook relies on.
         *
         * @param includeWebTools false when the user switched search off, or when the endpoint
         *   has already said it does not offer the tool routes.
         */
        fun fromEngine(
            engineTools: JSONArray,
            includeWebTools: Boolean = true,
            includePlateScreenshot: Boolean = true,
            includeAskUser: Boolean = true,
        ): AgentToolCatalog {
            val converted = JSONArray()
            val hidden = LinkedHashSet<String>()
            val skipped = LinkedHashSet<String>()
            val seen = LinkedHashSet<String>()
            for (index in 0 until engineTools.length()) {
                val engineTool = engineTools.optJSONObject(index) ?: continue
                val name = engineTool.optString("name").trim()
                if (name.isEmpty()) continue
                if (name in HIDDEN_TOOLS) {
                    hidden += name
                    continue
                }
                if (!FUNCTION_NAME.matches(name)) {
                    skipped += name
                    continue
                }
                if (!seen.add(name)) continue
                val schema = engineTool.optJSONObject("inputSchema")
                    ?.let { JSONObject(it.toString()) }
                    ?: JSONObject().put("type", "object").put("properties", JSONObject())
                val description = engineTool.optString("description").takeIf { it.isNotBlank() }
                    ?: engineTool.optString("title")
                converted.put(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", name)
                                .put("description", description)
                                .put("parameters", schema),
                        ),
                )
            }
            return AgentToolCatalog(
                engineTools = converted,
                plateTools = if (includePlateScreenshot) {
                    JSONArray().put(plateScreenshotDefinition())
                } else {
                    JSONArray()
                },
                webTools = if (includeWebTools) AgentWebTools.definitions() else JSONArray(),
                askTools = if (includeAskUser) JSONArray().put(askUserDefinition()) else JSONArray(),
                hiddenNames = hidden,
                skippedNames = skipped,
            )
        }
    }
}
