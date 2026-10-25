package com.tomppi.enderslicer.solidcraft

import org.json.JSONArray
import org.json.JSONObject

/**
 * What an agent's tool call means for the plate's live view.
 *
 * The engine answers a feature command with the bodies it created, changed and removed
 * (created_bodies, changed_bodies, removed_bodies - in the compact verbose:false form too).
 * That is the difference between a live view and a reload: only those bodies have to be
 * exported and re-tessellated after an edit. The calls that do not name bodies - undo, redo,
 * a parameter change, a new design, an opened file - fall back to re-reading the document body
 * by body, which is still a refresh of the same scene, not a load of a new one.
 */
object SolidCraftTools {

    /** Tools that can change the design; the bridge repaints after them. */
    val DOCUMENT_TOOLS: Set<String> = setOf(
        "execute", "batch", "undo", "redo", "new_design", "open", "set_parameter",
    )

    /** Tools whose whole point is a file the caller named; they change no design. */
    val FILE_TOOLS: Set<String> = setOf("export", "save", "screenshot")

    /** True when a tool call may have changed the geometry on the plate. */
    fun changesDocument(tool: String, arguments: JSONObject = JSONObject()): Boolean = when (tool) {
        "execute" -> commandChangesDocument(arguments.optString("command"))
        "batch" -> batchChangesDocument(arguments.optJSONArray("commands"))
        "set_parameter", "undo", "redo", "new_design", "open" -> true
        in FILE_TOOLS -> false
        else -> if (tool.contains('.')) commandChangesDocument(tool) else false
    }

    /**
     * Whether an engine command can change the design's geometry.
     *
     * The read-only half is the engine's own classification (its control channel names the same
     * families): inspection, measurement, the model/edge/face listings, exports and saves. A
     * command this does not know is treated as changing - the cost of guessing wrong towards a
     * repaint is a few milliseconds, and the cost of guessing wrong away from one is a plate
     * showing stale geometry.
     */
    fun commandChangesDocument(command: String): Boolean {
        val id = command.trim()
            .substringBefore(' ')
            .substringBefore('{')
            .substringBefore('(')
            .trim()
        if (id.isEmpty()) return true
        return when {
            id in NON_CHANGING_COMMANDS -> false
            id.startsWith("inspect.") -> false
            id.startsWith("model.") -> false
            id.startsWith("render.") -> false
            id.startsWith("engine.") -> false
            id.startsWith("diagnostics.") -> false
            id.startsWith("file.") -> false
            id.endsWith(".inspect") || id.endsWith(".list") -> false
            else -> true
        }
    }

    /** True when a command replaces or rebuilds the whole design rather than one feature. */
    fun commandNeedsFullRefresh(command: String): Boolean {
        val id = command.trim().substringBefore(' ').substringBefore('{').trim()
        return when {
            id.startsWith("parameters.") -> true
            id == "doc.open" || id == "file.new" || id == "file.open" -> true
            id == "edit.undo" || id == "edit.redo" || id == "edit.restore_checkpoint" -> true
            id.startsWith("component.") || id.startsWith("joint.") -> true
            id.startsWith("browser.") || id.startsWith("view.") -> false
            id.startsWith("sketch.") -> false
            id.startsWith("file.") -> false
            else -> false
        }
    }

    private fun batchChangesDocument(commands: JSONArray?): Boolean {
        if (commands == null) return true
        for (index in 0 until commands.length()) {
            val step = commands.optJSONObject(index) ?: return true
            val name = step.optString("command")
            val params = step.optJSONObject("params") ?: JSONObject()
            if (changesDocument(name, params)) return true
        }
        return false
    }

    /**
     * The delta one tool call leaves behind, or null when the design did not change.
     *
     * Called with the engine's answer to tools/call. A failure is not a change - the engine
     * leaves the design as it was - unless a batch says it could not roll back, in which case
     * the honest answer is a full refresh.
     */
    fun deltaFor(tool: String, arguments: JSONObject, result: JSONObject): DocumentDelta? {
        if (!changesDocument(tool, arguments)) return null
        val failed = McpToolResults.isError(result)
        val json = McpToolResults.json(result)
        if (failed) {
            val rolledBack = json?.optBoolean("rolled_back", true) ?: true
            if (rolledBack) return null
            return DocumentDelta(tool = tool, command = arguments.optString("command").takeIf { it.isNotBlank() }, fullRefresh = true)
        }
        if (json == null) {
            // A tool that answers with prose (or an image) still reported a change by being in
            // DOCUMENT_TOOLS; re-read the document rather than guess.
            return DocumentDelta(tool = tool, fullRefresh = true)
        }
        return when (tool) {
            "execute" -> deltaFromExecute(arguments, json)
            "batch" -> deltaFromBatch(arguments, json)
            "open" -> DocumentDelta(
                tool = tool,
                fullRefresh = true,
                openedPath = arguments.optString("path").takeIf { it.isNotBlank() },
            )
            "set_parameter", "undo", "redo", "new_design" ->
                DocumentDelta(tool = tool, fullRefresh = true)
            else -> DocumentDelta(tool = tool, fullRefresh = true)
        }
    }

    private fun deltaFromExecute(arguments: JSONObject, json: JSONObject): DocumentDelta {
        val command = arguments.optString("command").trim()
        if (commandNeedsFullRefresh(command)) {
            return DocumentDelta(tool = "execute", command = command.takeIf { it.isNotBlank() }, fullRefresh = true)
        }
        if (!hasBodyFields(json)) {
            // A sketch edit or a view change: it may still have moved geometry (a suppressed
            // feature, a visibility toggle), so it is not safe to assume nothing changed.
            return DocumentDelta(
                tool = "execute",
                command = command.takeIf { it.isNotBlank() },
                fullRefresh = !command.trim().startsWith("sketch."),
            )
        }
        val created = bodyNames(json, "created_bodies")
        val changed = bodyNames(json, "changed_bodies")
        val removed = bodyNames(json, "removed_bodies")
        // A body the same command removed cannot be exported to repaint, whatever else the
        // report says about it.
        return DocumentDelta(
            tool = "execute",
            command = command.takeIf { it.isNotBlank() },
            createdBodies = created.filterNot { it in removed },
            changedBodies = changed.filterNot { it in removed },
            removedBodies = removed,
        )
    }

    private fun deltaFromBatch(arguments: JSONObject, json: JSONObject): DocumentDelta {
        val created = LinkedHashSet<String>()
        val changed = LinkedHashSet<String>()
        val removed = LinkedHashSet<String>()
        var fullRefresh = false
        val steps = arguments.optJSONArray("commands")
        if (steps != null) {
            for (index in 0 until steps.length()) {
                val step = steps.optJSONObject(index) ?: continue
                val name = step.optString("command")
                val params = step.optJSONObject("params") ?: JSONObject()
                if (name == "execute") {
                    if (commandNeedsFullRefresh(params.optString("command"))) fullRefresh = true
                } else if (name in setOf("set_parameter", "undo", "redo", "new_design", "open")) {
                    fullRefresh = true
                } else if (name.contains('.') && commandNeedsFullRefresh(name)) {
                    fullRefresh = true
                }
            }
        }
        val results = json.optJSONArray("results")
        if (results != null) {
            for (index in 0 until results.length()) {
                val stepResult = results.optJSONObject(index) ?: continue
                if (!hasBodyFields(stepResult)) continue
                created += bodyNames(stepResult, "created_bodies")
                changed += bodyNames(stepResult, "changed_bodies")
                removed += bodyNames(stepResult, "removed_bodies")
            }
        } else {
            fullRefresh = true
        }
        // A body that was created and then removed inside one batch does not exist at the end.
        created -= removed
        changed -= removed
        return DocumentDelta(
            tool = "batch",
            createdBodies = created.toList(),
            changedBodies = changed.toList(),
            removedBodies = removed.toList(),
            fullRefresh = fullRefresh,
        )
    }

    private fun hasBodyFields(json: JSONObject): Boolean =
        json.has("created_bodies") || json.has("changed_bodies") || json.has("removed_bodies")

    /** Body names from one of the engine's body-list fields; accepts strings or {name} objects. */
    fun bodyNames(json: JSONObject, key: String): List<String> {
        val array = json.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            when (val value = array.opt(index)) {
                is String -> value.takeIf { it.isNotBlank() }
                is JSONObject -> value.optString("name").takeIf { it.isNotBlank() }
                else -> null
            }
        }
    }

    private val NON_CHANGING_COMMANDS = setOf(
        "document.inspect",
        "engine.commands",
        "engine.describe",
        "file.slicers",
        "file.export",
        "file.save",
        "file.save_as",
        "file.save_mesh",
        "render.status",
        "render.backends",
        "render.settings.get",
    )
}
