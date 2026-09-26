package com.tomppi.enderslicer.printer

import org.json.JSONArray
import org.json.JSONObject

/** What the printer front end shows about the machine this device is driving. */
data class KlipperPrinterState(
    /** True while the app has a working connection to klippy's API. */
    val connected: Boolean = false,
    /** klippy's own state: startup, ready, shutdown, or unknown before it answers. */
    val state: String = "unknown",
    /** klippy's explanation when it is not ready - the text a user needs to act on. */
    val stateMessage: String = "",
    val extruderTemperature: Double? = null,
    val extruderTarget: Double? = null,
    val bedTemperature: Double? = null,
    val bedTarget: Double? = null,
    val position: List<Double> = emptyList(),
    val homedAxes: String = "",
    /** The file the printer is working on, as the virtual SD card names it. */
    val printFileName: String? = null,
    /** printing, paused, complete, cancelled or error - klippy's own words. */
    val printState: String? = null,
    /** 0..1 through the file, from the virtual SD card's own position in it. */
    val printProgress: Double? = null,
    val printDurationSeconds: Double? = null,
    /** The link's timing margins, once klippy has reported a stats line. */
    val timing: KlipperTiming? = null,
    /** Where the host has queued moves to, in print time. */
    val printTime: Double? = null,
    /** Where the micro-controller has got to, on the same clock. */
    val estimatedPrintTime: Double? = null,
    /** How many times the micro-controller ran out of moves to execute. */
    val printStalls: Int? = null,
    /** The last thing that went wrong, cleared by the next successful call. */
    val error: String? = null,
    /**
     * What the host last said, when it is not reachable.
     *
     * klippy explains itself in its log and then may exit, and an exited host has no
     * API to ask: without this the screen can only say "not reachable", which is the
     * one thing the user already knows.
     */
    val hostLogTail: String? = null,
) {
    val isReady: Boolean get() = connected && state == "ready"
    val isHomed: Boolean get() = homedAxes.contains("x") && homedAxes.contains("y") && homedAxes.contains("z")
    val isPrinting: Boolean get() = printState == "printing"
    val isPaused: Boolean get() = printState == "paused"

    /**
     * How far ahead of the micro-controller the host has queued moves.
     *
     * This is klippy's own buffer_time, the same expression toolhead.py uses to decide
     * whether it is starving or over-buffered: print_time is where the host has
     * queued to, estimated_print_time is where the micro-controller has got to. Both
     * come from the toolhead object, so this is the figure klippy itself acts on
     * rather than an approximation of it.
     *
     * klippy aims to keep BUFFER_TIME_LOW (1s) to BUFFER_TIME_HIGH (2s) queued: below
     * the low mark it enters its priming state, above the high mark it pauses. A
     * printer being driven faster than it can be fed shows this heading for zero, and
     * [printStalls] counting up is the same failure after the fact.
     */
    val lookaheadSeconds: Double?
        get() = printTime?.let { queued -> estimatedPrintTime?.let { done -> queued - done } }

    /** True while the host is keeping up the way klippy wants it to. */
    val lookaheadIsHealthy: Boolean
        get() = lookaheadSeconds?.let { it >= BUFFER_TIME_LOW } ?: true

    companion object {
        /** toolhead.py's BUFFER_TIME_LOW: below this, klippy is priming. */
        const val BUFFER_TIME_LOW = 1.0

        /** toolhead.py's BUFFER_TIME_HIGH: above this, klippy pauses to let the MCU catch up. */
        const val BUFFER_TIME_HIGH = 2.0
    }
}

/**
 * The host-to-micro-controller timing margins, as klippy measures them.
 *
 * These are the numbers the feasibility question turns on, and klippy already keeps
 * them: mcu.last_stats is the serial and clock-sync half of the stats line it writes
 * to its log. srtt is the smoothed round trip, rttvar how much it moves, and rto the
 * timeout at which a message is considered lost - so rttvar against rto says how much
 * room there is before the link starts retransmitting, and srtt against rto says how
 * much there is on average.
 *
 * mcu_awake is the fraction of each micro-controller period spent doing work: the
 * closer that gets to 1, the closer the board is to not keeping up.
 */
data class KlipperTiming(
    val roundTripSeconds: Double? = null,
    val jitterSeconds: Double? = null,
    val retransmitTimeoutSeconds: Double? = null,
    val retransmittedBytes: Int? = null,
    val invalidBytes: Int? = null,
    val mcuAwake: Double? = null,
    val mcuTaskAverageSeconds: Double? = null,
) {
    /** How many times the round trip fits inside the timeout before a resend. */
    val headroom: Double?
        get() = roundTripSeconds?.takeIf { it > 0.0 }
            ?.let { rtt -> retransmitTimeoutSeconds?.div(rtt) }
}

/**
 * Merge one status payload into this state.
 *
 * klippy sends only what changed - a toolhead update during a print carries an
 * estimated print time and nothing else - so an absent field means "unchanged" and
 * must keep its value. Treating absent as empty is the difference between a position
 * that follows the machine and one that snaps back to zero every quarter second.
 */
internal fun KlipperPrinterState.withStatus(status: JSONObject): KlipperPrinterState {
    val extruder = status.optJSONObject("extruder")
    val bed = status.optJSONObject("heater_bed")
    val toolhead = status.optJSONObject("toolhead")
    val stats = status.optJSONObject("print_stats")
    val sdcard = status.optJSONObject("virtual_sdcard")
    return copy(
        extruderTemperature = extruder.number("temperature") ?: extruderTemperature,
        extruderTarget = extruder.number("target") ?: extruderTarget,
        bedTemperature = bed.number("temperature") ?: bedTemperature,
        bedTarget = bed.number("target") ?: bedTarget,
        position = toolhead?.optJSONArray("position")?.toDoubleList().orEmpty().ifEmpty { position },
        homedAxes = toolhead?.optString("homed_axes").orEmpty().ifEmpty { homedAxes },
        printFileName = stats?.optString("filename").orEmpty().ifEmpty { printFileName },
        printState = stats?.optString("state").orEmpty().ifEmpty { printState },
        printDurationSeconds = stats.number("print_duration") ?: printDurationSeconds,
        printProgress = sdcard.number("progress") ?: printProgress,
        timing = status.optJSONObject("mcu")?.optJSONObject("last_stats")?.toTiming() ?: timing,
        printTime = toolhead.number("print_time") ?: printTime,
        estimatedPrintTime = toolhead.number("estimated_print_time") ?: estimatedPrintTime,
        printStalls = toolhead.int("stalls") ?: printStalls,
    )
}

/**
 * One stats line, as a timing object.
 *
 * klippy's own field names, so a reader who has seen its log recognises these.
 */
private fun JSONObject.toTiming(): KlipperTiming? {
    if (length() == 0) return null
    return KlipperTiming(
        roundTripSeconds = number("srtt"),
        jitterSeconds = number("rttvar"),
        retransmitTimeoutSeconds = number("rto"),
        retransmittedBytes = int("bytes_retransmit"),
        invalidBytes = int("bytes_invalid"),
        mcuAwake = number("mcu_awake"),
        mcuTaskAverageSeconds = number("mcu_task_avg"),
    )
}

private fun JSONObject?.int(name: String): Int? =
    if (this != null && has(name) && !isNull(name)) optInt(name) else null

/** A numeric field, or null when klippy did not mention it this time. */
private fun JSONObject?.number(name: String): Double? =
    if (this != null && has(name) && !isNull(name)) optDouble(name) else null

private fun JSONArray.toDoubleList(): List<Double> = (0 until length()).map { optDouble(it) }
