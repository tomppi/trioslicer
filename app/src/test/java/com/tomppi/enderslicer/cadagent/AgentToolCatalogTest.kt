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

    /** The tools the brief names: the engine's 19 minus the five that do not apply here. */
    private val expectedNames = setOf(
        "list_commands", "describe_command", "execute", "batch", "inspect_design", "measure",
        "body_topology", "set_parameter", "export", "undo", "redo", "new_design", "open", "save",
    )

    @Test
    fun theEngineCatalogueYieldsTheFourteenToolsThePlateCanUse() {
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
        assertEquals(expectedNames, without.names)
        assertFalse(without.includesWebTools)
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
        )
        assertEquals(setOf("execute"), catalog.names)
        assertEquals(setOf("weird.name"), catalog.skippedNames)
    }

    @Test
    fun theWebToolDefinitionsAreFunctionToolsWithTypedArguments() {
        val catalog = AgentToolCatalog.fromEngine(JSONArray(), includeWebTools = true)
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
}
