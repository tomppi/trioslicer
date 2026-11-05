package com.tomppi.enderslicer.scanruler.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanRulerCommandCatalogTest {

    private fun command(name: String): JSONObject = JSONObject()
        .put("name", name)
        .put("title", "A title")
        .put("description", "What it does.")
        .put("input", JSONObject("""{"type":"object","properties":{"x":{"type":"string"}}}"""))
        .put("readOnly", name.endsWith("list"))

    @Test
    fun dotsBecomeUnderscoresForTools() {
        val command = ScanRulerCommandCatalog.Command(
            name = "reverse.note.add",
            title = null,
            description = null,
            input = JSONObject(),
            readOnly = false,
            returnsFile = false,
        )
        assertEquals("reverse_note_add", command.toolName)
    }

    @Test
    fun toolNamesResolveThroughTheCatalogueIncludingUnderscores() {
        val commands = ScanRulerCommandCatalog.parse(JSONArray()
            .put(command("body.compare_in_deviation"))
            .put(command("scan.tube_trace")))
        assertEquals(
            "body.compare_in_deviation",
            ScanRulerCommandCatalog.commandForTool(commands, "body_compare_in_deviation")?.name,
        )
        assertEquals(
            "scan.tube_trace",
            ScanRulerCommandCatalog.commandForTool(commands, "scan_tube_trace")?.name,
        )
        assertNull(ScanRulerCommandCatalog.commandForTool(commands, "body.compare.in.deviation"))
    }

    @Test
    fun theWaitToolPromisesOnlyThePairingWait() {
        val tools = ScanRulerCommandCatalog.mcpTools(emptyList())
        val wait = tools.getJSONObject(1)
        assertEquals("scanruler_wait", wait.getString("name"))
        assertTrue(wait.getString("description").contains("pair"))
        assertFalse(wait.getString("description").contains("running command"))
    }

    @Test
    fun theSchemaAndReadOnlyFlagPassThrough() {
        val tools = ScanRulerCommandCatalog.mcpTools(
            ScanRulerCommandCatalog.parse(JSONArray().put(command("reverse.note.list"))),
        )
        val tool = tools.getJSONObject(0)
        assertEquals("reverse_note_list", tool.getString("name"))
        assertEquals("object", tool.getJSONObject("inputSchema").getString("type"))
        assertEquals(true, tool.getJSONObject("annotations").getBoolean("readOnlyHint"))
    }

    @Test
    fun theHelpersAreAlwaysOffered() {
        val names = ArrayList<String>()
        val tools = ScanRulerCommandCatalog.mcpTools(emptyList())
        for (index in 0 until tools.length()) names += tools.getJSONObject(index).getString("name")
        assertTrue(names.contains("scanruler_status"))
        assertTrue(names.contains("scanruler_wait"))
        assertEquals(2, names.size)
    }

    @Test
    fun aMalformedHelloYieldsNoCommandsRatherThanCrashing() {
        val parsed = ScanRulerCommandCatalog.parse(JSONArray().put(JSONObject().put("title", "no name")))
        assertTrue(parsed.isEmpty())
    }
}
