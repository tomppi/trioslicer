package com.tomppi.enderslicer.cadagent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The app's `ask_user` tool: it asks, it ends the turn, and it never blocks.
 *
 * Hermetic against [FakeOpenAiServer]: the model scripts one tool call and there is no second
 * completion request - that absence is the assertion that asking ended the turn.
 */
class CadAgentAskUserTest {

    private lateinit var server: FakeOpenAiServer
    private val traces = CopyOnWriteArrayList<AgentToolTrace>()

    @Before
    fun startServer() {
        server = FakeOpenAiServer().start()
    }

    @After
    fun stopServer() {
        server.close()
    }

    private fun settings(): StoredAgentSettings = StoredAgentSettings(
        settings = AgentSettings(baseUrl = server.baseUrl, model = "fake-model", hasApiKey = true),
        apiKey = "sk-test-key",
    )

    private fun catalog(): AgentToolCatalog {
        val tools = JSONArray().put(
            JSONObject()
                .put("name", "execute")
                .put("description", "the execute tool")
                .put("inputSchema", JSONObject().put("type", "object")),
        )
        return AgentToolCatalog.fromEngine(tools, includeWebTools = false)
    }

    private class NoToolHost : AgentToolHost {
        val calls = CopyOnWriteArrayList<String>()
        override fun call(name: String, arguments: JSONObject): AgentToolHost.Result {
            calls += name
            return AgentToolHost.Result("ran " + name)
        }
    }

    private fun agent(host: AgentToolHost): CadAgent = CadAgent(
        toolHost = host,
        runbook = "RUNBOOK-MARKER",
        retryDelayMillis = 10L,
    )

    private fun askArguments(question: String = "Which material should the lid use?"): JSONObject =
        JSONObject()
            .put("question", question)
            .put("options", JSONArray().put("PLA").put("PETG"))
            .put("allow_text", true)

    @Test
    fun askingTheUserEndsTheTurnAndExposesTheQuestion() {
        server.responder = { FakeOpenAiServer.toolCall("ask_user", askArguments().toString()) }
        val host = NoToolHost()
        val localAgent = agent(host)

        val reply = localAgent.run(
            settings = settings(),
            catalog = catalog(),
            userText = "make me a lid",
            onToolTrace = { trace -> traces += trace },
        )

        assertEquals("asking ends the turn with no agent reply", "", reply)
        assertTrue("no engine tool ran", host.calls.isEmpty())
        assertEquals("one completion only: the answer starts a new turn", 1, server.requests.size)
        assertTrue("the ask never becomes a tool row", traces.none { it.name == "ask_user" })

        val question = localAgent.lastQuestion
        assertNotNull(question)
        assertEquals("Which material should the lid use?", question!!.question)
        assertEquals(listOf("PLA", "PETG"), question.options)
        assertTrue(question.allowText)
        assertTrue("the model is told the turn is over", question.resultText.contains("turn is over"))
    }

    @Test
    fun aMalformedQuestionIsAToolFailureTheModelCanCorrect() {
        server.responder = {
            if (server.requests.size == 1) {
                FakeOpenAiServer.toolCall("ask_user", JSONObject().put("question", "").toString())
            } else {
                FakeOpenAiServer.finalAnswer("asked without a question; continuing")
            }
        }
        val localAgent = agent(NoToolHost())

        val reply = localAgent.run(
            settings = settings(),
            catalog = catalog(),
            userText = "make me a lid",
        )

        assertEquals("asked without a question; continuing", reply)
        assertNull("a malformed question is not a question", localAgent.lastQuestion)
        assertEquals("the turn continued after the tool failure", 2, server.requests.size)
        val toolResult = server.requests[1].json
            ?.optJSONArray("messages")
            ?.let { messages ->
                (0 until messages.length()).mapNotNull { messages.optJSONObject(it) }
                    .lastOrNull { it.optString("role") == "tool" }
                    ?.optString("content")
            }
            .orEmpty()
        assertTrue("the model is told what to fix: " + toolResult, toolResult.contains("non-empty"))
    }
}
