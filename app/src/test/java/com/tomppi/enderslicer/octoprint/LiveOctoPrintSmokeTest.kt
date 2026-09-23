package com.tomppi.enderslicer.octoprint

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Opt-in smoke test against a live OctoPrint server. Skipped unless the
 * environment variables LIVE_OCTOPRINT_URL and LIVE_OCTOPRINT_KEYFILE are
 * set (the key file is the JSON {\"api_key\": ...} written by the Application
 * Keys flow). Exercises the real wire format, error handling and the CSRF
 * double-submit handshake for state-changing requests.
 */
class LiveOctoPrintSmokeTest {
    @Test
    fun liveServerRoundTripExercisesCsrf() {
        val url = System.getenv("LIVE_OCTOPRINT_URL") ?: ""
        val keyFile = System.getenv("LIVE_OCTOPRINT_KEYFILE") ?: ""
        assumeTrue("LIVE_OCTOPRINT_URL not set", url.isNotBlank())
        assumeTrue("LIVE_OCTOPRINT_KEYFILE not set", keyFile.isNotBlank())
        val keyText = File(keyFile).readText()
        val key = Regex("\"api_key\"\\s*:\\s*\"([^\"]+)\"").find(keyText)?.groupValues?.get(1)
            ?: error("api_key not found in $keyFile")

        val stepLog = System.getenv("LIVE_OCTOPRINT_STEPLOG")?.let(::File)
        fun step(message: String) {
            stepLog?.appendText((java.time.Instant.now().toString() + " " + message + "\n"))
        }

        val client = OctoPrintClient(url, key)
        try {
            step("client ready")
            val info = OctoPrintJson.parseServerInfo(client.version(), client.currentUser())
            step("version/currentuser ok: " + (info.userName ?: "?"))
            assertEquals("0.1", info.apiVersion)
            assertNotNull(info.serverVersion)
            assertNotNull(info.userName)
            assertTrue(info.permissions.isNotEmpty())

            client.jobState()
            step("job ok")
            client.connectionState()
            step("connection ok")
            val printer = runCatching { client.printerState() }.getOrNull()
            step("printer ok")
            assertTrue(printer == null || printer.text.isNotBlank())
            val (files, free) = client.files(force = true)
            assertNotNull(files)
            assertTrue(free == null || free >= 0L)
            client.webcamSettings()
            step("webcam ok")

            // State-changing round trip - exercises the CSRF double-submit path.
            val stamp = System.currentTimeMillis()
            val folder = "es-smoke-$stamp"
            val folder2 = "es-smoke-$stamp-2"
            val gcode = File.createTempFile("es-smoke-", ".gcode").apply {
                writeText("; TrioSlicer live smoke fixture\nG21\nG90\nM107\n")
            }
            try {
                step("createFolder1 start")
                client.createFolder("", folder)
                step("createFolder1 done")
                client.createFolder("", folder2)
                step("createFolder2 done")
                val upload = client.upload(
                    gcode, "audit-test.gcode", folder,
                    OctoPrintUploadAction.UPLOAD_AND_SELECT,
                ) { _, _ -> }
                assertTrue(upload.has("effectiveSelect"))
                // Selecting/printing requires an operational printer; on a
                // printer-less instance OctoPrint answers 409 (which is the
                // same failure the app surfaces). Any other error is real.
                val selectError = runCatching {
                    client.selectFile("$folder/audit-test.gcode", print = false)
                }.exceptionOrNull()
                if (selectError != null) {
                    assertTrue(
                        "select should only fail with 409 on a printer-less instance: $selectError",
                        selectError is OctoPrintClient.OctoPrintHttpException &&
                            selectError.statusCode == 409,
                    )
                }
                client.moveFile("$folder/audit-test.gcode", destination = folder2)
                client.copyFile("$folder2/audit-test.gcode", destination = folder)
                // This OctoPrint 2.0 rc build (Windows) raises ValueError in
                // its thumbnail path for per-file DELETE and answers 500;
                // folder deletion is reliable and cleans up recursively, so
                // the test asserts folder cleanup and tolerates the known bug.
                runCatching { client.deleteFile("$folder/audit-test.gcode") }
                runCatching { client.deleteFile("$folder2/audit-test.gcode") }
                val folderDelete = runCatching { client.deleteFile(folder) }.exceptionOrNull()
                assertNull("folder cleanup should succeed: $folderDelete", folderDelete)
                runCatching { client.deleteFile(folder2) }
            } finally {
                gcode.delete()
            }
        } finally {
            client.cancelActiveRequests()
        }
    }
}
