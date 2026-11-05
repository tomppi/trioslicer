package com.tomppi.enderslicer.modelling

import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine's command channel is a socket on loopback that the app reads until
 * the reply parses as JSON.
 *
 * That read used to be unbounded and to re-decode and re-parse the whole
 * buffer once per 16 KB chunk, so a peer holding the port could grow the
 * buffer without end and burn CPU doing it. These tests drive the reader with
 * a stream a test can end, which the engine's own socket is not.
 */
class EnginePreviewClientTest {
    private val client = EnginePreviewClient(tokenFile = null)

    @Test
    fun readsAReplyThatEndsInAWhitespacePaddedObject() {
        val reply = "{\"status\":\"success\"}"

        assertEquals(reply + "\n", client.readReply((reply + "\n").byteInputStream()))
    }

    @Test
    fun aCharacterSplitAcrossTheChunkBoundaryIsNotCorrupted() {
        // Decoding each chunk on its own turned a multi-byte character that
        // straddled 16 KB into U+FFFD, so the reply had to be decoded whole.
        // The padding puts the first byte of the check mark at 16 KB exactly.
        val body = "a".repeat(16 * 1024 - 12)
        val reply = "{\"result\":\"" + body + "\u2713\"}"

        val read = client.readReply(reply.byteInputStream())

        assertEquals(reply, read)
        assertEquals(body + "\u2713", JSONObject(read).getString("result"))
    }

    @Test
    fun anEndlessStreamWithoutAClosingBraceIsRefusedAtTheReplyLimit() {
        // Nothing here can parse, so only the cap can stop it - and it must stop
        // it long before a phone heap runs out.
        val error = runCatching {
            client.readReply(ByteArrayInputStream(ByteArray(2 * 1024 * 1024) { 'x'.code.toByte() }))
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("byte limit"))
    }

    @Test
    fun aStreamThatEndsMidObjectReportsTheClosedConnection() {
        val error = runCatching { client.readReply("abc".byteInputStream()) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("closed the connection"))
    }

    /**
     * A timeout means the engine already has the command - it runs what it accepted whether
     * or not the answer comes back - so the client reports it once instead of reconnecting
     * and sending it again. A second send is how one "All to plate" press imported every
     * object twice.
     */
    @Test
    fun aTimeoutIsReportedWithoutSendingTheCommandAgain() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        server.soTimeout = 700
        val connections = CopyOnWriteArrayList<Socket>()
        val holder = Thread {
            try {
                connections += server.accept()
                // Hold the answer past the client's read timeout; a retry would land here.
                Thread.sleep(400)
                runCatching { server.accept() }.getOrNull()?.let { connections += it }
            } catch (_: Throwable) {
                // The accept timeout at the end of the window is the "no retry" answer.
            }
        }
        holder.start()
        val live = EnginePreviewClient(host = "127.0.0.1", port = server.localPort, tokenFile = null)
        val error = runCatching { live.command("execute_code", JSONObject(), 150) }.exceptionOrNull()
        assertTrue("the timeout is reported as itself: " + error, error is SocketTimeoutException)
        holder.join(3_000)
        assertEquals(
            "the command goes out once; a retry would open a second connection",
            1,
            connections.size,
        )
        live.close()
        server.close()
    }

    /** The retry that stays: a dead socket (the engine restarted under the command) is reconnected once. */
    @Test
    fun aDeadSocketIsReconnectedOnceRatherThanGivenUpOn() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val connections = CopyOnWriteArrayList<Socket>()
        val holder = Thread {
            try {
                val first = server.accept()
                connections += first
                first.close()
                val second = server.accept()
                connections += second
                second.getOutputStream().apply {
                    write("{\"status\":\"success\"}\n".toByteArray())
                    flush()
                }
            } catch (_: Throwable) {
            }
        }
        holder.start()
        val live = EnginePreviewClient(host = "127.0.0.1", port = server.localPort, tokenFile = null)
        val reply = live.command("ping", JSONObject(), 2_000)
        assertEquals("success", reply.optString("status"))
        holder.join(2_000)
        assertEquals("the dead socket is reconnected exactly once", 2, connections.size)
        live.close()
        server.close()
    }
}
