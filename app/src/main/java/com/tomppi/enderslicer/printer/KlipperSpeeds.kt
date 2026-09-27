package com.tomppi.enderslicer.printer

import org.json.JSONObject

/**
 * The speeds in play, in the units each of them is actually in.
 *
 * Three different numbers get called "the speed", and the app showed only the first:
 *
 *  - what the file asks for, which is the feedrate of the last G-code move, in mm/s
 *    (klippy publishes it as gcode_move.speed);
 *  - what M220 makes of it - klippy's speed_factor is a ratio, 1.0 being the file's own
 *    speed, so the two multiplied are what the printer will actually try to do;
 *  - what the toolhead is doing at this instant, in mm/s, which is motion_report's
 *    live_velocity - the measured one, and the only one of the three that is a fact
 *    rather than a request.
 *
 * The limits are beside them because they are what makes the difference: a printer at 200%
 * does not go twice as fast once max_velocity is reached, and acceleration is what governs
 * every move too short to reach it.
 */
data class KlipperSpeeds(
    /** The last commanded feedrate, in mm/s, before the override. */
    val askedFor: Double?,
    /** M220's factor: 1.0 means the speed the file asked for. */
    val override: Double?,
    /** The toolhead's measured speed, in mm/s, at the moment of the reading. */
    val live: Double?,
    /** The extruder's measured speed, in mm/s of filament. */
    val filament: Double?,
    val maxVelocity: Double?,
    val maxAccel: Double?,
    val cornerVelocity: Double?,
) {
    /** What the file's feedrate becomes with the override applied, in mm/s. */
    val effective: Double? get() = askedFor?.let { it * (override ?: 1.0) }

    /** The same in mm per minute, which is the unit feedrates are written in. */
    val effectivePerMinute: Double? get() = effective?.times(60.0)

    /**
     * True when the override asks for more than the machine will give.
     *
     * Not an error: klippy clamps the move and the print carries on. It is the answer to
     * "why is 200% no faster", which is otherwise invisible.
     */
    val clamped: Boolean
        get() = effective?.let { asked -> maxVelocity?.let { asked > it + 0.01 } } == true

    companion object {
        fun of(state: KlipperPrinterState): KlipperSpeeds {
            val move = state.obj("gcode_move")
            val report = state.obj("motion_report")
            val toolhead = state.obj("toolhead")
            return KlipperSpeeds(
                askedFor = move?.number("speed"),
                override = move?.number("speed_factor"),
                live = report?.number("live_velocity"),
                filament = report?.number("live_extruder_velocity"),
                maxVelocity = toolhead?.number("max_velocity"),
                maxAccel = toolhead?.number("max_accel"),
                cornerVelocity = toolhead?.number("square_corner_velocity"),
            )
        }
    }
}

/** The speeds this printer is working with, from the objects it publishes. */
internal val KlipperPrinterState.speeds: KlipperSpeeds get() = KlipperSpeeds.of(this)

/** A number klippy may not have published yet, as a reading rather than an exception. */
internal fun Double?.orDash(decimals: Int = 1): String =
    this?.let { String.format(java.util.Locale.ROOT, "%." + decimals + "f", it) } ?: "—"

/** The same, for a JSON value that may be absent or a different type. */
internal fun JSONObject.numberOrNull(key: String): Double? = number(key)
