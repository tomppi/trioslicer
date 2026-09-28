package com.tomppi.enderslicer.printer

/**
 * The printer's configuration file, and the part of it klippy writes itself.
 *
 * Three things edit this file and they have to agree. The app ships a default in its
 * assets, a person may edit the file that is running, and klippy appends the values it
 * saves - a PID calibration, a Z offset, a bed mesh profile - to a block at the end
 * under a marker that tells the reader to leave it alone.
 *
 * That block is the reason this is not a string copy. Klipper's own SAVE_CONFIG splits
 * the file at exactly that marker, keeps the part above it as the configuration and
 * re-parses the part below as the saved values, so a new default can be applied without
 * throwing away a calibration: take the file as it is now, keep its saved block, and put
 * it back together with the new default above.
 *
 * The other half of it is forDevice: a configuration written for a host names a serial
 * port by device path and a gcode directory under someone's home, and neither exists on
 * a phone. What a person would otherwise fix by hand before building the app is fixed
 * here instead, and said out loud.
 */
internal object KlipperConfigFile {
    /** The two paths this device has to supply, which the shipped default leaves blank. */
    const val SERIAL_PLACEHOLDER = "__SERIAL__"
    const val GCODES_PLACEHOLDER = "__GCODES__"

    /** The line klippy puts above the values it saves, verbatim from configfile.py. */
    const val SAVED_MARKER = "#*# <---------------------- SAVE_CONFIG ---------------------->"

    /** What klippy prefixes every line of its saved block with. */
    private const val SAVED_PREFIX = "#*#"

    /** The shipped default with the paths filled in. */
    fun resolve(template: String, serialPath: String, gcodeDirectory: String): String =
        template.replace(SERIAL_PLACEHOLDER, serialPath)
            .replace(GCODES_PLACEHOLDER, gcodeDirectory)

    /**
     * Everything klippy has saved, from the marker down, or "" when it has saved nothing.
     *
     * Kept exactly as it was written, prefixes and all: klippy is the only thing that
     * reads it, and it is the only thing that should ever produce it.
     */
    fun savedBlock(text: String): String {
        val marker = text.indexOf(SAVED_MARKER)
        if (marker < 0) return ""
        val lineStart = text.lastIndexOf('\n', marker - 1)
        return text.substring(if (lineStart < 0) marker else lineStart + 1)
    }

    /** The configuration as a person wrote it: everything above that block. */
    fun withoutSavedBlock(text: String): String {
        val marker = text.indexOf(SAVED_MARKER)
        return if (marker < 0) text else text.substring(0, marker)
    }

    /**
     * Is this configuration still the one the app ships?
     *
     * Compared without the saved block, because that part is *meant* to differ: it is
     * what the printer has learned about itself. What is being asked is whether the
     * hand-written configuration - pins, kinematics, limits - has moved on.
     */
    fun differsFromShipped(running: String, shipped: String): Boolean =
        withoutAppInclude(withoutSavedBlock(running)).trim() !=
        withoutAppInclude(withoutSavedBlock(shipped)).trim()

    /**
     * The configuration without the app's own include line.
     *
     * The printer's file carries "[include app.cfg]" and the reference copy the app keeps
     * does not, so comparing them directly made every configuration look edited - the Machine
     * screen told the user their configuration was not the one this version ships even when
     * they had never touched it. The line is the app's, and it is not evidence of an edit.
     */
    fun withoutAppInclude(text: String): String = text.lines()
        .filterNot { line ->
            val trimmed = line.trim()
            trimmed.startsWith("[include", ignoreCase = true) &&
                trimmed.contains("app.cfg", ignoreCase = true)
        }
        .joinToString("\n")

    /**
     * The shipped default, with the values the printer has already saved carried over.
     *
     * Carried over is the word that was wrong here. klippy loads the file and the autosave
     * block together, and where both define the same option the *file* wins - the block's
     * copy is commented out at load (configfile.py:279-300), which is exactly what SAVE_CONFIG
     * relies on when it writes the block and comments the option it just saved (configfile.py:372).
     * So a pristine body spliced in front of a saved block silently reverts every option the
     * two share: the probe's Z offset, the PID terms, the shaper frequencies - the values this
     * function exists to preserve, and the ones the dialog promises are kept.
     *
     * The body's copies are therefore commented out here, as klippy comments them when it
     * saves. Everything else in the body is left exactly as shipped.
     */
    fun withSavedValues(shipped: String, existing: String): String {
        val saved = savedBlock(existing)
        if (saved.isBlank()) return shipped
        val savedOptions = savedOptionKeys(saved)
        val body = commentOutSavedOptions(withoutSavedBlock(shipped), savedOptions)
        return body.trimEnd() + "\n\n" + saved
    }

/**
     * The section-and-option pairs a saved block defines, as "section\u0000option".
     *
     * Read the way klippy reads the block: section headers in brackets, options as key: value
     * or key = value, and klippy's own "#*#" prefix stripped from the line first.
     */
    private fun savedOptionKeys(saved: String): Set<String> {
        val keys = mutableSetOf<String>()
        var section = ""
        for (raw in saved.lineSequence()) {
            val line = raw.removePrefix(SAVED_PREFIX).trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim().lowercase()
                continue
            }
            if (section.isEmpty() || line.isEmpty() || line.startsWith("#")) continue
            val option = line.substringBefore(':').substringBefore('=').trim().lowercase()
            if (option.isNotEmpty() && line.contains(':') || line.contains('=')) {
                keys += section + "\u0000" + option
            }
        }
        return keys
    }

    /** Comment out those options in the body, leaving every other line untouched. */
    private fun commentOutSavedOptions(body: String, savedOptions: Set<String>): String {
        if (savedOptions.isEmpty()) return body
        var section = ""
        return body.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                section = trimmed.substring(1, trimmed.length - 1).trim().lowercase()
                return@joinToString line
            }
            if (section.isEmpty() || trimmed.isEmpty() || trimmed.startsWith("#")) {
                return@joinToString line
            }
            val option = trimmed.substringBefore(':').substringBefore('=').trim().lowercase()
            if (option.isNotEmpty() && (section + "\u0000" + option) in savedOptions) {
                line.replaceFirst(trimmed, "# " + trimmed)
            } else {
                line
            }
        }
    }

    /**
     * A configuration this device can run, and what had to be done to it.
     *
     * [changes] are things a user should be told happened to the file they brought, and
     * [warnings] are things in it that this device cannot supply - each one naming what
     * will not work, because a button that silently does nothing is worse than one that
     * is not there.
     */
    data class DeviceRewrite(
        val text: String,
        val changes: List<String>,
        val warnings: List<String>,
    )

    /**
     * The app's own parts of a configuration, put into one a user brought.
     *
     * Everything else - pins, kinematics, rotation distances, probe offsets, bed size,
     * macros, the board's own settings - is the printer's, and is left exactly as it was
     * written. That is the whole point: this is their machine's description, and the app
     * only knows about the parts of it that are the phone rather than the printer.
     *
     * [availableFiles] is what else the user brought with them, by file name, so that an
     * [include] that came along can be left alone and one that did not can be commented
     * out with a warning rather than left to stop klippy from starting.
     */
    fun forDevice(
        text: String,
        serialPath: String,
        gcodeDirectory: String,
        availableFiles: Set<String> = emptySet(),
    ): DeviceRewrite {
        val changes = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val lines = withoutSavedBlock(text).lines().toMutableList()

        // The serial port. klippy opens whatever this says, and there is no
        // /dev/serial/by-id on a phone: it is the pty the app bridges the printer through.
        val serialAt = indexOfOption(lines, "mcu", SERIAL_OPTION)
        if (serialAt >= 0) {
            lines[serialAt] = lines[serialAt].takeWhile { it.isWhitespace() } + "serial: $serialPath"
            changes += "pointed the micro-controller at this app's printer connection"
        } else if (lines.any { CANBUS_OPTION.containsMatchIn(it.trim()) }) {
            warnings += "this configuration reaches its board over CAN, which the app cannot bridge; " +
                "the app drives a board on USB"
        } else if (lines.any { it.trim().equals("[mcu]", ignoreCase = true) }) {
            warnings += "the [mcu] section names no serial port, so there is nothing for the app to " +
                "open - the printer will not start"
        } else {
            warnings += "there is no [mcu] section, so this does not look like a printer.cfg"
        }

        // A second micro-controller is a Linux host board or a CAN toolhead board. There
        // is neither on a phone, and klippy refuses to start while a section names a board
        // it cannot reach - so it goes, and the screen says it went.
        additionalMcuSections(lines).forEach { (header, section) ->
            removeSection(lines, header)
            changes += "removed $section: there is no second board for the app to reach"
        }

        // A board on this connection is reset by command, not by DTR.
        //
        // klippy really does read restart_method here: only /dev/rpmsg_* and
        // /tmp/klipper_host_* are treated as something other than a serial port, and this
        // app's path is a symlink under its own files directory (mcu.py:569-579). With the
        // option absent klippy falls through to the arduino method - toggling DTR - and the
        // bridge moves bytes and nothing else, so the toggle never reaches the board and
        // FIRMWARE_RESTART ends in "Failed automated reset of MCU". 'command' asks the board
        // to reset itself, which is what the printer's own configuration used.
        val restartAt = indexOfOption(lines, "mcu", RESTART_OPTION)
        if (restartAt >= 0) {
            val current = lines[restartAt].substringAfter(':').substringAfter('=').trim()
            if (!current.equals("command", ignoreCase = true)) {
                lines[restartAt] = lines[restartAt].takeWhile { it.isWhitespace() } +
                    "restart_method: command"
                changes += "reset the board by command, which is the only reset this " +
                    "connection can deliver"
            }
        } else if (serialAt >= 0) {
            // A CAN board needs nothing: with no baud klippy uses the command method already.
            lines.add(serialAt + 1, "restart_method: command")
            changes += "reset the board by command, which is the only reset this connection " +
                "can deliver"
        }

        // baud is removed because it means nothing on this connection: the printer is reached
        // through a pseudo-terminal, where there is no line rate to set. (klippy does read the
        // option here - the comment that used to stand in this place claimed it refused the
        // option outright, which is not so - so it is harmless either way, and removing it
        // keeps a brought configuration from carrying a number that describes nothing.)
        val baudAt = indexOfOption(lines, "mcu", BAUD_OPTION)
        if (baudAt >= 0) {
            lines.removeAt(baudAt)
            changes += "removed baud: the app hands klippy a pty, which has no baud rate"
        }

        // The virtual SD card is where this app puts the file it prints, so it has to be
        // the app's own directory - and a configuration without the section cannot print a
        // file from the Files screen at all.
        val pathAt = indexOfOption(lines, "virtual_sdcard", SDCARD_PATH)
        if (pathAt >= 0) {
            lines[pathAt] = lines[pathAt].takeWhile { it.isWhitespace() } + "path: $gcodeDirectory"
            changes += "pointed the virtual SD card at this app's files"
        } else {
            val end = endOfSection(lines, "virtual_sdcard")
            if (end < lines.size) {
                lines.add(end, "path: $gcodeDirectory")
            } else {
                lines += listOf("", "[virtual_sdcard]", "path: $gcodeDirectory")
            }
            changes += "added [virtual_sdcard], so the app can print the files it slices"
        }

        // An include is resolved relative to the file that names it, and klippy will not
        // start if it is not there. One that came along is left alone; one that did not is
        // commented out here, where the user is being told about it.
        includesOf(text).forEach { name ->
            val known = availableFiles.any { it.equals(name, ignoreCase = true) }
            if (known) return@forEach
            val at = lines.indexOfFirst { INCLUDE.matches(it.trim()) && INCLUDE.find(it.trim())?.groupValues?.get(1)?.trim() == name }
            if (at >= 0) lines[at] = "# " + lines[at].trim() + "   # not imported: the app has no such file"
            warnings += "this configuration includes $name, which was not imported; the include has " +
                "been commented out"
        }

        // Sections and macros this app's own buttons use. Named rather than added: a
        // missing macro is the printer's business, and inventing one would change how it
        // pauses or levels.
        if (lines.none { it.trim().equals("[pause_resume]", ignoreCase = true) }) {
            warnings += "no [pause_resume] section, so the pause and resume buttons will not work"
        }
        if (lines.none { it.trim().equals("[exclude_object]", ignoreCase = true) }) {
            warnings += "no [exclude_object] section, so leaving an object out of a print will not work"
        }
        listOf("PAUSE", "RESUME", "CANCEL_PRINT").forEach { macro ->
            if (lines.none { it.trim().equals("[gcode_macro $macro]", ignoreCase = true) }) {
                warnings += "no $macro macro, so that button will report an unknown command"
            }
        }

        return DeviceRewrite(lines.joinToString("\n").trimEnd() + "\n", changes, warnings)
    }

    /**
     * The configuration, with an include of the app's own file in it.
     *
     * Added above the saved block, never inside it: everything below that marker belongs
     * to klippy and is re-parsed as its saved values, so a line put there would be read
     * as one and the block it was put in would be rejected. Unchanged when the include
     * is already there, so this can run at every start.
     */
    fun withAppInclude(text: String, name: String = "app.cfg"): String {
        val include = "[include " + name + "]"
        if (text.lines().any { it.trim().equals(include, ignoreCase = true) }) return text
        val marker = text.indexOf(SAVED_MARKER)
        val body = if (marker < 0) text.trimEnd() + "\n" else withoutSavedBlock(text).trimEnd() + "\n"
        val saved = savedBlock(text)
        return body + "\n" + include + "\n" + if (saved.isBlank()) "" else "\n" + saved
    }

    /**
     * Write input shaping into a configuration, and answer with the result.
     *
     * Shaping is the one motion setting that cannot be saved from the printer side:
     * SET_INPUT_SHAPER changes what is running and nothing else, and SAVE_CONFIG only
     * writes what klippy marked as changed. So a value that is meant to survive a
     * restart is written here, into the [input_shaper] section - the same treatment the
     * app gives a brought configuration's serial port, and for the same reason: it is
     * the part of the file this app is being asked to change.
     */
    fun withInputShaper(text: String, settings: List<ShaperSetting>): String {
        val lines = text.lines().toMutableList()
        val saved = savedBlock(text)
        val body = withoutSavedBlock(text).lines().toMutableList()
        var header = body.indexOfFirst { it.trim().equals("[input_shaper]", ignoreCase = true) }
        if (header < 0) {
            while (body.isNotEmpty() && body.last().isBlank()) body.removeAt(body.size - 1)
            body += listOf("", "[input_shaper]")
            header = body.size - 1
        }
        var end = header + 1
        while (end < body.size && !body[end].trim().startsWith("[")) end++

        settings.forEach { setting ->
            val axis = setting.axis.lowercase()
            val wanted = listOf(
                "shaper_type_$axis" to setting.type.lowercase(),
                "shaper_freq_$axis" to number(setting.frequency, 1),
            ) + listOfNotNull(
                // Bounded here as well as in the screen: klippy divides by sqrt(1 - zeta^2),
                // so a saved 1.0 is a configuration the host refuses to start from.
                setting.dampingRatio
                    ?.takeIf { it > 0.0 && it <= MAX_DAMPING_RATIO }
                    ?.let { "damping_ratio_$axis" to number(it, 3) },
            )
            wanted.forEach { (key, value) ->
                val at = (header + 1 until end).firstOrNull { index ->
                    // Either separator: klippy reads both, the configuration this app ships
                    // writes "shaper_type_x = mzv", and an earlier version of this only
                    // understood "name:" - so every save appended a second copy of each key.
                    // klippy takes the last one, so the value was right and the file grew by
                    // four lines a save, with the stale line first for anyone reading it.
                    val name = body[index].trim().substringBefore(':').substringBefore('=').trim()
                    name.equals(key, ignoreCase = true)
                }
                if (at != null) {
                    body[at] = "$key = $value"
                } else {
                    body.add(end, "$key = $value")
                    end++
                }
            }
        }

        val rebuilt = body.joinToString("\n").trimEnd() + "\n"
        return if (saved.isBlank()) rebuilt else rebuilt.trimEnd() + "\n\n" + saved
    }

    /** One axis's shaping, as it is written into the configuration. */
    data class ShaperSetting(
        val axis: String,
        val type: String,
        val frequency: Double,
        val dampingRatio: Double? = null,
    )

    /** The files a configuration includes, by the name it gives them. */
    fun includesOf(text: String): List<String> = text.lines()
        .mapNotNull { INCLUDE.matchEntire(it.trim())?.groupValues?.get(1)?.trim() }
        .filter { it.isNotEmpty() }

    /** The first option in a named section that matches, or -1. */
    private fun indexOfOption(lines: List<String>, section: String, option: Regex): Int {
        var inside = false
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) {
                inside = trimmed.equals("[$section]", ignoreCase = true)
                return@forEachIndexed
            }
            // A non-local return from an inline lambda, which is what makes this a find.
            if (inside && option.matches(trimmed)) return index
        }
        return -1
    }

    /** Where a named section ends: its last line, or the end of the file. */
    private fun endOfSection(lines: List<String>, section: String): Int {
        var inside = false
        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("[")) {
                if (inside) return index
                inside = trimmed.equals("[$section]", ignoreCase = true)
            }
        }
        return lines.size
    }

    /**
     * The [mcu <name>] sections, by header index and the header as it was written.
     *
     * The header rather than the name, because it is what the user will look for in their
     * file when the screen says it was removed.
     */
    private fun additionalMcuSections(lines: List<String>): List<Pair<Int, String>> =
        lines.withIndex()
            .mapNotNull { (index, line) ->
                val trimmed = line.trim()
                if (EXTRA_MCU.matchEntire(trimmed) == null) null else index to trimmed
            }

    /** Remove a section: its header, its options, and the blank line before the next one. */
    private fun removeSection(lines: MutableList<String>, headerIndex: Int) {
        var end = headerIndex + 1
        while (end < lines.size && !lines[end].trim().startsWith("[")) end++
        // A blank line at either edge goes with it, so the file does not grow a gap.
        while (end > headerIndex && lines[end - 1].isBlank()) end--
        lines.subList(headerIndex, end).clear()
    }

    //
    // klippy delimits an option on either ":" or "=" (configfile.py:178), and a configuration
    // brought from somewhere else may use either. Matching only ":" meant a file saying
    // "serial = /dev/ttyUSB0" was not recognised at all, and the app told the user their [mcu]
    // section named no serial port - which is not what their file said.
    //
    private val SERIAL_OPTION = Regex("serial\\s*[:=].*", RegexOption.IGNORE_CASE)
    private val CANBUS_OPTION = Regex("canbus_uuid\\s*[:=].*", RegexOption.IGNORE_CASE)
    private val RESTART_OPTION = Regex("restart_method\\s*[:=].*", RegexOption.IGNORE_CASE)
    private val BAUD_OPTION = Regex("baud\\s*[:=].*", RegexOption.IGNORE_CASE)
    /**
     * One option in one section, with everything else left as it was.
     *
     * The same shape as the shaping write, and for the same reason: a calibration klippy only
     * holds in memory is gone at the next start unless it is in the file, and SAVE_CONFIG does
     * not write this one. The separator and indentation of the line it replaces are kept, and
     * klippy's saved block stays where klippy put it.
     */
    /**
     * Add whole sections, each one before klippy's saved block, touching nothing else.
     *
     * The configuration is the user's: an existing section is left exactly as it is, however
     * it was written, so adding a macro can never rewrite one that is already there. Sections
     * go in at the end of the body, where a hand-added macro would go, and the saved block
     * stays last, where klippy expects to find it.
     */
    fun withSections(text: String, sections: List<String>): String {
        val additions = sections.filter { it.isNotBlank() }
        if (additions.isEmpty()) return text
        val saved = savedBlock(text)
        val body = withoutSavedBlock(text).trimEnd()
        val rebuilt = body + "\n\n" + additions.joinToString("\n\n") { it.trimEnd() } + "\n"
        return if (saved.isBlank()) rebuilt else rebuilt.trimEnd() + "\n\n" + saved
    }

    fun withOption(text: String, section: String, key: String, value: String): String {
        val saved = savedBlock(text)
        val body = withoutSavedBlock(text).lines().toMutableList()
        val header = body.indexOfFirst { it.trim().equals("[$section]", ignoreCase = true) }
        if (header < 0) return text
        var end = header + 1
        while (end < body.size && !body[end].trim().startsWith("[")) end++
        val at = (header + 1 until end).firstOrNull { index ->
            body[index].trim().substringBefore(':').substringBefore('=').trim()
                .equals(key, ignoreCase = true)
        }
        if (at != null) {
            val indent = body[at].takeWhile { it.isWhitespace() }
            val separator = if (body[at].substringBefore('=').contains(':')) ": " else " = "
            body[at] = indent + key + separator + value
        } else {
            body.add(end, key + ": " + value)
        }
        val rebuilt = body.joinToString("\n").trimEnd() + "\n"
        return if (saved.isBlank()) rebuilt else rebuilt.trimEnd() + "\n\n" + saved
    }

    /**
     * A number as klippy writes one in its own configuration: a dot for the decimal
     * point, whatever language the phone is set to. The same trap as the one that sent
     * G1 Y10,000 to the printer, in a file instead of a command.
     */
    private fun number(value: Double, decimals: Int): String =
        java.lang.String.format(java.util.Locale.ROOT, "%." + decimals + "f", value)

    /** The highest damping ratio klippy can do arithmetic with. */
    private const val MAX_DAMPING_RATIO = 0.9

    private val SDCARD_PATH = Regex("path\\s*[:=].*", RegexOption.IGNORE_CASE)
    private val INCLUDE = Regex("\\[include\\s+(.+?)]", RegexOption.IGNORE_CASE)
    private val EXTRA_MCU = Regex("\\[mcu\\s+(.+?)]", RegexOption.IGNORE_CASE)
}
