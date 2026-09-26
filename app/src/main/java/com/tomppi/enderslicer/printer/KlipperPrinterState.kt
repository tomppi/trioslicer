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
    /** The last thing that went wrong, cleared by the next successful call. */
    val error: String? = null,
) {
    val isReady: Boolean get() = connected && state == "ready"
    val isHomed: Boolean get() = homedAxes.contains("x") && homedAxes.contains("y") && homedAxes.contains("z")
    val isPrinting: Boolean get() = printState == "printing"
    val isPaused: Boolean get() = printState == "paused"
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
    )
}

/** A numeric field, or null when klippy did not mention it this time. */
private fun JSONObject?.number(name: String): Double? =
    if (this != null && has(name) && !isNull(name)) optDouble(name) else null

private fun JSONArray.toDoubleList(): List<Double> = (0 until length()).map { optDouble(it) }
