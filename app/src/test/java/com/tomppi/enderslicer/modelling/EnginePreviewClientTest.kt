package com.tomppi.enderslicer.modelling

import java.io.ByteArrayInputStream
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
}
