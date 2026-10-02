package com.tomppi.enderslicer.engine

import java.io.File

/**
 * Gives a Klipper start script a mesh to measure, when it has none.
 *
 * KAMP - the adaptive meshing macros this app ships for its Klipper host - replaces
 * BED_MESH_CALIBRATE. When that command is called, the macro reads the objects the slicer
 * declared and meshes only the ground they stand on. What it therefore needs from a file is
 * not a parameter but a call: BED_MESH_CALIBRATE, after the objects are defined. A start
 * script that measures its own mesh already has one, and one that only loads a saved profile
 * - or measures nothing at all, which is what a Klipper profile carried over from Marlin
 * often does - leaves KAMP with nothing to react to.
 *
 * So this scans the start of the file up to the first extruding move, which is the last point
 * at which a mesh can still be measured before plastic goes down, and:
 *
 *   - leaves the file alone if BED_MESH_CALIBRATE or G29 is already in front of that point,
 *   - leaves it alone if no G28 came before it either - Klipper refuses to probe an unhomed
 *     printer, so the line would turn a print into an error rather than into a mesh,
 *   - otherwise writes the call in, marked, immediately before that move.
 *
 * As late as possible is deliberate: object definitions come from the slicer and can sit
 * anywhere in the start script, and every one of them already read when the mesh runs is one
 * more object the mesh can be fitted to.
 *
 * The marker doubles as the idempotence check, so every post-slice path can call this without
 * counting calls - the same arrangement [GcodeProbePauseInjector] makes.
 */
internal object KlipperBedMeshInjector {
    /** Written above the inserted call, and what makes a second run a no-op. */
    const val MARKER = ";ENDERSLICER_KAMP_MESH"

    /** The call KAMP reacts to. No parameters: KAMP reads its own settings and the objects. */
    private const val MESH_COMMAND = "BED_MESH_CALIBRATE"

    /** Commands that already measure a mesh: Klipper's own, and this printer's G29 macro. */
    private val MEASURING = setOf("BED_MESH_CALIBRATE", "G29")

    /**
     * Inserts the call when the file needs it, and answers whether it did.
     *
     * False is not a failure. It is "this file already measures a mesh", "the printer was
     * never homed", or "the marker is already there".
     */
    fun inject(file: File): Boolean {
        require(file.isFile && file.length() > 0L) { "Sliced G-code is unavailable" }

        var homed = false
        var insertAt = -1
        var index = 0
        file.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.trim() == MARKER) return false
                // By name, not by parser: BED_MESH_CALIBRATE is a Klipper command with no
                // G or M number, and GcodeCommand only knows the numbered forms - which is
                // how a file that already measures its own mesh first slipped past this.
                val word = commandWord(line)
                if (word in MEASURING) return false
                if (word == "G28") homed = true
                val command = GcodeCommand.parse(line)
                if (command != null && extrudes(command)) {
                    insertAt = index
                    break
                }
                index++
            }
        }
        // No extrusion to hang it on, or nothing has been homed: either way the file is
        // left exactly as the slicer wrote it.
        if (insertAt < 0 || !homed) return false

        val temporary = File(file.parentFile, "${file.name}.kamp-mesh.tmp")
        temporary.delete()
        try {
            file.bufferedReader().use { reader ->
                temporary.bufferedWriter().use { writer ->
                    var current = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (current == insertAt) {
                            writer.write(MARKER)
                            writer.newLine()
                            writer.write(MESH_COMMAND)
                            writer.newLine()
                        }
                        writer.write(line)
                        writer.newLine()
                        current++
                    }
                }
            }
            try {
                java.nio.file.Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.io.IOException) {
                check(temporary.renameTo(file) || temporary.copyTo(file, overwrite = true).let { temporary.delete(); true }) {
                    "Unable to publish the KAMP mesh G-code"
                }
            }
        } finally {
            temporary.delete()
        }
        return true
    }

    /**
     * The command a line runs, upper-cased, with its parameters and comment removed.
     *
     * A comment has no word, so it answers with nothing and matches nothing.
     */
    private fun commandWord(line: String): String =
        line.substringBefore(';').trim().substringBefore(' ').uppercase(java.util.Locale.US)

    /** A move that pushes filament forward: the first one is where the print really starts. */
    private fun extrudes(command: GcodeCommand.Parsed): Boolean =
        command.opcode.firstOrNull() == 'G' && (command.value('E') ?: 0.0) > 0.0
}
