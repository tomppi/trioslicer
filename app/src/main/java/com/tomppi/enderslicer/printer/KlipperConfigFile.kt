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
 */
internal object KlipperConfigFile {
    /** The two paths this device has to supply, which the shipped default leaves blank. */
    const val SERIAL_PLACEHOLDER = "__SERIAL__"
    const val GCODES_PLACEHOLDER = "__GCODES__"

    /** The line klippy puts above the values it saves, verbatim from configfile.py. */
    const val SAVED_MARKER = "#*# <---------------------- SAVE_CONFIG ---------------------->"

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
        withoutSavedBlock(running).trim() != withoutSavedBlock(shipped).trim()

    /** The shipped default, with the values the printer has already saved carried over. */
    fun withSavedValues(shipped: String, existing: String): String {
        val saved = savedBlock(existing)
        if (saved.isBlank()) return shipped
        return withoutSavedBlock(shipped).trimEnd() + "\n\n" + saved
    }
}
