package com.tomppi.enderslicer.solidcraft

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the live view learns from an agent's call.
 *
 * The payloads are the exact shapes the real 0.22.1 engine was verified to send on the phone:
 * execute answers with created/changed/removed body names and no error flag, undo and redo
 * answer without any body fields, and a failed tool is a result with isError set.
 */
class SolidCraftToolsTest {

    private fun toolResult(json: JSONObject, isError: Boolean = false): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", json.toString())))
        .apply { if (isError) put("isError", true) }

    private fun executeArguments(command: String): JSONObject =
        JSONObject().put("command", command).put("params", JSONObject())

    @Test
    fun aBoxReportsItsCreatedBody() {
        val result = toolResult(
            JSONObject()
                .put("bodies", JSONArray().put("Body1"))
                .put("created_bodies", JSONArray().put("Body1"))
                .put("changed_bodies", JSONArray())
                .put("removed_bodies", JSONArray())
                .put("feature", 1),
        )
        val delta = SolidCraftTools.deltaFor("execute", executeArguments("solid.box"), result)!!
        assertEquals(listOf("Body1"), delta.createdBodies)
        assertEquals(listOf("Body1"), delta.repaintBodies)
        assertFalse(delta.fullRefresh)
    }

    @Test
    fun aFilletReportsItsChangedBody() {
        val result = toolResult(
            JSONObject()
                .put("bodies", JSONArray().put("Body1"))
                .put("created_bodies", JSONArray())
                .put("changed_bodies", JSONArray().put("Body1"))
                .put("removed_bodies", JSONArray()),
        )
        val delta = SolidCraftTools.deltaFor("execute", executeArguments("solid.fillet"), result)!!
        assertEquals(listOf("Body1"), delta.changedBodies)
        assertFalse(delta.fullRefresh)
    }

    @Test
    fun anUndoHasNoBodyReportSoTheWholeDocumentIsReRead() {
        val result = toolResult(JSONObject().put("undone", "Fillet1"))
        val delta = SolidCraftTools.deltaFor("undo", JSONObject(), result)!!
        assertTrue(delta.fullRefresh)
    }

    @Test
    fun aParameterEditRebuildsTheDocument() {
        val result = toolResult(JSONObject().put("param", "width"))
        val delta = SolidCraftTools.deltaFor(
            "set_parameter",
            JSONObject().put("name", "width").put("value", "60 mm"),
            result,
        )!!
        assertTrue(delta.fullRefresh)
    }

    @Test
    fun anOpenedFileNamesTheNewDocument() {
        val result = toolResult(JSONObject().put("bodies", JSONArray().put("part")))
        val delta = SolidCraftTools.deltaFor(
            "open",
            JSONObject().put("path", "/data/imports/part.step"),
            result,
        )!!
        assertTrue(delta.fullRefresh)
        assertEquals("/data/imports/part.step", delta.openedPath)
    }

    @Test
    fun aBatchAggregatesTheBodiesItsStepsChanged() {
        val result = toolResult(
            JSONObject()
                .put("ok", true)
                .put("completed", 2)
                .put(
                    "results",
                    JSONArray()
                        .put(JSONObject().put("created_bodies", JSONArray().put("Body1")))
                        .put(JSONObject().put("changed_bodies", JSONArray().put("Body1"))),
                ),
        )
        val arguments = JSONObject().put(
            "commands",
            JSONArray()
                .put(JSONObject().put("command", "solid.box").put("params", JSONObject()))
                .put(JSONObject().put("command", "solid.fillet").put("params", JSONObject())),
        )
        val delta = SolidCraftTools.deltaFor("batch", arguments, result)!!
        assertEquals(listOf("Body1"), delta.createdBodies)
        assertEquals(listOf("Body1"), delta.changedBodies)
        assertFalse(delta.fullRefresh)
    }

    @Test
    fun aBatchThatChangesAParameterIsAFullRefresh() {
        val result = toolResult(
            JSONObject().put("ok", true)
                .put("results", JSONArray().put(JSONObject().put("created_bodies", JSONArray().put("Body1")))),
        )
        val arguments = JSONObject().put(
            "commands",
            JSONArray().put(JSONObject().put("command", "set_parameter").put("params", JSONObject().put("name", "w"))),
        )
        assertTrue(SolidCraftTools.deltaFor("batch", arguments, result)!!.fullRefresh)
    }

    @Test
    fun aFailedToolChangesNothingAndAnUnrolledBackBatchForcesAFullRefresh() {
        val failed = toolResult(JSONObject().put("content", JSONArray()), isError = true)
        assertNull(SolidCraftTools.deltaFor("execute", executeArguments("solid.box"), failed))
        val halfApplied = toolResult(
            JSONObject().put("ok", false).put("rolled_back", false),
            isError = true,
        )
        assertTrue(SolidCraftTools.deltaFor("batch", JSONObject(), halfApplied)!!.fullRefresh)
    }

    @Test
    fun readOnlyAndFileToolsNeverRepaint() {
        assertFalse(SolidCraftTools.changesDocument("inspect_design"))
        assertFalse(SolidCraftTools.changesDocument("measure"))
        assertFalse(SolidCraftTools.changesDocument("export"))
        assertFalse(SolidCraftTools.changesDocument("save"))
        assertFalse(SolidCraftTools.changesDocument("screenshot"))
        assertFalse(SolidCraftTools.commandChangesDocument("document.inspect"))
        assertFalse(SolidCraftTools.commandChangesDocument("model.edges"))
        assertFalse(SolidCraftTools.commandChangesDocument("file.export"))
        assertTrue(SolidCraftTools.commandChangesDocument("solid.extrude"))
        assertTrue(SolidCraftTools.commandChangesDocument("sketch.create"))
    }

    @Test
    fun aRemovedBodyIsNotAlsoAskedForAsAChangedOne() {
        val result = toolResult(
            JSONObject()
                .put("created_bodies", JSONArray().put("Body2"))
                .put("changed_bodies", JSONArray().put("Body1"))
                .put("removed_bodies", JSONArray().put("Body1")),
        )
        val delta = SolidCraftTools.deltaFor("execute", executeArguments("body.delete"), result)!!
        assertEquals(listOf("Body2"), delta.createdBodies)
        assertTrue(delta.changedBodies.isEmpty())
        assertEquals(listOf("Body1"), delta.removedBodies)
    }

    @Test
    fun bodyNamesAcceptObjectsAsWellAsStrings() {
        val json = JSONObject().put(
            "bodies",
            JSONArray().put(JSONObject().put("name", "Body1")).put("Body2"),
        )
        assertEquals(listOf("Body1", "Body2"), SolidCraftTools.bodyNames(json, "bodies"))
    }
}
