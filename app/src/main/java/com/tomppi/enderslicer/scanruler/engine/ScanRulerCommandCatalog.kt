package com.tomppi.enderslicer.scanruler.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * The page's command catalogue as agent surfaces see it.
 * The page's hello carries CommandInfo objects; the MCP rule (upstream mcp/src/tools.js) maps
 * a command's dots to underscores for the tool name and passes the JSON Schema through.
 */
internal object ScanRulerCommandCatalog {

    data class Command(
        val name: String,
        val title: String?,
        val description: String?,
        val input: JSONObject,
        val readOnly: Boolean,
        val returnsFile: Boolean,
    ) {
        val toolName: String = toolNameFor(name)
    }

    fun toolNameFor(commandName: String): String = commandName.replace('.', '_')

    /**
     * The page command a tool name names. Tool names are not invertible by string surgery
     * (a.b_c and a_b.c both become a_b_c), so every resolution goes through the catalogue.
     */
    fun commandForTool(commands: List<Command>, toolName: String): Command? =
        commands.firstOrNull { it.toolName == toolName }

    fun parse(commands: JSONArray?): List<Command> {
        if (commands == null) return emptyList()
        val out = ArrayList<Command>(commands.length())
        for (index in 0 until commands.length()) {
            val item = commands.optJSONObject(index) ?: continue
            val name = item.optString("name").trim()
            if (name.isEmpty()) continue
            out += Command(
                name = name,
                title = item.optString("title").takeIf { it.isNotBlank() },
                description = item.optString("description").takeIf { it.isNotBlank() },
                input = item.optJSONObject("input")
                    ?: JSONObject("""{"type":"object","properties":{}}"""),
                readOnly = item.optBoolean("readOnly", false),
                returnsFile = item.optBoolean("returnsFile", false),
            )
        }
        return out.sortedBy { it.toolName }
    }

    fun mcpTools(commands: List<Command>): JSONArray {
        val tools = JSONArray()
        for (command in commands) {
            val description = buildString {
                if (command.title != null) append(command.title).append(". ")
                if (command.description != null) append(command.description.trim())
                if (command.readOnly) append(if (isEmpty()) "" else " ").append("(Read-only.)")
            }
            tools.put(
                JSONObject()
                    .put("name", command.toolName)
                    .put("description", description.ifBlank { "ScanRuler command " + command.name })
                    .put("inputSchema", command.input)
                    .put("annotations", JSONObject().put("readOnlyHint", command.readOnly)),
            )
        }
        tools.put(
            JSONObject()
                .put("name", "scanruler_status")
                .put("description", "Where the embedded ScanRuler page stands: phase, paired, tool count, workspace, notes.")
                .put("inputSchema", JSONObject("""{"type":"object","properties":{}}"""))
                .put("annotations", JSONObject().put("readOnlyHint", true)),
        )
        tools.put(
            JSONObject()
                .put("name", "scanruler_wait")
                .put("description", "Wait for the embedded ScanRuler page to pair.")
                .put("inputSchema", JSONObject(
                    """{"type":"object","properties":{"timeoutMs":{"type":"integer","minimum":0,"maximum":120000}}}""",
                ))
                .put("annotations", JSONObject().put("readOnlyHint", true)),
        )
        return tools
    }
}
