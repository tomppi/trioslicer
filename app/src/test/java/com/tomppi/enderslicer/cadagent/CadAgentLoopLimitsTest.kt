package com.tomppi.enderslicer.cadagent

import com.tomppi.enderslicer.viewer.PlateImage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The agent loop's bounds and memory: the per-turn plate-image cap, the request ceiling, the
 * record of a stopped/failed turn, conversation-wide tool ids, and the vision-refusal classifier.
 *
 * Hermetic: [FakeOpenAiServer] is a real local HTTP endpoint, so request bodies are the bytes the
 * app actually sends; no provider and no key are involved.
 */
class CadAgentLoopLimitsTest {

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
        settings = AgentSettings(
            baseUrl = server.baseUrl,
            model = "fake-model",
            hasApiKey = true,
            webSearch = false,
        ),
        apiKey = "sk-test-key",
    )

    private fun catalog(): AgentToolCatalog {
        val tools = JSONArray().put(
            JSONObject()
                .put("name", "execute")
                .put("description", "runs one command")
                .put("inputSchema", JSONObject().put("type", "object")),
        )
        return AgentToolCatalog.fromEngine(tools, includeWebTools = false)
    }

    private fun agent(host: AgentToolHost = AgentToolHost { _, _ -> AgentToolHost.Result("{}") }): CadAgent =
        CadAgent(toolHost = host, runbook = "RUNBOOK-MARKER", retryDelayMillis = 10L)

    private fun image(): PlateImage = PlateImage(
        bytes = ByteArray(64) { 0x11 },
        mimeType = "image/jpeg",
        widthPx = 320,
        heightPx = 200,
        bodies = 1,
        triangles = 12,
        tintVisible = false,
    )

    private fun imageParts(request: FakeOpenAiServer.RecordedRequest): Int {
        val messages = request.json?.optJSONArray("messages") ?: return 0
        var count = 0
        for (index in 0 until messages.length()) {
            val content = messages.optJSONObject(index)?.opt("content") as? JSONArray ?: continue
            for (part in 0 until content.length()) {
                if (content.optJSONObject(part)?.optString("type") == "image_url") count++
            }
        }
        return count
    }

    private fun hasImage(request: FakeOpenAiServer.RecordedRequest): Boolean = imageParts(request) > 0

    private fun hasToolResult(request: FakeOpenAiServer.RecordedRequest): Boolean {
        val messages = request.json?.optJSONArray("messages") ?: return false
        for (index in 0 until messages.length()) {
            if (messages.optJSONObject(index)?.optString("role") == "tool") return true
        }
        return false
    }

    private fun messageTexts(request: FakeOpenAiServer.RecordedRequest): List<String> {
        val messages = request.json?.optJSONArray("messages") ?: return emptyList()
        val texts = ArrayList<String>()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val role = message.optString("role")
            if (role != "user" && role != "assistant") continue
            val content = message.opt("content")
            if (content is String) texts += content
        }
        return texts
    }

    @Test
    fun aTurnKeepsOnlyTheLatestPlateRendersAndTheRowSaysWhenOneWasDropped() {
        // Six completions: five ask for a render, the sixth answers. Each render becomes an
        // image part; the sixth request must carry the capped number, and the row of the render
        // that pushed an older one out must say so.
        server.responder = {
            if (server.requests.size <= 5) {
                FakeOpenAiServer.toolCall("plate_screenshot", "{}", id = "call_" + server.requests.size)
            } else {
                FakeOpenAiServer.finalAnswer("done")
            }
        }
        val agent = agent()

        val answer = agent.run(
            settings = settings(),
            catalog = catalog(),
            userText = "render the plate a few times",
            view = AgentView(plate = PlateImageSource { image() }),
            onToolTrace = { trace -> traces += trace },
        )

        assertEquals("done", answer)
        assertEquals(
            "the last request keeps only the cap's images",
            CadAgent.MAX_TURN_IMAGES,
            imageParts(server.requests.last()),
        )
        assertTrue(
            "a render that dropped an older one did not say so: " +
                traces.filter { !it.running }.map { it.outcome },
            traces.any { !it.running && it.outcome.contains("dropped an older render") },
        )
    }

    @Test
    fun aStoppedTurnIsRememberedSoContinueHasAnAntecedent() {
        server.responder = {
            Thread.sleep(1_500)
            FakeOpenAiServer.finalAnswer("too late")
        }
        val agent = agent()
        val prompt = "make a box with twelve separate bodies"
        val worker = Thread { runCatching { agent.run(settings(), catalog(), prompt) } }
        worker.start()
        Thread.sleep(250)
        agent.cancel()
        worker.join(4_000)
        assertTrue("the turn did not end after Stop", !worker.isAlive)

        server.responder = { FakeOpenAiServer.finalAnswer("continuing the box") }
        agent.run(settings(), catalog(), "continue")

        val texts = messageTexts(server.requests.last())
        assertTrue("the stopped prompt is not in the model's memory: " + texts, texts.any { it.contains(prompt) })
        assertTrue(
            "the model is not told the turn was stopped: " + texts,
            texts.any { it.contains("stopped this turn") },
        )
    }

    @Test
    fun aFailedTurnIsRememberedTooWithItsNamedCode() {
        server.responder = { FakeOpenAiServer.unauthorized("sk-test-key") }
        val agent = agent()
        val failure = runCatching {
            agent.run(settings(), catalog(), "make a lid with a snap fit")
        }.exceptionOrNull()
        assertNotNull("the endpoint's refusal must fail the turn", failure)

        server.responder = { FakeOpenAiServer.finalAnswer("carrying on") }
        agent.run(settings(), catalog(), "continue")

        val texts = messageTexts(server.requests.last())
        assertTrue(
            "the failed prompt is not remembered: " + texts,
            texts.any { it.contains("make a lid with a snap fit") },
        )
        assertTrue(
            "the model is not told the turn failed and why: " + texts,
            texts.any { it.contains("This turn failed (unauthorized)") },
        )
    }

    @Test
    fun toolCallIdsDoNotRestartOnTheSecondTurn() {
        server.responder = { request ->
            if (hasToolResult(request)) {
                FakeOpenAiServer.finalAnswer("ok")
            } else {
                FakeOpenAiServer.toolCall("execute", "{\"command\":\"inspect_design\",\"params\":{}}", id = "call_x")
            }
        }
        val agent = agent()
        agent.run(settings(), catalog(), "first request", onToolTrace = { traces += it })
        agent.run(settings(), catalog(), "second request", onToolTrace = { traces += it })

        val ids = traces.filter { it.running }.map { it.callId }
        assertEquals("one tool call per turn", 2, ids.size)
        assertEquals("two turns must not reuse a tool-row id", ids.distinct(), ids)
        assertTrue("the second turn's id is after the first: " + ids, ids[1] != ids[0])
    }

    // --- the vision-refusal classifier ----------------------------------------------------

    @Test
    fun aCapabilityRefusalFallsBackAndIsRememberedForTheSession() {
        server.responder = { request ->
            if (hasImage(request)) FakeOpenAiServer.imageRefused()
            else FakeOpenAiServer.finalAnswer("worked from the description")
        }
        val agent = agent()
        val notices = CopyOnWriteArrayList<String>()

        val answer = agent.run(
            settings = settings(),
            catalog = catalog(),
            userText = "make this face thicker",
            view = AgentView(
                selectionBlock = "SELECTED FACE\n",
                plate = PlateImageSource { image() },
                attachPlateToSelection = true,
            ),
            onVisionNotice = { notices += it },
        )

        assertEquals("worked from the description", answer)
        assertTrue("the session remembers the refusal", agent.visionUnsupported)
        assertEquals(listOf(CadAgent.VISION_NOTICE), notices.toList())
    }

    @Test
    fun aSizeRefusalIsANormalErrorAndDoesNotMarkTheModelBlind() {
        server.responder = { request ->
            if (hasImage(request)) {
                FakeOpenAiServer.Reply(
                    400,
                    JSONObject().put(
                        "error",
                        JSONObject().put("message", "image exceeds maximum size of 512 KB"),
                    ).toString(),
                )
            } else {
                FakeOpenAiServer.finalAnswer("text only")
            }
        }
        val agent = agent()
        val failure = runCatching {
            agent.run(
                settings = settings(),
                catalog = catalog(),
                userText = "make this face thicker",
                view = AgentView(
                    selectionBlock = "SELECTED FACE\n",
                    plate = PlateImageSource { image() },
                    attachPlateToSelection = true,
                ),
            )
        }.exceptionOrNull()

        assertNotNull("a size refusal must fail the turn", failure)
        assertEquals("the size refusal keeps its own named code", "http-error", (failure as AgentException).code)
        assertTrue(
            "a size refusal must not mark the session blind",
            !agent.visionUnsupported,
        )
    }

    @Test
    fun aFormatRefusalIsNotACapabilityRefusalEither() {
        server.responder = { request ->
            if (hasImage(request)) {
                FakeOpenAiServer.Reply(
                    400,
                    JSONObject().put(
                        "error",
                        JSONObject().put("message", "unsupported image format: expected jpeg"),
                    ).toString(),
                )
            } else {
                FakeOpenAiServer.finalAnswer("text only")
            }
        }
        val agent = agent()
        val failure = runCatching {
            agent.run(
                settings = settings(),
                catalog = catalog(),
                userText = "make this face thicker",
                view = AgentView(
                    selectionBlock = "SELECTED FACE\n",
                    plate = PlateImageSource { image() },
                    attachPlateToSelection = true,
                ),
            )
        }.exceptionOrNull()

        assertNotNull("a format refusal must fail the turn", failure)
        assertTrue("a format refusal must not mark the session blind", !agent.visionUnsupported)
    }

    // --- the request ceiling --------------------------------------------------------------

    @Test
    fun theCeilingDropsTheOldestImagesUntilTheRequestFits() {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", "system prompt"))
        val indices = ArrayList<Int>()
        repeat(3) { number ->
            val content = JSONArray()
                .put(JSONObject().put("type", "text").put("text", "caption " + number))
                .put(
                    JSONObject()
                        .put("type", "image_url")
                        .put(
                            "image_url",
                            JSONObject().put(
                                "url",
                                "data:image/jpeg;base64," + "A".repeat(4_000),
                            ),
                        ),
                )
            messages.put(JSONObject().put("role", "user").put("content", content))
            indices += messages.length() - 1
        }
        val before = CadAgent.requestBytes(messages)
        val ceiling = before - 100

        val dropped = CadAgent.trimImagesForRequest(messages, indices, ceiling)

        assertTrue("the ceiling dropped no image: " + dropped, dropped >= 1)
        assertTrue(
            "the request still exceeds the ceiling: " +
                CadAgent.requestBytes(messages) + " > " + ceiling,
            CadAgent.requestBytes(messages) <= ceiling,
        )
        assertEquals("the image list is left only with the kept images", 3 - dropped, indices.size)
        val droppedMessage = messages.optJSONObject(1)?.optString("content").orEmpty()
        assertTrue(
            "a dropped image is replaced by its caption and a line saying so: " + droppedMessage,
            droppedMessage.contains("caption 0") && droppedMessage.contains("was dropped"),
        )
    }
}
