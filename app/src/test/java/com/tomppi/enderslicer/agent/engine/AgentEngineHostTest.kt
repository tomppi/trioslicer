package com.tomppi.enderslicer.agent.engine

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The in-app lane over the universal tool surfaces: the engine naming both lanes share, and
 * the honesty rules a tool call carries back to the model.
 */
class AgentEngineHostTest {

    private class FakeSurface : EngineToolSurface {
        override val id: String = "cad"
        override val displayName: String = "CAD engine"
        var reply: JSONObject = JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "ran")))
        var failure: Throwable? = null

        override fun isRunning(): Boolean = true
        override fun reachAndRules(): String = EngineCatalogs.reachAndRules(id)
        override fun tools(): JSONArray = EngineCatalogs.cadTools()

        override fun call(name: String, arguments: JSONObject, timeoutMillis: Long): JSONObject {
            failure?.let { throw it }
            return reply
        }
    }

    @Test
    fun theInstructionsNameTheEngineHowToReachItAndItsReadiness() {
        val text = AgentEngineContext.instructions(AgentEngine.CAD, "running")

        assertTrue("names the engine: " + text, text.contains("OCCT CAD"))
        assertTrue("names the tools: " + text, text.contains("execute_code"))
        assertTrue("names the readiness: " + text, text.contains("Readiness: running"))
    }

    @Test
    fun everyEngineHasInstructions() {
        AgentEngine.entries.forEach { engine ->
            val text = AgentEngineContext.instructions(engine, "running")
            assertTrue(engine.label + " instructions name its label", text.contains(engine.label))
        }
    }

    @Test
    fun aToolResultBecomesTheAgentsTextWithoutThrowing() {
        val host = WireEngineAgentHost(FakeSurface())
        val result = host.call("execute_code", JSONObject())

        assertEquals("ran", result.text)
        assertEquals(false, result.failed)
    }

    @Test
    fun aTimeoutSaysTheCallMayStillHaveRun() {
        val surface = FakeSurface().also { it.failure = EngineTimeoutException(300_000) }

        val result = WireEngineAgentHost(surface).call("slow", JSONObject())

        assertTrue(result.failed == true)
        assertTrue(result.text.contains("may still have run"))
    }

    @Test
    fun anUnavailableEngineNamesItsMenu() {
        val surface = FakeSurface().also { it.failure = EngineUnavailableException("down") }

        val result = WireEngineAgentHost(surface).call("any", JSONObject())

        assertTrue(result.failed == true)
        assertTrue(result.text.contains("start it in the app"))
    }

    @Test
    fun theScanRulerSurfaceAnswersBeforeItsWorkspaceExists() {
        val surface = ScanRulerToolSurface(session = { null })

        assertEquals("scanruler", surface.id)
        assertEquals(0, surface.tools().length())
        val error = runCatching { surface.call("scanruler_status", JSONObject(), 1_000) }.exceptionOrNull()
        assertTrue("a missing workspace is an unavailable engine", error is EngineUnavailableException)
    }

    @Test
    fun theSelectorIdsMatchTheExternalServer() {
        assertEquals("solidcraft", AgentEngine.SOLIDCRAFT.id)
        assertEquals("cad", AgentEngine.CAD.id)
        assertEquals("blender", AgentEngine.BLENDER.id)
        assertEquals("scanruler", AgentEngine.SCANRULER.id)
        assertEquals(AgentEngine.CAD, AgentEngine.fromId("CAD"))
    }
}
