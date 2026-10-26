package com.tomppi.enderslicer.cadagent

import com.tomppi.enderslicer.viewer.PlateImage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The plate render, the image part, and the honest text-only fallback - against a fake endpoint.
 *
 * The endpoint is a real local HTTP server ([FakeOpenAiServer]), so the request bodies are the
 * ones the app really sends: this is where "the image arrived at the endpoint" is checked by
 * reading the content array out of the request, and where the 400 that names the image is the
 * one a real provider sends. Nothing here talks to a real model.
 */
class PlateScreenshotTest {

    private lateinit var server: FakeOpenAiServer
    private val imageNotices = CopyOnWriteArrayList<String>()
    private val visionNotices = CopyOnWriteArrayList<String>()

    @Before
    fun startServer() {
        server = FakeOpenAiServer().start()
    }

    @After
    fun stopServer() {
        server.close()
    }

    private fun catalog(): AgentToolCatalog {
        val tools = JSONArray().put(
            JSONObject()
                .put("name", "execute")
                .put("description", "runs one engine command")
                .put("inputSchema", JSONObject().put("type", "object")),
        )
        return AgentToolCatalog.fromEngine(tools, includeWebTools = false)
    }

    private fun settings(): StoredAgentSettings = StoredAgentSettings(
        settings = AgentSettings(
            baseUrl = server.baseUrl,
            model = "fake-model",
            hasApiKey = false,
            webSearch = false,
        ),
        apiKey = "",
    )

    private fun image(
        width: Int = 768,
        height: Int = 480,
        tintVisible: Boolean = true,
    ): PlateImage = PlateImage(
        bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4, 5, 6),
        mimeType = "image/jpeg",
        widthPx = width,
        heightPx = height,
        bodies = 1,
        triangles = 12,
        tintVisible = tintVisible,
    )

    private fun source(image: PlateImage?): PlateImageSource = PlateImageSource { image }

    private fun agent(host: AgentToolHost = RecordingHost()): CadAgent = CadAgent(
        toolHost = host,
        runbook = "RUNBOOK-MARKER",
        log = {},
    )

    private class RecordingHost : AgentToolHost {
        override fun call(name: String, arguments: JSONObject): AgentToolHost.Result =
            AgentToolHost.Result("ok from " + name)
    }

    private fun view(
        attach: Boolean = false,
        plate: PlateImage? = image(),
        tinted: Boolean = false,
        selection: String = "",
    ): AgentView = AgentView(
        selectionBlock = selection,
        plate = source(plate),
        attachPlateToSelection = attach,
        faceTinted = tinted,
    )

    private fun selectionBlock(): String = """
        SELECTED FACE (the user picked this face on the plate; act on THIS face, not on a guess)
        body: Body1
        face: F1:-y (index 1)
        surface: planar
        area: 480 mm²
        centroid: (20, 0, 6) mm
        normal: (0, -1, 0)
        on screen: facing you

    """.trimIndent() + "\n\n"

    /** The image parts of one recorded request, or an empty list. */
    private fun imageParts(request: FakeOpenAiServer.RecordedRequest): List<JSONObject> {
        val messages = request.json?.optJSONArray("messages") ?: return emptyList()
        val parts = ArrayList<JSONObject>()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            val content = message.opt("content") as? JSONArray ?: continue
            for (part in 0 until content.length()) {
                val item = content.optJSONObject(part) ?: continue
                if (item.optString("type") == "image_url") parts += item
            }
        }
        return parts
    }

    /** The text a message carried, whether it was the plain form or a content array. */
    private fun messageTexts(request: FakeOpenAiServer.RecordedRequest): List<String> {
        val messages = request.json?.optJSONArray("messages") ?: return emptyList()
        val texts = ArrayList<String>()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            when (val content = message.opt("content")) {
                is String -> texts += content
                is JSONArray -> for (part in 0 until content.length()) {
                    val item = content.optJSONObject(part) ?: continue
                    if (item.optString("type") == "text") texts += item.optString("text")
                }
            }
        }
        return texts
    }

    // --- the tool ------------------------------------------------------------------------

    @Test
    fun thePlateScreenshotToolAttachesAnImagePartToTheNextUserMessage() {
        server.responder = {
            if (server.requests.size == 1) {
                FakeOpenAiServer.toolCall("plate_screenshot", "{}")
            } else {
                FakeOpenAiServer.finalAnswer("I can see the plate.")
            }
        }
        val traces = CopyOnWriteArrayList<AgentToolTrace>()
        val answer = agent().run(
            settings = settings(),
            catalog = catalog(),
            userText = "what does the plate look like?",
            view = view(tinted = true),
            onToolTrace = { traces += it },
            onImageAttached = { imageNotices += it },
        )
        assertEquals("I can see the plate.", answer)
        assertEquals("one tool call and one answer", 2, server.requests.size)

        // The tool result told the model the image follows.
        val toolMessages = messageTexts(server.requests[1])
        assertTrue(
            "the tool result carries the short report: " + toolMessages,
            toolMessages.any {
                it.contains("rendered the plate: 1 body, 12 triangles, current camera") &&
                    it.contains("the image follows")
            },
        )

        // And the next user-role message carries it: the OpenAI image content part.
        val parts = imageParts(server.requests[1])
        assertEquals("the image is attached to the next user message", 1, parts.size)
        val url = parts[0].getJSONObject("image_url").getString("url")
        assertTrue("it is a data: URL", url.startsWith("data:image/jpeg;base64,"))
        assertTrue("the payload is the encoded image", url.length > "data:image/jpeg;base64,".length)

        // The caption states the selected face is tinted, because it is.
        assertTrue(
            "the caption names the tint: " + messageTexts(server.requests[1]),
            messageTexts(server.requests[1]).any { it.contains(CadAgent.TINT_CAPTION) },
        )

        // The row names the picture by size and dimensions, never by its bytes.
        val row = traces.last { !it.running && it.tool == AgentToolCatalog.PLATE_SCREENSHOT }
        assertTrue("the row outcome: " + row.outcome, row.outcome.contains("768×480 px"))
        assertFalse(row.outcome.contains("base64"))

        // The transcript line the chat shows names it the same way.
        assertEquals(1, imageNotices.size)
        assertTrue(imageNotices[0].contains("768×480 px"))
        assertFalse("a transcript line never carries the payload", imageNotices[0].contains("base64"))
    }

    @Test
    fun withoutAPlateOnScreenTheToolSaysSoInsteadOfSendingAPicture() {
        server.responder = {
            if (server.requests.size == 1) {
                FakeOpenAiServer.toolCall("plate_screenshot", "{}")
            } else {
                FakeOpenAiServer.finalAnswer("I will work from the description.")
            }
        }
        val answer = agent().run(
            settings = settings(),
            catalog = catalog(),
            userText = "show me the plate",
            view = AgentView(plate = null),
            onImageAttached = { imageNotices += it },
        )
        assertEquals("I will work from the description.", answer)
        assertTrue("no picture can be attached", imageParts(server.requests[1]).isEmpty())
        val toolMessage = messageTexts(server.requests[1]).first { it.startsWith("tool error:") }
        assertTrue(toolMessage, toolMessage.contains("there is no picture to send"))
        assertTrue("no attachment line is claimed", imageNotices.isEmpty())
    }

    // --- the message that carries a face selection ---------------------------------------

    @Test
    fun aSelectionMessageCarriesThePlateRenderWhenTheSwitchIsOn() {
        server.responder = { FakeOpenAiServer.finalAnswer("ok") }
        agent().run(
            settings = settings(),
            catalog = catalog(),
            userText = "make this face 2 mm thicker",
            view = view(attach = true, tinted = true, selection = selectionBlock()),
            onImageAttached = { imageNotices += it },
        )
        assertEquals(1, server.requests.size)
        val parts = imageParts(server.requests[0])
        assertEquals("the render rides the message itself", 1, parts.size)
        val texts = messageTexts(server.requests[0])
        assertTrue("the SELECTED FACE block is in the message", texts.any { it.contains("SELECTED FACE") })
        assertTrue("the user's words follow it", texts.any { it.contains("make this face 2 mm thicker") })
        assertTrue("the caption says the face is tinted", texts.any { it.contains(CadAgent.TINT_CAPTION) })
        assertEquals(1, imageNotices.size)
    }

    @Test
    fun aRenderThatDoesNotShowTheTintDoesNotClaimIt() {
        // The selection exists, so the app would like to say the face is tinted - but the frame
        // came back without the amber in it, so the caption says nothing about a tint. Never
        // tell the model something the picture does not show.
        server.responder = { FakeOpenAiServer.finalAnswer("ok") }
        agent().run(
            settings = settings(),
            catalog = catalog(),
            userText = "make this face 2 mm thicker",
            view = view(
                attach = true,
                plate = image(tintVisible = false),
                tinted = true,
                selection = selectionBlock(),
            ),
        )
        assertTrue("the render still rides the message", imageParts(server.requests[0]).isNotEmpty())
        assertFalse(
            "no tint is claimed for a picture without it",
            messageTexts(server.requests[0]).any { it.contains(CadAgent.TINT_CAPTION) },
        )
    }

    @Test
    fun withTheSwitchOffTheBlockGoesAlone() {
        server.responder = { FakeOpenAiServer.finalAnswer("ok") }
        agent().run(
            settings = settings(),
            catalog = catalog(),
            userText = "make this face 2 mm thicker",
            view = view(attach = false, tinted = true, selection = selectionBlock()),
        )
        assertEquals(1, server.requests.size)
        assertTrue("no image is attached", imageParts(server.requests[0]).isEmpty())
        assertTrue(
            "the block still describes the face",
            messageTexts(server.requests[0]).any { it.contains("SELECTED FACE") },
        )
    }

    // --- the honest text-only fallback ---------------------------------------------------

    @Test
    fun aTextOnlyEndpointFallsBackOnceAndIsRememberedForTheSession() {
        server.responder = { request ->
            if (imageParts(request).isNotEmpty()) {
                FakeOpenAiServer.imageRefused()
            } else {
                FakeOpenAiServer.finalAnswer("Worked from the SELECTED FACE block.")
            }
        }
        val agent = agent()
        val answer = agent.run(
            settings = settings(),
            catalog = catalog(),
            userText = "make this face 2 mm thicker",
            view = view(attach = true, tinted = true, selection = selectionBlock()),
            onVisionNotice = { visionNotices += it },
        )
        assertEquals("Worked from the SELECTED FACE block.", answer)
        assertEquals("one refusal and one text-only retry", 2, server.requests.size)
        assertTrue("the first request carried the image", imageParts(server.requests[0]).isNotEmpty())
        assertTrue("the retry carries none", imageParts(server.requests[1]).isEmpty())
        assertEquals(
            "the transcript says it once, in the app's own sentence",
            listOf("this model cannot see images; sent the geometry description instead"),
            visionNotices.toList(),
        )
        assertEquals(CadAgent.VISION_NOTICE, visionNotices[0])
        // The retry is the same turn: the SELECTED FACE block is still there, without a picture.
        assertTrue(
            "the geometry description still went",
            messageTexts(server.requests[1]).any { it.contains("SELECTED FACE") },
        )

        // Remembered for the session: the next turn does not even ask for a render.
        server.requests.clear()
        server.responder = { FakeOpenAiServer.finalAnswer("still text-only") }
        var renders = 0
        val counting = PlateImageSource {
            renders++
            image()
        }
        agent.run(
            settings = settings(),
            catalog = catalog(),
            userText = "and now make it 1 mm thinner",
            view = AgentView(
                selectionBlock = selectionBlock(),
                plate = counting,
                attachPlateToSelection = true,
                faceTinted = true,
            ),
        )
        assertEquals(1, server.requests.size)
        assertTrue("no picture is sent after the refusal", imageParts(server.requests[0]).isEmpty())
        assertEquals("the plate renderer is not asked again", 0, renders)
        assertEquals("the notice is said once, not every turn", 1, visionNotices.size)
    }

    @Test
    fun aFetchReturnedFailureIsNotReadAsAVisionRefusal() {
        // A 400 that is not about the image keeps its own named failure instead of being
        // rewritten as "this model cannot see images".
        server.responder = { FakeOpenAiServer.contextLengthExceeded() }
        var failure: AgentException? = null
        try {
            agent().run(
                settings = settings(),
                catalog = catalog(),
                userText = "make this face thicker",
                view = view(attach = true, tinted = true, selection = selectionBlock()),
                onVisionNotice = { visionNotices += it },
            )
        } catch (error: AgentException) {
            failure = error
        }
        assertNotNull(failure)
        assertEquals("context-length", failure!!.code)
        assertTrue("the image was not blamed", visionNotices.isEmpty())
    }
}
