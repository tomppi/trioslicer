package com.tomppi.enderslicer.engine.gcode

import com.tomppi.enderslicer.engine.LayerEventType
import com.tomppi.enderslicer.engine.formatDecimal
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The G-code route as it stood at TrioSlicer 1.4.0, when it was the only one.
 *
 * **This file is frozen.** It is the Marlin route - and the RepRapFirmware and generic routes,
 * which it also served - exactly as it worked before Klipper was integrated into the app, and
 * work on the Klipper route does not touch it. A change here is a decision to change what a
 * Marlin printer is sent, and MarlinRouteContractTest will not let one happen quietly: it holds
 * the two G-code texts to the bytes they had at 1.4.0 and refuses any Klipper-only command in
 * this route's output.
 *
 * The texts and the encodings below are that release's, verbatim. The only additions are the
 * members [GcodeRoute] needs in order to be a route at all - [flavor], [pauseCommand] and the
 * migrate pair - and each returns what this route already did.
 */
internal class FrozenGcodeRoute(override val flavor: String) : GcodeRoute {

    /**
     * Which of the three dialects this flavour was, by 1.4.0's own rule and in its order:
     * Klipper was not one of them, and the order of these tests is what decided a flavour
     * that mentions two of them - "RepRap (Marlin/Sprinter)" is Marlin, "Duet RepRapFirmware"
     * is RepRap, and anything unrecognised is generic.
     */
    private val dialect: LegacyDialect = when {
        "reprapfirmware" in flavor.lowercase(Locale.US) ||
            "duet" in flavor.lowercase(Locale.US) ||
            flavor.lowercase(Locale.US) == "rrf" -> LegacyDialect.REPRAP_FIRMWARE
        "marlin" in flavor.lowercase(Locale.US) ||
            "sprinter" in flavor.lowercase(Locale.US) -> LegacyDialect.MARLIN
        else -> LegacyDialect.GENERIC
    }

    private enum class LegacyDialect { MARLIN, REPRAP_FIRMWARE, GENERIC }

    override fun pauseCommand(): String = "M0"

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
        LayerEventType.FILAMENT_CHANGE -> listOf("M600")
        LayerEventType.NOZZLE_TEMPERATURE -> {
            // Marlin's M109 S waits only while heating; R also waits for the temperature to
            // come back down, which descending towers require.
            val waitMode = if (isMarlin()) "R" else "S"
            listOf("M109 $waitMode" + format(required(value, type)))
        }
        LayerEventType.BED_TEMPERATURE -> listOf("M140 S" + format(required(value, type)))
        LayerEventType.FAN_SPEED -> {
            val percent = required(value, type).coerceIn(0.0, 100.0)
            if (percent <= 0.0) {
                listOf("M107")
            } else {
                val pwm = (percent * 255.0 / 100.0).roundToInt().coerceIn(0, 255)
                listOf("M106 S$pwm")
            }
        }
        LayerEventType.SPEED_FACTOR -> listOf("M220 S" + format(required(value, type)))
        LayerEventType.FLOW_FACTOR -> listOf("M221 S" + format(required(value, type)))
        LayerEventType.RETRACTION -> retractionCommands(
            lengthMm = required(value, type),
            speedMmPerSecond = secondaryValue ?: DEFAULT_RETRACTION_SPEED_MM_PER_SECOND,
        )
        LayerEventType.PRESSURE_ADVANCE -> pressureAdvanceCommands(required(value, type))
        LayerEventType.JUNCTION_DEVIATION -> junctionDeviationCommands(required(value, type))
        LayerEventType.CAMERA_TRIGGER -> listOf("M240")
        LayerEventType.MESSAGE -> listOf("M117 " + safeText(text))
        LayerEventType.CUSTOM_GCODE ->
            text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    }

    override fun isFirmwareRetract(command: com.tomppi.enderslicer.engine.GcodeCommand.Parsed): Boolean =
        (isMarlin() || isRepRap()) &&
            command.opcode == "G10" && command.parameterLetters.isNullOrEmpty()

    override fun isFirmwareUnretract(command: com.tomppi.enderslicer.engine.GcodeCommand.Parsed): Boolean =
        (isMarlin() || isRepRap()) &&
            command.opcode == "G11" && command.parameterLetters.isNullOrEmpty()

    private fun isMarlin(): Boolean = dialect == LegacyDialect.MARLIN

    private fun isRepRap(): Boolean = dialect == LegacyDialect.REPRAP_FIRMWARE

    private fun retractionCommands(lengthMm: Double, speedMmPerSecond: Double): List<String> {
        require(lengthMm in 0.0..100.0) { "Retraction length is outside 0..100 mm" }
        require(speedMmPerSecond in 0.1..1000.0) { "Retraction speed is outside 0.1..1000 mm/s" }
        return if (isMarlin() || isRepRap()) {
            listOf("M207 S" + format(lengthMm) + " F" + format(speedMmPerSecond * 60.0))
        } else {
            unsupported(LayerEventType.RETRACTION)
        }
    }

    private fun pressureAdvanceCommands(value: Double): List<String> {
        require(value in 0.0..10.0) { "Pressure advance is outside 0..10" }
        return when {
            isMarlin() -> listOf("M900 K" + format(value))
            isRepRap() -> listOf("M572 D0 S" + format(value))
            else -> unsupported(LayerEventType.PRESSURE_ADVANCE)
        }
    }

    private fun junctionDeviationCommands(value: Double): List<String> {
        require(value in 0.0..1.0) { "Junction deviation is outside 0..1 mm" }
        return if (isMarlin()) {
            listOf("M205 J" + format(value))
        } else {
            unsupported(LayerEventType.JUNCTION_DEVIATION)
        }
    }

    private fun unsupported(type: LayerEventType): Nothing = throw UnsupportedFirmwareCommand(
        flavor + " does not have a verified " + type.name.lowercase(Locale.US).replace('_', ' ') +
            " encoder",
    )

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
        private const val DEFAULT_RETRACTION_SPEED_MM_PER_SECOND = 25.0

        /**
         * The start G-code this route has given a new profile since 1.4.0, byte for byte.
         *
         * UBL, which is Marlin's: load the mesh from slot 0, then activate it. It is pinned as
         * a test resource as well, so a change to it fails a test rather than shipping.
         */
        val START_GCODE: String = """
            ; Ender 3 Custom Start G-code
            G92 E0 ; Reset Extruder
            G28 ; Home all axes
            G29 L0 ; load a valid mesh from slot 0
            G29 A  ; active the UBL system
            G1 Z2.0 F3000 ; Move Z Axis up little to prevent scratching of Heat Bed
            G1 X0.1 Y20 Z0.3 F5000.0 ; Move to start position
            G1 X0.1 Y200.0 Z0.3 F1500.0 E15 ; Draw the first line
            G1 X0.4 Y200.0 Z0.3 F5000.0 ; Move to side a little
            G1 X0.4 Y20 Z0.3 F1500.0 E30 ; Draw the second line
            G92 E0 ; Reset Extruder
            G1 Z2.0 F3000 ; Move Z Axis up little to prevent scratching of Heat Bed
            G1 X5 Y20 Z0.3 F5000.0 ; Move over to prevent blob squish
        """.trimIndent()

        val END_GCODE: String = """
            G91 ;Relative positioning
            G1 E-2 F2700 ;Retract a bit
            G1 E-2 Z0.2 F2400 ;Retract and raise Z
            G1 X5 Y5 F3000 ;Wipe out
            G1 Z10 ;Raise Z more
            G90 ;Absolute positioning

            G1 X0 Y20 ;Present print
            M106 S0 ;Turn-off fan
            M104 S0 ;Turn-off hotend
            M140 S0 ;Turn-off bed

            M84 X Y E ;Disable all steppers but Z
        """.trimIndent()
    }
}
