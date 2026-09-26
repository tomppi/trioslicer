package com.tomppi.enderslicer.printer

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire format, tested without a socket.
 *
 * Each case here is one that cost a build-install cycle on the phone: the ETX
 * terminator, a message split across two reads, and the object shape of
 * notify_status_update that silently discarded every update.
 */
class KlipperProtocolTest {

    @Test
    fun encodesRequestTerminatedWithEtx() {
        val bytes = KlipperProtocol.encode(7, "info")
        assertEquals(KlipperProtocol.ETX, bytes.last())
        // Parsed, not string-matched: Android's org.json preserves insertion order
        // and the JVM test artifact's does not, so a prefix assertion would be
        // testing the JSON library rather than this code.
        val request = JSONObject(String(bytes.copyOfRange(0, bytes.size - 1)))
        assertEquals(7, request.getInt("id"))
        assertEquals("info", request.getString("method"))
        assertEquals(0, request.getJSONObject("params").length())
    }

    @Test
    fun decodesTwoMessagesArrivingTogether() {
        val buffer = frame("""{"id":1,"result":{}}""") + frame("""{"id":2,"result":{}}""")
        val (messages, rest) = KlipperProtocol.decode(buffer)
        assertEquals(2, messages.size)
        assertEquals(1, messages[0].optInt("id"))
        assertEquals(2, messages[1].optInt("id"))
        assertEquals(0, rest.size)
    }

    @Test
    fun holdsPartialMessageUntilTheRestArrives() {
        val whole = frame("""{"id":3,"result":{"state":"ready"}}""")
        val (first, rest) = KlipperProtocol.decode(whole.copyOfRange(0, 10))
        assertEquals(0, first.size)
        assertEquals(10, rest.size)

        val (messages, leftover) =
            KlipperProtocol.decode(rest + whole.copyOfRange(10, whole.size))
        assertEquals(1, messages.size)
        assertEquals("ready", messages[0].getJSONObject("result").getString("state"))
        assertEquals(0, leftover.size)
    }

    @Test
    fun dropsMalformedFrameAndKeepsTheOnesAfterIt() {
        val buffer = frame("not json") + frame("""{"id":9,"result":{}}""")
        val (messages, _) = KlipperProtocol.decode(buffer)
        assertEquals(1, messages.size)
        assertEquals(9, messages[0].optInt("id"))
    }

    @Test
    fun readsStatusFromTheObjectFormKlippySends() {
        val message = JSONObject(
            """{"method":"notify_status_update",
                "params":{"eventtime":1.5,"status":{"toolhead":{"homed_axes":"xyz"}}}}""",
        )
        val status = KlipperProtocol.statusUpdate(message)
        assertEquals("xyz", status?.getJSONObject("toolhead")?.getString("homed_axes"))
    }

    @Test
    fun ignoresMessagesCarryingNoStatus() {
        assertNull(KlipperProtocol.statusUpdate(JSONObject("""{"id":1,"result":{}}""")))
        assertNull(
            KlipperProtocol.statusUpdate(JSONObject("""{"method":"notify_klippy_ready"}""")),
        )
    }

    @Test
    fun recognisesLifecycleNotifications() {
        assertEquals(
            "notify_klippy_ready",
            KlipperProtocol.lifecycle(JSONObject("""{"method":"notify_klippy_ready"}""")),
        )
        assertNull(KlipperProtocol.lifecycle(JSONObject("""{"method":"notify_status_update"}""")))
    }

    @Test
    fun statusMergeKeepsFieldsKlippyDidNotMention() {
        val before = KlipperPrinterState(
            connected = true,
            extruderTemperature = 24.4,
            extruderTarget = 0.0,
            bedTemperature = 24.3,
            bedTarget = 0.0,
            position = listOf(0.0, 0.0, 0.0),
            homedAxes = "",
        )
        // What a subscription really delivers for a toolhead: one changed field.
        val after = before.withStatus(JSONObject("""{"toolhead":{"homed_axes":"xyz"}}"""))
        assertEquals("xyz", after.homedAxes)
        assertEquals(listOf(0.0, 0.0, 0.0), after.position)
        assertEquals(24.4, after.extruderTemperature!!, 0.001)
    }

    @Test
    fun statusMergeTakesPositionAndTemperaturesWhenSent() {
        val state = KlipperPrinterState(connected = true).withStatus(
            JSONObject(
                """{"toolhead":{"position":[160.0,120.0,0.0,0.0]},
                   "extruder":{"temperature":24.6,"target":200.0}}""",
            ),
        )
        assertEquals(listOf(160.0, 120.0, 0.0, 0.0), state.position)
        assertEquals(24.6, state.extruderTemperature!!, 0.001)
        assertEquals(200.0, state.extruderTarget!!, 0.001)
        assertTrue(!state.isHomed)
    }

    @Test
    fun statusMergeFollowsAPrint() {
        val state = KlipperPrinterState(connected = true).withStatus(
            JSONObject(
                """{"print_stats":{"filename":"benchy.gcode","state":"printing",
                    "print_duration":90.0},
                   "virtual_sdcard":{"progress":0.25}}""",
            ),
        )
        assertEquals("benchy.gcode", state.printFileName)
        assertEquals("printing", state.printState)
        assertEquals(90.0, state.printDurationSeconds!!, 0.001)
        assertEquals(0.25, state.printProgress!!, 0.001)
        assertEquals(true, state.isPrinting)
        assertEquals(false, state.isPaused)
    }

    @Test
    fun statusMergeKeepsThePrintWhenOnlyProgressArrives() {
        val printing = KlipperPrinterState(
            connected = true, printFileName = "benchy.gcode", printState = "printing",
        )
        // What the virtual SD card sends while a print runs: progress, nothing else.
        val after = printing.withStatus(JSONObject("""{"virtual_sdcard":{"progress":0.75}}"""))
        assertEquals("benchy.gcode", after.printFileName)
        assertEquals("printing", after.printState)
        assertEquals(0.75, after.printProgress!!, 0.001)
    }

    @Test
    fun statusMergeReadsTheTimingMarginsKlippyKeeps() {
        // The shape klippy actually keeps: mcu.last_stats is its stats line, split on
        // spaces and parsed. Values here are from a real run on the phone.
        val state = KlipperPrinterState(connected = true).withStatus(
            JSONObject(
                """{"mcu":{"last_stats":{"mcu_awake":0.001,"mcu_task_avg":0.000014,
                    "bytes_write":1223,"bytes_read":4655,"bytes_retransmit":0,
                    "bytes_invalid":0,"srtt":0.004,"rttvar":0.001,"rto":0.025}}}""",
            ),
        )
        val timing = state.timing!!
        assertEquals(0.004, timing.roundTripSeconds!!, 1e-9)
        assertEquals(0.001, timing.jitterSeconds!!, 1e-9)
        assertEquals(0.025, timing.retransmitTimeoutSeconds!!, 1e-9)
        assertEquals(0, timing.retransmittedBytes)
        assertEquals(0.001, timing.mcuAwake!!, 1e-9)
        // 25 ms of patience for a 4 ms round trip: six attempts before a resend.
        assertEquals(6.25, timing.headroom!!, 0.01)
    }

    @Test
    fun statusMergeKeepsTimingWhenAStatsLineHasNotArrived() {
        val known = KlipperPrinterState(
            connected = true, timing = KlipperTiming(roundTripSeconds = 0.004),
        )
        val after = known.withStatus(JSONObject("""{"mcu":{"last_stats":{}}}"""))
        assertEquals(0.004, after.timing!!.roundTripSeconds!!, 1e-9)
    }

    @Test
    fun lookaheadIsTheDifferenceKlippyItselfUses() {
        // toolhead.py: buffer_time = print_time - estimated_print_time
        val state = KlipperPrinterState(connected = true).withStatus(
            JSONObject(
                """{"toolhead":{"print_time":101.5,"estimated_print_time":100.0,"stalls":0}}""",
            ),
        )
        assertEquals(1.5, state.lookaheadSeconds!!, 1e-9)
        // klippy's BUFFER_TIME_LOW is 1.0 s, so 1.5 s is the range it aims for.
        assertTrue(state.lookaheadIsHealthy)
        assertEquals(0, state.printStalls)
    }

    @Test
    fun lookaheadBelowTheLowMarkIsNotHealthy() {
        val state = KlipperPrinterState(connected = true).withStatus(
            JSONObject("""{"toolhead":{"print_time":100.4,"estimated_print_time":100.0}}"""),
        )
        assertEquals(0.4, state.lookaheadSeconds!!, 1e-9)
        assertTrue(!state.lookaheadIsHealthy)
    }

    @Test
    fun lookaheadIsUnknownUntilBothSidesHaveReported() {
        val state = KlipperPrinterState(connected = true).withStatus(
            JSONObject("""{"toolhead":{"print_time":101.5}}"""),
        )
        assertNull(state.lookaheadSeconds)
        // Nothing to judge yet, so nothing to complain about: a missing figure must not
        // read as a starved printer on the screen.
        assertTrue(state.lookaheadIsHealthy)
    }

    @Test
    fun stallsAndTheClocksSurviveAMergeThatMentionsOnlyOne() {
        val printing = KlipperPrinterState(
            connected = true, printTime = 101.5, estimatedPrintTime = 100.0, printStalls = 0,
        )
        val after = printing.withStatus(JSONObject("""{"toolhead":{"stalls":3}}"""))
        assertEquals(3, after.printStalls)
        assertEquals(1.5, after.lookaheadSeconds!!, 1e-9)
    }

    private fun frame(json: String) = json.toByteArray() + byteArrayOf(KlipperProtocol.ETX)
}
