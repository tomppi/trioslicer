package com.tomppi.enderslicer.solidcraft

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The typed tool surface, against a real child process implementing the engine's protocol.
 *
 * The fake engine answers with the exact body report shapes the real 0.22.1 engine was verified
 * to send on the phone: a box reports created_bodies, a fillet changed_bodies, an export writes
 * the file it was asked for.
 */
class SolidCraftSessionTest {

    private fun withSession(block: (SolidCraftSession) -> Unit) {
        val process = FakeSolidCraftEngine.processBuilder().start()
        val client = McpClient(StreamMcpTransport(process.inputStream, process.outputStream)).start()
        try {
            block(SolidCraftSession(client, clientName = "TrioSlicerTest", clientVersion = "1.9.5"))
        } finally {
            client.close()
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun initializeReadsTheEnginesIdentityAndProtocol() = withSession { session ->
        val result = session.initialize()
        assertEquals("2025-06-18", result.optString("protocolVersion"))
        assertEquals("solidcraft", session.serverName)
        assertEquals("0.22.1", session.serverVersion)
        assertEquals("2025-06-18", session.protocolVersion)
    }

    @Test
    fun listToolsReturnsTheEnginesOwnCatalog() = withSession { session ->
        session.initialize()
        val tools = session.listTools()
        assertTrue("expected the engine's tool list", tools.contains("execute"))
        assertTrue(tools.contains("batch"))
        assertTrue(tools.contains("export"))
    }

    @Test
    fun executeCarriesTheCreatedAndChangedBodies() = withSession { session ->
        session.initialize()
        val box = McpToolResults.json(session.execute("solid.box"))!!
        assertEquals(listOf("Body1"), SolidCraftTools.bodyNames(box, "created_bodies"))
        val fillet = McpToolResults.json(session.execute("solid.fillet"))!!
        assertEquals(listOf("Body1"), SolidCraftTools.bodyNames(fillet, "changed_bodies"))
    }

    @Test
    fun batchExportsEveryBodyInOneCall() = withSession { session ->
        session.initialize()
        val directory = createTempDir(prefix = "solidcraft-session-batch")
        try {
            val result = session.batch(
                listOf(
                    ToolStep(
                        "file.export",
                        JSONObject().put("path", java.io.File(directory, "a.stl").absolutePath)
                            .put("format", "stl").put("bodies", JSONArray().put("Body1")),
                    ),
                    ToolStep(
                        "file.export",
                        JSONObject().put("path", java.io.File(directory, "b.stl").absolutePath)
                            .put("format", "stl").put("bodies", JSONArray().put("Body2")),
                    ),
                ),
            )
            val json = McpToolResults.json(result)!!
            assertTrue(json.optBoolean("ok"))
            assertEquals(2, json.optInt("completed"))
            assertTrue(java.io.File(directory, "a.stl").isFile)
            assertTrue(java.io.File(directory, "b.stl").isFile)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun aToolFailureIsANamedExceptionWithTheEnginesOwnWords() = withSession { session ->
        session.initialize()
        try {
            // The raw call returns the engine's result, error flag and all; requireOk is the
            // place the app turns that into the named failure a caller can act on.
            session.requireOk("fail", session.callTool("fail"))
            fail("expected the engine's refusal")
        } catch (refusal: McpToolException) {
            assertEquals("fail", refusal.engineTool)
            assertTrue(refusal.engineMessage.contains("refused this on purpose"))
        }
    }

    @Test
    fun aTimeoutNamesTheCallAndLeavesTheSessionUsable() = withSession { session ->
        session.initialize()
        try {
            session.callTool("slow", timeoutMillis = 300)
            fail("expected a timeout")
        } catch (timeout: McpTimeoutException) {
            assertEquals("tools/call", timeout.method)
        }
        // The engine is still there and answers the next call: a timeout is a client budget,
        // not a dead session.
        val box = McpToolResults.json(session.execute("solid.box"))!!
        assertEquals(listOf("Body1"), SolidCraftTools.bodyNames(box, "created_bodies"))
    }

    @Test
    fun saveWritesTheDesignTheEngineNames() = withSession { session ->
        session.initialize()
        val directory = createTempDir(prefix = "solidcraft-session-save")
        try {
            val design = java.io.File(directory, "part.solidcraft")
            val result = McpToolResults.json(session.save(design.absolutePath))!!
            assertEquals(design.absolutePath, result.optString("path"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun inspectDesignReportsTheBodiesTheLiveViewReads() = withSession { session ->
        session.initialize()
        val design = session.inspectDesignJson(measure = false, sketches = false)
        val bodies = design.optJSONArray("bodies")
        assertNotNull(bodies)
        assertEquals("Body1", bodies!!.getJSONObject(0).optString("name"))
    }

    private fun createTempDir(prefix: String): java.io.File =
        java.nio.file.Files.createTempDirectory(prefix).toFile()
}
