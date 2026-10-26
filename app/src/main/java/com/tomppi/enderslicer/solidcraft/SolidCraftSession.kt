package com.tomppi.enderslicer.solidcraft

import org.json.JSONArray
import org.json.JSONObject

/**
 * One command in a batch: the engine command id and its parameters.
 *
 * The engine's own tool for this is [SolidCraftSession.batch], which runs the steps in order
 * and rolls every completed one back when one fails - all-or-nothing, which is what the app
 * wants from a refresh that exports the display tessellation and saves the design together.
 */
data class ToolStep(
    val command: String,
    val params: JSONObject = JSONObject(),
)

/**
 * The SolidCraft engine's MCP tool surface, typed for the calls the app makes itself.
 *
 * The agent's calls arrive through [SolidCraftBridge] and are forwarded untouched; these
 * helpers exist for what the app does on its own behalf: exporting the tessellation the plate
 * draws, saving the design that is the document's source of truth, opening a CAD file, and
 * exporting the print artifact a slice consumes.
 */
class SolidCraftSession(
    private val client: McpClient,
    private val clientName: String = "TrioSlicer",
    private val clientVersion: String = "1.9.7",
) {

    /** What the engine said about itself at initialize; null before the handshake. */
    @Volatile
    var serverName: String? = null
        private set

    @Volatile
    var serverVersion: String? = null
        private set

    @Volatile
    var protocolVersion: String? = null
        private set

    /** The engine's standing instructions, for the diagnostics log. */
    @Volatile
    var instructions: String? = null
        private set

    /**
     * The MCP lifecycle: initialize, then notifications/initialized.
     *
     * The version is the one the engine advertises (2025-06-18). It negotiates down itself when
     * handed one it does not know, so asking for the newest is both correct and future-proof.
     */
    fun initialize(timeoutMillis: Long = INITIALIZE_TIMEOUT_MILLIS): JSONObject {
        val result = client.request(
            "initialize",
            JSONObject()
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("capabilities", JSONObject())
                .put(
                    "clientInfo",
                    JSONObject().put("name", clientName).put("version", clientVersion),
                ),
            timeoutMillis,
        )
        protocolVersion = result.optString("protocolVersion").takeIf { it.isNotBlank() }
        val info = result.optJSONObject("serverInfo")
        serverName = info?.optString("name")?.takeIf { it.isNotBlank() }
        serverVersion = info?.optString("version")?.takeIf { it.isNotBlank() }
        instructions = result.optString("instructions").takeIf { it.isNotBlank() }
        client.notify("notifications/initialized")
        return result
    }

    /** Every tool the engine offers, for the diagnostics log and the connect status. */
    fun listTools(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): List<String> =
        toolDefinitions(timeoutMillis).let { tools ->
            (0 until tools.length()).mapNotNull { index ->
                tools.optJSONObject(index)?.optString("name")?.takeIf { it.isNotBlank() }
            }
        }

    /**
     * The engine's complete tool catalogue - names, descriptions and JSON schemas.
     *
     * The engine is the one owner of this list, and two consumers read this one answer: the
     * in-app agent, in this process, and an external agent through [SolidCraftBridge], which
     * forwards the same `tools/list`. Nothing in the app restates the schemas, so a tool the
     * engine adds reaches both without an app change.
     */
    fun toolDefinitions(timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): JSONArray {
        val result = client.request("tools/list", JSONObject(), timeoutMillis)
        return result.optJSONArray("tools") ?: JSONArray()
    }

    /** A raw tools/call. The result is the engine's MCP result, errors included. */
    fun callTool(
        name: String,
        arguments: JSONObject = JSONObject(),
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): JSONObject = client.request(
        "tools/call",
        JSONObject().put("name", name).put("arguments", arguments),
        timeoutMillis,
    )

    /** Runs one engine command, and throws [McpToolException] when the engine refuses it. */
    fun execute(
        command: String,
        params: JSONObject = JSONObject(),
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): JSONObject = requireOk(
        "execute",
        callTool("execute", JSONObject().put("command", command).put("params", params), timeoutMillis),
    )

    /**
     * Runs several engine commands as one call, so a display export and a design save are one
     * round trip - and so a failure leaves the design exactly where it was (rollback is the
     * engine's default).
     */
    fun batch(
        steps: List<ToolStep>,
        rollback: Boolean = true,
        timeoutMillis: Long = EXPORT_TIMEOUT_MILLIS,
    ): JSONObject {
        val commands = JSONArray()
        steps.forEach { step ->
            commands.put(JSONObject().put("command", step.command).put("params", step.params))
        }
        return requireOk(
            "batch",
            callTool(
                "batch",
                JSONObject().put("commands", commands).put("rollback", rollback),
                timeoutMillis,
            ),
        )
    }

    /**
     * Writes one artifact. The format follows the path's extension unless [format] names it,
     * exactly as the engine's Export list does (stl, stla, step, 3mf, obj, gltf...).
     */
    fun export(
        path: String,
        format: String? = null,
        timeoutMillis: Long = EXPORT_TIMEOUT_MILLIS,
    ): JSONObject = requireOk(
        "export",
        callTool(
            "export",
            JSONObject().put("path", path).apply { if (format != null) put("format", format) },
            timeoutMillis,
        ),
    )

    /** Opens a STEP, IGES, 3MF, OBJ, glTF or .solidcraft file as a new design. */
    fun open(path: String, timeoutMillis: Long = OPEN_TIMEOUT_MILLIS): JSONObject = requireOk(
        "open",
        callTool("open", JSONObject().put("path", path), timeoutMillis),
    )

    /** Saves the live design as a .solidcraft document. */
    fun save(path: String, timeoutMillis: Long = EXPORT_TIMEOUT_MILLIS): JSONObject = requireOk(
        "save",
        callTool("save", JSONObject().put("path", path), timeoutMillis),
    )

    /** Replaces the design with an empty one. */
    fun newDesign(name: String? = null, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): JSONObject =
        requireOk(
            "new_design",
            callTool("new_design", JSONObject().apply { if (name != null) put("name", name) }, timeoutMillis),
        )

    /** The design's own report: bodies, volumes, timeline, parameters. */
    fun inspectDesign(
        measure: Boolean = true,
        sketches: Boolean = false,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): JSONObject = requireOk(
        "inspect_design",
        callTool(
            "inspect_design",
            JSONObject().put("measure", measure).put("sketches", sketches),
            timeoutMillis,
        ),
    )

    /** The design as JSON, as [inspectDesign] returns it. */
    fun inspectDesignJson(measure: Boolean = true, sketches: Boolean = false): JSONObject =
        McpToolResults.json(inspectDesign(measure, sketches))
            ?: throw McpException("the SolidCraft engine's inspect_design answer was not JSON")

    /** Turns an MCP tools/call result into a checked one. */
    fun requireOk(tool: String, result: JSONObject): JSONObject {
        if (!McpToolResults.isError(result)) return result
        throw McpToolException(tool, McpToolResults.text(result))
    }

    companion object {
        const val PROTOCOL_VERSION = "2025-06-18"
        const val INITIALIZE_TIMEOUT_MILLIS: Long = 60_000L
        const val DEFAULT_TIMEOUT_MILLIS: Long = 120_000L
        const val EXPORT_TIMEOUT_MILLIS: Long = 300_000L
        const val OPEN_TIMEOUT_MILLIS: Long = 300_000L
    }
}

/** The parts of an MCP tools/call result the app reads. */
object McpToolResults {
    /** The first text content, which is how every SolidCraft tool answers. */
    fun text(result: JSONObject): String {
        val content = result.optJSONArray("content") ?: return result.toString()
        for (index in 0 until content.length()) {
            val item = content.optJSONObject(index) ?: continue
            if (item.optString("type") == "text") return item.optString("text")
        }
        return result.toString()
    }

    /** The text parsed as the JSON object most engine results carry, or null when it is not. */
    fun json(result: JSONObject): JSONObject? = runCatching { JSONObject(text(result)) }.getOrNull()

    fun isError(result: JSONObject): Boolean = result.optBoolean("isError", false)

    /** A tools/call result that reports a tool-level failure, as MCP expects. */
    fun errorResult(message: String): JSONObject = JSONObject()
        .put(
            "content",
            JSONArray().put(JSONObject().put("type", "text").put("text", message)),
        )
        .put("isError", true)
}

/** A tool the engine ran and refused; the message is the engine's own sentence. */
class McpToolException(
    val engineTool: String,
    val engineMessage: String,
) : McpException("the SolidCraft engine's " + engineTool + " failed: " + engineMessage)
