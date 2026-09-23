package com.tomppi.enderslicer.engine

import java.util.Locale

/** One canonical, fail-closed command policy shared by all G-code safety consumers. */
internal object GcodeCommandPolicy {
    private val LINEAR_PARAMETERS = setOf('X', 'Y', 'Z', 'E', 'F')
    private val SAFE_NON_MOTION_G = setOf(4, 10, 11, 17, 21, 90, 91, 92, 94)
    private val TRUSTED_STARTUP_G = setOf(27, 28, 29)
    // G27 is the park-head command used by the AML start script (and kept
    // startup-only: after printable motion begins it is rejected like G28).
    private val SAFE_CUSTOM_EVENT_COMMANDS = setOf(
        "M104",
        "M109",
        "M140",
        "M190",
        "M106",
        "M107",
        "M117",
        "M118",
        "M300",
        "M400",
    )

    // C29 (mriscoc ProUI): mesh inset for adaptive mesh leveling.
    // L/R/F/B bound the probe region, N/X/Y the grid density, T/V report
    // modifiers; A/M/C are flag-only (AML mode, maximize area, center area).
    private val C29_ARGUMENT_RULES: Map<Char, ReadOnlyArgumentRule> = mapOf(
        'L' to ReadOnlyArgumentRule(allowFlag = false, numericRange = 0.0..1000.0, integerOnly = true),
        'R' to ReadOnlyArgumentRule(allowFlag = false, numericRange = 0.0..1000.0, integerOnly = true),
        'F' to ReadOnlyArgumentRule(allowFlag = false, numericRange = 0.0..1000.0, integerOnly = true),
        'B' to ReadOnlyArgumentRule(allowFlag = false, numericRange = 0.0..1000.0, integerOnly = true),
        'N' to ReadOnlyArgumentRule(allowFlag = false, numericRange = 0.0..99.0, integerOnly = true),
        'X' to ReadOnlyArgumentRule(allowFlag = false, numericRange = 0.0..99.0, integerOnly = true),
        'Y' to ReadOnlyArgumentRule(allowFlag = false, numericRange = 0.0..99.0, integerOnly = true),
        'T' to ReadOnlyArgumentRule(allowFlag = false, numericRange = 0.0..100.0, integerOnly = true),
        'V' to ReadOnlyArgumentRule(allowFlag = false, numericRange = 0.0..4.0, integerOnly = true),
        'A' to ReadOnlyArgumentRule(allowFlag = true),
        'M' to ReadOnlyArgumentRule(allowFlag = true),
        'C' to ReadOnlyArgumentRule(allowFlag = true),
    )

    private fun requireC29Arguments(command: GcodeCommand.Parsed, consumer: String) {
        requireReadOnlyArguments(command, consumer, location = "", rules = C29_ARGUMENT_RULES)
    }

    private data class ReadOnlyArgumentRule(
        val allowFlag: Boolean,
        val numericRange: ClosedFloatingPointRange<Double>? = null,
        val integerOnly: Boolean = false,
    )

    fun requireLinearParameters(command: GcodeCommand.Parsed) {
        require(command.hasOnlyParameters(LINEAR_PARAMETERS)) {
            "Unsupported ${command.opcode} parameters ${command.parameterLetters.sorted().joinToString("")}"
        }
    }

    fun requireNonPlanarSupported(command: GcodeCommand.Parsed, inPrintableLayers: Boolean) {
        requireUnframed(command, "Non-planar slicing")
        when (command.family) {
            'G' -> when (command.code) {
                0, 1 -> requireLinearParameters(command)
                2, 3 -> error(
                    "Non-planar slicing cannot safely interpret G2/G3 arcs; disable arc fitting and custom arc purge paths",
                )
                in SAFE_NON_MOTION_G -> Unit
                in TRUSTED_STARTUP_G -> require(!inPrintableLayers) {
                    "Non-planar slicing does not allow ${command.opcode} after printable motion has started"
                }
                else -> error(
                    "Non-planar slicing cannot safely interpret ${command.opcode}; remove unsupported motion or coordinate commands",
                )
            }
            'M' -> requirePublishedM(command, lineNumber = null, consumer = "Non-planar slicing")
            'T' -> require(command.code == 0) { "Non-planar slicing does not support tool changes (${command.opcode})" }
            else -> error("Non-planar slicing cannot safely interpret command family ${command.family}")
        }
    }

    fun requireConicalSupported(command: GcodeCommand.Parsed, inPrintableLayers: Boolean) {
        requireUnframed(command, "Conical slicing")
        when (command.family) {
            'G' -> when (command.code) {
                0, 1 -> requireLinearParameters(command)
                2, 3 -> error(
                    "Conical slicing cannot safely interpret G2/G3 arcs; disable arc fitting and custom arc purge paths",
                )
                in SAFE_NON_MOTION_G -> Unit
                in TRUSTED_STARTUP_G -> require(!inPrintableLayers) {
                    "Conical slicing does not allow ${command.opcode} after printable motion has started"
                }
                else -> error(
                    "Conical slicing cannot safely interpret ${command.opcode}; remove unsupported motion or coordinate commands",
                )
            }
            'M' -> requirePublishedM(command, lineNumber = null, consumer = "Conical slicing")
            'T' -> require(command.code == 0) { "Conical slicing does not support tool changes (${command.opcode})" }
            else -> error("Conical slicing cannot safely interpret command family ${command.family}")
        }
    }

    fun requirePublishedSafe(
        command: GcodeCommand.Parsed,
        currentLayer: Int?,
        lineNumber: Int,
        inEndGcode: Boolean = false,
    ) {
        requireUnframed(command, "Published G-code at line $lineNumber")
        when (command.family) {
            'G' -> when (command.code) {
                0, 1 -> requireLinearParameters(command)
                2, 3 -> error(
                    "Unsupported G2/G3 arc at line $lineNumber; the G-code was not made available for export",
                )
                in SAFE_NON_MOTION_G -> Unit
                in TRUSTED_STARTUP_G -> require(currentLayer == null || inEndGcode) {
                    "Unsafe ${command.opcode} at line $lineNumber after printable layers began"
                }
                else -> error(
                    "Unsupported printer motion/state command ${command.opcode} at line $lineNumber; " +
                        "the G-code was not made available for export",
                )
            }
            'M' -> requirePublishedM(command, lineNumber, "Published G-code")
            'T' -> require(command.code == 0) {
                "Unsupported tool change ${command.opcode} at line $lineNumber"
            }
            'C' -> {
                require(command.code == 29) {
                    "Unsupported C-code ${command.opcode} at line $lineNumber; only C29 (AML) is allowed"
                }
                requireC29Arguments(command, "Published G-code at line $lineNumber")
            }
            else -> error(
                "Unsupported command family ${command.family} at line $lineNumber; " +
                    "the G-code was not made available for export",
            )
        }
    }

    /** Rejects executable lines that the numeric parser intentionally cannot model. */
    fun requirePublishedTextSafe(rawLine: String, gcodeFlavor: String, lineNumber: Int) {
        val command = rawLine.substringBefore(';').trim()
        if (command.isEmpty()) return
        val flavor = gcodeFlavor.lowercase(Locale.US)
        val pressureAdvance = KLIPPER_PRESSURE_ADVANCE.matchEntire(command)
        val retraction = if (pressureAdvance == null) KLIPPER_RETRACTION.matchEntire(command) else null
        val safe = "klipper" in flavor && when {
            pressureAdvance != null -> {
                val advance = pressureAdvance.groupValues[1].toDoubleOrNull()
                advance != null && advance.isFinite() && advance in KLIPPER_ADVANCE_MIN..KLIPPER_ADVANCE_MAX
            }
            retraction != null -> {
                val length = retraction.groupValues[1].toDoubleOrNull()
                val speed = retraction.groupValues[2].toDoubleOrNull()
                length != null && length.isFinite() && length in KLIPPER_RETRACT_LENGTH_MIN..KLIPPER_RETRACT_LENGTH_MAX &&
                    speed != null && speed.isFinite() && speed in KLIPPER_RETRACT_SPEED_MIN..KLIPPER_RETRACT_SPEED_MAX
            }
            else -> false
        }
        require(safe) {
            "Unsupported textual or malformed command at line $lineNumber; " +
                "the G-code was not made available for export"
        }
    }

    fun requirePreviewSafe(command: GcodeCommand.Parsed, spatialMovesSeen: Int) {
        requireUnframed(command, "Nozzle Path")
        when (command.family) {
            'G' -> when (command.code) {
                0, 1 -> requireLinearParameters(command)
                2, 3 -> error(
                    "Nozzle Path cannot safely display G2/G3 arcs; re-slice without arc commands",
                )
                in SAFE_NON_MOTION_G -> Unit
                in TRUSTED_STARTUP_G -> require(spatialMovesSeen == 0) {
                    "Nozzle Path cannot safely display ${command.opcode} after spatial motion has started"
                }
                else -> error(
                    "Nozzle Path cannot safely display ${command.opcode}; re-slice without unsupported motion commands",
                )
            }
            'M' -> requirePublishedM(command, lineNumber = null, consumer = "Nozzle Path")
            'T' -> require(command.code == 0) {
                "Nozzle Path cannot safely display tool change ${command.opcode}"
            }
            'C' -> {
                require(command.code == 29) {
                    "Nozzle Path cannot safely display ${command.opcode}; only C29 (AML) is allowed"
                }
                requireC29Arguments(command, "Nozzle Path")
            }
            else -> error("Nozzle Path cannot safely display command family ${command.family}")
        }
    }

    fun requireSafeCustomEvent(command: GcodeCommand.Parsed) {
        requireUnframed(command, "Custom G-code")
        require(command.opcode in SAFE_CUSTOM_EVENT_COMMANDS) {
            "Custom G-code command ${command.opcode} is not in the state-neutral safety allowlist"
        }
    }

    fun speedFactor(command: GcodeCommand.Parsed): Double? {
        if (command.opcode != "M220") return null
        val percent = requireNotNull(command.value('S')) { "M220 requires an S percentage" }
        require(command.hasOnlyParameters(setOf('S', 'T'))) { "M220 contains unsupported parameters" }
        require(percent.isFinite() && percent in 1.0..999.0) { "M220 speed factor is outside 1..999%" }
        return percent / 100.0
    }

    private fun requirePublishedM(command: GcodeCommand.Parsed, lineNumber: Int?, consumer: String) {
        val location = lineNumber?.let { " at line $it" }.orEmpty()
        fun only(vararg letters: Char) {
            require(command.hasOnlyParameters(letters.toSet())) {
                "$consumer rejects unsupported ${command.opcode} parameters$location"
            }
        }
        fun bounded(letter: Char, minimum: Double, maximum: Double, required: Boolean = false) {
            val value = command.value(letter)
            if (required) requireNotNull(value) { "$consumer requires ${command.opcode} $letter$location" }
            if (value != null) require(value.isFinite() && value in minimum..maximum) {
                "$consumer rejects ${command.opcode} $letter outside $minimum..$maximum$location"
            }
        }

        when (command.code) {
            0, 1 -> only('P', 'S')
            18, 84 -> only('S', 'X', 'Y', 'Z', 'E')
            25, 77, 82, 83, 107, 117, 118, 240, 400 -> Unit
            27 -> requireReadOnlyArguments(
                command = command,
                consumer = consumer,
                location = location,
                rules = mapOf('C' to ReadOnlyArgumentRule(allowFlag = true)),
            )
            31, 115, 119 -> requireReadOnlyArguments(
                command = command,
                consumer = consumer,
                location = location,
                rules = emptyMap(),
            )
            142 -> {
                // PrusaSlicer 3.x computes the heatbreak cooling target from the
                // filament temperature (M142 S36 style) at the start of a print.
                only('S', 'R')
                bounded('S', 0.0, 500.0)
                bounded('R', 0.0, 500.0)
            }
            486 -> {
                // PrusaSlicer 3.x emits M486 S0/S-1 (cancel-object tracking
                // enable/disable) and M486 A<object name> (register object).
                only('S', 'A')
                bounded('S', -1.0, 1.0)
            }
            74 -> {
                // PrusaSlicer 3.x per-layer marker. W is the extruded filament
                // WEIGHT in grams (GCode.cpp computes w = v * density * 0.001 and
                // the shipped prusa3-base.json sends [extruded_weight_total]), not
                // a percentage, so the bound has to clear any printable spool.
                only('W')
                bounded('W', 0.0, 100_000.0)
            }
            73 -> {
                // PrusaSlicer 3.x emits both M73 P/R (progress %, remaining
                // minutes) and M73 Q/S (alternate progress variant).
                only('P', 'Q', 'R', 'S')
                bounded('P', 0.0, 100.0)
                bounded('Q', 0.0, 100.0)
                bounded('R', 0.0, 1_000_000.0)
                bounded('S', 0.0, 1_000_000.0)
            }
            104, 109 -> {
                only('S', 'R', 'T')
                bounded('S', 0.0, 500.0)
                bounded('R', 0.0, 500.0)
                bounded('T', 0.0, 32.0)
            }
            105 -> requireReadOnlyArguments(
                command = command,
                consumer = consumer,
                location = location,
                rules = mapOf(
                    'R' to ReadOnlyArgumentRule(allowFlag = true),
                    'T' to ReadOnlyArgumentRule(
                        allowFlag = false,
                        numericRange = 0.0..32.0,
                        integerOnly = true,
                    ),
                ),
            )
            106 -> {
                only('P', 'S')
                bounded('P', 0.0, 255.0)
                bounded('S', 0.0, 255.0)
            }
            114 -> requireReadOnlyArguments(
                command = command,
                consumer = consumer,
                location = location,
                rules = setOf('D', 'E', 'R').associateWith {
                    ReadOnlyArgumentRule(allowFlag = true)
                },
            )
            140, 190 -> {
                only('S', 'R')
                bounded('S', 0.0, 200.0)
                bounded('R', 0.0, 200.0)
            }
            204 -> {
                only('P', 'R', 'S', 'T')
                command.parameterLetters.forEach { bounded(it, 0.0, 100_000.0) }
            }
            205 -> {
                only('B', 'E', 'J', 'S', 'T', 'X', 'Y', 'Z')
                command.parameterLetters.forEach { letter ->
                    bounded(letter, 0.0, if (letter == 'J') 1.0 else 100_000.0)
                }
            }
             207 -> {
                only('F', 'R', 'S', 'T', 'W', 'Z')
                command.parameterLetters.forEach { bounded(it, 0.0, 100_000.0) }
            }
            201, 203 -> {
                only('X', 'Y', 'Z', 'E')
                command.parameterLetters.forEach { bounded(it, 0.0, 100_000.0) }
            }
            208 -> {
                only('F', 'R', 'S')
                command.parameterLetters.forEach { bounded(it, 0.0, 100_000.0) }
            }
            220, 221 -> {
                only('S', 'T')
                bounded('S', 1.0, 999.0, required = true)
                bounded('T', 0.0, 32.0)
            }
            300 -> {
                only('P', 'S')
                bounded('P', 0.0, 600_000.0)
                bounded('S', 0.0, 100_000.0)
            }
            420 -> {
                only('S', 'Z')
                bounded('S', 0.0, 1.0)
                bounded('Z', 0.0, 100.0)
            }
            503 -> requireReadOnlyArguments(
                command = command,
                consumer = consumer,
                location = location,
                rules = mapOf(
                    'S' to ReadOnlyArgumentRule(
                        allowFlag = true,
                        numericRange = 0.0..1.0,
                        integerOnly = true,
                    ),
                ),
            )
            572 -> {
                only('D', 'S', 'T')
                bounded('D', 0.0, 255.0)
                bounded('S', 0.0, 10.0, required = true)
                bounded('T', 0.0, 32.0)
            }
            600 -> {
                only('B', 'E', 'L', 'R', 'U', 'X', 'Y', 'Z')
                command.parameterLetters.forEach { bounded(it, -1_000.0, 1_000.0) }
            }
            900 -> {
                only('K', 'L', 'S', 'T')
                bounded('K', 0.0, 10.0)
                bounded('L', 0.0, 10.0)
                bounded('S', 0.0, 10.0)
                bounded('T', 0.0, 32.0)
            }
            981 -> {
                // Spaghetti-detector toggle, emitted by the Creality start G-code the app
                // imports ("M981 S1 P20000 ;open spaghetti detector") and by the machine
                // profiles the OrcaSlicer engine ships. It is a runtime toggle the machine owns:
                // no motion, no geometry and no persistent calibration changes, firmware that
                // knows it enables its detector, and firmware that does not ignores an unknown
                // M-code - which is why it is allowed instead of refused.
                only('S', 'P')
                bounded('S', 0.0, 1.0)
                bounded('P', 0.0, 600_000.0)
            }
            else -> error(
                "$consumer rejects unmodeled persistent or machine-control command ${command.opcode}$location; " +
                    "the G-code was not made available for export",
            )
        }
    }

    private fun requireReadOnlyArguments(
        command: GcodeCommand.Parsed,
        consumer: String,
        location: String,
        rules: Map<Char, ReadOnlyArgumentRule>,
    ) {
        val compact = command.rawArguments.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return

        val seen = hashSetOf<Char>()
        var offset = 0
        while (offset < compact.length) {
            val match = READ_ONLY_ARGUMENT.find(compact, offset)
                ?.takeIf { it.range.first == offset }
                ?: error("$consumer rejects malformed ${command.opcode} arguments$location")
            val letter = match.groupValues[1].single().uppercaseChar()
            val rule = rules[letter]
                ?: error("$consumer rejects unsupported ${command.opcode} argument $letter$location")
            require(seen.add(letter)) {
                "$consumer rejects duplicate ${command.opcode} argument $letter$location"
            }

            val rawValue = match.groupValues[2]
            if (rawValue.isEmpty()) {
                require(rule.allowFlag) {
                    "$consumer requires a value for ${command.opcode} $letter$location"
                }
            } else {
                val range = requireNotNull(rule.numericRange) {
                    "$consumer rejects a value for flag-only ${command.opcode} $letter$location"
                }
                val value = rawValue.toDoubleOrNull()
                require(value != null && value.isFinite() && value in range) {
                    "$consumer rejects ${command.opcode} $letter outside ${range.start}..${range.endInclusive}$location"
                }
                if (rule.integerOnly) {
                    require(value == value.toInt().toDouble()) {
                        "$consumer requires an integer ${command.opcode} $letter$location"
                    }
                }
            }
            offset = match.range.last + 1
        }
    }

    private fun requireUnframed(command: GcodeCommand.Parsed, consumer: String) {
        require(!command.hasLineNumber && !command.hasChecksum) {
            "$consumer does not accept line-number or checksum framing; re-slice unframed G-code"
        }
    }

    private val READ_ONLY_ARGUMENT = Regex(
        "([A-Za-z])([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))?",
    )
    private val KLIPPER_PRESSURE_ADVANCE = Regex(
        "^SET_PRESSURE_ADVANCE\\s+ADVANCE=[+]?(\\d+(?:\\.\\d*)?|\\.\\d+)$",
        RegexOption.IGNORE_CASE,
    )
    private val KLIPPER_RETRACTION = Regex(
        "^SET_RETRACTION\\s+RETRACT_LENGTH=[+]?(\\d+(?:\\.\\d*)?|\\.\\d+)\\s+" +
            "RETRACT_SPEED=[+]?(\\d+(?:\\.\\d*)?|\\.\\d+)$",
        RegexOption.IGNORE_CASE,
    )
    private const val KLIPPER_ADVANCE_MIN = 0.0
    private const val KLIPPER_ADVANCE_MAX = 100.0
    private const val KLIPPER_RETRACT_LENGTH_MIN = 0.0
    private const val KLIPPER_RETRACT_LENGTH_MAX = 200.0
    private const val KLIPPER_RETRACT_SPEED_MIN = 0.0
    private const val KLIPPER_RETRACT_SPEED_MAX = 500.0
}
