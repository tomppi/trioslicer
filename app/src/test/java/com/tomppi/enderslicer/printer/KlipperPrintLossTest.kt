package com.tomppi.enderslicer.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a connection loss is allowed to say about a print.
 *
 * The two routes are different machines, and the bug this pins is treating them the same:
 * the phone's websocket to the PC's Moonraker closed five times during one print that never
 * stopped, and every close put "The print was interrupted" on the user's phone, appended a
 * history record and reset the state the next snapshot is read against. A remote drop says
 * nothing about the printer; a local one is the print dying, because there the host is the
 * process that was printing.
 */
class KlipperPrintLossTest {
    private val local = KlipperHostChoice(mode = KlipperHostMode.DEVICE)
    private val remote = KlipperHostChoice(mode = KlipperHostMode.PC, host = "100.72.208.101")

    @Test
    fun aLocalDropWhilePrintingIsThePrintDying() {
        assertTrue("the app's own host dies with its socket", KlipperPrintLoss.lostWithTheHost(local))
        assertFalse(
            "and the belief goes with it, so the reconnect cannot report the same loss twice",
            KlipperPrintLoss.beliefAfterDrop(local, wasRunning = true),
        )
        assertEquals(
            "which leaves exactly the drop's own record",
            KlipperPrintLoss.AfterReconnect.Nothing,
            KlipperPrintLoss.afterReconnect(false, "standby", ""),
        )
    }

    @Test
    fun aRemoteDropSaysNothingAtAll() {
        assertFalse(
            "the printer is not the phone's websocket",
            KlipperPrintLoss.lostWithTheHost(remote),
        )
        assertTrue(
            "so the belief that a print is running stands, and the next snapshot is read against it",
            KlipperPrintLoss.beliefAfterDrop(remote, wasRunning = true),
        )
        assertEquals(
            "a print still printing after the reconnect is still nothing to say",
            KlipperPrintLoss.AfterReconnect.Nothing,
            KlipperPrintLoss.afterReconnect(true, "printing", "cube.gcode"),
        )
    }

    @Test
    fun aRemoteHostThatCameBackWithNoPrintLostIt() {
        assertTrue(KlipperPrintLoss.beliefAfterDrop(remote, wasRunning = true))
        assertEquals(
            "standby with no file is a host that restarted, and the print went with it",
            KlipperPrintLoss.AfterReconnect.HostRestarted,
            KlipperPrintLoss.afterReconnect(true, "standby", ""),
        )
        assertEquals(
            "a standby that still names a file is a printer waiting, not a lost print",
            KlipperPrintLoss.AfterReconnect.Nothing,
            KlipperPrintLoss.afterReconnect(true, "standby", "cube.gcode"),
        )
        assertEquals(
            "and nothing is inferred when the app did not believe a print was running",
            KlipperPrintLoss.AfterReconnect.Nothing,
            KlipperPrintLoss.afterReconnect(false, "standby", ""),
        )
    }

    @Test
    fun aRemotePrintThatEndedWhileAwayIsKlippysOwnStory() {
        // complete, cancelled and error are states klippy names for itself. The reconnect
        // says nothing about them, and the ordinary transition is what tells the phone -
        // once, with klippy's own state and message.
        for (state in listOf("complete", "cancelled", "error")) {
            assertEquals(
                "the reconnect does not invent an interruption for $state",
                KlipperPrintLoss.AfterReconnect.Nothing,
                KlipperPrintLoss.afterReconnect(true, state, "cube.gcode"),
            )
            assertTrue(
                "and the ordinary transition is what reports it: $state",
                KlipperNotifications.endedBetween("printing", state),
            )
        }
    }

    @Test
    fun thePrintThatNeverStoppedIsTheCaseThisExistsFor() {
        // Five drops, one print, from the user's own moonraker log: every one of them is
        // now silence, and the print stays the printer's business the whole way.
        repeat(5) {
            assertFalse(KlipperPrintLoss.lostWithTheHost(remote))
            assertEquals(
                KlipperPrintLoss.AfterReconnect.Nothing,
                KlipperPrintLoss.afterReconnect(true, "printing", "cube.gcode"),
            )
            assertTrue(KlipperPrintLoss.beliefAfterDrop(remote, wasRunning = true))
        }
    }
}
