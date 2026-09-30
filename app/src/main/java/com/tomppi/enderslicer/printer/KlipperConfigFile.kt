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
        // Highest index first, recomputed after each removal: a snapshot of indices goes
        // stale the moment the first section is cleared out of the list those indices describe.
        while (true) {
            val next = additionalMcuSections(lines).maxByOrNull { it.first } ?: break
            removeSection(lines, next.first)
            changes += "removed ${next.second}: there is no second board for the app to reach"
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
            val known = availableFiles.contains(name)
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
                // Either separator: klippy reads both, and the configuration this app ships
                // writes "shaper_type_x = mzv".
                val matches = (header + 1 until end).filter { index ->
                    val name = body[index].trim().substringBefore(':').substringBefore('=').trim()
                    name.equals(key, ignoreCase = true)
                }
                if (matches.isEmpty()) {
                    body.add(end, "$key = $value")
                    end++
                } else {
                    //
                    // One line, carrying the new value - and the extras removed.
                    //
                    // An earlier version of this only understood "name:", so every save
                    // appended a second copy of each key. Replacing the first and leaving the
                    // rest would be worse than it looks: klippy reads the LAST value, so the
                    // stale duplicate further down the section is the one that would win, and
                    // a saved measurement would appear to do nothing.
                    //
                    body[matches.first()] = "$key = $value"
                    matches.drop(1).sortedDescending().forEach { index ->
                        body.removeAt(index)
                        end--
                    }
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
     * The printer-describing settings the two configurations disagree about.
     *
     * Two configurations of one printer have to differ in how each host reaches it, so most
     * of what stands between them is not drift at all. Only two things are compared: klippy's
     * saved block, which is everything the printer has saved about itself, and the handful of
     * body options that describe the printer rather than the host ([BODY_OPTIONS]). A line
     * that says how a host reaches the machine - the serial port, the gcode directory, an
     * include, a second micro-controller - differs because the hosts differ, and is neither
     * reported here nor moved by [withSynced].
     *
     * A setting only one of the two carries is not reported either: a copy moves a value, and
     * writing one where the other host has never had it is not a copy of anything.
     */
    fun differences(a: String, b: String): List<Difference> {
        val there = settingsOf(b).associateBy { it.key }
        return settingsOf(a).mapNotNull { setting ->
            val other = there[setting.key] ?: return@mapNotNull null
            if (sameValue(setting.value, other.value)) return@mapNotNull null
            Difference(
                section = setting.section,
                option = setting.option,
                valueA = normalize(setting.value),
                valueB = normalize(other.value),
            )
        }
    }

    /**
     * [target] with every printer setting that differs replaced by [source]'s value.
     *
     * Only values move. Every other line - the serial port, the restart method, the gcode
     * directory, the pins, the kinematics, the [mcu] sections, the includes - is handed back
     * exactly as it was, because that is what makes one of these configurations the phone's
     * and the other a computer's.
     *
     * A value goes back where it was read from: into klippy's block for everything in it,
     * into the body for the options the body may carry. That is the place klippy reads the
     * value from, so the next start of either host acts on the copy.
     */
    fun withSynced(target: String, source: String): String {
        val from = settingsOf(source).associateBy { it.key }
        val edits = settingsOf(target).mapNotNull { setting ->
            val other = from[setting.key] ?: return@mapNotNull null
            if (sameValue(setting.value, other.value)) null else setting to other.value
        }
        if (edits.isEmpty()) return target
        val lines = target.split("\n").toMutableList()
        // Rewritten from the last one up, so that a value running over several lines - a bed
        // mesh grid - does not move the lines the edits above it are counted from.
        edits.sortedByDescending { it.first.first }.forEach { (setting, value) ->
            lines.subList(setting.first, setting.last + 1).clear()
            lines.addAll(setting.first, setting.rewritten(value))
        }
        return lines.joinToString("\n")
    }

    /**
     * One setting the two configurations disagree about.
     *
     * [valueA] is the value in the first configuration compared and [valueB] in the second, so
     * a screen showing one host's value against the other's knows which is which by the order
     * it passed them in. Both are on one line, the way that screen shows them.
     */
    data class Difference(
        val section: String,
        val option: String,
        val valueA: String,
        val valueB: String,
    )

    /**
     * The body options a sync may move, by section.
     *
     * Everything else in the body belongs to the host that wrote it: the serial port, the
     * restart method, the gcode directory, the pins, the kinematics, every [mcu] section and
     * every include. klippy's saved block needs no list, because all of it may move - every
     * line in it is something the printer saved about itself.
     */
    private val BODY_OPTIONS: Map<String, Set<String>> = mapOf(
        "extruder" to setOf(
            "rotation_distance",
            "pressure_advance",
            "pressure_advance_smooth_time",
        ),
        //
        // Shaping goes into the body when this app writes it - the Shaping screen edits the file
        // - and into the saved block when klippy saves a SET_INPUT_SHAPER. The block is read whole
        // either way, so these six are here for the pair where a host keeps them in the body:
        // without them a measured frequency was not offered to the other host at all, and that is
        // the calibration most worth copying between two hosts of one printer.
        //
        "input_shaper" to setOf(
            "shaper_type_x",
            "shaper_freq_x",
            "damping_ratio_x",
            "shaper_type_y",
            "shaper_freq_y",
            "damping_ratio_y",
        ),
        "printer" to setOf(
            "max_accel",
            "max_velocity",
            "square_corner_velocity",
            "max_z_velocity",
            "max_z_accel",
            "minimum_cruise_ratio",
        ),
    )

    /** One setting as the file has it: where it is, what it says, and how it is written. */
    private data class Setting(
        val section: String,
        val option: String,
        val value: String,
        /** The first line up to the value's own first character, kept so a write can reuse it. */
        val prefix: String,
        /** True when klippy's saved block carries it, false when the hand-written body does. */
        val saved: Boolean,
        val first: Int,
        val last: Int,
    ) {
        val key: String get() = section + "\u0000" + option

        /** The lines that carry [value] here, in this setting's own shape. */
        fun rewritten(value: String): List<String> {
            val parts = value.split('\n')
            // A saved value that runs over several lines carries the marker on every one of
            // them, exactly as klippy writes it ("#*# " and then the indented continuation).
            val continuation = if (saved) SAVED_PREFIX + " " else ""
            return listOf(prefix + parts.first()) + parts.drop(1).map { continuation + it }
        }
    }

    /** An option's name, its value, and the part of its line that comes before the value. */
    private data class OptionLine(val name: String, val value: String, val prefix: String)

    /**
     * Every setting a sync may move, as the file has it.
     *
     * The body and klippy's block are read together because klippy reads them together: a pair
     * the body defines wins over the block's copy of it, whose own copy is commented out when
     * the file is read (configfile.py:279-300). For the pairs a sync may move, then, the body's
     * value is the one taken where the body has one, and the block's is used only where the
     * body says nothing - which is exactly what klippy does with the two of them.
     *
     * A body option outside [BODY_OPTIONS] is not read at all. It is not a value a sync may
     * move, so it is not one a sync compares either: where it duplicates something in the
     * block, writing the block is what klippy's own reader would call the same setting, and
     * that is the copy that is offered rather than the body line the whitelist does not name.
     */
    private fun settingsOf(text: String): List<Setting> {
        val found = mutableListOf<Setting>()
        var section = ""
        var saved = false
        var continuation = -1
        text.split("\n").forEachIndexed { index, raw ->
            if (!saved && raw.contains(SAVED_MARKER)) {
                // Everything from the marker down is klippy's own saved values.
                saved = true
                section = ""
                continuation = -1
                return@forEachIndexed
            }
            val stripped = if (saved) savedLine(raw) else raw
            val content = stripped.trim()
            // klippy reads nothing from a commented line, which is what an option it has
            // saved is written as.
            if (content.isEmpty() || content.startsWith("#") || content.startsWith(";")) {
                return@forEachIndexed
            }
            header(content)?.let { name ->
                section = name
                continuation = -1
                return@forEachIndexed
            }
            if (section.isEmpty()) return@forEachIndexed
            // A value that runs over several lines - a bed mesh grid - is written on the lines
            // under its option, indented, and klippy joins them back together when it reads it.
            if (continuation >= 0 && stripped.firstOrNull()?.isWhitespace() == true) {
                val setting = found[continuation]
                found[continuation] =
                    setting.copy(value = setting.value + "\n" + stripped, last = index)
                return@forEachIndexed
            }
            val option = optionLine(raw, saved) ?: return@forEachIndexed
            if (!saved && option.name !in BODY_OPTIONS[section].orEmpty()) {
                continuation = -1
                return@forEachIndexed
            }
            found += Setting(section, option.name, option.value, option.prefix, saved, index, index)
            continuation = found.lastIndex
        }
        // A pair klippy reads twice in the same place is the last one of them; a pair the body
        // and the block both carry is the body's.
        val settings = LinkedHashMap<String, Setting>()
        found.forEach { setting ->
            val seen = settings[setting.key]
            if (seen == null || seen.saved == setting.saved) settings[setting.key] = setting
        }
        return settings.values.toList()
    }

    /**
     * An option as klippy reads one.
     *
     * The name runs to the first ":" or "=" (configfile.py:178) whatever the line says after
     * it, and a "#" or ";" begins a comment rather than a value. What comes before the value is
     * kept so that a write can leave the indentation and the separator as they were.
     */
    private fun optionLine(raw: String, saved: Boolean): OptionLine? {
        val text = (if (saved) savedLine(raw) else raw)
            .substringBefore('#')
            .substringBefore(';')
        val at = text.indexOfFirst { it == ':' || it == '=' }
        if (at <= 0) return null
        val name = text.substring(0, at).trim().lowercase()
        if (name.isEmpty()) return null
        var start = at + 1
        while (start < text.length && text[start].isWhitespace()) start++
        return OptionLine(
            name = name,
            value = text.substring(start).trimEnd(),
            prefix = (if (saved) SAVED_PREFIX + " " else "") + text.substring(0, start),
        )
    }

    /**
     * One line of klippy's saved block, as klippy reads it back.
     *
     * configfile.py:274 takes exactly four characters off - "#*# " - so the space after the
     * marker is part of it. Leaving it on would make every line of the block look indented,
     * and a bed mesh grid is the only thing that is.
     */
    private fun savedLine(raw: String): String = raw.removePrefix(SAVED_PREFIX).removePrefix(" ")

    /** The name inside a section header, or null when the line is not one. */
    private fun header(line: String): String? {
        val text = line.substringBefore('#').trim()
        if (text.length < 3 || text.first() != '[' || text.last() != ']') return null
        return text.substring(1, text.length - 1).trim().lowercase()
    }

    /** Whether two written values say the same thing, the whitespace between them aside. */
    private fun sameValue(a: String, b: String): Boolean = normalize(a) == normalize(b)

    /** A value on one line, the way a screen shows it. */
    private fun normalize(value: String): String = value.trim().replace(WHITESPACE, " ")

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

    /** The runs of whitespace between the parts of a value, which say nothing about it. */
    private val WHITESPACE = Regex("\\s+")
}
