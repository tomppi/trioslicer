package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.engine.gcode.GcodeRoute

/**
 * Gives a Klipper start script a mesh to measure, when it has none.
 *
 * KAMP - the adaptive meshing macros the app ships for its Klipper host - replaces
 * BED_MESH_CALIBRATE. When that command is called, the macro reads the objects the slicer declared
 * and meshes only the ground they stand on. What it needs from a slice is therefore not a
 * parameter but a call, in front of the first extrusion.
 *
 * The call goes into the **start script**, and that is the whole point of this file's shape. It
 * was written into the sliced file first, after the engines had run, and every slice then failed:
 * [GcodeSanitizer] checks engine output against the file's dialect - Cura, Prusa and Orca are all
 * Marlin-family - and BED_MESH_CALIBRATE is not a Marlin command, so the file was rejected and
 * withheld, with the engine's own exit code sitting at 0 in the log. A start script is the user's
 * own text and is trusted by that check, which is what makes the call legal here.
 *
 * So this answers with a script rather than editing a file: the same script, or the same script
 * with the call added in front of its first extruding move - which is the last point at which a
 * mesh can still be measured before plastic goes down, and the point past which the slicer's own
 * object definitions cannot help anyway. It adds nothing when the script already measures a mesh
 * (BED_MESH_CALIBRATE or this printer's G29 macro), nothing when the printer was never homed -
 * Klipper refuses to probe an unhomed printer, and a line that errors turns a print into a
 * failure rather than into a mesh - and nothing when its own marker is already there, so a script
 * that is passed through twice does not collect two calls.
 */
internal object KlipperBedMeshInjector {
    /** Written above the inserted call, and what makes a second pass a no-op. */
    const val MARKER = ";ENDERSLICER_KAMP_MESH"

    /** The call KAMP reacts to. No parameters: KAMP reads its own settings and the objects. */
    private const val MESH_COMMAND = "BED_MESH_CALIBRATE"

    /** Commands that already measure a mesh: Klipper's own, and this printer's G29 macro. */
    private val MEASURING = setOf("BED_MESH_CALIBRATE", "G29")

    /**
     * The start script to slice with, given the switch and the printer's flavour.
     *
     * Both gates are here rather than at the call sites so that the three engines cannot disagree
     * about when this applies, which is how a Klipper command ended up in a Marlin file once
     * already.
     */
    fun withMeshCallIfWanted(enabled: Boolean, flavor: String, startGcode: String): String =
        if (enabled && GcodeRoute.isKlipperFlavor(flavor)) withMeshCall(startGcode) else startGcode

    /** Whether a script already carries the call this app writes, which is what the switch shows. */
    fun hasMeshCall(startGcode: String): Boolean =
        startGcode.lineSequence().any { it.trim() == MARKER }

    /**
     * The script with the call taken out again, so the switch can be turned back off.
     *
     * The pair this app writes is removed and nothing else: a line that is not the mesh command
     * is left alone even if it sits where the call would be.
     */
    fun withoutMeshCall(startGcode: String): String {
        val lines = startGcode.replace("\r\n", "\n").lines()
        val marker = lines.indexOfFirst { it.trim() == MARKER }
        if (marker < 0) return startGcode
        val callFollows = lines.getOrNull(marker + 1)?.trim().equals(MESH_COMMAND, ignoreCase = true) == true
        val end = if (callFollows) marker + 2 else marker + 1
        val result = ArrayList<String>(lines.size)
        result.addAll(lines.subList(0, marker))
        result.addAll(lines.subList(minOf(end, lines.size), lines.size))
        return result.joinToString("\n")
    }

    /** The script with the call added, or the script unchanged when it is not needed. */
    fun withMeshCall(startGcode: String): String {
        if (startGcode.isBlank()) return startGcode
        // A start script that came from a Windows editor, or from one of the profiles this app
        // imports, can end its lines with CRLF. The command parser reads such a line's last
        // parameter as part of the carriage return, so an extruding move looked like no extruding
        // move and the scan quietly found nothing to inject in front of - which is exactly how
        // the call went missing on a profile whose start script had CRLF endings.
        val normalized = startGcode.replace("\r\n", "\n")
        val lines = normalized.lines()
        var homed = false
        var insertAt = -1
        for ((index, line) in lines.withIndex()) {
            if (line.trim() == MARKER) return startGcode
            // By name, not by parser: BED_MESH_CALIBRATE is a Klipper command with no G or M
            // number, and GcodeCommand only knows the numbered forms.
            val word = commandWord(line)
            if (word in MEASURING) return startGcode
            if (word == "G28") homed = true
            if (pushesFilament(line)) {
                insertAt = index
                break
            }
        }
        if (insertAt < 0 || !homed) return startGcode
        val result = ArrayList<String>(lines.size + 2)
        result.addAll(lines.subList(0, insertAt))
        result.add(MARKER)
        result.add(MESH_COMMAND)
        result.addAll(lines.subList(insertAt, lines.size))
        return result.joinToString("\n")
    }

    /**
     * The command a line runs, upper-cased, with its parameters and comment removed.
     *
     * A comment has no word, so it answers with nothing and matches nothing.
     */
    private fun commandWord(line: String): String =
        line.substringBefore(';').trim().substringBefore(' ').uppercase(java.util.Locale.US)

    /** A move that pushes filament forward: the first one is where the print really starts. */
    private fun pushesFilament(line: String): Boolean {
        val command = GcodeCommand.parse(line) ?: return false
        return command.opcode.firstOrNull() == 'G' && (command.value('E') ?: 0.0) > 0.0
    }
}
