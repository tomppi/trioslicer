package com.tomppi.enderslicer.agent.engine

import android.content.Context
import com.tomppi.enderslicer.cadagent.AgentException

/**
 * The in-app CAD and Blender agents' runbooks.
 *
 * Host-side agents read `docs/skills/` through the harness skill catalog. The phone's agent
 * cannot open a file on the computer, so the same documents travel in the APK:
 * `assets/engine-runbooks/` holds committed copies kept byte-identical by
 * `scripts/publish-skills.mjs`, and `assets/agent/solidcraft-engine.md` is staged by
 * `:app:copyAgentRunbook`. One source, two deliveries: the in-app runbook and the external skill
 * cannot drift silently.
 *
 * What the runbooks cannot know is that *this* agent is already inside the engine: no bridge, no
 * port, no token, no computer. [liveRules] says so, and repeats the measured rules an agent must
 * not learn the hard way (the 300 s gate, the point-in-solid cost, the silent-boolean trap).
 */
object EngineRunbook {

    private const val ENGINE_RUNBOOKS = "engine-runbooks/"
    private const val PLATE_RUNBOOK = "agent/solidcraft-engine.md"

    /** The APK documents for one engine, in reading order. Empty for the page-owned ScanRuler. */
    fun documents(engine: AgentEngine): List<String> = when (engine) {
        AgentEngine.CAD -> listOf(
            ENGINE_RUNBOOKS + "cad-engine.md",
            ENGINE_RUNBOOKS + "occt-engine-rules.md",
        )
        AgentEngine.BLENDER -> listOf(ENGINE_RUNBOOKS + "blender-mcp-engine.md")
        AgentEngine.SOLIDCRAFT -> listOf(PLATE_RUNBOOK)
        AgentEngine.SCANRULER -> emptyList()
    }

    /**
     * The rules only the phone agent needs: it is already connected, the plate is the user's
     * viewport, and the engine's measured costs are the difference between a part and a wedged
     * engine. Every line here was a way an agent got it wrong while a runbook read as satisfied.
     */
    fun liveRules(engine: AgentEngine): String = when (engine) {
        AgentEngine.CAD -> CAD_LIVE_RULES
        AgentEngine.BLENDER -> BLENDER_LIVE_RULES
        else -> ""
    }

    /** The whole system prompt: the live rules first, then the staged documents. */
    fun systemPrompt(engine: AgentEngine, runbooks: String): String = buildString {
        append(liveRules(engine))
        append("\n\n---\n\n")
        append(
            "Below are the runbooks for the " + engine.label + " engine your tools drive - the " +
                "same documents the app ships to computer agents as skills, so they are the " +
                "tool-surface reference. This agent runs inside the phone: ignore every " +
                "connection instruction in them.\n\n"
        )
        append(runbooks)
    }

    /**
     * The staged documents for [engine], joined as one reference.
     *
     * @throws AgentException when an asset is missing or empty - a build that skipped the copy
     *   task is a packaging failure, and the sheet can say that instead of running an agent with
     *   no documentation of its own tools.
     */
    fun load(context: Context, engine: AgentEngine): String {
        val documents = documents(engine)
        check(documents.isNotEmpty()) { engine.label + " has no staged runbook" }
        return documents.joinToString("\n\n---\n\n") { assetPath ->
            try {
                context.applicationContext.assets.open(assetPath)
                    .bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                    .takeIf { it.isNotBlank() }
                    ?: throw AgentException(
                        "runbook-missing",
                        "The agent runbook asset is empty (" + assetPath + "); this build " +
                            "cannot run the " + engine.label + " agent.",
                    )
            } catch (error: AgentException) {
                throw error
            } catch (error: Throwable) {
                throw AgentException(
                    "runbook-missing",
                    "The agent runbook asset is missing from this APK (" + assetPath + "); " +
                        "this build cannot run the " + engine.label + " agent.",
                    error,
                )
            }
        }
    }

    /** The CAD agent: the same facts the host-side occt-engine-rules skill carries. */
    private val CAD_LIVE_RULES: String = """You are the CAD agent inside TrioSlicer, an Android 3D-printing app. The tools listed with this conversation are already yours: they run inside this phone against the app's own build123d/OpenCASCADE engine - nothing to connect, no bridge, no port, no token, no computer. Ignore the connection instructions in the runbooks below; they are for an agent running on a computer.

- The plate is the user's viewport. The engine's render draws a picture for you; the user is already looking at the plate, so never tell them to refresh or reload.
- Work in millimetres, Z up, and build the part where it should print: on the bed, positive Z, inside the build volume. Verify with get_metrics and the cheap geometry checks below, and take back a wrong edit rather than stacking a second one on top.
- One command at a time on one thread: a long command blocks every caller. Estimate unit cost x count before any loop and print progress every few percent.
- A call is answered after 300 s and the engine keeps running it; there is no cancel. Chunk work below about 4 minutes, or tell the user what it will take.
- Never loop point-in-solid: is_inside costs about 67 ms per point on a threaded part (60,000 points is about 67 minutes and wedges the engine). The same facts come from a face-type census (about 0.05 s), B-spline surface sampling for diameters and depth (about 0.07 s), a local dz/dtheta for pitch (about 0.013 s), or the section tool when a 2D profile is the deliverable (about 0.03 s on a simple part, about 8.5 s on a threaded one).
- Verify every boolean changed the volume and the face count: OCCT can silently return the input unchanged. If it did not change, adjust the overlap instead of proceeding.
- describe_at names the nearest sub-shape (a pick helper); it is not an inside/outside test.
- execute_code returns prints only, and an exception returns its message with the prints lost - print progress and expected values as you go.
- If a tool times out, do not resend it: the engine may still be running it. Report the state; restarting the engine from the CAD menu is the only recovery.
- Answer with what changed and its dimensions, briefly. If a tool fails, say what the engine said instead of retrying it blindly."""

    /** The Blender agent: what the host-side blender-mcp-engine skill carries. */
    private val BLENDER_LIVE_RULES: String = """You are the Blender agent inside TrioSlicer, an Android 3D-printing app. The tools listed with this conversation are already yours: they run inside this phone against the app's own embedded Blender 3.6 - nothing to connect, no bridge, no port, no token, no computer. Ignore the connection instructions in the runbook below; they are for an agent running on a computer.

- The scene stays alive between calls: edit it, do not rebuild it from scratch.
- export_stl writes one binary STL of the whole scene and it hot-loads onto the plate as its own object; give every export a new file name so the plate does not dedupe it.
- execute_code runs bpy; prints are the only output returned, and an exception returns its message with the prints lost.
- The plate is the user's viewport. Do not slice here - the app's own slicers do that. Say what changed and its dimensions, briefly."""
}
