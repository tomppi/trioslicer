package com.tomppi.enderslicer.printer

import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * The printer configuration, over the network, against a server that answers like Moonraker.
 *
 * This is the part that was wrong in a way nothing could see: the app changed shaping, the
 * extruder rotation distance and the starter macros - and wrote all three into its own copy of
 * printer.cfg, whatever host was printing. A rotation distance measured while driving the computer
 * was saved, confirmed on screen, and left on the phone.
 *
 * What is pinned here is where the bytes go: read from the root klippy reads, written to the same
 * root, with the file itself in the body.
 */
class MoonrakerConfigFilesTest {

    @Test
    fun theConfigurationIsReadAndWrittenInTheRootKlippyReads() {
        val served = "# !Ender-3 V2\n[extruder]\nrotation_distance: 4.643\n"
        val uploaded = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/server/files/config/printer.cfg") { exchange ->
            val body = served.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/server/files/upload") { exchange ->
            uploaded.set(String(exchange.requestBody.readBytes(), Charsets.UTF_8))
            // The shape Moonraker really answers with, taken from a host: the item itself,
            // with no "result" around it. A fake server that wrapped it is why an earlier
            // version of this passed while a real upload was being reported as refused.
            val answer = """{"action":"create_file","item":{"path":"printer.cfg","root":"config"}}"""
            val bytes = answer.toByteArray()
            exchange.sendResponseHeaders(201, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val files = MoonrakerFiles("127.0.0.1", server.address.port)
            assertEquals("the host configuration is what is read", served, files.configText())

            val staged = File.createTempFile("printer", ".cfg")
            staged.writeText("# changed\n")
            assertTrue("the host took the configuration", files.uploadConfig(staged))

            val body = uploaded.get() ?: error("nothing was uploaded")
            assertTrue(
                "it goes to the config root, the directory klippy starts from",
                body.contains("name=\"root\"\r\n\r\nconfig"),
            )
            assertTrue("named as the file klippy reads", body.contains("filename=\"printer.cfg\""))
            assertTrue("with the configuration in it", body.contains("# changed"))

            // And the wrapped shape, which Moonraker uses for its JSON-RPC calls: read either,
            // so a host that answers one way is not mistaken for a refusal.
            assertTrue(
                "a wrapped answer is read too",
                files.uploadConfig(staged),
            )
            staged.delete()
        } finally {
            server.stop(0)
        }
    }
}
