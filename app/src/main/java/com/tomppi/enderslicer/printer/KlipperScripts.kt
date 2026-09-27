package com.tomppi.enderslicer.printer

import java.util.Locale

/**
 * The G-code this app sends, built in one place.
 *
 * Two reasons it is not assembled at the buttons.
 *
 * The first is the decimal point. String formatting follows the phone's locale, and on a
 * phone set to Finnish or German `"%.3f".format(10.0)` is `"10,000"` - which turned a jog
 * into `G1 Y10,000 F3000`, a command klippy answered with *Unable to parse move*. The
 * button did nothing, the screen said nothing, and only the console showed why. Every
 * figure that leaves here is formatted for the wire rather than for a person.
 *
 * The second is that a command is worth reading. What this app sends is what the printer
 * does, and none of it should be built somewhere nobody can check it.
 */
internal object KlipperScripts {
    /** The saved states the relative moves are wrapped in, one each to keep them apart. */
    private const val JOG_STATE = "trioslicer_jog"
    private const val EXTRUDE_STATE = "trioslicer_extrude"

    /** Home all axes, or the ones named. */
    fun home(axes: String = ""): String =
        if (axes.isBlank()) "G28" else "G28 $axes"

    /**
     * Move one axis by a distance, without changing how the machine is positioned.
     *
     * The state is saved and restored around a relative move rather than switching to
     * relative and switching back: a G91 that is never undone - the link drops, the
     * printer shuts down - would leave the machine in the mode that makes the next
     * absolute move a relative one, which is a crash.
     */
    fun jog(axis: String, distance: Double, feedrate: Int): String = listOf(
        "SAVE_GCODE_STATE NAME=$JOG_STATE",
        "G91",
        "G1 $axis${number(distance)} F$feedrate",
        "RESTORE_GCODE_STATE NAME=$JOG_STATE",
    ).joinToString("\n")

    /** Nudge the first layer up or down, which is what a Z offset is for. */
    fun zOffset(delta: Double, move: Boolean): String =
        "SET_GCODE_OFFSET Z_ADJUST=${number(delta)}" + if (move) " MOVE=1" else ""

    /** Push filament through, or pull it back, in the same kind of saved state. */
    fun extrude(lengthMm: Double, feedrate: Int): String = listOf(
        "SAVE_GCODE_STATE NAME=$EXTRUDE_STATE",
        "M83",
        "G1 E${number(lengthMm, 2)} F$feedrate",
        "RESTORE_GCODE_STATE NAME=$EXTRUDE_STATE",
    ).joinToString("\n")

    fun pressureAdvance(advance: Double): String =
        "SET_PRESSURE_ADVANCE ADVANCE=${number(advance, 4)}"

    fun retraction(length: Double, speed: Double): String =
        "SET_RETRACTION RETRACT_LENGTH=${number(length, 2)} RETRACT_SPEED=${number(speed, 1)}"

    /** The part fan, as a percentage of full speed, in M106's 0-255. */
    fun fan(percent: Int): String = "M106 S${percent.coerceIn(0, 100) * 255 / 100}"

    fun hotend(celsius: Int): String = "M104 S$celsius"

    fun bed(celsius: Int): String = "M140 S$celsius"

    /** Everything off at once, which is what a user means by "cool down". */
    fun coolDown(): String = "M104 S0\nM140 S0"

    /** M220: print speed, as a percentage of what the file asks for. */
    fun speedFactor(percent: Int): String = "M220 S${percent.coerceIn(1, 999)}"

    /** M221: extrusion, as a percentage of what the file asks for. */
    fun extrudeFactor(percent: Int): String = "M221 S${percent.coerceIn(1, 999)}"

    fun pidCalibrate(heater: String, target: Int): String =
        "PID_CALIBRATE HEATER=$heater TARGET=$target"

    /**
     * Any heater that is not the hotend or the bed is addressed by its own section name:
     * `heater_generic chamber` is `SET_HEATER_TEMPERATURE HEATER=chamber`.
     */
    fun heaterTemperature(heater: String, celsius: Int): String =
        "SET_HEATER_TEMPERATURE HEATER=${heater.substringAfter(' ')} TARGET=$celsius"

    /** Load, save or remove a saved mesh profile: LOAD, SAVE, REMOVE. */
    fun meshProfile(action: String, name: String): String = "BED_MESH_PROFILE $action=$name"

    /**
     * A number as klippy's parser wants it: a dot for the decimal point, always.
     *
     * Locale.ROOT rather than the phone's locale, which is the whole reason this is here.
     */
    private fun number(value: Double, decimals: Int = 3): String =
        String.format(Locale.ROOT, "%.${decimals}f", value)
}
