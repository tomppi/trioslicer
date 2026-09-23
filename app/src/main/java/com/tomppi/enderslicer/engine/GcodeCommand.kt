package com.tomppi.enderslicer.engine

/**
 * Small parser for command forms Cura, Marlin and user G-code commonly emit.
 * Command identity is canonical: numeric aliases such as G01, g001 and M083
 * become G1 and M83 before any safety decision is made.
 */
internal object GcodeCommand {
    data class Identity(
        val family: Char,
        val code: Int,
    ) {
        init {
            require(family in 'A'..'Z') { "G-code command family must be uppercase ASCII" }
            require(code >= 0) { "G-code command number cannot be negative" }
        }

        val opcode: String get() = "$family$code"
    }

    data class Parsed(
        val identity: Identity,
        private val parameters: Map<Char, Double>,
        val rawArguments: String,
        val hasLineNumber: Boolean,
        val hasChecksum: Boolean,
    ) {
        val opcode: String get() = identity.opcode
        val family: Char get() = identity.family
        val code: Int get() = identity.code
        val parameterLetters: Set<Char> get() = parameters.keys

        fun value(letter: Char): Double? = parameters[letter.uppercaseChar()]

        fun has(letter: Char): Boolean = parameters.containsKey(letter.uppercaseChar())

        fun hasOnlyParameters(allowed: Set<Char>): Boolean = parameters.keys.all(allowed::contains)
    }

    fun parse(rawLine: String): Parsed? {
        var command = rawLine.substringBefore(';').trim()
        if (command.isEmpty()) return null

        // The framed form is "N<line> <command> *<checksum>", and Marlin only
        // reads a checksum after a line number. An asterisk anywhere else is
        // payload: M117 and M118 carry free text, and "Loading * * *" used to be
        // truncated to "Loading" and then rejected as framed G-code.
        val lineNumber = LINE_NUMBER.find(command)?.takeIf { it.range.first == 0 }
        val checksum = if (lineNumber == null) {
            null
        } else {
            CHECKSUM.find(command)?.takeIf { it.range.last == command.lastIndex }
        }
        if (checksum != null) command = command.substring(0, checksum.range.first).trimEnd()
        if (lineNumber != null) {
            command = command.substring(lineNumber.range.last + 1).trimStart()
        }

        val opcodeMatch = OPCODE.find(command)?.takeIf { it.range.first == 0 } ?: return null
        val family = opcodeMatch.groupValues[1].single().uppercaseChar()
        val numericToken = opcodeMatch.groupValues[2]
        if ('.' in numericToken || numericToken.startsWith('-')) return null
        val numericValue = numericToken.removePrefix("+").toLongOrNull() ?: return null
        if (numericValue > Int.MAX_VALUE) return null

        val parameters = linkedMapOf<Char, Double>()
        val remainder = command.substring(opcodeMatch.range.last + 1).trim()
        PARAMETER.findAll(remainder).forEach { match ->
            val letter = match.groupValues[1].single().uppercaseChar()
            val value = match.groupValues[2].toDoubleOrNull()
            if (value != null && value.isFinite()) parameters[letter] = value
        }
        return Parsed(
            identity = Identity(family, numericValue.toInt()),
            parameters = parameters,
            rawArguments = remainder,
            hasLineNumber = lineNumber != null,
            hasChecksum = checksum != null,
        )
    }

    private val LINE_NUMBER = Regex("^[Nn]\\d+\\s*")
    /** The checksum at the end of a framed line: "*" followed by its digits. */
    private val CHECKSUM = Regex("\\*\\s*\\d+$")
    private val OPCODE = Regex("^([A-Za-z])([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))")
    // Scientific notation is intentionally excluded here: in compact G-code,
    // E starts the extrusion parameter (for example Y-2E1.25), not an exponent.
    private val PARAMETER = Regex("([A-Za-z])\\s*([+-]?(?:\\d+(?:\\.\\d*)?|\\.\\d+))")
}
