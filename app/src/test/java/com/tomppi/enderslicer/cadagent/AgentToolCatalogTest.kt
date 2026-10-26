package com.tomppi.enderslicer.cadagent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalogue is the engine's, converted rather than restated.
 *
 * The fixture is shaped exactly like the 0.22.1 engine's `tools/list` answer (name, title,
 * description, inputSchema, annotations); the conversion must pass the engine's schema through
 * untouched, hide only the five tools the plate cannot use, and add the app's two web tools only
 * when they are allowed.
 */
class AgentToolCatalogTest {

    private fun engineTool(name: String, schema: JSONObject = JSONObject().put("type", "object")): JSONObject =
        JSONObject()
            .put("name", name)
            .put("title", "Title " + name)
            .put("description", "Description of " + name)
            .put("inputSchema", schema)
            .put("annotations", JSONObject().put("readOnlyHint", true))

    private fun engineCatalog(): JSONArray = JSONArray().apply {
        listOf(
            "list_commands", "describe_command", "execute", "batch", "inspect_design", "measure",
            "body_topology", "set_parameter", "screenshot", "export", "crash_reports", "running",
            "pending", "undo", "redo", "new_design", "open", "save", "skill_reference",
        ).forEach { put(engineTool(it)) }
    }

    /**
     * The tools the brief names: the engine's 19 minus the five that do not apply here, plus the
     * app's own plate render.
     */
    private val engineOnlyNames = setOf(
        "list_commands", "describe_command", "execute", "batch", "inspect_design", "measure",
        "body_topology", "set_parameter", "export", "undo", "redo", "new_design", "open", "save",
    )
    // The app's own tools: the plate render and the question the agent can ask the user.
    private val expectedNames = engineOnlyNames + setOf("plate_screenshot", "ask_user")

    @Test
    fun theEngineCatalogueYieldsTheToolsThePlateCanUse() {
        val catalog = AgentToolCatalog.fromEngine(engineCatalog(), includeWebTools = false)
        assertEquals(expectedNames, catalog.names)
        assertEquals(expectedNames.size, catalog.tools.length())
        assertEquals(
            setOf("screenshot", "crash_reports", "running", "pending", "skill_reference"),
            catalog.hiddenNames,
        )
        assertFalse(catalog.isEmpty)
        assertFalse(catalog.includesWebTools)
        // The app's two tools are its own, not the engine's, so they are not "hidden" engine tools.
        assertFalse("web_search" in catalog.names)
        assertFalse("web_fetch" in catalog.names)
    }

    @Test
    fun aWebToolIsAddedOnTopOnlyWhenAllowed() {
        val withWeb = AgentToolCatalog.fromEngine(engineCatalog(), includeWebTools = true)
        assertEquals(expectedNames + setOf("web_search", "web_fetch"), withWeb.names)
        assertTrue(withWeb.includesWebTools)
        // Removing them for an endpoint that does not offer the routes keeps the engine's tools.
        val without = withWeb.withoutWebTools()
        assertEquals("the engine's tools and the plate render stay", expectedNames, without.names)
        assertFalse(without.includesWebTools)
        assertTrue("the plate render is the app's, not the endpoint's", without.includesPlateScreenshot)
    }

    @Test
    fun theEnginesSchemaIsPassedThroughUnchanged() {
        val schema = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put(
                "properties",
                JSONObject().put(
                    "command",
                    JSONObject().put("type", "string").put("description", "the command id"),
                ),
            )
            .put("required", JSONArray().put("command"))
        val catalog = AgentToolCatalog.fromEngine(
            JSONArray().put(engineTool("execute", schema)),
            includeWebTools = false,
        )
        val tool = catalog.tools.getJSONObject(0)
        assertEquals("function", tool.getString("type"))
        val function = tool.getJSONObject("function")
        assertEquals("execute", function.getString("name"))
        assertEquals("Description of execute", function.getString("description"))
        assertEquals(schema.toString(), function.getJSONObject("parameters").toString())
    }

    @Test
    fun aToolNameThatIsNotALegalFunctionNameIsSkipped() {
        val catalog = AgentToolCatalog.fromEngine(
            JSONArray()
                .put(engineTool("execute"))
                .put(engineTool("weird.name")),
            includeWebTools = false,
            includePlateScreenshot = false,
            includeAskUser = false,
        )
        assertEquals(setOf("execute"), catalog.names)
        assertEquals(setOf("weird.name"), catalog.skippedNames)
    }

    @Test
    fun theWebToolDefinitionsAreFunctionToolsWithTypedArguments() {
        val catalog = AgentToolCatalog.fromEngine(
            JSONArray(),
            includeWebTools = true,
            includePlateScreenshot = false,
            includeAskUser = false,
        )
        assertEquals(setOf("web_search", "web_fetch"), catalog.names)
        val search = catalog.tools.getJSONObject(0).getJSONObject("function")
        assertEquals("web_search", search.getString("name"))
        assertTrue(search.getString("description").contains("UNTRUSTED"))
        val parameters = search.getJSONObject("parameters")
        assertEquals("object", parameters.getString("type"))
        assertTrue(parameters.getJSONObject("properties").has("query"))
        assertTrue(parameters.getJSONObject("properties").has("count"))
        assertEquals(1, parameters.getJSONArray("required").length())
        val fetch = catalog.tools.getJSONObject(1).getJSONObject("function")
        assertEquals("web_fetch", fetch.getString("name"))
        assertTrue(fetch.getJSONObject("parameters").getJSONObject("properties").has("url"))
    }

    @Test
    fun thePlateRenderIsAnAppToolWithNoParameters() {
        val catalog = AgentToolCatalog.fromEngine(engineCatalog(), includeWebTools = false)
        assertTrue("the render is offered beside the engine's tools", "plate_screenshot" in catalog.names)
        assertTrue(catalog.includesPlateScreenshot)
        val definition = (0 until catalog.tools.length())
            .map { catalog.tools.getJSONObject(it).getJSONObject("function") }
            .first { it.getString("name") == "plate_screenshot" }
        val parameters = definition.getJSONObject("parameters")
        assertEquals("object", parameters.getString("type"))
        assertEquals(0, parameters.getJSONObject("properties").length())
        assertFalse("nothing to fill in, so nothing to get wrong", parameters.getBoolean("additionalProperties"))
        val description = definition.getString("description")
        assertTrue(description, description.contains("current camera"))
        assertTrue("the selected face is in the picture", description.contains("tinted"))
        // The engine's own screenshot stays hidden: this is the app's renderer, not the engine's.
        assertFalse("screenshot" in catalog.names)
        assertTrue("screenshot" in catalog.hiddenNames)
    }

    @Test
    fun theAskUserToolIsAnAppToolThatEndsTheTurn() {
        val catalog = AgentToolCatalog.fromEngine(engineCatalog(), includeWebTools = false)
        assertTrue("the question tool is offered beside the engine's tools", "ask_user" in catalog.names)
        assertTrue(catalog.includesAskUser)
        val definition = (0 until catalog.tools.length())
            .map { catalog.tools.getJSONObject(it).getJSONObject("function") }
            .first { it.getString("name") == "ask_user" }
        val description = definition.getString("description")
        assertTrue("asking ends the turn: " + description, description.contains("END THE TURN"))
        assertTrue("the answer starts the next turn: " + description, description.contains("next user"))
        val parameters = definition.getJSONObject("parameters")
        assertEquals("object", parameters.getString("type"))
        val properties = parameters.getJSONObject("properties")
        assertEquals("string", properties.getJSONObject("question").getString("type"))
        assertEquals("array", properties.getJSONObject("options").getString("type"))
        assertEquals("boolean", properties.getJSONObject("allow_text").getString("type"))
        assertEquals(1, parameters.getJSONArray("required").length())
        assertEquals("question", parameters.getJSONArray("required").getString(0))
    }

    @Test
    fun aCatalogueBuiltWithoutTheAppToolsIsTheEnginesAlone() {
        val catalog = AgentToolCatalog.fromEngine(
            engineCatalog(),
            includeWebTools = false,
            includePlateScreenshot = false,
            includeAskUser = false,
        )
        assertEquals(engineOnlyNames, catalog.names)
        assertFalse(catalog.includesPlateScreenshot)
        assertFalse(catalog.includesAskUser)
    }
}
