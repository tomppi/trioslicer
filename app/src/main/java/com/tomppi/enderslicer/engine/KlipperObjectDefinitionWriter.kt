package com.tomppi.enderslicer.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * Gives a Cura slice the object definitions KAMP needs to mesh adaptively.
 *
 * PrusaSlicer and OrcaSlicer declare their objects with EXCLUDE_OBJECT_DEFINE lines; CuraEngine
 * writes none at all. KAMP reads those definitions at the moment BED_MESH_CALIBRATE runs, so a Cura
 * slice on a Klipper host always answered "No objects detected!" and measured the whole bed - the
 * feature working, with nothing to work on.
 *
 * The footprint is already in the file: the slicer's own header states the print's bounds
 * (;MINX and friends, which the sanitizer has just corrected from the real moves), so the
 * definition can be written from the file itself rather than from a model the app would have to
 * re-derive. A rectangle around that footprint is what KAMP needs - it meshes the ground the
 * polygon covers, and the polygon's shape only decides how much spare bed comes with it.
 *
 * Nothing happens when the file already declares an object (the other two engines, or a user who
 * writes their own), when it asks for no mesh, or when it states no bounds.
 */
internal object KlipperObjectDefinitionWriter {
    private const val MARKER = ";ENDERSLICER_KAMP_MESH"
    private const val MESH_COMMAND = "BED_MESH_CALIBRATE"
    private const val DEFINITION = "EXCLUDE_OBJECT_DEFINE"
    private const val START = "EXCLUDE_OBJECT_START"
    private const val NAME = "enderslicer_model"

    /** How much bed around the print the mesh should still cover, in millimetres. */
    private const val MARGIN_MM = 2.0

    /** Adds the definition in front of the mesh call. True when the file was changed. */
    fun inject(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val lines = file.readText().replace("\r\n", "\n").lines()
        if (lines.any { it.startsWith(DEFINITION) }) return false
        val meshAt = lines.indexOfFirst {
            it.trim() == MARKER || it.substringBefore(';').trim().equals(MESH_COMMAND, ignoreCase = true)
        }
        if (meshAt < 0) return false
        val minX = header(lines, ";MINX:") ?: return false
        val minY = header(lines, ";MINY:") ?: return false
        val maxX = header(lines, ";MAXX:") ?: return false
        val maxY = header(lines, ";MAXY:") ?: return false
        val left = minX - MARGIN_MM
        val bottom = minY - MARGIN_MM
        val right = maxX + MARGIN_MM
        val top = maxY + MARGIN_MM
        val polygon = "[[" + number(left) + "," + number(bottom) + "],[" +
            number(right) + "," + number(bottom) + "],[" +
            number(right) + "," + number(top) + "],[" +
            number(left) + "," + number(top) + "]]"
        val centre = number((minX + maxX) / 2.0) + "," + number((minY + maxY) / 2.0)
        val definition = DEFINITION + " NAME=" + NAME + " CENTER=" + centre + " POLYGON=" + polygon
        val output = ArrayList<String>(lines.size + 2)
        output.addAll(lines.subList(0, meshAt))
        output.add(definition)
        output.add(START + " NAME=" + NAME)
        output.addAll(lines.subList(meshAt, lines.size))
        val temporary = File(file.parentFile, file.name + ".objects")
        temporary.writeText(output.joinToString("\n"))
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return true
    }

    private fun header(lines: List<String>, prefix: String): Double? =
        lines.firstOrNull { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.trim()
            ?.toDoubleOrNull()

    private fun number(value: Double): String = String.format(Locale.US, "%.3f", value)
}
