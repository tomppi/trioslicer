package com.tomppi.enderslicer.printer

/**
 * A Marlin-only command in a sliced file, if its start G-code has one.
 *
 * Not the file's `;FLAVOR:` line, which says which G-code dialect the file is written in rather
 * than which firmware it is for: Orca and Prusa write `;FLAVOR:Marlin` with their Klipper flavour
 * selected, because Klipper reads Marlin G-code. Reading it as the firmware warned about files
 * that had been sliced correctly.
 *
 * What actually goes wrong is narrow, and worth naming exactly. `G29 L0` and `G29 A` are Marlin's
 * UBL commands. On a Klipper host `G29` is whichever macro the printer defines, and the usual one
 * homes and meshes - so the print homes twice and probes the bed twice before its first layer, the
 * second probe measuring a bed the first one has touched. `M420` recalls a mesh and `G26` runs a
 * test pattern, neither of which Klipper has at all.
 */
internal fun marlinOnlyStartCommand(lines: Sequence<String>): String? =
    lines.take(HEADER_LINES)
        .map { it.substringBefore(';').trim() }
        .firstOrNull { command ->
            val upper = command.uppercase()
            upper.startsWith("G29") || upper.startsWith("M420") || upper.startsWith("G26")
        }

/** How far into a file its start block can be: every slicer writes these at the top. */
internal const val HEADER_LINES = 60
