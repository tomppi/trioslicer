package com.tomppi.enderslicer.harness

/**
 * Which engine the user is working with, decided by the menu they are in.
 *
 * The agent cannot see the app's screen. Both engines are reachable from the same harness
 * session and each has a skill, so without being told, the agent picks by guessing from the
 * wording of the request - and "make the bracket thinner" reads exactly the same whether the
 * model was built in Blender or in CAD. The menu the user is standing in is the only thing
 * that actually knows, so that is what gets sent.
 *
 * The line goes in front of the user's own words rather than replacing them: the words are
 * what the agent acts on, and this is what disambiguates them. What the chat displays stays
 * the user's text alone, so the conversation reads the way they typed it.
 */
internal enum class EngineMode(
    /** What the user calls it, and what the menu is labelled. */
    val label: String,
    /** The skill that knows how to drive it. */
    val skill: String,
    /** What that engine is for, in the terms the skills themselves use. */
    val scope: String,
    /** Where the engine listens, for the one engine the agent drives over an MCP bridge. */
    private val bridge: String = "",
) {
    BLENDER(
        label = "Blender",
        skill = "blender-mcp-engine",
        scope = "editing an existing mesh: reshaping, deepening, fixing walls",
    ),
    CAD(
        label = "CAD",
        skill = "cad-engine",
        scope = "parametric modelling: exact geometry, real dimensions, STEP in and out",
    ),
    SOLIDCRAFT(
        label = "SolidCraft",
        skill = "solidcraft-engine",
        scope = "the live CAD document the main plate viewport draws: parametric features, " +
            "exact geometry, STEP in and out, every change visible on the plate as it is made",
        bridge = "The engine is this phone's own SolidCraft process: its MCP bridge is on " +
            "port 9878. The CAD agent sheet's Copy on the SolidCraft row already puts the " +
            "whole hand-over on the clipboard as one line - tcp://<device address>:9878 " +
            "token=<token> when Reachable on the network is on - use its URL as the address " +
            "and its token on every message; with the switch off the bridge is loopback only " +
            "(127.0.0.1:9878, reached through adb forward tcp:9878 tcp:9878). The app renders " +
            "the document itself - never use the engine's screenshot tool - and the plate " +
            "follows every edit live.",
    ),
    ;

    /**
     * What the agent is told, in front of every prompt sent from this menu.
     *
     * The other engine is named as well, and deliberately: "use CAD" on its own still
     * leaves an agent that has just been reading Blender material free to reach for bpy,
     * and the failure is silent - it produces a mesh that looks right and cannot be
     * dimensioned.
     */
    val preamble: String
        get() = "[The user is in the $label menu and is working with the $label engine " +
            "($scope). Use the $skill skill for this request. The other engine is not the " +
            "one in view." + if (bridge.isBlank()) "" else " $bridge" + "]"

    /** The user's text with the engine stated in front of it. */
    fun prompt(text: String): String = preamble + "\n\n" + text
}
