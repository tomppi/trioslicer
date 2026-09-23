package com.tomppi.enderslicer.engine

import com.tomppi.enderslicer.model.SlicerSettings

/**
 * App-owned settings consumed by the enderslicercura CuraEngine masonry-walls
 * patch. Like the bead-angle keys they intentionally do not belong to Cura's
 * upstream definition tree and are appended to the resolved slice snapshot
 * after Cura evaluates its dependency graph.
 */
internal object MasonryWallsEngineSettings {
    const val ENABLED = "enderslicer_masonry_walls_enabled"

    fun values(settings: SlicerSettings): LinkedHashMap<String, String> =
        linkedMapOf(ENABLED to settings.masonryWallsEnabled.toString())

    fun validate(settings: SlicerSettings) {
        // Single boolean flag; nothing to range-check.
        settings.masonryWallsEnabled
    }
}
