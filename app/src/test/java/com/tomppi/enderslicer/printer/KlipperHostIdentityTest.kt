package com.tomppi.enderslicer.printer

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One app, two Klippers, one state object - and which host that state describes.
 *
 * The user tapped the Device tab and read the PC's cancelled print under the phone's name:
 * the repository holds a single state flow whichever host is chosen, reconnect() cleared
 * only "connected" and "error", and every field that came from the host just left survived
 * into the connection to the next one. These pin the two rules that stop it - a state that
 * describes no printer while a connection is being made, and a generation that refuses
 * anything from a connection that is no longer current.
 */
class KlipperHostIdentityTest {
    @Test
    fun aHostChangeStartsFromAStateThatDescribesNoPrinter() {
        val cleared = pcPrintOnScreen().withoutHost(device)

        assertFalse(cleared.connected)
        assertNull("no print", cleared.printState)
        assertNull(cleared.printFileName)
        assertNull(cleared.printProgress)
        assertNull(cleared.printDurationSeconds)
        assertNull("no temperatures", cleared.extruderTemperature)
        assertNull(cleared.extruderTarget)
        assertNull(cleared.bedTemperature)
        assertTrue("no position", cleared.position.isEmpty())
        assertTrue("no object tree", cleared.objects.isEmpty())
        assertTrue("no published object list", cleared.published.isEmpty())
        assertTrue(
            "no cameras from the other host, and none claimed either",
            cleared.webcams.isNullOrEmpty(),
        )
        assertEquals(
            "and nothing the Machine tab prints about the other host",
            KlipperHost(),
            cleared.host,
        )
        assertTrue(
            "which is every name, path, CPU and process id it had",
            cleared.host.hostname.isBlank() && cleared.host.cpuInfo.isBlank() &&
                cleared.host.pythonPath.isBlank() && cleared.host.configFile.isBlank() &&
                cleared.host.logFile.isBlank() && cleared.host.softwareVersion.isBlank() &&
                cleared.host.processId == 0,
        )
        assertNull("and no host identity carried over", cleared.remoteHost)
        assertNull(cleared.error)
        assertEquals("connecting", cleared.state)
        assertEquals("this device", cleared.hostLabel)
        assertTrue(
            "with the host being connected to said out loud",
            cleared.stateMessage.contains("this device"),
        )
    }

    /**
     * The other direction, which is the one the user actually hit: the phone's host is not
     * running, and the card under the Device tab must not describe the computer.
     */
    @Test
    fun connectingToTheComputerNamesTheComputerAndNotThePhone() {
        val pc = KlipperHostChoice(mode = KlipperHostMode.PC, host = "100.64.13.7")
        val cleared = pcPrintOnScreen().withoutHost(pc)

        assertEquals("the choice's host, so the route's own affordances stay right", "100.64.13.7", cleared.remoteHost)
        assertEquals("and the identity tag the card reads", "100.64.13.7", cleared.hostLabel)
        assertTrue(cleared.stateMessage.contains("100.64.13.7"))
        assertNull("while nothing of the print it had is on screen", cleared.printState)
    }

    @Test
    fun aMessageFromTheConnectionThatWasJustLeftIsRefused() {
        val connections = KlipperConnectionGeneration()

        val first = connections.next()
        assertTrue("the connection that just started is the current one", connections.isCurrent(first))

        val second = connections.next()
        assertFalse("asking for a new connection retires the old one", connections.isCurrent(first))
        assertTrue(connections.isCurrent(second))
    }

    /**
     * The switch itself is not a print loss: nothing was interrupted, the app simply stopped
     * watching one host and started watching the other. Both halves of that are pinned here -
     * the state the screens render, and the one thing a loss would have written.
     */
    @Test
    fun aSwitchWhileTheOtherHostWasPrintingLeavesNoPrintAndNoRecord() {
        val cleared = pcPrintOnScreen().withoutHost(device)
        assertNull("nothing on screen names a print", cleared.printState)

        val viewModel = repositorySource()
        val reconnect = viewModel.substringAfter("fun reconnect()").substringBefore("/**")
        assertTrue(
            "and the switch clears the state through the one function that describes no host",
            reconnect.contains("withoutHost(hostChoice.load())"),
        )
        assertFalse(
            "while never treating the host change as a lost print",
            reconnect.contains("recordPrintLostWithTheHost"),
        )
        assertTrue(
            "and the print the old host was running is forgotten with it, or the next " +
                "failure would report that print as interrupted",
            reconnect.contains("lastPrintState = null"),
        )
    }

    /** The wiring that makes the rules above the ones the app actually runs. */
    @Test
    fun theRepositoryConsultsTheRulesWhereItMatters() {
        val source = repositorySource()

        val watch = source.substringAfter("private suspend fun watch()").substringBefore("private fun recordPrintLostWithTheHost")
        assertTrue(
            "a drop consults the route before it calls a print lost",
            watch.contains("KlipperPrintLoss.lostWithTheHost(hostChoice.load())"),
        )
        assertTrue(
            "and a preference it cannot read is read as no loss, not as one",
            watch.contains("getOrDefault(false)"),
        )

        val follow = source.substringAfter("private suspend fun follow()").substringBefore("private fun readHostLogTail")
        assertTrue(
            "a connection starts from a state that describes no printer",
            follow.contains("_state.update { it.withoutHost(choice) }"),
        )
        assertTrue(
            "and takes a generation the writes are checked against",
            follow.contains("val mine = connection.next()"),
        )
        assertTrue(
            "with pushes from a retired connection dropped",
            follow.contains("if (connection.isCurrent(mine)) applyNotification(message)"),
        )
        assertTrue(
            "the first snapshot settles a print left running",
            follow.contains("KlipperPrintLoss.afterReconnect("),
        )
        assertTrue(
            "through the ordinary transition, notification included",
            follow.contains("reportPrintEnd(previous, next)") && follow.contains("recordPrintIfItEnded()"),
        )
        assertTrue(
            "and every state write in the connection is guarded",
            source.contains("private inline fun updateIfCurrent(") &&
                follow.contains("updateIfCurrent(mine)"),
        )
    }

    /**
     * The state the user was looking at when he reported it: the PC's cancelled print, its
     * numbers and its identity, on screen while the Device tab is the one selected.
     */
    private fun pcPrintOnScreen(): KlipperPrinterState = KlipperPrinterState(
        connected = true,
        state = "ready",
        stateMessage = "",
        extruderTemperature = 214.5,
        extruderTarget = 215.0,
        bedTemperature = 59.8,
        bedTarget = 60.0,
        position = listOf(12.0, 34.0, 5.0),
        printFileName = "cube.gcode",
        printState = "cancelled",
        remoteHost = "100.64.13.7",
        hostLabel = "100.64.13.7",
        webcams = listOf(
            KlipperWebcam(
                name = "printer",
                streamUrl = "http://host/webcam?stream",
                snapshotUrl = "http://host/webcam",
            ),
        ),
        printProgress = 0.42,
        printDurationSeconds = 613.0,
        objects = mapOf("print_stats" to JSONObject().put("state", "cancelled")),
        published = listOf("print_stats", "toolhead"),
    )

    private fun repositorySource(): String {
        val candidates = listOf(
            File("src/main/java/com/tomppi/enderslicer/printer/KlipperPrinterRepository.kt"),
            File("app/src/main/java/com/tomppi/enderslicer/printer/KlipperPrinterRepository.kt"),
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Unable to locate KlipperPrinterRepository.kt from " + File(".").absolutePath)
    }

    private companion object {
        val device = KlipperHostChoice(mode = KlipperHostMode.DEVICE)
    }
}
