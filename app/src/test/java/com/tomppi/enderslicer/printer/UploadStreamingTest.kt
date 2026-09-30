package com.tomppi.enderslicer.printer

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upload's multipart, split so the file part can be streamed.
 *
 * upload() sends the head and the tail with the file written between them, straight from disk,
 * because reading a large slice into the heap and copying it twice more is how an upload ran out
 * of memory.
 */
class UploadStreamingTest {

    @Test
    fun headPlusContentPlusTailIsTheWholeBody() {
        val content = ByteArray(4096) { (it % 251).toByte() }
        val boundary = "----TrioSlicerTest"
        val whole = MoonrakerFiles.uploadBody(boundary, "benchy.gcode", content, "gcodes")
        val split = MoonrakerFiles.uploadHead(boundary, "benchy.gcode", "gcodes") +
            content +
            MoonrakerFiles.uploadTail(boundary)
        assertArrayEquals("the streamed form must be byte for byte the same", whole, split)
    }

    @Test
    fun theChunkIsLargeEnoughToBeWorthStreaming() {
        assertTrue(
            "a small chunk would make the streaming pointless",
            MoonrakerFiles.UPLOAD_CHUNK_BYTES >= 64 * 1024,
        )
    }

    /**
     * What actually reaches a host, for a file that spans several chunks.
     *
     * The equivalence test above proves the pieces compose to the right bytes; this proves the
     * socket receives them, and that the body is sent under a declared Content-Length rather
     * than chunked - the difference the fixed-length streaming mode exists to make.
     */
    @Test
    fun aFileSpanningSeveralChunksArrivesWholeUnderAFixedLength() {
        val received = AtomicReference<ByteArray>()
        val declaredLength = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/server/files/upload") { exchange ->
            declaredLength.set(exchange.requestHeaders.getFirst("Content-Length"))
            received.set(exchange.requestBody.readBytes())
            val answer = """{"action":"create_file","item":{"path":"big.gcode","root":"gcodes"}}"""
            val bytes = answer.toByteArray()
            exchange.sendResponseHeaders(201, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val payload = ByteArray(300 * 1024) { (it % 251).toByte() }
            val source = File.createTempFile("big", ".gcode").apply { writeBytes(payload) }
            val files = MoonrakerFiles("127.0.0.1", server.address.port)
            assertTrue("the host took it", files.upload(source, "big.gcode"))
            val body = received.get() ?: error("nothing arrived")
            assertEquals("a declared length, not a chunked body", body.size.toString(), declaredLength.get())
            assertTrue("the whole file is inside the body", contains(body, payload))
        } finally {
            server.stop(0)
        }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (start in 0..(haystack.size - needle.size)) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return true
        }
        return false
    }
}
