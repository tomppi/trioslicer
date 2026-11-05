package com.tomppi.enderslicer.printer

/**
 * Naming rules for files handed to the host's virtual SD card.
 *
 * Kept apart from the repository so they can be tested: a name with a slash in it
 * would be read by klippy as a subdirectory, and one starting with a dot would be
 * hidden from the file list the printer reports back.
 */
internal object KlipperPrint {
    /** The directory under filesDir that [virtual_sdcard] is pointed at. */
    const val GCODE_DIR = "gcodes"

    /**
     * What the printer calls a file this app hands it.
     *
     * The suffix check is case-sensitive because klippy's is: its virtual_sdcard only
     * opens files whose extension is exactly "gcode", so a name ending in ".GCODE" is
     * one it answers "Unable to open file" to. Such a name gets the real suffix rather
     * than being passed through as if it already had one.
     */
    fun fileName(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = base.map { c ->
            if (c.isLetterOrDigit() || c in "-_. ") c else '_'
        }.joinToString("").trimStart('.')
        val named = cleaned.ifBlank { "print" }
        return if (named.endsWith(".gcode")) named else "$named.gcode"
    }
}
