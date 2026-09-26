package com.tomppi.enderslicer.printer

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The app's client, run against a real klippy.
 *
 * Every mistake this client has made was a protocol detail - ETX framing, a method name
 * that belongs to the HTTP interface rather than the socket, the shape of a status
 * update - and each was found by running it against something. This runs it against the
 * real thing, using the same client and the same protocol code the app ships, with only
 * the socket replaced.
 *
 * Skipped where no klippy is listening, so it neither fails nor pretends elsewhere. The
 * path can be pointed at another one with KLIPPY_SOCKET.
 */
class KlipperClientIntegrationTest {
    private val socket = File(
        System.getenv("KLIPPY_SOCKET") ?: "/home/tomppi/printer_data/comms/klippy.sock",
    )

    @Before
    fun requireKlippy() {
        assumeTrue("no klippy socket at $socket", socket.exists())
    }

    private fun connect(): KlipperClient {
        val client = KlipperClient(
            JvmUnixSocketTransport(socket.absolutePath), socket.absolutePath,
        )
        client.connect()
        return client
    }

    @Test
    fun asksARealKlippyWhatItIs() {
        val client = connect()
        try {
            val info = client.info()
            // A real answer has a state in it, whatever that state is: stopping at the
            // reply being JSON would pass on an error object too.
            assertTrue("no state in $info", info.has("state"))
            assertTrue("no state_message in $info", info.has("state_message"))
        } finally {
            client.close()
        }
    }

    @Test
    fun theSubscribeReplyIsTheFirstSnapshot() {
        val client = connect()
        try {
            // The whole reason the repository subscribes before it queries: this reply
            // is the snapshot, and a separate query beforehand would leave a window in
            // which a change is neither seen nor pushed.
            val snapshot = client.subscribe("toolhead", "print_stats")
            assertTrue("no toolhead in $snapshot", snapshot.has("toolhead"))
            assertTrue("no print_stats in $snapshot", snapshot.has("print_stats"))
        } finally {
            client.close()
        }
    }

    @Test
    fun aQueryAnswersWithWhatWasAsked() {
        val client = connect()
        try {
            val status = client.query("toolhead")
            assertTrue("no toolhead in $status", status.has("toolhead"))
        } finally {
            client.close()
        }
    }

    @Test
    fun anUnknownMethodComesBackAsAnError() {
        val client = connect()
        try {
            val thrown = runCatching { client.call("no/such/endpoint") }.exceptionOrNull()
            // printer.info is the HTTP path Moonraker translates; on this socket it is
            // answered with an error, which is how that mistake announced itself.
            assertTrue(
                "expected a KlipperError, got $thrown",
                thrown is KlipperClient.KlipperError,
            )
        } finally {
            client.close()
        }
    }
}
