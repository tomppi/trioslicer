package com.tomppi.enderslicer.engine

import java.util.Locale
import kotlin.math.roundToInt

/** Encodes layer events through the machine's declared firmware dialect. */
class CalibrationFirmwareEncoder private constructor(
    val dialect: FirmwareDialect,
    val declaredFlavor: String,
) {
    enum class FirmwareDialect { MARLIN, KLIPPER, REPRAP_FIRMWARE, GENERIC }

    class UnsupportedFirmwareCommand(message: String) : IllegalArgumentException(message)

    fun commands(
        type: LayerEventType,
        layerNumber: Int,
        value: Double? = null,
        secondaryValue: Double? = null,
        text: String = "",
    ): List<String> = when (type) {
        LayerEventType.PAUSE -> listOf("M117 Pause layer $layerNumber", "M0")
        LayerEventType.FILAMENT_CHANGE -> listOf("M600")
        LayerEventType.NOZZLE_TEMPERATURE -> {
            // Marlin's M109 S waits only while heating; R also waits for the
            // temperature to come back down, which descending towers require.
            val waitMode = if (dialect == FirmwareDialect.MARLIN) "R" else "S"
            listOf("M109 $waitMode${format(required(value, type))}")
        }
        LayerEventType.BED_TEMPERATURE -> listOf("M140 S${format(required(value, type))}")
        LayerEventType.FAN_SPEED -> {
            val percent = required(value, type).coerceIn(0.0, 100.0)
            if (percent <= 0.0) {
                listOf("M107")
            } else {
                val pwm = (percent * 255.0 / 100.0).roundToInt().coerceIn(0, 255)
                listOf("M106 S$pwm")
            }
        }
        LayerEventType.SPEED_FACTOR -> listOf("M220 S${format(required(value, type))}")
        LayerEventType.FLOW_FACTOR -> listOf("M221 S${format(required(value, type))}")
        LayerEventType.RETRACTION -> retractionCommands(
            lengthMm = required(value, type),
            speedMmPerSecond = secondaryValue ?: DEFAULT_RETRACTION_SPEED_MM_PER_SECOND,
        )
        LayerEventType.PRESSURE_ADVANCE -> pressureAdvanceCommands(required(value, type))
        LayerEventType.JUNCTION_DEVIATION -> junctionDeviationCommands(required(value, type))
        LayerEventType.CAMERA_TRIGGER -> listOf("M240")
        LayerEventType.MESSAGE -> listOf("M117 ${safeText(text)}")
        LayerEventType.CUSTOM_GCODE -> text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    }

    fun hotendOffCommand(): String = "M104 S0"

    internal fun isFirmwareRetract(command: GcodeCommand.Parsed): Boolean = when (dialect) {
        FirmwareDialect.REPRAP_FIRMWARE -> command.opcode == "G10" && command.parameterLetters.isEmpty()
        // Marlin uses G10 both for firmware retraction (bare) and for workspace
        // / tool offsets (G10 P0 X… Y… Z…, G10 R, G10 L). Only the bare form is
        // a retraction.
        FirmwareDialect.MARLIN -> command.opcode == "G10" && command.parameterLetters.isEmpty()
        FirmwareDialect.KLIPPER, FirmwareDialect.GENERIC -> false
    }

    internal fun isFirmwareUnretract(command: GcodeCommand.Parsed): Boolean = when (dialect) {
        FirmwareDialect.REPRAP_FIRMWARE -> command.opcode == "G11" && command.parameterLetters.isEmpty()
        FirmwareDialect.MARLIN -> command.opcode == "G11" && command.parameterLetters.isEmpty()
        FirmwareDialect.KLIPPER, FirmwareDialect.GENERIC -> false
    }

    private fun retractionCommands(lengthMm: Double, speedMmPerSecond: Double): List<String> {
        require(lengthMm in 0.0..100.0) { "Retraction length is outside 0..100 mm" }
        require(speedMmPerSecond in 0.1..1000.0) { "Retraction speed is outside 0.1..1000 mm/s" }
        return when (dialect) {
            FirmwareDialect.MARLIN,
            FirmwareDialect.REPRAP_FIRMWARE -> listOf(
                "M207 S${format(lengthMm)} F${format(speedMmPerSecond * 60.0)}",
            )
            FirmwareDialect.KLIPPER -> listOf(
                "SET_RETRACTION RETRACT_LENGTH=${format(lengthMm)} RETRACT_SPEED=${format(speedMmPerSecond)}",
            )
            FirmwareDialect.GENERIC -> unsupported(LayerEventType.RETRACTION)
        }
    }

    private fun pressureAdvanceCommands(value: Double): List<String> {
        require(value in 0.0..10.0) { "Pressure advance is outside 0..10" }
        return when (dialect) {
            FirmwareDialect.MARLIN -> listOf("M900 K${format(value)}")
            FirmwareDialect.KLIPPER -> listOf("SET_PRESSURE_ADVANCE ADVANCE=${format(value)}")
            FirmwareDialect.REPRAP_FIRMWARE -> listOf("M572 D0 S${format(value)}")
            FirmwareDialect.GENERIC -> unsupported(LayerEventType.PRESSURE_ADVANCE)
        }
    }

    private fun junctionDeviationCommands(value: Double): List<String> {
        require(value in 0.0..1.0) { "Junction deviation is outside 0..1 mm" }
        return when (dialect) {
            FirmwareDialect.MARLIN -> listOf("M205 J${format(value)}")
            FirmwareDialect.KLIPPER,
            FirmwareDialect.REPRAP_FIRMWARE,
            FirmwareDialect.GENERIC -> unsupported(LayerEventType.JUNCTION_DEVIATION)
        }
    }

    private fun unsupported(type: LayerEventType): Nothing = throw UnsupportedFirmwareCommand(
        "$declaredFlavor does not have a verified ${type.name.lowercase(Locale.US).replace('_', ' ')} encoder",
    )

    private fun required(value: Double?, type: LayerEventType): Double = requireNotNull(value) {
        "${type.name.lowercase(Locale.US).replace('_', ' ')} requires a numeric value"
    }.also { require(it.isFinite()) { "Layer-event value must be finite" } }

    private fun safeText(value: String): String = value
        .replace(Regex("[\\r\\n;]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(64)
        .ifBlank { "EnderSlicer event" }

    private fun format(value: Double): String = formatDecimal(value, 5)

    companion object {
        fun fromFlavor(rawFlavor: String): CalibrationFirmwareEncoder {
            val flavor = rawFlavor.trim().ifBlank { PrinterEnvelope.DEFAULT_GCODE_FLAVOR }
            val normalized = flavor.lowercase(Locale.US)
            val dialect = when {
                "klipper" in normalized -> FirmwareDialect.KLIPPER
                "reprapfirmware" in normalized || "duet" in normalized || normalized == "rrf" -> {
                    FirmwareDialect.REPRAP_FIRMWARE
                }
                "marlin" in normalized || "sprinter" in normalized -> FirmwareDialect.MARLIN
                else -> FirmwareDialect.GENERIC
            }
            return CalibrationFirmwareEncoder(dialect, flavor)
        }

        private const val DEFAULT_RETRACTION_SPEED_MM_PER_SECOND = 25.0
    }
}
