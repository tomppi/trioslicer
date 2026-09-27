package com.tomppi.enderslicer.printer

/** One second's readings, which is what the temperature chart is drawn from. */
data class KlipperTemperatureSample(
    val atMillis: Long,
    /** By object name, so a series is one sensor's line on the chart. */
    val temperatures: Map<String, Double>,
)

/**
 * The last few minutes of temperatures, sampled rather than streamed.
 *
 * klippy pushes a reading every time one changes, which is many times a second, and a
 * chart of that is a chart of the network rather than of the heater. This keeps one
 * sample a second for five minutes: enough to see a hotend settle at its target, a bed
 * overshoot, or a heater that is not keeping up, and bounded so that a print running
 * overnight cannot fill the phone's memory with the history of its own hotend.
 */
internal class KlipperTemperatureLog(
    private val windowMillis: Long = WINDOW_MS,
    private val intervalMillis: Long = INTERVAL_MS,
) {
    private val samples = ArrayDeque<KlipperTemperatureSample>()
    private var lastSample = 0L

    /**
     * Take a reading if one is due, and answer with the window.
     *
     * Null when nothing was taken, so that a caller does not hand the screen a new list
     * ten times a second for a chart that only changed once.
     */
    fun record(now: Long, heaters: List<KlipperHeater>): List<KlipperTemperatureSample>? {
        if (now - lastSample < intervalMillis) return null
        val readings = heaters.mapNotNull { heater ->
            heater.temperature?.let { heater.name to it }
        }.toMap()
        if (readings.isEmpty()) return null
        lastSample = now
        samples.addLast(KlipperTemperatureSample(now, readings))
        while (samples.isNotEmpty() && now - samples.first().atMillis > windowMillis) {
            samples.removeFirst()
        }
        return snapshot()
    }

    fun snapshot(): List<KlipperTemperatureSample> = samples.toList()

    fun clear() {
        samples.clear()
        lastSample = 0L
    }

    companion object {
        /** How much history the chart shows. */
        const val WINDOW_MS = 5 * 60_000L

        /** How often a reading is kept. */
        const val INTERVAL_MS = 1000L
    }
}
