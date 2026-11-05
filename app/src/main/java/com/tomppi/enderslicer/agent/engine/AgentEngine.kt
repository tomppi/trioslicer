package com.tomppi.enderslicer.agent.engine

/**
 * Which modelling engine the conversation is working with.
 *
 * The same selection on both lanes: the external agents take it from engine_select on the
 * unified MCP server, and the in-app agent takes it from the Ask AI window's engine menu.
 * The ids are the selector's own, so one name means one engine everywhere.
 */
enum class AgentEngine(val id: String, val label: String) {
    SOLIDCRAFT("solidcraft", "SolidCraft"),
    CAD("cad", "OCCT CAD"),
    BLENDER("blender", "Blender"),
    SCANRULER("scanruler", "ScanRuler");

    companion object {
        fun fromId(id: String): AgentEngine? = entries.firstOrNull { it.id == id.trim().lowercase() }
    }
}

/**
 * What the agent is told about the engine it is driving: the same reach-and-rules text the
 * external selector answers with, so both lanes hear one story.
 */
internal object AgentEngineContext {

    /** The system-prompt section naming the engine, how to reach its tools and its rules. */
    fun instructions(engine: AgentEngine, readiness: String, extra: String = ""): String =
        "The active tool host for this conversation is " + engine.label + " - one of TrioSlicer's " +
            "modelling engines. " + EngineCatalogs.reachAndRules(engine.id) +
            " Readiness: " + readiness + "." +
            (if (extra.isEmpty()) "" else "\n\n" + extra)

    /** The readiness sentence for the engine menus and the selector. */
    fun readinessOf(running: Boolean): String =
        if (running) "running" else "not started - start it in the app first"
}

/**
 * The universal MCP door's menu, as the chat window shows it. The caller owns the prompts and
 * the clipboard - the window only names what to copy and what is switched on.
 */
data class AgentDoorMenu(
    /** One line saying where the door is, e.g. "MCP server - 127.0.0.1:9878". */
    val doorLine: String,
    /** Whether Reachable on the network is on. */
    val networkOn: Boolean,
    val onToggleNetwork: (Boolean) -> Unit,
    val onCopyConnectLine: () -> Unit,
    val onCopyToken: () -> Unit,
    /** Each engine's readiness sentence for the engine menu. */
    val readiness: Map<AgentEngine, String> = emptyMap(),
    /** Which engines are running right now - the engine menu's Start rows key on this. */
    val running: Map<AgentEngine, Boolean> = emptyMap(),
)
