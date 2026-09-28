package com.tomppi.enderslicer.engine.gcode

import com.tomppi.enderslicer.engine.LayerEventType
import com.tomppi.enderslicer.engine.formatDecimal
import java.util.Locale

/**
 * The route for the Klipper host inside this app.
 *
 * This file owns everything about Klipper G-code, and it is the one that changes as the Klipper
 * work continues. It shares no implementation with [FrozenGcodeRoute]: where the two agree -
 * M104, M106, M140, M117 - they agree because the commands are the same on both machines, not
 * because one calls the other. The route is chosen once, from the profile's flavour, in
 * [GcodeRoute.forFlavor].
 *
 * The differences are the point of the file: Klipper stops a print with PAUSE rather than M0,
 * has no M600 (a filament change is a pause with a message and then RESUME), retracts through
 * SET_RETRACTION, shapes corners with SET_PRESSURE_ADVANCE and has no junction deviation at all.
 */
internal class KlipperGcodeRoute(override val flavor: String) : GcodeRoute {

    override fun pauseCommand(): String = "PAUSE"

    override fun startGcode(): String = START_GCODE

    override fun endGcode(): String = END_GCODE

    override fun hotendOffCommand(): String = "M104 S0"

    override fun commands(
        type: LayerEventType,
        layerNumber: Int,
        value: Double?,
        secondaryValue: Double?,
        text: String,
    ): List<String> = when (type) {
        LayerEventType.PAUSE -> listOf("M117 Pause layer $layerNumber", pauseCommand())
        // klippy has no M600, and answers an unknown command with "Unknown command" while the
        // print carries on - so the filament change is a pause with a message, and the user
        // loads the filament and resumes.
        LayerEventType.FILAMENT_CHANGE -> listOf("M117 Change filament", pauseCommand())
        LayerEventType.NOZZLE_TEMPERATURE ->
            // Klipper's M109 takes S and waits for it, which is what a tower needs.
            listOf("M109 S" + format(required(value, type)))
        LayerEventType.BED_TEMPERATURE -> listOf("M140 S" + format(required(value, type)))
        LayerEventType.FAN_SPEED -> {
            val percent = required(value, type).coerceIn(0.0, 100.0)
            if (percent <= 0.0) {
                listOf("M107")
            } else {
                val pwm = (percent * 255.0 / 100.0).toInt().coerceIn(0, 255)
                listOf("M106 S$pwm")
            }
        }
        LayerEventType.SPEED_FACTOR -> listOf("M220 S" + format(required(value, type)))
        LayerEventType.FLOW_FACTOR -> listOf("M221 S" + format(required(value, type)))
        LayerEventType.RETRACTION -> listOf(
            "SET_RETRACTION RETRACT_LENGTH=" + format(required(value, type)) +
                " RETRACT_SPEED=" + format(secondaryValue ?: DEFAULT_RETRACTION_SPEED),
        )
        LayerEventType.PRESSURE_ADVANCE -> listOf(
            "SET_PRESSURE_ADVANCE ADVANCE=" + format(required(value, type)),
        )
        // Klipper has no junction deviation: it shapes corners with square_corner_velocity in
        // the configuration and with input shaping. Refused rather than sent as an unknown
        // command that would quietly do nothing.
        LayerEventType.JUNCTION_DEVIATION -> throw UnsupportedFirmwareCommand(
            "Klipper has no junction deviation; it uses square_corner_velocity and input shaping",
        )
        // Unverified on Klipper: klippy implements no M240, so this is a no-op there. Left as it
        // is rather than guessed at - a camera trigger on Klipper is a macro, and which macro is
        // the printer's business.
        LayerEventType.CAMERA_TRIGGER -> listOf("M240")
        LayerEventType.MESSAGE -> listOf("M117 " + safeText(text))
        LayerEventType.CUSTOM_GCODE ->
            text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    }

    /** Klipper has no firmware retraction of its own: G10 and G11 are not commands there. */
    override fun isFirmwareRetract(command: com.tomppi.enderslicer.engine.GcodeCommand.Parsed): Boolean = false

    override fun isFirmwareUnretract(command: com.tomppi.enderslicer.engine.GcodeCommand.Parsed): Boolean = false

    private fun required(value: Double?, type: LayerEventType): Double = requireNotNull(value) {
        type.name.lowercase(Locale.US).replace('_', ' ') + " requires a numeric value"
    }.also { require(it.isFinite()) { "Layer-event value must be finite" } }

    private fun safeText(value: String): String = value
        .replace(Regex("[\\r\\n;]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(64)
        .ifBlank { "EnderSlicer event" }

    private fun format(value: Double): String = formatDecimal(value, 5)

    private companion object {
        private const val DEFAULT_RETRACTION_SPEED = 25.0

        /**
         * A new Klipper profile starts here.
         *
         * G28 and one mesh measurement per print: the printer re-probes rather than loading a
         * saved profile, because that is right whatever the printer's history is. The profile
         * line below it is what to use instead when a mesh is saved and the wait is unwanted.
         */
        val START_GCODE: String = """
            ; Ender 3 start G-code for Klipper
            G92 E0 ; reset the extruder's position
            G28 ; home all axes
            BED_MESH_CALIBRATE ; probe the bed for this print
            ; BED_MESH_PROFILE LOAD=default ; ...or use a saved mesh instead of probing
            G1 Z2.0 F3000 ; lift
            G1 X0.1 Y20 Z0.3 F5000.0 ; move to the start of the prime line
            G1 X0.1 Y200.0 Z0.3 F1500.0 E15 ; draw the first line
            G1 X0.4 Y200.0 Z0.3 F5000.0 ; move across a little
            G1 X0.4 Y20 Z0.3 F1500.0 E30 ; draw the second line
            G92 E0 ; reset the extruder again
            G1 Z2.0 F3000 ; lift off the line
            G1 X5 Y20 Z0.3 F5000.0 ; move clear of it
        """.trimIndent()

        /**
         * M84 releases every stepper on Klipper, Z included: its M84 takes no axis words, where
         * Marlin's does. On a leadscrew that is not a drop, and the comment says what happens.
         */
        val END_GCODE: String = """
            G91 ; relative positioning
            G1 E-2 F2700 ; retract a little
            G1 E-2 Z0.2 F2400 ; retract and raise
            G1 X5 Y5 F3000 ; wipe
            G1 Z10 ; raise further
            G90 ; absolute positioning

            G1 X0 Y20 ; present the print
            M106 S0 ; part fan off
            M104 S0 ; hotend off
            M140 S0 ; bed off

            M84 ; release every stepper, Z included: Klipper's M84 ignores axis words
        """.trimIndent()
    }
}
