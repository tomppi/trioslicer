package com.tomppi.enderslicer.printer

import org.json.JSONObject

/**
 * The speeds in play, in the units each of them is actually in.
 *
 * Three different numbers get called "the speed":
 *
 *  - what the file asks for, which is the feedrate of the last G-code move. klippy publishes it
 *    as gcode_move.speed, and it is in mm PER MINUTE - the unit F is written in, and the one
 *    field in klippy that is not mm/s. G1 assigns it straight from F with no division
 *    (gcode_move.py:134-139), so 1500 here is the F1500 a slicer wrote, which is 25 mm/s;
 *  - what M220 makes of it - klippy speed_factor is a ratio, 1.0 being the file own speed, so
 *    the two multiplied are what the printer will actually try to do;
 *  - what the toolhead is doing at this instant, in mm/s, which is motion_report live_velocity -
 *    the measured one, and the only one of the three that is a fact rather than a request. The
 *    motion system is mm/s throughout, which is why the limits and every other number here are.
 *
 * The limits are beside them because they are what makes the difference: a printer at 200% does
 * not go twice as fast once max_velocity is reached, and acceleration is what governs every move
 * too short to reach it.
 *
 * Reading gcode_move.speed as mm/s was this class own mistake for one release. The dashboard
 * showed a file asking F1500 as "1500 mm/s", multiplied it by sixty to get a mm/min figure, and
 * then compared that against a mm/s limit - so every print looked as though it were being clamped
 * at sixty times its real speed, and a warning said so. The units are in the names now, and the
 * comparison is between two things that are both mm/s.
 */
data class KlipperSpeeds(
    /** The last commanded feedrate in mm per minute - the number the file wrote after F. */
    val askedPerMinute: Double?,
    /** M220 factor: 1.0 means the speed the file asked for. */
    val override: Double?,
    /** The toolhead measured speed, in mm/s, at the moment of the reading. */
    val live: Double?,
    /** The extruder measured speed, in mm/s of filament. */
    val filament: Double?,
    val maxVelocity: Double?,
    val maxAccel: Double?,
    val cornerVelocity: Double?,
) {
    /** The file own figure in mm/s, which is how print speeds are talked about. */
    val askedPerSecond: Double? get() = askedPerMinute?.div(60.0)

    /** What the file asks for once M220 is applied, in mm per minute. */
    val effectivePerMinute: Double? get() = askedPerMinute?.let { it * (override ?: 1.0) }

    /** The same in mm/s - and max_velocity is in mm/s, so this is the one to compare. */
    val effectivePerSecond: Double? get() = effectivePerMinute?.div(60.0)

    /**
     * True when the override asks for more than the machine will give.
     *
     * Not an error: klippy clamps the move and the print carries on. It is the answer to
     * "why is 200% no faster", which is otherwise invisible.
     */
    val clamped: Boolean
        get() = effectivePerSecond?.let { asked -> maxVelocity?.let { asked > it + 0.01 } } == true

    companion object {
        fun of(state: KlipperPrinterState): KlipperSpeeds {
            val move = state.obj("gcode_move")
            val report = state.obj("motion_report")
            val toolhead = state.obj("toolhead")
            return KlipperSpeeds(
                askedPerMinute = move?.number("speed"),
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
