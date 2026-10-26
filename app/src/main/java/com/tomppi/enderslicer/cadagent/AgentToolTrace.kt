package com.tomppi.enderslicer.cadagent

import org.json.JSONArray
import org.json.JSONObject

/**
 * One tool call, as a chat surface draws it.
 *
 * The agent emits this twice per call: once as it starts (running, no duration or outcome yet)
 * and once when it ends, with the time it took and one line about what came back. A chat that
 * shows only prose looks like nothing is happening while the engine builds; these rows are what
 * make the work visible - "solid.box 12 ms", "web_search \"M3 head\" 13 ms - 3 results" - and a
 * failed call is a row with [failed] set, never silence.
 *
 * [arguments] is a short, already-redacted summary: it never carries the API key or an
 * Authorization header. The credential is the one thing a tool row must not be able to leak.
 */
data class AgentToolTrace(
    /** Unique within a turn; the end event carries the same id as its start. */
    val callId: Int,
    /** The tool name as the model called it (execute, batch, web_search...). */
    val tool: String,
    /** The row's title: the command id for execute/solid.box, the tool name otherwise. */
    val name: String,
    /** Short argument summary: the command and its key params, a query, a URL. */
    val arguments: String,
    val running: Boolean = true,
    val durationMillis: Long = 0,
    /** One line about the result: "1 body created (Body1)", "3 results", an error sentence. */
    val outcome: String = "",
    val failed: Boolean = false,
)

/**
 * How a tool call is summarised for a row - the only place tool rows get their text.
 *
 * Everything here takes the key and masks it out of the result: the engine's and the endpoint's
 * own strings are redacted where they are produced, but a row is rendered into a transcript that
 * the user may copy and send on, and a second mask at the place the text is built is cheap.
 */
object AgentToolSummary {

    /** The key never survives into a row, a transcript or a copied message. */
    fun redact(text: String, apiKey: String): String =
        if (apiKey.isBlank()) text else text.replace(apiKey, "[key]")

    /** The row's title: for execute the engine command, otherwise the tool's own name. */
    fun displayName(tool: String, arguments: JSONObject?): String = when (tool) {
        "execute" -> arguments?.optString("command")?.trim()?.takeIf { it.isNotEmpty() } ?: "execute"
        else -> tool
    }

    /**
     * The short argument summary under the row's title.
     *
     * A model can put anything in arguments, so this is bounded and one-line by construction:
     * the command and at most three parameters, a quoted query, a URL, a file name.
     */
    fun arguments(tool: String, arguments: JSONObject?, rawArguments: String): String {
        if (arguments == null) return clip(singleLine(rawArguments), 80)
        return when (tool) {
            "execute" -> executeArguments(arguments)
            "batch" -> batchArguments(arguments)
            AgentWebTools.SEARCH -> quote(clip(arguments.optString("query").trim(), 90))
            AgentWebTools.FETCH -> clip(arguments.optString("url").trim(), 120)
            "set_parameter" -> {
                val name = arguments.optString("name").trim()
                val value = arguments.optString("expression").trim()
                    .ifEmpty { arguments.optString("value").trim() }
                listOf(name, value).filter { it.isNotEmpty() }.joinToString(" = ")
            }
            "export", "save", "open" -> clip(fileName(arguments.optString("path")), 80)
            else -> ""
        }
    }

    /**
     * One line about the result.
     *
     * Engine tools answer with their own JSON - a body report, an export's byte count, a list -
     * and the outcome is read from it; the web tools hand their own outcome in. Anything this
     * does not understand is the result's first line, clipped, because a row is not a log.
     */
    fun outcome(tool: String, text: String, failed: Boolean): String {
        val clean = singleLine(text.removePrefix(CadAgent.TOOL_ERROR)).trim()
        if (failed) return clip(clean, 120).ifEmpty { "failed" }
        val json = runCatching { JSONObject(text) }.getOrNull()
        if (json != null) {
            bodyReport(json)?.let { return it }
            if (tool == "batch") {
                json.optJSONArray("results")?.let { return it.length().toString() + " steps completed" }
            }
            when (tool) {
                "export" -> {
                    val parts = mutableListOf<String>()
                    formatName(json.optString("format"))?.let(parts::add)
                    json.optLong("bytes", -1).takeIf { it >= 0 }?.let { parts += it.toString() + " bytes" }
                    return parts.joinToString(", ").ifEmpty { "exported" }
                }
                "save" -> return "saved"
                "open" -> return "opened"
                "undo" -> return json.optString("undone").trim().takeIf { it.isNotEmpty() }
                    ?.let { "undone " + it } ?: "undone"
                "redo" -> return json.optString("redone").trim().takeIf { it.isNotEmpty() }
                    ?.let { "redone " + it } ?: "redone"
                "inspect_design" -> return "design read"
                "measure" -> return "measured"
                "list_commands" -> return "catalogue read"
                "describe_command" -> return "command described"
                "new_design" -> return "new design"
                "body_topology" -> return "topology read"
            }
        }
        return clip(clean, 120).ifEmpty { "done" }
    }

    // --- pieces ---------------------------------------------------------------------------

    private fun executeArguments(arguments: JSONObject): String {
        val command = arguments.optString("command").trim()
        val params = arguments.optJSONObject("params")
        val detail = params?.let { compactJson(it, 3) }.orEmpty()
        return clip(listOf(command, detail).filter { it.isNotEmpty() }.joinToString(" "), 100)
    }

    private fun batchArguments(arguments: JSONObject): String {
        val commands = arguments.optJSONArray("commands") ?: return ""
        val names = (0 until commands.length()).mapNotNull { index ->
            commands.optJSONObject(index)?.optString("command")?.trim()?.takeIf { it.isNotEmpty() }
        }
        if (names.isEmpty()) return ""
        val shown = names.take(3).joinToString(", ")
        val more = if (names.size > 3) ", …" else ""
        return clip(names.size.toString() + " steps: " + shown + more, 100)
    }

    private fun compactJson(json: JSONObject, maxEntries: Int): String {
        val parts = mutableListOf<String>()
        val keys = json.keys()
        var more = false
        while (keys.hasNext()) {
            if (parts.size >= maxEntries) {
                more = true
                break
            }
            val key = keys.next()
            parts += key + "=" + compactValue(json.opt(key))
        }
        if (keys.hasNext()) more = true
        return "{" + parts.joinToString(", ") + (if (more) ", …" else "") + "}"
    }

    private fun compactValue(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is String -> clip(value, 24)
        is JSONArray -> "[" + value.length() + " items]"
        is JSONObject -> "{…}"
        else -> value.toString()
    }

    /** The bodies one engine call created, changed or removed, as the row's one line. */
    private fun bodyReport(json: JSONObject): String? {
        val created = bodyNames(json, "created_bodies")
        val changed = bodyNames(json, "changed_bodies")
        val removed = bodyNames(json, "removed_bodies")
        return when {
            created.isNotEmpty() -> countLine(created, "created")
            changed.isNotEmpty() -> countLine(changed, "changed")
            removed.isNotEmpty() -> countLine(removed, "removed")
            json.has("created_bodies") || json.has("changed_bodies") || json.has("removed_bodies") ->
                "no body changed"
            else -> null
        }
    }

    private fun countLine(names: List<String>, verb: String): String {
        val listed = names.take(3).joinToString(", ")
        val more = if (names.size > 3) ", …" else ""
        return names.size.toString() + (if (names.size == 1) " body " else " bodies ") + verb +
            " (" + listed + more + ")"
    }

    private fun bodyNames(json: JSONObject, key: String): List<String> {
        val array = json.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            when (val value = array.opt(index)) {
                is String -> value.takeIf { it.isNotBlank() }
                is JSONObject -> value.optString("name").takeIf { it.isNotBlank() }
                else -> null
            }
        }
    }

    private fun formatName(format: String): String? {
        if (format.isBlank()) return null
        val lower = format.lowercase()
        return when {
            lower.contains("stl") -> "STL"
            lower.contains("step") || lower.contains("stp") -> "STEP"
            lower.contains("3mf") -> "3MF"
            lower.contains("obj") -> "OBJ"
            lower.contains("gltf") -> "glTF"
            else -> format
        }
    }

    private fun fileName(path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return ""
        return trimmed.substringAfterLast('/').substringAfterLast('\\')
    }

    private fun quote(text: String): String = if (text.isEmpty()) "" else "\"" + text + "\""

    private fun singleLine(text: String): String =
        text.replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s+"), " ").trim()

    private fun clip(text: String, max: Int): String =
        if (text.length <= max) text else text.take(max) + "…"
}
