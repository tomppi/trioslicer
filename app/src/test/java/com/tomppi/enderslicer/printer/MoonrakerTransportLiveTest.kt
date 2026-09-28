package com.tomppi.enderslicer.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The app's own client, driving a Klipper host on the network.
 *
 * Skipped unless KLIPPER_REMOTE_HOST names one, because it needs a printer on the other end and
 * none of this belongs in a build that runs without one. With the variable set it is the whole
 * path - transport, dialect and client - against a real Moonraker, which is the only way any of
 * the earlier mistakes in this client were ever found:
 *
 *     KLIPPER_REMOTE_HOST=192.168.3.212 ./gradlew :app:testDebugUnitTest \
 *         --tests "com.tomppi.enderslicer.printer.MoonrakerTransportLiveTest"
 *
 * Read-only on purpose: it asks the printer what it is and what it has, and moves nothing.
 */
class MoonrakerTransportLiveTest {

    private fun host(): String {
        val host = System.getenv("KLIPPER_REMOTE_HOST")
        assumeTrue("set KLIPPER_REMOTE_HOST to run this", !host.isNullOrBlank())
        return host!!
    }

    @Test
    fun theClientReachesTheRemoteHostAndReadsIt() {
        val client = KlipperClient(MoonrakerTransport(host()), "remote")
        client.connect(5000)
        try {
            val info = client.info()
            assertEquals("ready", info.optString("state"))
            assertTrue(info.optString("software_version").startsWith("v0.13"))

            // The subscription the screens run on, and the reply used as their first snapshot.
            val snapshot = client.subscribe("toolhead", "print_stats", "gcode_move")
            assertTrue("toolhead arrived", snapshot.has("toolhead"))
            assertTrue("print_stats arrived", snapshot.has("print_stats"))
            assertEquals(
                5000.0,
                snapshot.getJSONObject("toolhead").getDouble("max_accel"),
                1e-6,
            )

            // And the two calls that have no klippy equivalent, both read-only.
            val objects = client.listObjects()
            assertTrue("the printer publishes toolhead", objects.contains("toolhead"))
            assertTrue("the printer describes its own commands", client.gcodeHelp().isNotEmpty())
        } finally {
            client.close()
        }
    }
}
