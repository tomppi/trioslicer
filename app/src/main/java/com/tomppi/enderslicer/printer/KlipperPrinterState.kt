package com.tomppi.enderslicer.printer

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
