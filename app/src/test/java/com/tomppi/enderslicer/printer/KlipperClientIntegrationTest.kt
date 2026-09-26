package com.tomppi.enderslicer.printer

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
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
    fun aRealReplyMergesIntoWhatTheScreenReads() {
        val client = connect()
        try {
            val snapshot = client.subscribe(*KlipperPrinterRepository.WATCHED)
            val state = KlipperPrinterState(connected = true).withStatus(snapshot)

            // The fields the screen renders, checked against the shapes a real klippy
            // sends rather than against fixtures written here. A fixture agrees with
            // itself; it cannot tell you the API moved.
            assertTrue(
                "toolhead.position should be four numbers, was ${state.position}",
                state.position.size == 4,
            )
            assertTrue(
                "toolhead.homed_axes should be a string",
                state.homedAxes.length <= 3,
            )
            // These two are always present once the config has loaded, whatever state
            // the printer is in.
            assertTrue("print_stats was not merged", snapshot.has("print_stats"))
            assertTrue("toolhead was not merged", snapshot.has("toolhead"))
        } finally {
            client.close()
        }
    }

    @Test
    fun aRealNotificationIsRecognisedAsAStatusUpdate() {
        val client = connect()
        try {
            val arrived = CountDownLatch(1)
            var merged: KlipperPrinterState? = null
            client.onNotification = { message ->
                // The exact path the app uses, over a message klippy sent by itself.
                KlipperProtocol.statusUpdate(message)?.let { status ->
                    merged = KlipperPrinterState(connected = true).withStatus(status)
                    arrived.countDown()
                }
            }
            client.subscribe("toolhead")
            // Skipped rather than failed when nothing arrives, because a printer in
            // shutdown has a stopped clock and klippy only pushes what changes: there is
            // genuinely nothing to send. With a printer running this exercises the path
            // that reads a real update - which is the one that was wrong for a day.
            assumeTrue(
                "no status update in ten seconds: nothing is changing on this klippy",
                arrived.await(10, TimeUnit.SECONDS),
            )
            assertNotNull("the update carried nothing the merge recognises", merged)
        } finally {
            client.close()
        }
    }

    @Test
    fun theTimingFieldsTheScreenReadsAreTheOnesKlippyReports() {
        val client = connect()
        try {
            val timing = KlipperPrinterState(connected = true)
                .withStatus(client.query("mcu")).timing
            assumeTrue("this klippy has not reported a stats line", timing != null)
            // Read by name, every one of them: a rename upstream would leave the timing
            // card quietly empty rather than failing anywhere.
            assertNotNull("mcu.last_stats.srtt", timing!!.roundTripSeconds)
            assertNotNull("mcu.last_stats.rttvar", timing.jitterSeconds)
            assertNotNull("mcu.last_stats.rto", timing.retransmitTimeoutSeconds)
            assertNotNull("mcu.last_stats.bytes_retransmit", timing.retransmittedBytes)
            assertNotNull("mcu.last_stats.bytes_invalid", timing.invalidBytes)
            assertNotNull("mcu.last_stats.mcu_awake", timing.mcuAwake)
            assertNotNull("headroom, which is rto over srtt", timing.headroom)
        } finally {
            client.close()
        }
    }

    @Test
    fun theLookaheadIsComputableFromWhatKlippyReports() {
        val client = connect()
        try {
            val state = KlipperPrinterState(connected = true)
                .withStatus(client.query("toolhead"))
            // The objective's own quantity: where the host has queued to against where
            // the micro-controller has got to, both reported by klippy.
            assertNotNull("toolhead.print_time", state.printTime)
            assertNotNull("toolhead.estimated_print_time", state.estimatedPrintTime)
            assertNotNull("the lookahead between them", state.lookaheadSeconds)
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
