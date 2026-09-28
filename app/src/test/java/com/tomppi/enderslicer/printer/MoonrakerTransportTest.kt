package com.tomppi.enderslicer.printer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The remote transport against a WebSocket server, without needing a printer.
 *
 * A fake Moonraker does the handshake, reads what the app sent and pushes a status update back,
 * so the whole path is exercised: the handshake and its accept value, the request namespaced and
 * masked on the way out, and the notification turned back into the shape the client reads on the
 * way in. This is the layer where being slightly wrong is invisible, so it is worth a real socket
 * rather than a stub.
 */
class MoonrakerTransportTest {

    @Test
    fun theClientSpeaksToAFakeMoonraker() {
        val server = ServerSocket(0)
        val port = server.localPort
        val sawRequest = AtomicReference<String>()
        val requestSeen = CountDownLatch(1)

        val thread = Thread {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                val output = socket.getOutputStream()
                val request = readHeaders(input)
                val key = request.lineSequence()
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
                // What the app sends, unmasked and recorded so the test can read it.
                val frame = readFrame(input)
                sawRequest.set(String(frame))
                requestSeen.countDown()
                // And what Moonraker pushes: a status update, in Moonraker's shape.
                val notification = JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("method", "notify_status_update")
                    .put(
                        "params",
                        JSONArray()
                            .put(JSONObject().put("toolhead", JSONObject().put("max_accel", 5000.0)))
                            .put(1234.5),
                    )
                    .toString()
                    .toByteArray()
                output.write(KlipperWebSocket.encode(KlipperWebSocket.OPCODE_TEXT, notification))
                output.flush()
                Thread.sleep(400)
            }
        }
        thread.isDaemon = true
        thread.start()

        val transport = MoonrakerTransport("127.0.0.1", port)
        transport.connect(3000)
        transport.write(
            KlipperProtocol.encode(
                4,
                "objects/subscribe",
                JSONObject().put("objects", JSONObject().put("toolhead", JSONObject.NULL)),
            ),
        )
        assertTrue("the request reached the server", requestSeen.await(3, TimeUnit.SECONDS))

        // It has to be the name Moonraker knows, in an envelope Moonraker reads.
        val sent = JSONObject(sawRequest.get())
        assertEquals("printer.objects.subscribe", sent.getString("method"))
        assertEquals("2.0", sent.getString("jsonrpc"))
        assertEquals(4, sent.getInt("id"))

        // And what comes back is what the client already knows how to read.
        val message = readOneMessage(transport)
        val params = message.getJSONObject("params")
        assertEquals(1234.5, params.getDouble("eventtime"), 1e-9)
        assertEquals(
            5000.0,
            params.getJSONObject("status").getJSONObject("toolhead").getDouble("max_accel"),
            1e-9,
        )
        transport.close()
        server.close()
    }

    @Test
    fun aCallMoonrakerDoesNotHaveIsAnsweredRatherThanTimedOut() {
        val transport = MoonrakerTransport("127.0.0.1", 1) // never connected: not needed here
        transport.write(KlipperProtocol.encode(11, "gcode/subscribe_output", JSONObject()))
        val buffer = ByteArray(256)
        val count = transport.read(buffer)
        assertTrue("an answer without a round trip", count > 0)
        val reply = JSONObject(String(buffer, 0, count - 1))
        assertEquals(11, reply.getInt("id"))
        assertTrue(reply.has("result"))
    }

    /** Read the server's request headers, up to the blank line. */
    private fun readHeaders(input: InputStream): String {
        val text = StringBuilder()
        while (!text.endsWith("\r\n\r\n")) {
            val next = input.read()
            if (next < 0) break
            text.append(next.toChar())
        }
        return text.toString()
    }

    /** One frame from the client, unmasked. */
    private fun readFrame(input: InputStream): ByteArray {
        val first = input.read()
        val second = input.read()
        var length = second and 0x7F
        if (length == 126) {
            length = (input.read() shl 8) or input.read()
        } else if (length == 127) {
            length = 0
            repeat(8) { length = (length shl 8) or input.read() }
        }
        val mask = ByteArray(4)
        if ((second and 0x80) != 0) {
            for (index in 0..3) mask[index] = input.read().toByte()
        }
        val payload = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(payload, read, length - read)
            if (count < 0) break
            read += count
        }
        if ((second and 0x80) != 0) {
            for (index in payload.indices) {
                payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
            }
        }
        assertEquals("a client frame must be masked and be text", 0x81, first)
        return payload
    }

    /** A whole ETX-terminated message through the transport's read. */
    private fun readOneMessage(transport: KlipperTransport): JSONObject {
        val buffer = ByteArray(2048)
        val message = StringBuilder()
        while (true) {
            val count = transport.read(buffer)
            if (count < 0) break
            for (index in 0 until count) {
                val byte = buffer[index]
                if (byte == KlipperProtocol.ETX) return JSONObject(message.toString())
                message.append(byte.toInt().toChar())
            }
        }
        error("no message arrived")
    }
}
