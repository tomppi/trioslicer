package com.tomppi.enderslicer.cadagent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One turn's resilience: the one safe retry, and the sentences a pasted transcript carries.
 *
 * Everything is hermetic: a local [FakeOpenAiServer] that can drop a connection, a fake tool
 * host, and no network beyond 127.0.0.1.
 */
class CadAgentRetryTest {

    private lateinit var server: FakeOpenAiServer
    private val notices = CopyOnWriteArrayList<String>()
    private val logs = CopyOnWriteArrayList<String>()

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

    private fun catalog(vararg names: String): AgentToolCatalog {
        val tools = JSONArray()
        names.forEach { name ->
            tools.put(
                JSONObject()
                    .put("name", name)
                    .put("description", "the " + name + " tool")
                    .put("inputSchema", JSONObject().put("type", "object")),
            )
        }
        return AgentToolCatalog.fromEngine(tools, includeWebTools = false)
    }

    private class RecordingHost : AgentToolHost {
        val calls = CopyOnWriteArrayList<String>()
        override fun call(name: String, arguments: JSONObject): AgentToolHost.Result {
            calls += name
            return AgentToolHost.Result("ok from " + name)
        }
    }

    private fun agent(
        host: AgentToolHost = RecordingHost(),
        retryDelayMillis: Long = 10L,
        clientFactory: ((StoredAgentSettings) -> OpenAiChatClient)? = null,
    ): CadAgent = CadAgent(
        toolHost = host,
        runbook = "RUNBOOK-MARKER",
        retryDelayMillis = retryDelayMillis,
        log = { message -> logs += message },
        clientFactory = clientFactory ?: { stored ->
            OpenAiChatClient(
                baseUrl = stored.settings.baseUrl,
                apiKey = stored.apiKey,
                model = stored.settings.model,
                log = { message -> logs += message },
            )
        },
    )

    private fun run(agent: CadAgent): String = agent.run(
        settings = settings(),
        catalog = catalog("execute"),
        userText = "a 40 x 30 x 10 box",
        onRetry = { notice -> notices += notice },
    )

    @Test
    fun aTransportFailureBeforeAnyToolRanIsRetriedOnceAndThenAnswers() {
        server.responder = {
            if (server.requests.size == 1) {
                FakeOpenAiServer.dropConnection()
            } else {
                FakeOpenAiServer.finalAnswer("the box is on the plate")
            }
        }
        val host = RecordingHost()

        val answer = run(agent(host))

        assertEquals("the box is on the plate", answer)
        assertEquals("the call was sent twice", 2, server.requests.size)
        assertEquals("the transcript says the one retry is happening", listOf(CadAgent.RETRY_NOTICE), notices.toList())
        assertTrue("the retried request carries the same conversation", server.requests[1].body == server.requests[0].body)
        assertTrue("no tool ran, so nothing could repeat", host.calls.isEmpty())
    }

    @Test
    fun aSecondTransportFailureIsNamedWithHostRoundSecondsAndTheBackgroundHint() {
        server.responder = {
            if (server.requests.size == 1) {
                FakeOpenAiServer.toolCall("execute", "{}")
            } else {
                FakeOpenAiServer.dropConnection()
            }
        }
        val host = RecordingHost()

        try {
            run(agent(host))
            fail("expected the transport failure")
        } catch (error: AgentException) {
            assertEquals("transport", error.code)
            val message = error.message.orEmpty()
            assertTrue("names the host: " + message, message.contains("127.0.0.1:"))
            assertTrue("names the round: " + message, message.contains("round 2"))
            assertTrue("names the elapsed seconds: " + message, message.contains(" s (round 2"))
            assertTrue("says the retry was not sent after a tool ran: " + message,
                message.contains("had already changed the design"))
            assertTrue("carries the one sentence that helps: " + message,
                message.contains(CadAgent.BACKGROUND_HINT))
            assertTrue("carries the transport reason: " + message, message.contains("Could not reach the endpoint"))
        }
        assertEquals("one call, then the call after the tool - the failure is not retried", 2, server.requests.size)
        assertTrue("no retry notice when a tool already ran", notices.isEmpty())
        assertEquals("the tool did run once", listOf("execute"), host.calls.toList())
    }

    @Test
    fun aTimeoutIsRetriedOnceAndTheFinalMessageNamesTheSecondsAndTheFreezeHint() {
        server.responder = {
            // Hold the answer past the client's read timeout, twice.
            Thread.sleep(400)
            FakeOpenAiServer.finalAnswer("too late")
        }
        val agent = agent(
            clientFactory = { stored ->
                OpenAiChatClient(
                    baseUrl = stored.settings.baseUrl,
                    apiKey = stored.apiKey,
                    model = stored.settings.model,
                    connectTimeoutMillis = 300,
                    readTimeoutMillis = 80,
                    log = { message -> logs += message },
                )
            },
        )

        try {
            run(agent)
            fail("expected the timeout")
        } catch (error: AgentException) {
            assertEquals("timeout", error.code)
            val message = error.message.orEmpty()
            assertTrue("names what happened: " + message, message.contains("did not answer"))
            assertTrue("names the elapsed seconds: " + message, message.contains(" s (round 1 at 127.0.0.1:"))
            assertTrue("says the retry failed too: " + message, message.contains("The one retry failed too."))
            assertTrue("carries the background hint: " + message, message.contains(CadAgent.BACKGROUND_HINT))
        }
        assertEquals("first attempt and its one retry", 2, server.requests.size)
        assertEquals(listOf(CadAgent.RETRY_NOTICE), notices.toList())
    }

    @Test
    fun aContextLength400IsNamedAsTheContextWindow() {
        server.responder = { FakeOpenAiServer.contextLengthExceeded() }

        try {
            run(agent())
            fail("expected the context-length error")
        } catch (error: AgentException) {
            assertEquals("context-length", error.code)
            val message = error.message.orEmpty()
            assertTrue("names the round and host: " + message, message.contains("Round 1 at 127.0.0.1:"))
            assertTrue("names the context window: " + message, message.contains("context window"))
            assertTrue("carries the provider's own sentence: " + message, message.contains("maximum context length is 128000"))
        }
        assertEquals("an HTTP refusal is never retried", 1, server.requests.size)
        assertTrue(notices.isEmpty())
    }

    @Test
    fun aToolRoundLimitNamesTheCountAndSaysTheUserCanAskToContinue() {
        server.responder = { FakeOpenAiServer.toolCall("execute", "{}") }

        try {
            run(agent())
            fail("expected the round limit")
        } catch (error: AgentException) {
            assertEquals("tool-round-limit", error.code)
            val message = error.message.orEmpty()
            assertTrue("names how many rounds ran: " + message, message.contains("8 tool rounds"))
            assertTrue("says the user can ask it to continue in one message: " + message,
                message.contains("one more message - 'continue'"))
        }
    }

    @Test
    fun aCancelledTurnIsNotWrappedInTheRetryOrErrorSentence() {
        server.responder = {
            Thread.sleep(2_000)
            FakeOpenAiServer.finalAnswer("too late")
        }
        val agent = agent()
        val codes = CopyOnWriteArrayList<String>()
        val worker = Thread {
            try {
                run(agent)
            } catch (error: AgentException) {
                codes += error.code
            }
        }
        worker.start()
        Thread.sleep(300)
        agent.cancel()
        worker.join(4_000)
        assertEquals(listOf("cancelled"), codes.toList())
        assertTrue("a cancel is never retried", notices.isEmpty())
    }
}
