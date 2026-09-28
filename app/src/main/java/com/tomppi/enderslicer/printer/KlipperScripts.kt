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

    /**
     * A signed distance, as the commands that take one want it: TESTZ Z=-0.050.
     *
     * Its own function because the sign is part of the number rather than a word
     * beside it, and formatting it is where the decimal point lives.
     */
    fun offset(delta: Double): String = number(delta)

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

    /**
     * Set one axis of input shaping, live.
     *
     * Nothing is saved by this: the printer's configuration keeps the values it has
     * until they are written into [input_shaper] and the host restarts. The damping
     * ratio is left out when it was not given, because klippy then keeps the one that
     * belongs to the shaper rather than being handed a default that may not.
     */
    fun inputShaper(axis: String, type: String, frequency: Double, dampingRatio: Double?): String {
        val upper = axis.uppercase()
        val parts = mutableListOf(
            "SHAPER_TYPE_$upper=" + type.lowercase(),
            "SHAPER_FREQ_$upper=" + number(frequency, 1),
        )
        if (dampingRatio != null) {
            parts += "DAMPING_RATIO_$upper=" + number(dampingRatio, 3)
        }
        return "SET_INPUT_SHAPER " + parts.joinToString(" ")
    }

    /**
     * Play the resonance sweep, for the phone to measure.
     *
     * The command comes from the playback module in the payload, which mirrors Klipper's
     * own resonance test: the same generator, the same moves. The frequencies and the rate
     * are passed back to the analysis, which has to know what was played when.
     */
    fun playResonances(
        axis: String,
        freqStart: Double,
        freqEnd: Double,
        hzPerSec: Double,
        accelPerHz: Double,
    ): String =
        "PLAY_RESONANCES AXIS=" + axis.uppercase() +
            " FREQ_START=" + number(freqStart, 1) +
            " FREQ_END=" + number(freqEnd, 1) +
            " HZ_PER_SEC=" + number(hzPerSec, 2) +
            " ACCEL_PER_HZ=" + number(accelPerHz, 1)

    /**
     * The sweep run gently, for when the phone is riding the toolhead.
     *
     * The excitation is `accel_per_hz * f` - 7200 mm/s^2 at the top of the band, three
     * quarters of a g. Without the phone that is what the machine was designed to survive;
     * with 253 grams added to the carriage it is a quarter more force than the belts and
     * the motor were ever asked for, and a skipped step during a sweep would be silent and
     * would corrupt the measurement. Half the excitation is still far above the sensor's
     * own noise.
     */
    const val GENTLE_ACCEL_PER_HZ = 30.0
    const val STANDARD_ACCEL_PER_HZ = 60.0

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

    /**
     * PID calibration, by the name the heater is known by.
     *
     * klippy's lookup_heater is keyed by the short name - the part after the section type -
     * so `heater_generic chamber` is calibrated as `HEATER=chamber`. The hotend and the bed
     * have no type in their name and are unaffected, which is why the screen offers this for
     * those two and the mistake stayed hidden.
     */
    /**
     * Push filament through for the extruder calibration, slowly.
     *
     * Klipper's own procedure: mark the filament, ask for a known length, measure what
     * actually moved. A millimetre a second, because the point is to measure the extruder
     * and not to find out where it skips.
     */
    fun extrudeForCalibration(lengthMm: Double): String =
        "SAVE_GCODE_STATE NAME=extrude_calibration\n" +
            "M83\n" +
            "G1 E" + number(lengthMm, 1) + " F60\n" +
            "RESTORE_GCODE_STATE NAME=extrude_calibration"

    /**
     * The extruder's rotation distance: set it, or ask what it is.
     *
     * The command is a mux keyed by the extruder's config name, so the default extruder is
     * "extruder" - not the empty string a section-type strip would produce. With no distance
     * klippy reports the value in use, which is how the screen fills itself in.
     */
    fun rotationDistance(extruder: String, distance: Double? = null): String {
        val head = "SET_EXTRUDER_ROTATION_DISTANCE EXTRUDER=" + quoted(extruder)
        return if (distance == null) head else head + " DISTANCE=" + number(distance, 3)
    }

    /** the figure an extruder calibration arrives at, to three places as Klipper asks. */
    fun correctedRotationDistance(current: Double, requested: Double, measured: Double): Double? {
        if (current <= 0.0 || requested <= 0.0 || measured <= 0.0) return null
        val corrected = current * measured / requested
        return kotlin.math.round(corrected * 1000.0) / 1000.0
    }

    fun pidCalibrate(heater: String, target: Int): String =
        "PID_CALIBRATE HEATER=" + quoted(heater.substringAfter(' ')) + " TARGET=$target"

    /**
     * A target for anything with a temperature, by the name it is known by.
     *
     * Two commands, because klippy has two: `heater_generic chamber` is a Heater and takes
     * SET_HEATER_TEMPERATURE HEATER=chamber, while a `temperature_fan` is not a Heater at all
     * and registers SET_TEMPERATURE_FAN_TARGET TEMPERATURE_FAN=<name>. Sending the first to a
     * fan answered "The value 'chamber' is not valid for HEATER", and the control failed
     * with only the console saying why.
     */
    fun heaterTemperature(heater: String, celsius: Int): String {
        val short = quoted(heater.substringAfter(' '))
        return if (heater.startsWith("temperature_fan", ignoreCase = true)) {
            "SET_TEMPERATURE_FAN_TARGET TEMPERATURE_FAN=$short TARGET=$celsius"
        } else {
            "SET_HEATER_TEMPERATURE HEATER=$short TARGET=$celsius"
        }
    }

    /** Load, save or remove a saved mesh profile: LOAD, SAVE, REMOVE. */
    fun meshProfile(action: String, name: String): String =
        "BED_MESH_PROFILE $action=" + quoted(name)

    /**
     * A name, quoted for klippy's extended-command parser.
     *
     * Every command that is not one of G-code's own is re-parsed by klippy with shlex
     * (gcode.py's _get_extended_params), so an unquoted name with a space in it splits into
     * two words, the second of which has no "=" in it, and the answer is "Malformed command".
     * That is what a sliced model called "Phone Stand" produced: the file was copied to the
     * printer, appeared in the list, and every print button did nothing at all.
     *
     * Names this app makes cannot contain a quote - [KlipperPrint.fileName] maps everything
     * but letters, digits, dash, underscore, dot and space to an underscore - and a quote that
     * arrives from somewhere else is dropped here rather than being allowed to close the
     * quoting early.
     */
    fun quoted(name: String): String = "\"" + name.replace("\"", "") + "\""

    /**
     * A number as klippy's parser wants it: a dot for the decimal point, always.
     *
     * Locale.ROOT rather than the phone's locale, which is the whole reason this is here.
     */
    private fun number(value: Double, decimals: Int = 3): String =
        String.format(Locale.ROOT, "%.${decimals}f", value)
}
