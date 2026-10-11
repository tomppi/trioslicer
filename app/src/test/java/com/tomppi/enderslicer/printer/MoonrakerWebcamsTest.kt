package com.tomppi.enderslicer.printer

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The camera list, and the difference between "none" and "not read".
 *
 * The bug this pins: the list was fetched once per session, before the connection was made,
 * and every failure - a timeout, a refused connection, a Moonraker still coming up, a tailnet
 * not up yet - came back as an empty list, which is the same value as a host that publishes no
 * cameras. The card then said "No camera is configured on this host." for the rest of the
 * session, from a request that never arrived.
 */
class MoonrakerWebcamsTest {

    @Test
    fun aHostThatAnswersWithNoCamerasSaysSo() = withServer(
        body = """{"result":{"webcams":[]}}""",
        status = 200,
    ) { webcams ->
        assertEquals("an answer, and the answer is none", emptyList<KlipperWebcam>(), webcams.list())
    }

    @Test
    fun aHostThatCannotAnswerIsNotAHostWithoutCameras() = withServer(
        body = "boom",
        status = 500,
    ) { webcams ->
        assertNull("500 is not an answer about the host's configuration", webcams.list())
    }

    @Test
    fun aBodyThatIsNotTheListIsNotAnAnswerEither() = withServer(
        body = """{"error":"not found"}""",
        status = 200,
    ) { webcams ->
        assertNull("there is no result to read", webcams.list())
    }

    @Test
    fun aCameraThatIsThereIsReadAndMadeAbsolute() = withServer(
        body = """
            {"result":{"webcams":[
              {"name":"printer","enabled":true,"stream_url":"/webcam/stream",
               "snapshot_url":"/webcam/snapshot"},
              {"name":"off","enabled":false,"snapshot_url":"/webcam/snapshot"}
            ]}}
        """.trimIndent(),
        status = 200,
    ) { webcams ->
        val listed = webcams.list()
        assertEquals("one camera, the disabled one left out", 1, listed?.size)
        val camera = listed!!.first()
        assertEquals("printer", camera.name)
        // A path belongs to the web server on the host itself, which is where a camera is
        // published - Moonraker hands out "/webcam/snapshot" and the host answers it on 80,
        // not on Moonraker's own port.
        assertEquals("http://127.0.0.1/webcam/stream", camera.streamUrl)
        assertEquals("http://127.0.0.1/webcam/snapshot", camera.snapshotUrl)
    }

    private var port = 0

    private fun withServer(
        body: String,
        status: Int,
        check: (MoonrakerWebcams) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/server/webcams/list") { exchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        port = server.address.port
        try {
            check(MoonrakerWebcams(host = "127.0.0.1", port = port))
        } finally {
            server.stop(0)
        }
    }
}
