package com.tomppi.enderslicer.printer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WebSocket framing, which is only ever wrong silently.
 *
 * The lengths are the part worth pinning: 125 bytes and fewer go in the header, 126 to 65535
 * take two bytes after it, and longer takes eight. A frame with the wrong encoding is not
 * rejected, it simply never arrives as a message, and the screen waits.
 */
class KlipperWebSocketTest {
    private val mask = byteArrayOf(0x01, 0x02, 0x03, 0x04)

    @Test
    fun aClientFrameIsMaskedAsTheSpecificationRequires() {
        val encoded = KlipperWebSocket.encode(KlipperWebSocket.OPCODE_TEXT, "hi".toByteArray(), mask)
        assertEquals(0x81.toByte(), encoded[0])
        assertEquals(0x82.toByte(), encoded[1]) // masked, and two bytes long
        assertArrayEquals(mask, encoded.copyOfRange(2, 6))
        // And the mask is applied to the payload, not just announced.
        assertTrue(encoded.size == 8)
    }

    @Test
    fun itRoundTripsAtEveryLengthEncoding() {
        for (size in listOf(0, 1, 125, 126, 127, 65535, 65536)) {
            val payload = ByteArray(size) { (it % 251).toByte() }
            val encoded = KlipperWebSocket.encode(KlipperWebSocket.OPCODE_TEXT, payload, mask)
            val (frames, rest) = KlipperWebSocket.decode(encoded)
            assertEquals("size $size produced ${frames.size} frames", 1, frames.size)
            assertArrayEquals("size $size did not survive", payload, frames[0].payload)
            assertEquals(KlipperWebSocket.OPCODE_TEXT, frames[0].opcode)
            assertTrue("size $size left ${rest.size} bytes", rest.isEmpty())
        }
    }

    @Test
    fun aFrameSplitAcrossReadsIsHeldUntilItIsWhole() {
        val encoded = KlipperWebSocket.encode(KlipperWebSocket.OPCODE_TEXT, "hello".toByteArray(), mask)
        val (first, remainder) = KlipperWebSocket.decode(encoded.copyOfRange(0, 3))
        assertTrue("half a frame is not a frame", first.isEmpty())
        assertEquals(3, remainder.size)
        val (second, left) = KlipperWebSocket.decode(remainder + encoded.copyOfRange(3, encoded.size))
        assertEquals(1, second.size)
        assertEquals("hello", String(second[0].payload))
        assertTrue(left.isEmpty())
    }

    @Test
    fun twoFramesInOneReadBothArrive() {
        val both = KlipperWebSocket.encode(KlipperWebSocket.OPCODE_TEXT, "one".toByteArray(), mask) +
            KlipperWebSocket.encode(KlipperWebSocket.OPCODE_TEXT, "two".toByteArray(), mask)
        val (frames, rest) = KlipperWebSocket.decode(both)
        assertEquals(2, frames.size)
        assertEquals("one", String(frames[0].payload))
        assertEquals("two", String(frames[1].payload))
        assertTrue(rest.isEmpty())
    }

    @Test
    fun aServerFrameIsNotMaskedAndStillReads() {
        // What Moonraker actually sends: no mask bit, no key.
        val server = byteArrayOf(0x81.toByte(), 0x02, 0x68, 0x69)
        val (frames, _) = KlipperWebSocket.decode(server)
        assertEquals("hi", String(frames[0].payload))
    }

    @Test
    fun controlFramesKeepTheirOpcodes() {
        for (opcode in listOf(KlipperWebSocket.OPCODE_PING, KlipperWebSocket.OPCODE_PONG, KlipperWebSocket.OPCODE_CLOSE)) {
            val (frames, _) = KlipperWebSocket.decode(KlipperWebSocket.encode(opcode, ByteArray(0), mask))
            assertEquals(opcode, frames[0].opcode)
        }
    }

    @Test
    fun theHandshakeIsTheOneMoonrakerWillAccept() {
        val request = KlipperWebSocket.handshakeRequest("192.168.3.212", 7125, "/websocket", "key123")
        assertTrue(request.startsWith("GET /websocket HTTP/1.1\r\n"))
        assertTrue(request.contains("Upgrade: websocket\r\n"))
        assertTrue(request.contains("Sec-WebSocket-Version: 13\r\n"))
        assertTrue("the key has to be sent when there is one", request.contains("X-Api-Key: key123\r\n"))
        assertTrue("the request ends with a blank line", request.endsWith("\r\n\r\n"))
        // The accept value is a SHA-1 of the key and the fixed GUID, which is what makes the
        // handshake a handshake rather than an HTTP request that happens to have headers.
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", KlipperWebSocket.handshakeAccept("dGhlIHNhbXBsZSBub25jZQ=="))
    }
}
