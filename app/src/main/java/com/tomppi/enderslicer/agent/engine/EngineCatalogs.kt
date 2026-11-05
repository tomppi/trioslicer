package com.tomppi.enderslicer.agent.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Blender and CAD engines' commands, as MCP tools.
 *
 * The engines speak their own type/params wire and have no tools/list of their own, so the
 * catalogue lives here - one entry per command the engine's dispatcher knows, with the
 * parameter shapes read from the engine's own handler signatures. The names are the engine's
 * command names verbatim: a selector switch must not rename what the engine runs.
 */
internal object EngineCatalogs {

    private fun tool(
        name: String,
        description: String,
        properties: JSONObject = JSONObject(),
        required: List<String> = emptyList(),
    ): JSONObject {
        val schema = JSONObject().put("type", "object").put("properties", properties)
        if (required.isNotEmpty()) {
            schema.put("required", JSONArray().also { required.forEach(it::put) })
        }
        return JSONObject()
            .put("name", name)
            .put("description", description)
            .put("inputSchema", schema)
    }

    private fun str(description: String) = JSONObject().put("type", "string").put("description", description)
    private fun num(description: String) = JSONObject().put("type", "number").put("description", description)
    private fun int(description: String) = JSONObject().put("type", "integer").put("description", description)
    private fun bool(description: String) = JSONObject().put("type", "boolean").put("description", description)

    /** The CAD engine (build123d on OCCT): cad_mcp_slim.py's dispatcher, command for command. */
    fun cadTools(): JSONArray = JSONArray().put(
        tool("execute_code", "Run Python in the CAD engine (build123d, OCP and the scene helpers are " +
            "pre-imported; the namespace is fresh each call). The general-purpose tool: build and " +
            "inspect exact geometry with it.",
            JSONObject().put("code", str("The Python to run.")), listOf("code")),
    ).put(
        tool("get_scene_info", "What the scene holds: every shape's name, dimensions and position."),
    ).put(
        tool("get_object_info", "One shape's metrics by name; the scene's own when no name is given.",
            JSONObject().put("name", str("Shape name; omit for the scene."))),
    ).put(
        tool("get_metrics", "Volume, area and bounding box of a shape.",
            JSONObject().put("name", str("Shape name; omit for the whole scene."))),
    ).put(
        tool("export_step", "Export one shape (or the scene) as STEP - the file that carries design intent.",
            JSONObject().put("filepath", str("Destination path in the engine workspace."))
                .put("name", str("Shape name; omit for the scene.")), listOf("filepath")),
    ).put(
        tool("export_stl", "Export one shape (or the scene) as a binary STL. Exported files appear on the plate.",
            JSONObject().put("filepath", str("Destination path in the engine workspace."))
                .put("name", str("Shape name; omit for the scene."))
                .put("tolerance", num("Mesh tolerance in mm (default 0.1; relative in build123d terms).")), listOf("filepath")),
    ).put(
        tool("export_mesh", "Export one shape (or the scene) as OBJ/glTF/3MF by format or extension.",
            JSONObject().put("filepath", str("Destination path in the engine workspace."))
                .put("name", str("Shape name; omit for the scene."))
                .put("format", str("obj, gltf or 3mf; inferred from the extension when omitted.")), listOf("filepath")),
    ).put(
        tool("describe_at", "What is at a point: the face under it (planar, cylindrical...) and its geometry.",
            JSONObject().put("x", num("X in mm.")).put("y", num("Y in mm.")).put("z", num("Z in mm."))
                .put("name", str("Limit the search to one shape.")), listOf("x", "y", "z")),
    ).put(
        tool("view", "The engine's own viewport: turn the camera, or pick the face under a point. " +
            "The camera persists between calls. Renders are for reports - the plate is the live view.",
            JSONObject().put("filepath", str("Where to write the rendered frame (PNG)."))
                .put("name", str("Shape name; omit for the scene."))
                .put("turn_yaw", num("Yaw turn in degrees.")).put("turn_pitch", num("Pitch turn in degrees."))
                .put("pan_dx", num("Pan X in viewport units.")).put("pan_dy", num("Pan Y in viewport units."))
                .put("zoom", num("Zoom multiplier."))
                .put("select_x", num("Viewport X to pick a face at.")).put("select_y", num("Viewport Y to pick a face at."))
                .put("reset", bool("Reset the camera first."))
                .put("width", int("Frame width in pixels.")).put("height", int("Frame height in pixels."))
                .put("shaded", bool("Shaded (true) or wireframe (false)."))
                .put("orientation", str("iso, front, back, left, right, top or bottom."))
                .put("antialiasing", bool("Antialias the frame.")), listOf("filepath")),
    ).put(
        tool("analyze", "Will it print: thin walls vs the nozzle, overhang needing support, and bed fit.",
            JSONObject().put("name", str("Shape name; omit for the scene."))
                .put("nozzle", num("Nozzle diameter in mm.")).put("layer", num("Layer height in mm."))
                .put("overhang_deg", num("Overhang threshold in degrees."))
                .put("bed_x", num("Bed width in mm.")).put("bed_y", num("Bed depth in mm."))
                .put("bed_z", num("Build height in mm.")).put("samples", int("Sampling density."))),
    ).put(
        tool("section", "Cut one shape with a plane and export the section as SVG/DXF.",
            JSONObject().put("name", str("Shape name; omit for the scene."))
                .put("axis", str("x, y or z.")).put("offset", num("Plane offset in mm."))
                .put("out", str("Destination path; must end in .svg or .dxf.")), listOf("out")),
    ).put(
        tool("import_file", "Open a STEP/IGES/3MF/OBJ/glTF/STL file into the scene as a shape.",
            JSONObject().put("filepath", str("Path in the engine workspace."))
                .put("name", str("Name for the imported shape."))
                .put("unit", str("mm, cm, m or in (default mm)."))
                .put("replace", bool("Replace a same-named shape (default true).")), listOf("filepath")),
    ).put(
        tool("clearance", "Measure the clearance between a shape and a point cloud.",
            JSONObject().put("cloud", str("The point cloud to measure against."))
                .put("name", str("Shape name; omit for the scene."))
                .put("x", num("Probe point X.")).put("y", num("Probe point Y.")).put("z", num("Probe point Z."))
                .put("samples", int("Sampling density.")), listOf("cloud")),
    ).put(
        tool("viewer_settings", "The viewer's appearance: tessellation, background, grid, axes, projection, edges.",
            JSONObject().put("tessellation", num("Tessellation quality."))
                .put("background", str("Background colour.")).put("grid", bool("Show the grid."))
                .put("grid_step_mm", num("Grid spacing in mm.")).put("axes", bool("Show the axes."))
                .put("projection", str("perspective or orthographic.")).put("edges", bool("Show edges."))
                .put("antialiasing", bool("Antialias the view."))),
    ).put(
        tool("render", "Draw the scene (or one shape) offscreen and write a PNG - what the agent sees.",
            JSONObject().put("filepath", str("Destination PNG path."))
                .put("name", str("Shape name; omit for the scene."))
                .put("view", str("iso, front, back, left, right, top or bottom."))
                .put("width", int("Width in pixels.")).put("height", int("Height in pixels."))
                .put("shaded", bool("Shaded (true) or wireframe (false)."))
                .put("highlight_faces", str("Faces to highlight."))
                .put("highlight_overhang", str("Overhang highlight mode.")), listOf("filepath")),
    ).put(
        tool("get_addon_info", "The engine's identity: versions and what it is built with."),
    )

    /** The Blender engine: blender_mcp_slim.py's dispatcher. */
    fun blenderTools(): JSONArray = JSONArray().put(
        tool("execute_code", "Run bpy Python in the embedded Blender scene. The general-purpose " +
            "tool: build and edit the scene with it, then export_stl to hand a model to the plate.",
            JSONObject().put("code", str("The bpy Python to run.")), listOf("code")),
    ).put(
        tool("get_scene_info", "What the Blender scene holds: objects, dimensions, the scene name."),
    ).put(
        tool("get_object_info", "One object's dimensions and transform by name; the scene's own when omitted.",
            JSONObject().put("name", str("Object name; omit for the scene."))),
    ).put(
        tool("export_stl", "Export all scene meshes as one binary STL. The file hot-loads onto the plate.",
            JSONObject().put("filepath", str("Destination path in the engine workspace (files/blender/exports).")), listOf("filepath")),
    ).put(
        tool("get_world_state_snapshot", "A snapshot of the scene state the engine keeps for the workspace."),
    ).put(
        tool("get_addon_info", "The engine's identity: Blender version and what the addon reports."),
    )

    /** The selector's own tools - always listed, whatever engine is active. */
    fun selectorTools(): JSONArray = JSONArray().put(
        tool("engine_select", "Select which engine this conversation works with. The answer names " +
            "the engine, how to reach its tools and the rules that govern it; tools/list then shows " +
            "only that engine's tools. SolidCraft is the default.",
            JSONObject().put("engine", str("solidcraft, cad or blender.")), listOf("engine")),
    ).put(
        tool("engine_status", "Which engine is selected and whether each engine is running."),
    )

    /** The per-engine rules the selector's answer carries. */
    fun reachAndRules(id: String): String = when (id) {
        "solidcraft" ->
            "SolidCraft is the live CAD document on the plate. Reach it with its own tool names " +
            "through this same server. Rules: the plate repaints as your edits land - what you " +
            "build is what the user sees, immediately. The document has one author at a time: while " +
            "the in-app agent works, calls are refused with -32010 - wait and retry. There is no " +
            "screenshot tool: the plate is the viewport and the user's camera is theirs - ask them " +
            "to look. Files you export are the document's own (STEP/STL/3MF); converting the " +
            "document to plate models is the human's press, never yours."
        "cad" ->
            "The CAD engine is exact geometry: build123d on OpenCASCADE, Python via execute_code " +
            "(build123d, OCP and the scene helpers are pre-imported; each call is a fresh " +
            "namespace). Reach it with its own tool names through this same server. Rules: " +
            "export_stl/export_mesh/export_step write into the engine workspace and exported " +
            "meshes appear on the plate as their own objects. render draws what the engine sees " +
            "for reports; the plate is the live view. Import units matter - say them."
        "scanruler" ->
            "ScanRuler is the embedded scan-measurement workspace the user's viewport shows. Reach " +
            "it with the page's own tool names through this same server. Rules: read the notes " +
            "first (reverse_note_list) - they are the user's pinned instructions - and answer and " +
            "resolve them (reverse_note_add / reverse_note_resolve) as you work. Measurements are " +
            "taken on the loaded scan; never invent a scan or a project file: if the page has no " +
            "scan, ask the user to load one. Files you export are saved by the app into " +
            "Download/ScanRuler and the tool result names the content URI. Everything you do is " +
            "visible on the page - keep replies short and refer to the page's own numbers."
        "blender" ->
            "The Blender engine is the embedded Blender 3.6 scene, bpy via execute_code. Reach it " +
            "with its own tool names through this same server. Rules: the scene stays alive " +
            "between calls - edit it, do not rebuild from scratch. export_stl writes one binary " +
            "STL of the whole scene and it hot-loads onto the plate as its own object; export new " +
            "file names (the plate dedupes by name and time). Do not slice here - the app's own " +
            "engines do that."
        else -> "Unknown engine."
    }

    /** What the selector answers with: the surface's own reach text and its readiness. */
    fun selectorAnswer(displayName: String, rules: String, running: String): String =
        "Selected engine: " + displayName + ". " + rules + " Readiness: " + running + "."
}
