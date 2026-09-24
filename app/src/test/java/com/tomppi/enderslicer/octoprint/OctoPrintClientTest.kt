package com.tomppi.enderslicer.octoprint

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
class OctoPrintClientTest {
    @Test
    fun normalizesLocalHostAndPreservesPathPrefix() {
        // Bare hostnames default to HTTPS so the API key is never silently sent in cleartext.
        assertEquals(
            "https://octopi.local/",
            OctoPrintClient.normalizeBaseUrl("octopi.local").toString(),
        )
        assertEquals(
            "https://printer.example/octoprint/",
            OctoPrintClient.normalizeBaseUrl("https://printer.example/octoprint").toString(),
        )
    }

    @Test
    fun explicitHttpSchemeIsPreservedForTrustedLan() {
        assertEquals(
            "http://octopi.local/",
            OctoPrintClient.normalizeBaseUrl("http://octopi.local").toString(),
        )
    }

    @Test
    fun rejectsCredentialsAndQueryDataInServerUrl() {
        assertTrue(
            runCatching { OctoPrintClient.normalizeBaseUrl("http://user:password@octopi.local") }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { OctoPrintClient.normalizeBaseUrl("http://octopi.local/?token=secret") }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { OctoPrintClient.normalizeBaseUrl("http://octopi.local/#fragment") }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun validatesRemotePathsWithoutDotSegmentTraversal() {
        assertEquals(
            "models/cube.gcode",
            OctoPrintClient.normalizeRemotePath("/models/cube.gcode/", allowBlank = false),
        )
        assertEquals("", OctoPrintClient.normalizeRemotePath("", allowBlank = true))
        assertTrue(
            runCatching { OctoPrintClient.normalizeRemotePath("models/../api", false) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { OctoPrintClient.normalizeRemotePath("models\\cube.gcode", false) }
                .exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { OctoPrintClient.normalizeRemotePath("models//cube.gcode", false) }
                .exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun transitionalPrinterStateCountsAsActiveJob() {
        val pausing = OctoPrintUiState(
            printer = OctoPrintPrinterState(operational = true, pausing = true),
        )
        assertTrue(pausing.isTransitioning)
        assertTrue(pausing.hasActiveJob)

        val cancelling = OctoPrintUiState(
            job = OctoPrintJobState(state = "Cancelling"),
        )
        assertTrue(cancelling.isTransitioning)
        assertTrue(cancelling.hasActiveJob)
    }

    @Test
    fun freshSafetySnapshotOverridesCachedIdleState() {
        val cached = OctoPrintUiState(
            config = OctoPrintConfig(baseUrl = "http://octopi.local"),
            hasApiKey = true,
            printer = OctoPrintPrinterState(operational = true, ready = true),
            job = OctoPrintJobState(state = "Operational"),
        )
        val fresh = OctoPrintSafetyPreflight.merge(
            cached = cached,
            job = OctoPrintJobState(state = "Printing"),
            connection = OctoPrintConnectionState(state = "Operational"),
            printer = OctoPrintPrinterState(operational = true, printing = true),
            refreshedAtEpochMillis = 1234L,
        )

        assertTrue(fresh.hasActiveJob)
        assertTrue(fresh.isPrinting)
        assertEquals(1234L, fresh.lastUpdatedEpochMillis)
    }

    @Test
    fun parsesPrinterTemperaturesAndFlags() {
        val printer = OctoPrintJson.parsePrinter(
            JSONObject(
                """
                {
                  "temperature": {
                    "tool0": {"actual": 205.4, "target": 210.0, "offset": 0},
                    "bed": {"actual": 59.8, "target": 60.0, "offset": 0}
                  },
                  "state": {
                    "text": "Printing",
                    "flags": {
                      "operational": true,
                      "printing": true,
                      "paused": false,
                      "ready": false,
                      "closedOrError": false
                    }
                  },
                  "sd": {"ready": true}
                }
                """.trimIndent(),
            ),
        )

        assertEquals("Printing", printer.text)
        assertTrue(printer.operational)
        assertTrue(printer.printing)
        assertFalse(printer.paused)
        assertEquals(205.4, printer.tools.getValue("tool0").actual!!, 0.001)
        assertEquals(60.0, printer.bed?.target!!, 0.001)
        assertTrue(printer.sdReady)
    }

    @Test
    fun parsesJobProgressWithoutInventingMissingValues() {
        val job = OctoPrintJson.parseJob(
            JSONObject(
                """
                {
                  "state": "Printing",
                  "job": {
                    "file": {"name": "cube.gcode", "path": "tests/cube.gcode", "origin": "local", "size": 12345},
                    "estimatedPrintTime": 600
                  },
                  "progress": {
                    "completion": 42.5,
                    "filepos": 5000,
                    "printTime": 255,
                    "printTimeLeft": 345
                  }
                }
                """.trimIndent(),
            ),
        )

        assertEquals("cube.gcode", job.fileName)
        assertEquals("tests/cube.gcode", job.filePath)
        assertEquals(42.5, job.completionPercent!!, 0.001)
        assertEquals(255, job.elapsedSeconds)
        assertEquals(345, job.remainingSeconds)
        assertNull(job.currentZ)
    }

    @Test
    fun flattensNestedOctoPrintFolders() {
        val (files, freeBytes) = OctoPrintJson.parseFiles(
            JSONObject(
                """
                {
                  "free": "3.2GB",
                  "files": [
                    {
                      "name": "calibration",
                      "display": "calibration",
                      "path": "calibration",
                      "type": "folder",
                      "origin": "local",
                      "children": [
                        {
                          "name": "pa.gcode",
                          "display": "pa.gcode",
                          "path": "calibration/pa.gcode",
                          "type": "machinecode",
                          "origin": "local",
                          "size": 123
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(3_200_000_000L, freeBytes)
        assertEquals(2, files.size)
        assertTrue(files[0].isFolder)
        assertFalse(files[1].isFolder)
        assertEquals(1, files[1].depth)
        assertEquals("calibration/pa.gcode", files[1].path)
    }

    @Test
    fun unsafeServerFilePathsAreSkipped() {
        val (files, _) = OctoPrintJson.parseFiles(
            JSONObject(
                """
                {
                  "files": [
                    {"name":"safe.gcode","path":"safe.gcode","type":"machinecode"},
                    {"name":"escape.gcode","path":"../api/version","type":"machinecode"},
                    {"name":"backslash.gcode","path":"folder\\\\file.gcode","type":"machinecode"}
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertEquals(listOf("safe.gcode"), files.map { it.path })
    }

    @Test
    fun parsesNumericAndBinaryFreeSpaceValues() {
        assertEquals(
            999_999L,
            OctoPrintJson.parseFiles(JSONObject("{\"free\":999999,\"files\":[]}" )).second,
        )
        assertEquals(
            1_610_612_736L,
            OctoPrintJson.parseFiles(JSONObject("{\"free\":\"1.5GiB\",\"files\":[]}" )).second,
        )
        assertNull(
            OctoPrintJson.parseFiles(JSONObject("{\"free\":\"unknown\",\"files\":[]}" )).second,
        )
    }

    @Test
    fun rewritesLoopbackWebcamSnapshotToConfiguredServerHost() {
        val client = OctoPrintClient("http://octopi.local")
        assertEquals(
            "http://octopi.local:8080/webcam/?action=snapshot",
            client.resolveWebcamSnapshotUrl("http://localhost:8080/webcam/?action=snapshot")?.toString(),
        )
        assertEquals(
            "http://octopi.local:8080/webcam/?action=snapshot",
            client.resolveWebcamSnapshotUrl("http://127.0.0.1:8080/webcam/?action=snapshot")?.toString(),
        )
        assertEquals(
            "http://octopi.local/webcam/?action=snapshot",
            client.resolveWebcamSnapshotUrl("/webcam/?action=snapshot")?.toString(),
        )
    }

    @Test
    fun blocksCloudMetadataAndLinkLocalWebcamSnapshotTargets() {
        val client = OctoPrintClient("http://octopi.local")
        assertNull(client.resolveWebcamSnapshotUrl("http://169.254.169.254/latest/meta-data/"))
        assertNull(client.resolveWebcamSnapshotUrl("http://169.254.0.1:8080/webcam/"))
        assertNull(client.resolveWebcamSnapshotUrl("http://[fc00::1]:8080/webcam/"))
        assertNull(client.resolveWebcamSnapshotUrl("http://[fd12:3456:789a::1]/webcam/"))
    }

    @Test
    fun allowsRfc1918WebcamTargetsOnTheLan() {
        val client = OctoPrintClient("http://octopi.local")
        assertEquals(
            "http://192.168.1.50:8080/webcam/?action=snapshot",
            client.resolveWebcamSnapshotUrl("http://192.168.1.50:8080/webcam/?action=snapshot")?.toString(),
        )
        assertEquals(
            "http://10.0.0.7/webcam/?action=snapshot",
            client.resolveWebcamSnapshotUrl("http://10.0.0.7/webcam/?action=snapshot")?.toString(),
        )
    }

    @Test
    fun preservesNonLoopbackWebcamSnapshotHost() {
        val client = OctoPrintClient("http://octopi.local")
        assertEquals(
            "http://192.168.1.50:8080/webcam/?action=snapshot",
            client.resolveWebcamSnapshotUrl("http://192.168.1.50:8080/webcam/?action=snapshot")?.toString(),
        )
    }

    @Test
    fun keepsLoopbackRewriteWhenServerUsesPathPrefix() {
        val client = OctoPrintClient("https://printer.example/octoprint")
        assertEquals(
            "https://printer.example:8080/webcam/?action=snapshot",
            client.resolveWebcamSnapshotUrl("https://localhost:8080/webcam/?action=snapshot")?.toString(),
        )
    }

    @Test
    fun rewritesEveryLoopbackSpellingToTheConfiguredServerHost() {
        // Comparing the host against "::1" and "127.0.0.1" never matched an
        // IPv6 literal (URI.getHost() keeps its brackets) and missed the rest of
        // 127.0.0.0/8, so these URLs used to be fetched from the phone's own
        // loopback instead of the configured OctoPrint host.
        val client = OctoPrintClient("http://octopi.local")
        for (loopback in listOf(
            "http://[::1]:8080/webcam/?action=snapshot",
            "http://[0:0:0:0:0:0:0:1]:8080/webcam/?action=snapshot",
            "http://127.0.0.2:8080/webcam/?action=snapshot",
            "http://[::ffff:127.0.0.1]:8080/webcam/?action=snapshot",
        )) {
            assertEquals(
                "loopback spelling not rewritten: $loopback",
                "http://octopi.local:8080/webcam/?action=snapshot",
                client.resolveWebcamSnapshotUrl(loopback)?.toString(),
            )
        }
    }

    @Test
    fun keepsALoopbackAddressedServerReachable() {
        // A server the user configured as 127.0.0.1 is still the server: the
        // rewrite sends the webcam URL there, and the guard must not refuse the
        // result - that setup works today.
        val client = OctoPrintClient("http://127.0.0.1:5000")
        assertEquals(
            "http://127.0.0.1/webcam/?action=snapshot",
            client.resolveWebcamSnapshotUrl("http://localhost/webcam/?action=snapshot")?.toString(),
        )
    }

    @Test
    fun webcamErrorIsExposedInUiState() {
        val state = OctoPrintUiState(webcamError = "Webcam snapshot failed: Connection refused")
        assertEquals("Webcam snapshot failed: Connection refused", state.webcamError)
        val cleared = state.copy(webcamFrame = byteArrayOf(1), webcamError = null)
        assertNull(cleared.webcamError)
    }

    @Test
    fun recognizesCommonWebcamImageMagicBytes() {
        val client = OctoPrintClient("http://octopi.local")
        val jpeg = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0, 0,
        )
        assertTrue(client.looksLikeImage(jpeg))
        val png = byteArrayOf(
            0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(), 0x0D, 0x0A, 0x1A, 0x0A,
        )
        assertTrue(client.looksLikeImage(png))
        val gif = byteArrayOf(
            0x47.toByte(), 0x49.toByte(), 0x46.toByte(), 0x38.toByte(), 0x39, 0x61, 0, 0,
        )
        assertTrue(client.looksLikeImage(gif))
        val webp = byteArrayOf(
            0x52.toByte(), 0x49.toByte(), 0x46.toByte(), 0x46.toByte(), 0, 0, 0, 0,
            0x57.toByte(), 0x45.toByte(), 0x42.toByte(), 0x50.toByte(),
        )
        assertTrue(client.looksLikeImage(webp))
    }

    @Test
    fun rejectsNonImageWebcamResponses() {
        val client = OctoPrintClient("http://octopi.local")
        assertFalse(client.looksLikeImage("<html>login page</html>".toByteArray()))
        assertFalse(client.looksLikeImage("{".toByteArray()))
        assertFalse(client.looksLikeImage(byteArrayOf(0, 0, 0, 0)))
    }

    @Test
    fun parsesPortSuffixedCsrfCookie() {
        val pair = parseCsrfCookie(
            listOf("session_P5000=abc123; HttpOnly; Path=/", "csrf_token_P5000=IjJ0b2tlbg; Path=/"),
        )
        assertEquals("csrf_token_P5000", pair?.first)
        assertEquals("IjJ0b2tlbg", pair?.second)
    }

    @Test
    fun parsesPathPrefixedCsrfCookie() {
        val pair = parseCsrfCookie(
            listOf("csrf_token_P5000_Roctoprint=IjJ0b2tlbg; path=/octoprint; HttpOnly"),
        )
        assertEquals("csrf_token_P5000_Roctoprint", pair?.first)
        assertEquals("IjJ0b2tlbg", pair?.second)
    }

    @Test
    fun parsesPlainCsrfCookie() {
        val pair = parseCsrfCookie(listOf("csrf_token=IjJ0b2tlbg"))
        assertEquals("csrf_token", pair?.first)
        assertEquals("IjJ0b2tlbg", pair?.second)
    }

    @Test
    fun ignoresNonCsrfAndMalformedSetCookies() {
        assertNull(parseCsrfCookie(listOf("session_P5000=abc; Path=/")))
        assertNull(parseCsrfCookie(listOf("")))
        assertNull(parseCsrfCookie(listOf("=value")))
        assertNull(parseCsrfCookie(emptyList()))
        assertNull(parseCsrfCookie(listOf("csrf_token=")));
    }

    @Test
    fun derivesAdminFromGroupsAndPermissions() {
        val version = JSONObject("{\"api\":\"0.1\",\"server\":\"2.0.0rc4\",\"text\":\"OctoPrint 2.0.0rc4\"}")

        val viaGroup = JSONObject("{\"name\":\"tester\",\"groups\":[\"users\",\"admins\"],\"permissions\":[\"STATUS\"]}")
        assertTrue(OctoPrintJson.parseServerInfo(version, viaGroup).userIsAdmin)

        val viaPermission = JSONObject("{\"name\":\"tester\",\"groups\":[\"users\"],\"permissions\":[\"STATUS\",\"ADMIN\"]}")
        assertTrue(OctoPrintJson.parseServerInfo(version, viaPermission).userIsAdmin)

        val legacyAdmin = JSONObject("{\"name\":\"tester\",\"admin\":true,\"permissions\":[]}")
        assertTrue(OctoPrintJson.parseServerInfo(version, legacyAdmin).userIsAdmin)

        val plainUser = JSONObject("{\"name\":\"tester\",\"groups\":[\"users\"],\"permissions\":[\"STATUS\"]}")
        assertFalse(OctoPrintJson.parseServerInfo(version, plainUser).userIsAdmin)

        val modern = JSONObject("{\"name\":\"tester\",\"groups\":[\"users\"],\"permissions\":[\"STATUS\",\"CONNECTION\"]}")
        assertFalse(OctoPrintJson.parseServerInfo(version, modern).userIsAdmin)
    }

    @Test
    fun flatteningRefusesAHostileFolderNestWithItsOwnMessage() {
        // A server can nest folders until the recursion overflows the stack, and
        // the caller then reported a bare StackOverflowError with no file list.
        var node = JSONObject().put("type", "folder").put("path", "bottom")
        for (level in 1..40) {
            node = JSONObject()
                .put("type", "folder")
                .put("path", "level-" + level)
                .put("children", JSONArray().put(node))
        }

        val error = runCatching {
            OctoPrintJson.parseFiles(JSONObject().put("files", JSONArray().put(node)))
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("nests more than 32 folders deep"))
    }

    @Test
    fun flatteningStillReadsAFolderTreeOfOrdinaryDepth() {
        val child = JSONObject().put("type", "machinecode").put("path", "folder/cube.gcode")
        val folder = JSONObject()
            .put("type", "folder")
            .put("path", "folder")
            .put("children", JSONArray().put(child))

        val (files, freeBytes) = OctoPrintJson.parseFiles(JSONObject().put("files", JSONArray().put(folder)))

        assertEquals(listOf("folder", "folder/cube.gcode"), files.map { it.path })
        assertNull(freeBytes)
    }

    @Test
    fun treatsOnlyTheDocumentedApiRejectionAsAnInvalidKey() {
        val client = OctoPrintClient("http://octopi.local", "stored-key")
        val rejection = JSONObject().put("error", "Invalid API key")

        assertTrue(client.isApiKeyRejection(URI("http://octopi.local/api/printer"), 403, rejection))

        // Every one of these used to erase the stored key, because the old check
        // only searched the peer's message text for "api key".
        assertFalse(client.isApiKeyRejection(URI("http://elsewhere.example/api/printer"), 403, rejection))
        assertFalse(client.isApiKeyRejection(URI("http://octopi.local/webcam/?action=snapshot"), 403, rejection))
        assertFalse(client.isApiKeyRejection(URI("http://octopi.local/api/printer"), 403, null))
        assertFalse(client.isApiKeyRejection(URI("http://octopi.local/api/printer"), 401, rejection))
        // OctoPrint turns any aborted API route into a JSON error, so a
        // permission 403 (a valid key whose user may not read the endpoint) has
        // this shape and must not be read as a rejected key.
        assertFalse(
            client.isApiKeyRejection(
                URI("http://octopi.local/api/settings"),
                403,
                JSONObject().put(
                    "error",
                    "You don't have the permission to access the requested resource. " +
                        "It is either read-protected or not readable by the server.",
                ),
            ),
        )
    }

    @Test
    fun parsesPsuControlStateAndRejectsResponsesWithoutTheFlag() {
        assertEquals(true, OctoPrintJson.parsePowerState(JSONObject().put("isPSUOn", true)))
        assertEquals(false, OctoPrintJson.parsePowerState(JSONObject().put("isPSUOn", false)))
        assertNull(OctoPrintJson.parsePowerState(JSONObject()))
    }

    @Test
    fun readsPsuStateAndPostsPsuControlCommands() {
        val posts = CopyOnWriteArrayList<String>()
        val server = startFakeServer { exchange ->
            when {
                exchange.requestURI.path != "/api/plugin/psucontrol" ->
                    exchange.respond(200, "<html></html>", "text/html")

                exchange.requestMethod == "GET" -> exchange.respond(200, """{"isPSUOn": true}""")

                else -> {
                    posts += exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                    exchange.respond(200, "")
                }
            }
        }
        try {
            val client = OctoPrintClient(server.baseUrl(), "test-key")

            assertEquals(true, client.powerState())

            client.setPower(false)
            client.setPower(true)
            assertEquals(
                listOf("""{"command":"turnPSUOff"}""", """{"command":"turnPSUOn"}"""),
                posts.toList(),
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun missingPsuControlPluginIsAnAbsentFeatureRatherThanAnError() {
        val server = startFakeServer { exchange -> exchange.respond(404, """{"error": "Not found"}""") }
        try {
            val client = OctoPrintClient(server.baseUrl(), "test-key")

            assertNull(client.powerState())
        } finally {
            server.stop(0)
        }
    }

    private fun startFakeServer(handler: (HttpExchange) -> Unit): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(
            "/",
            HttpHandler { exchange ->
                try {
                    handler(exchange)
                } finally {
                    exchange.close()
                }
            },
        )
        server.start()
        return server
    }

    private fun HttpServer.baseUrl(): String = "http://127.0.0.1:" + address.port

    private fun HttpExchange.respond(status: Int, body: String, contentType: String = "application/json") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.add("Content-Type", contentType)
        sendResponseHeaders(status, if (bytes.isEmpty()) -1L else bytes.size.toLong())
        if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) }
    }
}
