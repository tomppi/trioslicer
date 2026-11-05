package com.tomppi.enderslicer.printer

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Starting a print over one that is running, and the recovery when the connection went
 * under the start.
 *
 * The rule is the Files tab's, at the repository: a start sends the live-state reset
 * before SDCARD_PRINT_FILE, so a second tap (or a start that lost a race) would clear
 * the babystep Z offset and the M220/M221 factors of the print that is already running
 * and then have its own command refused. The repository refuses first, and says so.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class KlipperPrintStartTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(Dispatchers.IO)

    @Test
    fun aStartIsRefusedWhileAPrintIsPrinting() = runBlocking {
        val fake = FakeMoonraker(printState = "printing")
        val repository = KlipperPrinterRepository(context, scope)
        try {
            KlipperHostChoiceStore(context).save(
                KlipperHostChoice(mode = KlipperHostMode.PC, host = "127.0.0.1", port = fake.port),
            )
            repository.start()
            awaitPrintState(repository, "printing")

            val source = File(context.cacheDir, "slice.gcode").apply { writeText("G28\n") }
            val result = repository.printFile(source.absolutePath, "cube")

            assertTrue("the start is refused", result.isFailure)
            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue(
                "and it names the print it would reset: " + message,
                message.contains("the printer is printing"),
            )
            assertTrue(
                "nothing is sent to a printer mid-print: " + fake.scripts,
                fake.scripts.isEmpty(),
            )
        } finally {
            repository.stop()
            fake.close()
        }
    }

    @Test
    fun aStartIsRefusedWhileAPrintIsPaused() = runBlocking {
        val fake = FakeMoonraker(printState = "paused")
        val repository = KlipperPrinterRepository(context, scope)
        try {
            KlipperHostChoiceStore(context).save(
                KlipperHostChoice(mode = KlipperHostMode.PC, host = "127.0.0.1", port = fake.port),
            )
            repository.start()
            awaitPrintState(repository, "paused")

            repository.printGcodeFile("cube.gcode")

            val error = repository.state.value.error.orEmpty()
            assertTrue("the refusal is reported: " + error, error.contains("the printer is paused"))
            assertTrue(
                "and a paused print is not reset by a start over it: " + fake.scripts,
                fake.scripts.isEmpty(),
            )
        } finally {
            repository.stop()
            fake.close()
        }
    }

    @Test
    fun aDroppedConnectionNamesTheRecoveryRatherThanNotConnected() = runBlocking {
        KlipperHostChoiceStore(context).save(KlipperHostChoice(mode = KlipperHostMode.DEVICE))
        // Nothing has connected, which is what a connection that dropped under the start
        // leaves behind - the case the recovery in printFile was written for. The old
        // order reached the first send before the check and reported only "not connected".
        val repository = KlipperPrinterRepository(context, scope)
        val source = File(context.cacheDir, "slice.gcode").apply { writeText("G28\n") }
        val result = repository.printFile(source.absolutePath, "cube")

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(
            "the user is told the way back: " + message,
            message.contains("print it from Files"),
        )
        assertTrue(
            "and the file the message names really is on the host",
            File(KlipperHostFiles.gcodes(context.filesDir), "cube.gcode").isFile,
        )
    }

    @Test
    fun aPrintThatEndsIsWrittenDownOnce() = runBlocking {
        val fake = FakeMoonraker(printState = "complete")
        val repository = KlipperPrinterRepository(context, scope)
        try {
            KlipperHostChoiceStore(context).save(
                KlipperHostChoice(mode = KlipperHostMode.PC, host = "127.0.0.1", port = fake.port),
            )
            repository.start()
            awaitPrintState(repository, "complete")

            val deadline = System.currentTimeMillis() + 5_000
            while (repository.history.value.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            val records = repository.history.value
            assertEquals("the ended print is written down exactly once", 1, records.size)
            assertEquals("cube.gcode", records.first().fileName)
            assertEquals("complete", records.first().outcome)
        } finally {
            repository.stop()
            fake.close()
        }
    }

    private fun awaitPrintState(repository: KlipperPrinterRepository, printState: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (repository.state.value.printState == printState) return
            Thread.sleep(20)
        }
        error("the fake host never reported " + printState + ": the state says " + repository.state.value.printState)
    }

    /**
     * A Moonraker that answers the way the real one does: the WebSocket the app watches
     * the printer over, and the plain HTTP a camera list is fetched with, on the one
     * port Moonraker serves both on. [scripts] records every G-code the app sends, which
     * is how "nothing was sent" is proven.
     */
    private class FakeMoonraker(private val printState: String) {
        private val server = ServerSocket(0)
        private val running = AtomicBoolean(true)
        val port: Int get() = server.localPort
        val scripts = CopyOnWriteArrayList<String>()

        init {
            Thread {
                while (running.get()) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    Thread { runCatching { handle(socket) } }.apply { isDaemon = true; start() }
                }
            }.apply { isDaemon = true; start() }
        }

        private fun handle(socket: Socket) {
            socket.use { connection ->
                val input = connection.getInputStream()
                val output = connection.getOutputStream()
                val headers = readHeaders(input)
                if (!headers.contains("Upgrade: websocket", ignoreCase = true)) {
                    // The camera list is plain HTTP on the same port; it is not what this
                    // fake is here to answer.
                    output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    output.flush()
                    return
                }
                val key = headers.lineSequence()
                    .first { it.startsWith("Sec-WebSocket-Key:") }
                    .substringAfter(':').trim()
                output.write(
                    (
                        "HTTP/1.1 101 Switching Protocols\r\n" +
                            "Upgrade: websocket\r\n" +
                            "Connection: Upgrade\r\n" +
                            "Sec-WebSocket-Accept: " + KlipperWebSocket.handshakeAccept(key) + "\r\n\r\n"
                        ).toByteArray(),
                )
                output.flush()
                var pending = ByteArray(0)
                while (running.get()) {
                    val chunk = ByteArray(4096)
                    val count = input.read(chunk)
                    if (count < 0) return
                    pending += chunk.copyOfRange(0, count)
                    val (frames, rest) = KlipperWebSocket.decode(pending)
                    pending = rest
                    for (frame in frames) {
                        // A WebSocket server must answer a ping with a pong (RFC 6455), and
                        // the transport relies on it: the pong is what brings its reader back
                        // to the locally answered reply it is holding.
                        if (frame.opcode == KlipperWebSocket.OPCODE_PING) {
                            output.write(KlipperWebSocket.encode(KlipperWebSocket.OPCODE_PONG, frame.payload))
                            output.flush()
                            continue
                        }
                        if (frame.opcode != KlipperWebSocket.OPCODE_TEXT) continue
                        val request = JSONObject(String(frame.payload))
                        val method = request.optString("method")
                        if (method == "printer.gcode.script") {
                            scripts.add(request.optJSONObject("params")?.optString("script").orEmpty())
                        }
                        output.write(
                            KlipperWebSocket.encode(
                                KlipperWebSocket.OPCODE_TEXT,
                                JSONObject()
                                    .put("id", request.optInt("id"))
                                    .put("result", resultFor(method))
                                    .toString()
                                    .toByteArray(),
                            ),
                        )
                        output.flush()
                    }
                }
            }
        }

        private fun resultFor(method: String): JSONObject = when (method) {
            "printer.info" -> JSONObject()
                .put("state", "ready")
                .put("state_message", "")
                .put("software_version", "v0.13.0-fake")
            "printer.objects.list" -> JSONObject()
                .put("objects", JSONArray().put("toolhead").put("print_stats"))
            "printer.objects.subscribe" -> JSONObject()
                .put("eventtime", 1.0)
                .put(
                    "status",
                    JSONObject().put(
                        "print_stats",
                        JSONObject().put("state", printState).put("filename", "cube.gcode"),
                    ),
                )
            else -> JSONObject()
        }

        /** Read the request headers, up to the blank line. */
        private fun readHeaders(input: InputStream): String {
            val text = StringBuilder()
            while (!text.endsWith("\r\n\r\n")) {
                val next = input.read()
                if (next < 0) break
                text.append(next.toChar())
            }
            return text.toString()
        }

        fun close() {
            running.set(false)
            runCatching { server.close() }
        }
    }
}
