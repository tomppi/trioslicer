package com.tomppi.enderslicer.printer

import java.io.File

/**
 * What the file being printed asks the machine for, against what the machine allows.
 *
 * Worth showing because the two are set in different places and neither screen mentions the
 * other: the profile's machine limits become M204 at the head of the file, the printer's own
 * [printer] max_accel and max_velocity are in printer.cfg, and a file asking for less than the
 * machine can do simply prints slowly - with nothing anywhere saying so.
 *
 * Only M204 means anything to Klipper. M201, M203 and M205 are Marlin's - acceleration per axis,
 * maximum feedrate, jerk - and klippy has no such commands: it answers "Unknown command" and
 * runs the move at the printer's own limits instead. So a file that looks like it sets 500 mm/s
 * as a ceiling is really setting nothing, and the app says which lines it ignored rather than
 * leaving the user to believe them.
 */
internal data class KlipperFileLimits(
    /** What the file asks for, from M204: min(P, T) as klippy reads it, or S for both. */
    val askedAccel: Double? = null,
    val askedTravelAccel: Double? = null,
    /** The printer's own limits, from toolhead. */
    val allowedAccel: Double? = null,
    val allowedVelocity: Double? = null,
    /** Marlin's limit lines the file carries, which klippy ignores. */
    val ignoredMarlinLimits: List<String> = emptyList(),
) {
    /** True when the file is the tighter of the two, which is the common surprise. */
    val accelerationIsTheFilesOwn: Boolean
        get() {
            val asked = askedAccel ?: return false
            val allowed = allowedAccel ?: return false
            return asked < allowed * 0.99
        }

    /** True when the printer will clamp what the file asks for. */
    val accelerationIsClampedByThePrinter: Boolean
        get() {
            val asked = askedAccel ?: return false
            val allowed = allowedAccel ?: return false
            return asked > allowed * 1.01
        }

    companion object {
        /** Read the limit lines, which are written before the first move. */
        fun fromGcode(lines: Sequence<String>): KlipperFileLimits {
            var asked: Double? = null
            var travel: Double? = null
            val ignored = mutableListOf<String>()
            for (raw in lines) {
                val line = raw.substringBefore(';').trim()
                if (line.isEmpty()) continue
                val opcode = line.substringBefore(' ').uppercase()
                when (opcode) {
                    "M204" -> {
                        val parameters = parameters(line)
                        val print = parameters['P'] ?: parameters['S']
                        val travelAccel = parameters['T'] ?: parameters['S']
                        val both = listOfNotNull(print, travelAccel)
                        if (both.isNotEmpty()) {
                            asked = both.min()
                            travel = both.max()
                        }
                    }
                    "M201", "M203", "M205", "M900" -> ignored += opcode
                }
            }
            return KlipperFileLimits(
                askedAccel = asked,
                askedTravelAccel = travel,
                ignoredMarlinLimits = ignored.distinct().sorted(),
            )
        }

        /** The file's ask and the machine's limits together, or null when neither is known. */
        fun of(printer: KlipperPrinterState, file: File?): KlipperFileLimits? {
            val head = file?.takeIf { it.isFile }?.let { source ->
                runCatching {
                    source.bufferedReader().use { reader -> reader.lineSequence().take(400).toList() }
                }.getOrDefault(emptyList())
            }.orEmpty()
            val limits = fromGcode(head.asSequence()).copy(
                allowedAccel = printer.speeds.maxAccel,
                allowedVelocity = printer.speeds.maxVelocity,
            )
            return limits.takeIf {
                it.askedAccel != null || it.allowedAccel != null ||
                    it.ignoredMarlinLimits.isNotEmpty()
            }
        }

        private fun parameters(line: String): Map<Char, Double> {
            val found = mutableMapOf<Char, Double>()
            for (word in line.split(' ').drop(1)) {
                val letter = word.firstOrNull()?.uppercaseChar() ?: continue
                val value = word.drop(1).toDoubleOrNull() ?: continue
                found[letter] = value
            }
            return found
        }
    }
}
