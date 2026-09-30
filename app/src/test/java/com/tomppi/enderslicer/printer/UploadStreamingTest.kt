package com.tomppi.enderslicer.printer

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upload's multipart, and what the host actually receives.
 *
 * Two tests in an earlier version of this file could not fail: one compared uploadBody with the
 * three pieces it is defined as, and the other asserted a constant. They are gone. What is left
 * pins the bytes against a hand-written expectation, and the wire against a fake Moonraker.
 *
 * What is NOT here, and cannot honestly be: a test that the streaming change happened. The old
 * implementation sent the same bytes; the difference was in the heap, which a JVM test cannot
 * observe without depending on its own heap size. The change is reviewed, not pinned.
 */
class UploadStreamingTest {

    @Test
    fun theBodyIsTheMultipartMoonrakerReads() {
        // Written out by hand rather than composed from the functions under test.
        val boundary = "----TrioSlicerTest"
        val expected = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"root\"\r\n\r\n" +
                "gcodes\r\n" +
                "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"file\"; filename=\"benchy.gcode\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n" +
                "G28\n" +
                "\r\n--$boundary--\r\n"
            ).toByteArray()
        val actual = MoonrakerFiles.uploadHead(boundary, "benchy.gcode", "gcodes") +
            "G28\n".toByteArray() +
            MoonrakerFiles.uploadTail(boundary)
        assertArrayEquals("the wire format Moonraker parses", expected, actual)
    }

    @Test
    fun aFileSpanningSeveralChunksArrivesWholeUnderAFixedLength() {
        val received = AtomicLong()
        val declaredLength = AtomicLong(-1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/server/files/upload") { exchange ->
            declaredLength.set(exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull() ?: -1L)
            // Counted, not buffered: this test is about what reaches the socket.
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = exchange.requestBody.read(buffer)
                if (read <= 0) break
                total += read
            }
            received.set(total)
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
            assertEquals("a declared length, not a chunked body", received.get(), declaredLength.get())
            assertTrue("and it is more than the file alone", received.get() > payload.size)
        } finally {
            server.stop(0)
        }
    }

    /**
     * A file far larger than the heap a test is given, streamed from a sparse file.
     *
     * This is the closest a test can come to the change that mattered: reading it into memory
     * with readBytes() and copying it twice more is what the fix removed.
     */
    @Test
    fun aFileLargerThanTheHeapIsStreamedNotHeld() {
        val received = AtomicLong()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/server/files/upload") { exchange ->
            val buffer = ByteArray(256 * 1024)
            var total = 0L
            while (true) {
                val read = exchange.requestBody.read(buffer)
                if (read <= 0) break
                total += read
            }
            received.set(total)
            val answer = """{"action":"create_file","item":{"path":"huge.gcode","root":"gcodes"}}"""
            val bytes = answer.toByteArray()
            exchange.sendResponseHeaders(201, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val size = 512L * 1024 * 1024
            val source = File.createTempFile("huge", ".gcode")
            RandomAccessFile(source, "rw").use { it.setLength(size) }
            val files = MoonrakerFiles("127.0.0.1", server.address.port)
            assertTrue("a file larger than the heap was uploaded", files.upload(source, "huge.gcode"))
            assertTrue("and the host received it", received.get() >= size)
        } finally {
            server.stop(0)
        }
    }
}
