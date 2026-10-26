package com.tomppi.enderslicer.cadagent

import com.tomppi.enderslicer.solidcraft.DocumentDelta
import com.tomppi.enderslicer.solidcraft.FakeSolidCraftEngine
import com.tomppi.enderslicer.solidcraft.McpClient
import com.tomppi.enderslicer.solidcraft.SolidCraftAgentHost
import com.tomppi.enderslicer.solidcraft.SolidCraftSession
import com.tomppi.enderslicer.solidcraft.StreamMcpTransport
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * The in-app agent's loop, against a scripted OpenAI-compatible HTTP server.
 *
 * Everything is hermetic: the endpoint is a local [FakeOpenAiServer], and the engine side is
 * either a fake [AgentToolHost] or the repository's [FakeSolidCraftEngine] child process - the
 * same one the MCP session tests use. No network, no provider, no key.
 */
class CadAgentTest {

    private lateinit var server: FakeOpenAiServer
    private val logs = CopyOnWriteArrayList<String>()
    private val deltas = CopyOnWriteArrayList<DocumentDelta>()
    private var engineProcess: Process? = null
    private var engineClient: McpClient? = null
    private var session: SolidCraftSession? = null

    @Before
    fun startServer() {
        server = FakeOpenAiServer().start()
    }

    @After
    fun stopServer() {
        server.close()
        engineClient?.close()
        engineClient = null
        session = null
        engineProcess?.let { process ->
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        engineProcess = null
    }

    /** A real child process speaking the engine's protocol, as SolidCraftSessionTest does. */
    private fun startFakeEngineSession(): SolidCraftSession {
        val process = FakeSolidCraftEngine.processBuilder().start()
        engineProcess = process
        val client = McpClient(StreamMcpTransport(process.inputStream, process.outputStream)).start()
        engineClient = client
        return SolidCraftSession(client).also { session = it }
    }

    private fun syntheticCatalog(vararg names: String, includeWebTools: Boolean = false): AgentToolCatalog {
        val tools = JSONArray()
        names.forEach { name ->
            tools.put(
                JSONObject()
                    .put("name", name)
                    .put("description", "the " + name + " tool")
                    .put(
                        "inputSchema",
                        JSONObject().put("type", "object")
                            .put("properties", JSONObject().put("command", JSONObject().put("type", "string"))),
                    ),
            )
        }
        return AgentToolCatalog.fromEngine(tools, includeWebTools = includeWebTools)
    }

    private fun settings(key: String = "sk-test-key"): StoredAgentSettings = StoredAgentSettings(
        settings = AgentSettings(
            baseUrl = server.baseUrl,
            model = "fake-model",
            hasApiKey = key.isNotEmpty(),
        ),
        apiKey = key,
    )

    private fun agent(
        host: AgentToolHost,
        runbook: String = "RUNBOOK-MARKER",
        maxToolRounds: Int = 8,
    ): CadAgent = CadAgent(
        toolHost = host,
        runbook = runbook,
        maxToolRounds = maxToolRounds,
        log = { message -> logs += message },
    )

    /** A host that counts calls and answers with [answer]; used when the engine is not needed. */
    private class RecordingHost(private val answer: (String, JSONObject) -> String = { name, _ -> "ok from " + name }) :
        AgentToolHost {
        val calls = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        override fun call(name: String, arguments: JSONObject): AgentToolHost.Result {
            calls += name to arguments
            return AgentToolHost.Result(answer(name, arguments))
        }
    }

    // --- the round trip --------------------------------------------------------------------

    @Test
    fun aToolCallRoundTripRunsTheToolOnTheEngineAndAnswers() {
        val engine = startFakeEngineSession()
        val host = SolidCraftAgentHost(
            session = { engine },
            onDocumentChanged = { delta -> deltas += delta },
            log = { message -> logs += message },
        )
        server.responder = { request ->
            val askedForTool = request.json?.optJSONArray("messages")?.let { messages ->
                (0 until messages.length()).any { index ->
                    messages.optJSONObject(index)?.optString("role") == "tool"
                }
            } ?: false
            if (askedForTool) {
                FakeOpenAiServer.finalAnswer("A 40 x 30 x 10 box is on the plate.")
            } else {
                FakeOpenAiServer.toolCall(
                    name = "execute",
                    arguments = JSONObject()
                        .put("command", "solid.box")
                        .put(
                            "params",
                            JSONObject().put("length", 40).put("width", 30).put("height", 10),
                        )
                        .toString(),
                )
            }
        }
        val agent = agent(host)
        val catalog = AgentToolCatalog.fromEngine(engine.toolDefinitions())
        val answer = agent.run(settings(), catalog, "a 40 x 30 x 10 box with 3 mm fillets")

        assertEquals("A 40 x 30 x 10 box is on the plate.", answer)
        assertEquals("one tool round: the call, then the answer", 2, server.requests.size)
        // The tool ran in process on the engine, and the live view got the delta the edit names.
        assertEquals(1, deltas.size)
        assertEquals(listOf("Body1"), deltas.first().createdBodies)
        val toolResult = toolResultOf(server.requests[1])
        assertTrue(
            "the model must see the engine's own body report: " + toolResult,
            toolResult.contains("created_bodies"),
        )
        assertTrue(toolResult.contains("Body1"))
    }

    private fun toolResultOf(request: FakeOpenAiServer.RecordedRequest): String {
        val messages = request.json?.optJSONArray("messages") ?: return ""
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString("role") == "tool") return message.optString("content")
        }
        return ""
    }

    // --- the request ----------------------------------------------------------------------

    @Test
    fun theRequestCarriesTheToolsTheSystemPromptAndTheModel() {
        server.responder = { FakeOpenAiServer.finalAnswer("Understood.") }
        val host = RecordingHost()
        val agent = agent(host)
        val catalog = syntheticCatalog("execute", "batch", "inspect_design")
        assertEquals("Understood.", agent.run(settings(), catalog, "make it 2 mm thicker"))

        assertEquals(1, server.requests.size)
        val request = server.requests.first()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer sk-test-key", request.authorization)
        val body = request.json ?: error("the request body is not JSON")
        assertEquals("fake-model", body.getString("model"))
        assertEquals(false, body.getBoolean("stream"))

        val messages = body.getJSONArray("messages")
        val system = messages.getJSONObject(0)
        assertEquals("system", system.getString("role"))
        val systemPrompt = system.getString("content")
        assertTrue("the live rules are in the prompt", systemPrompt.contains("only author"))
        assertTrue("the untrusted-web rule is in the prompt", systemPrompt.contains("UNTRUSTED"))
        assertTrue("the shipped runbook is in the prompt", systemPrompt.contains("RUNBOOK-MARKER"))
        assertEquals("the user's own words are the user message", "make it 2 mm thicker", messages.getJSONObject(1).getString("content"))

        val tools = body.getJSONArray("tools")
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getJSONObject("function").getString("name") }
        assertEquals(
            "the engine's tools, then the app's own plate render and question tool",
            listOf(
                "execute",
                "batch",
                "inspect_design",
                AgentToolCatalog.PLATE_SCREENSHOT,
                AgentToolCatalog.ASK_USER,
            ),
            names,
        )
        assertEquals("function", tools.getJSONObject(0).getString("type"))
        assertEquals(
            "the engine's own schema is passed through",
            "object",
            tools.getJSONObject(0).getJSONObject("function").getJSONObject("parameters").getString("type"),
        )

        assertTrue("the key is a header, never a body field", !request.body.contains("sk-test-key"))
    }

    // --- named failures -------------------------------------------------------------------

    @Test
    fun a401IsANamedErrorAndTheKeyIsNotRepeated() {
        server.responder = { FakeOpenAiServer.unauthorized("sk-test-key") }
        val agent = agent(RecordingHost())
        try {
            agent.run(settings(), syntheticCatalog("execute"), "build a box")
            fail("expected the endpoint's refusal")
        } catch (error: AgentException) {
            assertEquals("unauthorized", error.code)
            assertTrue(error.message.orEmpty().contains("401"))
            assertTrue(
                "a provider that echoes the key must not put it in the error line: " + error.message,
                !error.message.orEmpty().contains("sk-test-key"),
            )
            assertTrue(
                "the endpoint's sentence survives: " + error.message,
                error.message.orEmpty().contains("Invalid API key"),
            )
        }
        logs.forEach { line ->
            assertTrue("the key must not be logged: " + line, !line.contains("sk-test-key"))
        }
    }

    @Test
    fun aMalformedToolCallIsReportedToTheModelAndTheLoopContinues() {
        server.responder = { request ->
            if (toolResultOf(request).isEmpty()) {
                FakeOpenAiServer.toolCall("execute", "arguments that are not JSON")
            } else {
                FakeOpenAiServer.finalAnswer("Sent one JSON object this time.")
            }
        }
        val host = RecordingHost()
        val answer = agent(host).run(settings(), syntheticCatalog("execute"), "build a box")

        assertEquals("Sent one JSON object this time.", answer)
        assertEquals("nothing ran for a call the app could not read", 0, host.calls.size)
        assertEquals(2, server.requests.size)
        val reported = toolResultOf(server.requests[1])
        assertTrue("the model is told what was wrong: " + reported, reported.startsWith(CadAgent.TOOL_ERROR))
        assertTrue(reported.contains("not a JSON object"))
    }

    @Test
    fun aToolFailureIsReturnedToTheModelRatherThanThrownAtTheUi() {
        server.responder = { request ->
            if (toolResultOf(request).isEmpty()) {
                FakeOpenAiServer.toolCall("execute", "{\"command\":\"solid.nope\"}")
            } else {
                FakeOpenAiServer.finalAnswer("The engine does not know that command; asking.")
            }
        }
        val host = RecordingHost { _, _ -> "the CAD engine refused execute: unknown command solid.nope" }
        val answer = agent(host).run(settings(), syntheticCatalog("execute"), "build a box")

        assertEquals("The engine does not know that command; asking.", answer)
        assertEquals(1, host.calls.size)
        val reported = toolResultOf(server.requests[1])
        assertTrue("the engine's own sentence is what the model reads", reported.contains("unknown command solid.nope"))
    }

    @Test
    fun aHiddenToolIsRefusedByNameAndThePlateIsExplained() {
        server.responder = { request ->
            if (toolResultOf(request).isEmpty()) {
                FakeOpenAiServer.toolCall("screenshot", "{}")
            } else {
                FakeOpenAiServer.finalAnswer("I will look through the plate instead.")
            }
        }
        val host = RecordingHost()
        val catalog = AgentToolCatalog.fromEngine(
            JSONArray().put(
                JSONObject().put("name", "execute")
                    .put("description", "run")
                    .put("inputSchema", JSONObject().put("type", "object")),
            ).put(
                JSONObject().put("name", "screenshot")
                    .put("description", "render")
                    .put("inputSchema", JSONObject().put("type", "object")),
            ),
        )
        val answer = agent(host).run(settings(), catalog, "look at it")
        assertEquals("I will look through the plate instead.", answer)
        assertEquals(0, host.calls.size)
        val reported = toolResultOf(server.requests[1])
        assertTrue(reported.contains("does not offer screenshot"))
        assertTrue(reported.contains("plate is the viewport"))
    }

    // --- bounds ---------------------------------------------------------------------------

    @Test
    fun aModelThatKeepsCallingToolsStopsAtTheConfiguredRoundLimit() {
        server.responder = { FakeOpenAiServer.toolCall("list_commands", "{}") }
        try {
            agent(RecordingHost(), maxToolRounds = 3)
                .run(settings(), syntheticCatalog("list_commands"), "list everything")
            fail("expected the runaway loop to be stopped")
        } catch (error: AgentException) {
            assertEquals("tool-round-limit", error.code)
            val message = error.message.orEmpty()
            assertTrue("names how many rounds ran: " + message, message.contains("3 tool rounds"))
            assertTrue(
                "says one more message can continue it: " + message,
                message.contains("'continue'"),
            )
        }
        // One request, then one per tool round up to the limit; no fourth answer was asked for.
        assertEquals(4, server.requests.size)
    }

    @Test
    fun cancelEndsTheTurnWithTheNamedCancellation() {
        server.responder = {
            // Hold the completion open so the cancel lands while a request is in flight.
            Thread.sleep(5_000)
            FakeOpenAiServer.finalAnswer("too late")
        }
        val agent = agent(RecordingHost())
        val codes = CopyOnWriteArrayList<String>()
        val worker = Thread {
            try {
                agent.run(settings(), syntheticCatalog("execute"), "build a box")
            } catch (error: AgentException) {
                codes += error.code
            }
        }
        worker.start()
        Thread.sleep(700)
        agent.cancel()
        worker.join(6_000)
        assertEquals("the turn stopped with the named cancellation", listOf("cancelled"), codes.toList())
    }

    @Test
    fun aMissingEndpointIsNamedBeforeAnyRequestIsSent() {
        val agent = agent(RecordingHost())
        try {
            agent.run(
                StoredAgentSettings(AgentSettings(baseUrl = "", model = "fake-model"), ""),
                syntheticCatalog("execute"),
                "build a box",
            )
            fail("expected the not-configured error")
        } catch (error: AgentException) {
            assertEquals("not-configured", error.code)
        }
        assertEquals("nothing was sent", 0, server.requests.size)
    }

    // --- the observable trace --------------------------------------------------------------

    @Test
    fun everyToolCallIsTracedFromItsStartToItsOutcome() {
        server.responder = { request ->
            if (toolResultOf(request).isEmpty()) {
                FakeOpenAiServer.toolCall(
                    "execute",
                    // A model can echo anything, including the key it was never given; the
                    // row's argument summary must not repeat it.
                    JSONObject().put("command", "solid.box")
                        .put("params", JSONObject().put("length", 40).put("note", "sk-test-key"))
                        .toString(),
                )
            } else {
                FakeOpenAiServer.finalAnswer("the box is on the plate")
            }
        }
        val host = RecordingHost { _, _ ->
            JSONObject()
                .put("created_bodies", JSONArray().put("Body1"))
                .put("changed_bodies", JSONArray())
                .put("removed_bodies", JSONArray())
                .toString()
        }
        val traces = CopyOnWriteArrayList<AgentToolTrace>()
        val answer = agent(host).run(
            settings(),
            syntheticCatalog("execute"),
            "build a box",
            onToolTrace = { trace -> traces += trace },
        )

        assertEquals("the box is on the plate", answer)
        assertEquals("a start and an end row", 2, traces.size)
        val start = traces[0]
        assertEquals(1, start.callId)
        assertEquals("execute", start.tool)
        assertEquals("solid.box", start.name)
        assertTrue(start.running)
        assertTrue("the command and its params are in the row: " + start.arguments, start.arguments.contains("solid.box"))
        assertTrue(start.arguments.contains("length=40"))
        assertFalse("the key is not in a tool row: " + start.arguments, start.arguments.contains("sk-test-key"))

        val end = traces[1]
        assertEquals("the end row is the same call", start.callId, end.callId)
        assertFalse(end.running)
        assertTrue("a real duration is measured", end.durationMillis >= 0)
        assertTrue("the outcome names the body: " + end.outcome, end.outcome.contains("1 body created"))
        assertFalse(end.failed)
        assertFalse("the key is not in an outcome: " + end.outcome, end.outcome.contains("sk-test-key"))
    }

    @Test
    fun aFailedToolCallIsAnEndedFailedRowNotSilence() {
        server.responder = { request ->
            if (toolResultOf(request).isEmpty()) {
                FakeOpenAiServer.toolCall("execute", "{\"command\":\"solid.nope\"}")
            } else {
                FakeOpenAiServer.finalAnswer("renaming it")
            }
        }
        val host = AgentToolHost { _, _ ->
            AgentToolHost.Result(
                "the CAD engine refused execute: unknown command solid.nope",
                failed = true,
            )
        }
        val traces = CopyOnWriteArrayList<AgentToolTrace>()
        agent(host).run(
            settings(),
            syntheticCatalog("execute"),
            "build a box",
            onToolTrace = { trace -> traces += trace },
        )
        val end = traces.last()
        assertFalse(end.running)
        assertTrue("a failure is a red row", end.failed)
        assertTrue("the engine's sentence is the row", end.outcome.contains("unknown command"))
    }

    @Test
    fun aMalformedToolCallStillGetsAStartedAndFailedRow() {
        server.responder = { request ->
            if (toolResultOf(request).isEmpty()) {
                FakeOpenAiServer.toolCall("execute", "arguments that are not JSON")
            } else {
                FakeOpenAiServer.finalAnswer("sent one object")
            }
        }
        val traces = CopyOnWriteArrayList<AgentToolTrace>()
        agent(RecordingHost()).run(
            settings(),
            syntheticCatalog("execute"),
            "build a box",
            onToolTrace = { trace -> traces += trace },
        )
        assertEquals(2, traces.size)
        assertTrue(traces[0].running)
        assertFalse(traces[1].running)
        assertTrue(traces[1].failed)
        assertTrue(traces[1].outcome.contains("not JSON"))
    }

    @Test
    fun aToolOutcomeThatEchoesTheKeyIsMaskedInTheRow() {
        server.responder = { request ->
            if (toolResultOf(request).isEmpty()) {
                FakeOpenAiServer.toolCall("execute", "{\"command\":\"solid.box\"}")
            } else {
                FakeOpenAiServer.finalAnswer("done")
            }
        }
        val host = AgentToolHost { _, _ ->
            AgentToolHost.Result("refused: the header was Bearer sk-test-key", failed = true)
        }
        val traces = CopyOnWriteArrayList<AgentToolTrace>()
        agent(host).run(
            settings(),
            syntheticCatalog("execute"),
            "build a box",
            onToolTrace = { trace -> traces += trace },
        )
        val end = traces.last()
        assertTrue(end.failed)
        assertFalse("the key never becomes row text: " + end.outcome, end.outcome.contains("sk-test-key"))
        assertTrue(end.outcome.contains("[key]"))
    }

    @Test
    fun theConversationIsRememberedAcrossTurns() {
        server.responder = { FakeOpenAiServer.finalAnswer("done") }
        val agent = agent(RecordingHost())
        val catalog = syntheticCatalog("execute")
        agent.run(settings(), catalog, "first")
        agent.run(settings(), catalog, "second")
        val messages = server.requests[1].json?.getJSONArray("messages") ?: JSONArray()
        // system, user(first), assistant(first), user(second)
        assertEquals(4, messages.length())
        assertEquals("first", messages.getJSONObject(1).getString("content"))
        assertEquals("second", messages.getJSONObject(3).getString("content"))
        assertEquals("system + 2 remembered turns", 4, agent.rememberedTurns)
    }
}
