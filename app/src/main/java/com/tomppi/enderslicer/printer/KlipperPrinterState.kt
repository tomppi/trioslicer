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
    /** The last thing that went wrong, cleared by the next successful call. */
    val error: String? = null,
) {
    val isReady: Boolean get() = connected && state == "ready"
    val isHomed: Boolean get() = homedAxes.contains("x") && homedAxes.contains("y") && homedAxes.contains("z")
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
    return copy(
        extruderTemperature = extruder.temperature("temperature") ?: extruderTemperature,
        extruderTarget = extruder.temperature("target") ?: extruderTarget,
        bedTemperature = bed.temperature("temperature") ?: bedTemperature,
        bedTarget = bed.temperature("target") ?: bedTarget,
        position = toolhead?.optJSONArray("position")?.toDoubleList().orEmpty().ifEmpty { position },
        homedAxes = toolhead?.optString("homed_axes").orEmpty().ifEmpty { homedAxes },
    )
}

/** A temperature field, or null when klippy did not mention it this time. */
private fun JSONObject?.temperature(name: String): Double? =
    if (this != null && has(name) && !isNull(name)) optDouble(name) else null

private fun JSONArray.toDoubleList(): List<Double> = (0 until length()).map { optDouble(it) }
