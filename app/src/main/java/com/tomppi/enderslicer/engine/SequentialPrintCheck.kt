package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.PlateObject
import com.tomppi.enderslicer.model.SlicerSettings
import com.tomppi.enderslicer.viewer.MeshBounds
import java.util.Locale

/**
 * Refuses a plate that cannot be printed one object at a time.
 *
 * No engine makes this check for us: CuraEngine never reads its own head polygon, the
 * PrusaSlicer this app ships has the sequential-collision check commented out, and the Orca
 * console never calls Print::validate. A plate that gets past this guard can therefore drive
 * the head through a finished part, so the check is deliberately conservative - refusing an
 * arrangement that might have printed costs one rearrangement, the other mistake breaks a
 * printer.
 *
 * Reasons are reported in a fixed order, so one plate always produces one message:
 * 1. an object taller than the gantry (no arrangement can fix it, so it is reported first),
 * 2. the first pair, in list order, whose footprints touch or overlap in XY and Z,
 * 3. the first pair, in list order, whose head clearance overlaps.
 */
object SequentialPrintCheck {

    /**
     * Why this plate cannot be printed one object at a time, or null when it can. The message
     * is shown to the user as-is, so it names the offending objects.
     */
    fun refuseReason(models: List<PlateObject>, settings: SlicerSettings): String? {
        // One object at a time with one object is just a normal print, however tall it is.
        if (models.size < 2) return null

        for (model in models) {
            // Measured to the top of the object: that is the height the gantry has to clear.
            val topMm = model.bounds.maxZ.toDouble()
            if (topMm > settings.gantryHeightMm) {
                return "${model.name} is ${mm(topMm)} mm tall, above the ${mm(settings.gantryHeightMm)} mm " +
                    "gantry height, so nothing can be printed after it. Print it on its own."
            }
        }

        for (i in models.indices) {
            for (j in i + 1 until models.size) {
                val first = models[i]
                val second = models[j]
                if (first.bounds.touches(second.bounds)) {
                    return "${first.name} and ${second.name} touch or overlap, so they cannot be printed " +
                        "one object at a time. Move them apart."
                }
                // Either object may be the finished one: the engine picks the print order, so
                // both directions have to clear.
                val headReachesAcross =
                    headSweep(first, settings).overlaps(second.bounds) ||
                        headSweep(second, settings).overlaps(first.bounds)
                if (headReachesAcross) {
                    return "${first.name} and ${second.name} are too close for the print head: while one " +
                        "is printed, the head would sweep through the finished one. Move them further apart."
                }
            }
        }
        return null
    }

    /**
     * Where the nozzle can stand while printing [model]: its footprint grown by the head polygon.
     * The larger offset on each side is used, so head extents that only reach one way still give
     * a conservative box.
     */
    private fun headSweep(model: PlateObject, settings: SlicerSettings): MeshBounds {
        val bounds = model.bounds
        return MeshBounds(
            minX = bounds.minX + minOf(settings.printheadXMinMm, settings.printheadXMaxMm).toFloat(),
            minY = bounds.minY + minOf(settings.printheadYMinMm, settings.printheadYMaxMm).toFloat(),
            minZ = bounds.minZ,
            maxX = bounds.maxX + maxOf(settings.printheadXMinMm, settings.printheadXMaxMm).toFloat(),
            maxY = bounds.maxY + maxOf(settings.printheadYMinMm, settings.printheadYMaxMm).toFloat(),
            maxZ = bounds.maxZ,
        )
    }

    /** Touching counts as overlapping: a zero gap leaves no room for the head or a mistake. */
    private fun MeshBounds.overlaps(other: MeshBounds): Boolean =
        minX <= other.maxX && other.minX <= maxX && minY <= other.maxY && other.minY <= maxY

    /** Two objects that share space in XY and in Z: one would be printed inside the other. */
    private fun MeshBounds.touches(other: MeshBounds): Boolean =
        overlaps(other) && minZ <= other.maxZ && other.minZ <= maxZ

    private fun mm(value: Double): String = String.format(Locale.US, "%.1f", value)
}
